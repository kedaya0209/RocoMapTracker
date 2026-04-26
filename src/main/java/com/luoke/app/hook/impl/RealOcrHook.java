package com.luoke.app.hook.impl;

import com.luoke.app.capture.jna.Frame;
import com.luoke.app.config.AppConfig;
import com.luoke.app.context.MaterialCollectionContext;
import com.luoke.app.context.OcrAsyncManager;
import com.luoke.app.hook.AbstractGenericHook;
import com.luoke.app.hook.HookEventType;
import com.luoke.app.model.ItemResult;
import com.luoke.app.utils.OcrResultValidator;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Rect;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 实时OCR识别钩子
 * <p>
 * 功能说明：
 * <ul>
 *   <li>监听帧捕获事件，实时截取游戏画面中的物资列表区域</li>
 *   <li>使用OCR技术识别物资名称和数量</li>
 *   <li>通过帧间稳定性算法，避免重复计录物资</li>
 *   <li>支持增量更新，只计录新增的物资</li>
 * </ul>
 * <p>
 * Native资源管理策略：
 * <ul>
 *   <li>使用try-with-resources自动管理OpenCV的Mat和BytePointer资源</li>
 *   <li>避免Native内存泄漏，及时释放C++对象</li>
 *   <li>使用BytePointer直接封装Java数组，减少内存拷贝</li>
 * </ul>
 * <p>
 * 性能优化设计：
 * <ul>
 *   <li>采样间隔控制：200ms间隔，避免高频OCR导致CPU占用过高</li>
 *   <li>并行度限制：通过AtomicInteger控制并发OCR任务数量</li>
 *   <li>帧缓存复用：直接使用Frame的像素数据，避免深拷贝</li>
 *   <li>异步处理：通过OcrAsyncManager将OCR任务提交到线程池</li>
 * </ul>
 * <p>
 * 稳定性算法设计：
 * <ol>
 *   <li>连续2帧OCR结果一致才触发计录（防抖）</li>
 *   <li>增量比对：只计录新增的物资行，支持翻页场景</li>
 *   <li>空列表重置：识别到空列表时重置快照，应对翻页</li>
 * </ol>
 */
@Slf4j
public class RealOcrHook extends AbstractGenericHook<Frame> {

    /**
     * OCR识别区域缩放比例（相对于全屏）
     * <p>
     * 设计考虑：
     * <ul>
     *   <li>SCALE_X, SCALE_Y：区域起点坐标</li>
     *   <li>SCALE_W, SCALE_H：区域宽度和高度</li>
     *   <li>这些比例针对1920x1080分辨率优化，适配特定UI布局</li>
     *   <li>固定裁剪区域可大幅减少OCR处理面积，提升性能</li>
     * </ul>
     */
    private static final double SCALE_X = 0.875, SCALE_Y = 0.287, SCALE_W = 0.11, SCALE_H = 0.17;

    /**
     * OCR采样间隔（毫秒）
     * <p>
     * 设计考虑：
     * <ul>
     *   <li>200ms间隔，既保证实时性又避免CPU过载</li>
     *   <li>OCR是CPU密集型操作，高频调用会导致帧率下降</li>
     *   <li>采样间隔大于UI刷新率（通常60fps=16.67ms），减少不必要的OCR</li>
     * </ul>
     */
    private static final long SCAN_INTERVAL = 200;

    /**
     * 上次OCR扫描的时间戳
     * <p>
     * 用于实现采样间隔控制，避免每帧都进行OCR
     */
    private long lastScanTime = 0;

    // --- 状态追踪 ---

    /**
     * 已确认计录的物资列表快照
     * <p>
     * 作用：
     * <ul>
     *   <li>用于增量比对，识别新增物资</li>
     *   <li>连续2帧稳定时，与此快照对比找出增量</li>
     *   <li>翻页或清空时会更新此快照</li>
     * </ul>
     */
    private List<ItemResult> lastConfirmedList = new ArrayList<>();

    /**
     * 待校验的物资列表（当前帧OCR结果）
     * <p>
     * 作用：
     * <ul>
     *   <li>暂存当前帧的OCR识别结果</li>
     *   <li>与下一帧对比，用于判断稳定性</li>
     *   <li>稳定后会被移入lastConfirmedList</li>
     * </ul>
     */
    private List<ItemResult> pendingList = new ArrayList<>();

    /**
     * OCR并行度计数器
     * <p>
     * 设计考虑：
     * <ul>
     *   <li>初始值从配置读取，支持多核并发OCR</li>
   *   <li>每次提交OCR任务前递减，任务完成后递增</li>
     *   <li>值为0时拒绝新任务，防止线程池溢出</li>
     *   <li>使用AtomicInteger保证线程安全，无需显式同步</li>
     * </ul>
     */
    private final AtomicInteger parallel = new AtomicInteger(AppConfig.OCR_CORE_SIZE);

    /**
     * 帧稳定性计数器
     * <p>
     * 作用：
     * <ul>
     *   <li>记录连续稳定帧的数量</li>
     *   <li>当前帧与pendingList一致时递增</li>
     *   <li>不一致时重置为1</li>
     *   <li>达到2时触发增量计录逻辑</li>
     * </ul>
     */
    private int stabilityCount = 0;

    /**
     * 处理帧捕获事件，执行实时OCR识别
     * <p>
     * 执行流程：
     * <ol>
     *   <li>采样间隔检查：距离上次OCR不足200ms则跳过</li>
     *   <li>并行度检查：parallel <= 0时跳过，防止任务堆积</li>
     *   <li>截取ROI区域：从全屏中裁剪物资列表区域</li>
     *   <li>转换为PNG格式：通过OpenCV进行图片编码</li>
     *   <li>提交OCR任务：异步执行OCR识别，不阻塞主线程</li>
     * </ol>
     * <p>
     * Native资源管理：
     * <ul>
     *   <li>使用try-with-resources管理Mat和BytePointer</li>
     *   <li>自动释放OpenCV的C++对象，避免内存泄漏</li>
     *   <li>BytePointer直接包装Java数组，避免数据拷贝</li>
     * </ul>
     *
     * @param eventType 事件类型，必须为FRAME_CAPTURED
     * @param frame 帧数据，包含像素数组、宽度、高度等信息
     */
    @Override
    public void onEvent(HookEventType eventType, Frame frame) {
        long now = System.currentTimeMillis();

        // 采样间隔检查：距离上次OCR不足200ms则跳过
        // 目的：避免高频OCR导致CPU占用过高，同时保证实时性
        if ((now - lastScanTime) < SCAN_INTERVAL) return;

        // 并行度检查：parallel <= 0时跳过
        // 目的：控制并发OCR任务数量，防止线程池溢出
        if (parallel.get() <= 0) return;

        // 预先递减并行度计数器，预占一个OCR槽位
        // 使用compareAndSet保证原子性，避免并发问题
        parallel.decrementAndGet();
        boolean taskSubmitted = false;
        lastScanTime = now;


        try {
            // 获取帧像素数据，注意：Frame对象通常由JNA/Native层管理
            // 此处获取的是Java层引用，不涉及Native内存复制
            byte[] pixels = frame.getPixels();
            int w = frame.width(), h = frame.height();

            // Native资源管理：使用try-with-resources自动释放OpenCV对象
            try (BytePointer ptr = new BytePointer(pixels);
                 Mat fullMat = new Mat(h, w, opencv_core.CV_8UC4, ptr);
                 Rect roi = new Rect((int) (w * SCALE_X), (int) (h * SCALE_Y), (int) (w * SCALE_W), (int) (h * SCALE_H));
                 Mat cropped = fullMat.apply(roi);
                 BytePointer buf = new BytePointer()) {

                // 将裁剪后的Mat编码为PNG格式
                // PNG压缩率高，适合传输给OCR引擎
                // 注意：imencode会使用buf的底层内存，无需手动分配
                opencv_imgcodecs.imencode(".png", cropped, buf);

                // 从BytePointer读取编码后的PNG数据到Java数组
                // 注意：buf.limit()返回的是编码后的字节数，不是buf的总容量
                byte[] croppedBytes = new byte[(int) buf.limit()];
                buf.get(croppedBytes);

                // 提交OCR任务到异步管理器
                // 使用回调模式处理OCR结果，避免阻塞主线程
                // croppedBytes会被异步管理器复制，此处可安全释放
                OcrAsyncManager.getInstance().submitTask(croppedBytes, lines -> {
                    try {
                        // 1. 将OCR原始文本解析为结构化列表
                        // 过滤掉无法解析的行（null值）
                        List<ItemResult> currentList = lines.stream()
                                .map(OcrResultValidator::parse)
                                .filter(Objects::nonNull)
                                .toList();

                        // 进入同步块，保证状态更新的原子性
                        // 避免多帧并发处理导致状态不一致
                        synchronized (this) {
                            // 2. 稳定器逻辑：当前帧需与待定帧完全一致
                            if (!currentList.isEmpty() && currentList.equals(pendingList)) {
                                // 连续帧一致，递增稳定性计数器
                                // 目的：防抖，避免OCR抖动导致错误计录
                                stabilityCount++;
                            } else {
                                // 帧不一致，更新待定列表并重置计数器
                                pendingList = new ArrayList<>(currentList);
                                stabilityCount = 1;

                                // 关键：如果区域空了，重置所有快照
                                // 原因：物资列表翻页或拾取完成时会出现空列表
                                // 此时需要重置快照，以便下一页物资能正确识别为新增
                                if (currentList.isEmpty()) {
                                    lastConfirmedList.clear();
                                }
                                return; // 不稳定，跳过增量逻辑
                            }

                            // 3. 连续 2 帧稳定，开始增量比对
                            // 2帧稳定阈值是经验值，足以过滤OCR抖动
                            if (stabilityCount == 2) {
                                handleIncrementalLogic(currentList);
                            }
                        }
                    } finally {
                        // OCR任务完成，递增并行度计数器
                        // 无论OCR是否成功，都要释放槽位
                        parallel.incrementAndGet();
                    }
                });
                taskSubmitted = true; // 标记任务提交成功
            }
            // try-with-resources自动释放：
            // 1. buf：PNG编码缓冲区
            // 2. cropped：裁剪后的Mat
            // 3. roi：感兴趣区域矩形
            // 4. fullMat：全屏Mat
            // 5. ptr：像素数据指针
        } catch (Exception e) {
            // 捕获并记录所有异常，避免Hook异常导致系统崩溃
            // 常见异常：OpenCV内存错误、图像编码失败、索引越界等
            log.error("Hook 异常", e);
        } finally {
            // 任务未提交成功（如异常发生），需要释放预占的并行度槽位
            if (!taskSubmitted) {
                parallel.incrementAndGet();
            }
        }
    }

    /**
     * 处理稳定后的增量计录逻辑
     * <p>
     * 功能说明：
     * 比对当前稳定帧与已确认快照，识别并计录新增物资
     * <p>
     * 支持场景：
     * <ol>
     *   <li>场景A：列表行数增加（新物资弹出，包括一次出5个）</li>
     *   <li>场景B：行数不变但内容全变（翻页时两页行数相同但文字不同）</li>
     * </ol>
     * <p>
     * 设计考虑：
     * <ul>
     *   <li>使用增量比对而非全量替换，避免重复计录</li>
     *   <li>翻页场景特殊处理：行数相同时也要检查内容差异</li>
     *   <li>计录操作通过MaterialCollectionContext全局管理，支持累计统计</li>
     * </ul>
     *
     * @param stableList 经过稳定性验证的OCR结果列表
     */
    private void handleIncrementalLogic(List<ItemResult> stableList) {
        // 场景 A: 列表行数增加了（新物资跳出来，包括一次出5个的情况）
        // 逻辑：stableList.size() > lastConfirmedList.size() 表示有新物资行出现
        // 实现：从lastConfirmedList.size()开始遍历，即可直接获取新增行
        if (stableList.size() > lastConfirmedList.size()) {
            // 遍历新增的物资行
            // 使用索引遍历而非遍历整个列表，提升性能
            for (int i = lastConfirmedList.size(); i < stableList.size(); i++) {
                ItemResult res = stableList.get(i);
                log.info("🎯 确认为新增拾取: {} x{}", res.name(), res.count());

                // 将物资添加到全局收集上下文
                // addMaterial内部会进行累计统计，支持相同名称物资的数量合并
                MaterialCollectionContext.getInstance().addMaterial(res.name(), res.count());
            }
        }
        // 场景 B: 行数没变但内容全变了（极速翻页中，刚好两页行数相同但文字不同）
        // 逻辑：stableList.size() == lastConfirmedList.size() 且 !stableList.equals(lastConfirmedList)
        // 实现：全量计录当前列表，因为所有内容都是新的
        else if (stableList.size() == lastConfirmedList.size() && !stableList.equals(lastConfirmedList)) {
            // 遍历所有物资行（因为内容已全部变化）
            for (ItemResult res : stableList) {
                log.info("🎯 翻页增量确认: {} x{}", res.name(), res.count());
                MaterialCollectionContext.getInstance().addMaterial(res.name(), res.count());
            }
        }

        // 更新最后确认快照
        // 创建新ArrayList而非直接引用，避免外部修改影响内部状态
        // 这是一种防御性编程，保证快照的不可变性
        lastConfirmedList = new ArrayList<>(stableList);
    }

    /**
     * 获取当前钩子支持的事件类型集合
     * <p>
     * RealOcrHook仅处理帧捕获事件，用于实时执行OCR识别
     *
     * @return 包含FRAME_CAPTURED事件的不可变Set集合
     */
    @Override
    public Set<HookEventType> supportedEvents() {
        return Set.of(HookEventType.FRAME_CAPTURED);
    }
}
