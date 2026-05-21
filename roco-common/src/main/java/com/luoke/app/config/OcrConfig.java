package com.luoke.app.config;

import net.jcip.annotations.NotThreadSafe;
import java.util.Properties;

/**
 * OCR 参数持久化 
 */
@NotThreadSafe
public final class OcrConfig {

    // ============================================================
    // OCR 参数
    // ============================================================
    /**
     * OCR 并发信号量大小
     */
    public static int OCR_CORE_SIZE = 1;

    // --- 扫描与稳定性 ---
    /**
     * OCR 扫描最小间隔（毫秒）
     */
    public static long OCR_SCAN_INTERVAL = 200;
    /**
     * OCR 稳定性判定连续次数
     */
    public static int OCR_STABILITY_THRESHOLD = 2;

    // --- 线程池 ---
    /**
     * OCR 线程池核心/最大线程数
     */
    public static int OCR_THREAD_POOL_SIZE = 2;
    /**
     * OCR 任务队列容量
     */
    public static int OCR_TASK_QUEUE_CAPACITY = 10;
    /**
     * OCR 任务超时（毫秒，超过丢弃）
     */
    public static long OCR_TASK_TIMEOUT_MS = 500;

    // --- OCR ROI ---
    /**
     * OCR ROI 万分比坐标 X
     */
    public static int ROI_OCR_X = 8750;
    /**
     * OCR ROI 万分比坐标 Y
     */
    public static int ROI_OCR_Y = 2070;
    /**
     * OCR ROI 万分比宽度
     */
    public static int ROI_OCR_W = 1100;
    /**
     * OCR ROI 万分比高度
     */
    public static int ROI_OCR_H = 2100;

    // --- OCR 识别参数 ---
    /**
     * 识别标准高度（像素）
     */
    public static int OCR_REC_STD_HEIGHT = 52;
    /**
     * 文本检测热力图阈值
     */
    public static float OCR_TEXT_HEAT_THRESHOLD = 0.20f;
    /**
     * 检测到文本后垂直扩展像素
     */
    public static int OCR_EXPAND_Y = 4;
    /**
     * 检测输入填充对齐值
     */
    public static int OCR_DET_ALIGNMENT = 32;
    /**
     * 识别输入宽度对齐值
     */
    public static int OCR_REC_WIDTH_ALIGNMENT = 8;
    /**
     * 二值化阈值（低于此值为文本）
     */
    public static int OCR_BINARY_THRESHOLD = 150;
    private OcrConfig() {
        throw new AssertionError("禁止实例化配置类");
    }

    public static void load(Properties prop) {
        OCR_CORE_SIZE = ConfigHelper.getInt(prop, "ocr.core.size", OCR_CORE_SIZE);
        OCR_SCAN_INTERVAL = ConfigHelper.getLong(prop, "ocr.scan.interval", OCR_SCAN_INTERVAL);
        OCR_STABILITY_THRESHOLD = ConfigHelper.getInt(prop, "ocr.stability.threshold", OCR_STABILITY_THRESHOLD);
        OCR_THREAD_POOL_SIZE = ConfigHelper.getInt(prop, "ocr.thread.pool.size", OCR_THREAD_POOL_SIZE);
        OCR_TASK_QUEUE_CAPACITY = ConfigHelper.getInt(prop, "ocr.task.queue.capacity", OCR_TASK_QUEUE_CAPACITY);
        OCR_TASK_TIMEOUT_MS = ConfigHelper.getLong(prop, "ocr.task.timeout.ms", OCR_TASK_TIMEOUT_MS);
    }

    public static void save(StringBuilder sb) {
        sb.append("# OCR 并发信号量大小\n");
        sb.append("ocr.core.size=").append(OCR_CORE_SIZE).append("\n");
        sb.append("# OCR 扫描最小间隔（毫秒）\n");
        sb.append("ocr.scan.interval=").append(OCR_SCAN_INTERVAL).append("\n");
        sb.append("# OCR 稳定性判定连续次数\n");
        sb.append("ocr.stability.threshold=").append(OCR_STABILITY_THRESHOLD).append("\n");
        sb.append("# OCR 线程池核心/最大线程数\n");
        sb.append("ocr.thread.pool.size=").append(OCR_THREAD_POOL_SIZE).append("\n");
        sb.append("# OCR 任务队列容量\n");
        sb.append("ocr.task.queue.capacity=").append(OCR_TASK_QUEUE_CAPACITY).append("\n");
        sb.append("# OCR 任务超时（毫秒，超过丢弃）\n");
        sb.append("ocr.task.timeout.ms=").append(OCR_TASK_TIMEOUT_MS).append("\n\n");
    }
}
