package com.luoke.app.capture;

import com.luoke.app.capture.jna.Frame;
import com.luoke.app.capture.jna.FrameCallback;
import com.luoke.app.capture.jna.WgcLibrary;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import lombok.Setter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.function.Consumer;

/**
 * Windows Graphics Capture (WGC) 封装类
 * <p>
 * 该类封装了Windows.Graphics.Capture API，用于捕获指定窗口的屏幕内容。
 * 核心功能包括：
 * <ul>
 *   <li>Native DLL加载与初始化</li>
 *   <li>窗口捕获的启动与停止</li>
 *   <li>帧数据的回调处理</li>
 *   <li>Native资源的安全释放</li>
 * </ul>
 *
 * <h3>Native资源管理</h3>
 * <ul>
 *   <li>DLL在类加载时从资源中提取并加载</li>
 *   <li>捕获器通过init_capturer/destroy_capturer管理</li>
 *   <li>必须调用close()释放资源，否则会导致内存泄漏</li>
 * </ul>
 *
 * <h3>线程模型</h3>
 * <ul>
 *   <li>回调在Native线程中执行</li>
 *   <li>running标志用于控制回调的执行</li>
 *   <li>需要保证线程安全的回调处理</li>
 * </ul>
 *
 * <h3>性能优化</h3>
 * <ul>
 *   <li>DLL加载使用静态初始化，避免重复加载</li>
 *   <li>使用volatile标志控制回调，避免竞态条件</li>
 * </ul>
 *
 * @author RocoMapTracker Team
 * @since 1.0
 */
public class WgcCapture {

    /**
     * Native库实例
     * 静态初始化，确保只加载一次DLL
     */
    public static final WgcLibrary LIB = loadLibrary();

    /**
     * 目标窗口句柄
     * 用于指定要捕获的窗口
     */
    private final long hwnd;

    /**
     * 运行状态标志
     * 使用volatile保证多线程可见性
     * 用于控制回调是否处理新帧
     */
    private volatile boolean running;

    /**
     * 帧回调包装器
     * 实现FrameCallback接口，连接Native层和Java层
     */
    private final WindowCaptureHook frameCallback = new WindowCaptureHook();


    /**
     * 构造窗口捕获器
     *
     * @param hwnd 目标窗口的句柄，用于指定要捕获的窗口
     */
    public WgcCapture(long hwnd) {
        this.hwnd = hwnd;
    }

    /**
     * 加载Native DLL库
     * <p>
     * 该方法执行以下步骤：
     * <ol>
     *   <li>从资源路径读取DLL文件</li>
     *   <li>创建临时文件并写入DLL内容</li>
     *   <li>使用JNA加载DLL</li>
     * </ol>
     *
     * <h3>资源管理</h3>
     * <ul>
     *   <li>临时文件标记为deleteOnExit，JVM退出时自动删除</li>
     *   <li>使用try-with-resources确保流正确关闭</li>
     *   <li>DLL文件大小较小，内存中拷贝是安全的</li>
     * </ul>
     *
     * <h3>性能考虑</h3>
     * <ul>
     *   <li>静态初始化，只加载一次</li>
     *   <li>8KB缓冲区平衡内存使用和IO效率</li>
     * </ul>
     *
     * @return 加载的WgcLibrary实例
     * @throws RuntimeException 如果DLL不存在或加载失败
     */
    private static WgcLibrary loadLibrary() {
        // DLL资源路径，位于resources/dll/capture.dll
        String dllResourcePath = "/dll/capture.dll";
        try (InputStream inputStream = WgcCapture.class.getResourceAsStream(dllResourcePath)) {
            if (inputStream == null) {
                throw new RuntimeException("DLL 不存在: " + dllResourcePath);
            }

            // 创建临时DLL文件
            // 注意：在native image环境下，需要特别处理文件系统访问
            File tempDll = File.createTempFile("capture", ".dll");
            // JVM退出时自动删除临时文件
            tempDll.deleteOnExit();

            // 将资源DLL写入临时文件
            try (FileOutputStream out = new FileOutputStream(tempDll)) {
                byte[] buffer = new byte[8192]; // 8KB缓冲区
                int len;
                while ((len = inputStream.read(buffer)) != -1) {
                    out.write(buffer, 0, len);
                }
            }

            // 使用JNA加载DLL
            return Native.load(tempDll.getAbsolutePath(), WgcLibrary.class);
        } catch (Exception e) {
            throw new RuntimeException("加载 capture.dll 失败", e);
        }
    }

    /**
     * 启动捕获循环
     * <p>
     * 该方法启动Native层的窗口捕获，并设置回调函数：
     * <ol>
     *   <li>检查是否已经在运行</li>
     *   <li>设置用户回调函数</li>
     *   <li>调用Native层的init_capturer启动捕获</li>
     * </ol>
     *
     * <h3>Native资源分配</h3>
     * <ul>
     *   <li>init_capturer会在Native层分配资源</li>
     *   <li>必须调用close()释放这些资源</li>
     *   <li>不释放会导致内存泄漏和句柄泄漏</li>
     * </ul>
     *
     * <h3>线程模型</h3>
     * <ul>
     *   <li>Native层在独立线程中产生帧数据</li>
     *   <li>回调在Native线程中执行，需要考虑线程安全</li>
     *   <li>running标志控制回调的执行</li>
     * </ul>
     *
     * @param callback 帧回调函数，接收到新帧时调用
     *                 注意：回调在Native线程中执行
     * @param showBorder 是否显示捕获边框，用于调试
     *                   true显示边框，false不显示
     */
    public void startLoop(Consumer<Frame> callback, boolean showBorder) {
        // 防止重复启动
        if (running) return;
        running = true;

        // 设置用户回调函数
        frameCallback.setCallback(callback);

        // 调用Native层启动捕获
        // 此时会分配Native资源，需要通过close()释放
        LIB.init_capturer(hwnd, showBorder ? 1 : 0, frameCallback);
    }

    /**
     * 窗口捕获回调包装器
     * <p>
     * 该内部类实现FrameCallback接口，作为Native层和Java层之间的桥梁：
     * <ul>
     *   <li>接收Native层的帧数据</li>
     *   <li>将帧数据封装为Frame对象</li>
     *   <li>调用用户注册的回调函数</li>
     * </ul>
     *
     * <h3>线程安全</h3>
     * <ul>
     *   <li>onFrame在Native线程中执行</li>
     *   <li>callback的调用是线程不安全的，需要用户保证</li>
     *   <li>running标志确保回调的线程安全控制</li>
     * </ul>
     *
     * <h3>性能优化</h3>
     * <ul>
     *   <li>快速失败机制，避免不必要的回调</li>
     *   <li>Frame对象是轻量级的，只包含指针引用</li>
     * </ul>
     */
    @Setter
    public class WindowCaptureHook implements FrameCallback {

        /**
         * 用户注册的帧回调函数
         * 使用@Setter注解生成的setter方法设置
         */
        private Consumer<Frame> callback;

        /**
         * Native层调用的帧回调方法
         * <p>
         * 该方法在Native线程中被调用，执行以下逻辑：
         * <ol>
         *   <li>检查运行状态和错误码</li>
         *   <li>验证数据指针的有效性</li>
         *   <li>创建Frame对象并调用用户回调</li>
         * </ol>
         *
         * <h3>参数说明</h3>
         * <ul>
         *   <li>data: Native内存指针，指向帧数据</li>
         *   <li>len: 数据长度（字节）</li>
         *   <li>w: 帧宽度（像素）</li>
         *   <li>h: 帧高度（像素）</li>
         *   <li>pitch: 每行字节数（考虑对齐）</li>
         *   <li>code: 错误码，0表示成功</li>
         * </ul>
         *
         * <h3>内存管理</h3>
         * <ul>
         *   <li>data指针由Native层管理，不要在Java层释放</li>
         *   <li>Frame对象只持有指针引用，不复制数据</li>
         *   <li>用户需要及时处理帧数据，避免累积</li>
         * </ul>
         *
         * @param data Native内存指针，指向帧数据
         * @param len 数据长度（字节）
         * @param w 帧宽度（像素）
         * @param h 帧高度（像素）
         * @param pitch 每行字节数（考虑内存对齐）
         * @param code 错误码，0表示成功，非0表示错误
         */
        @Override
        public void onFrame(Pointer data, long len, int w, int h, int pitch, int code) {
            // 快速失败检查：确保捕获器正在运行、无错误且数据有效
            if (!running || code != 0 || data == null) return;

            // 创建Frame对象并调用用户回调
            // Frame对象是轻量级的，只包含指针引用
            callback.accept(new Frame(data, w, h, pitch));
        }
    }

    /**
     * 关闭捕获器并释放Native资源
     * <p>
     * 该方法执行以下操作：
     * <ol>
     *   <li>检查是否正在运行，避免重复关闭</li>
     *   <li>设置running标志为false，停止回调处理</li>
     *   <li>调用Native层的destroy_capturer释放资源</li>
     * </ol>
     *
     * <h3>资源释放</h3>
     * <ul>
     *   <li>Native层的捕获器资源被释放</li>
     *   <li>停止接收新的帧数据</li>
     *   <li>避免内存泄漏和句柄泄漏</li>
     * </ul>
     *
     * <h3>线程安全</h3>
     * <ul>
     *   <li>running标志保证线程安全</li>
     *   <li>可以安全地从任何线程调用</li>
     *   <li>即使回调线程正在执行，也会安全停止</li>
     * </ul>
     *
     * <h3>使用建议</h3>
     * <ul>
     *   <li>必须在不再需要捕获时调用</li>
     *   <li>建议在finally块中调用</li>
     *   <li>调用后不应再使用该对象</li>
     * </ul>
     */
    public void close() {
        // 防止重复关闭
        if (!running) return;

        // 设置标志为false，停止回调处理
        // 这会通知onFrame方法不再处理新的帧
        running = false;

        // 调用Native层释放资源
        // 必须调用此方法，否则会导致内存泄漏
        LIB.destroy_capturer();
    }
}