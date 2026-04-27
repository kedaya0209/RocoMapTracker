package com.luoke.app.macher.player;

import com.luoke.app.model.cnn.ArrowPredictService;
import lombok.extern.slf4j.Slf4j;

/**
 * 极速版箭头检测器 (单例模式)
 * 逻辑：Rust 传入灰度字节流 -> 直接喂给 CNN 模型 -> 返回预测角度
 */
@Slf4j
public class ArrowDetector {

    private static volatile ArrowDetector instance;
    private final ArrowPredictService arrowService;

    // 私有化构造函数
    private ArrowDetector() {
        this.arrowService = new ArrowPredictService();
    }

    /**
     * 获取单例实例
     */
    public static ArrowDetector getInstance() {
        if (instance == null) {
            synchronized (ArrowDetector.class) {
                if (instance == null) {
                    instance = new ArrowDetector();
                }
            }
        }
        return instance;
    }

    /**
     * 初始化底层的 CNN 服务
     * 建议在程序启动时显式调用一次
     */
    public void init() throws Exception {
        this.arrowService.init();
    }

    /**
     * 核心检测方法
     *
     * @param grayData Rust 传过来的 gray_slice 对应的字节数组
     * @param width    ROI 的宽度
     * @param height   ROI 的高度
     */
    public Player detectPlayer(byte[] grayData, int width, int height) {
        if (grayData == null || grayData.length == 0) {
            return new Player(false, 0);
        }

        try {
            // 调用单例内部持有的 service 进行推理
            return arrowService.predict(grayData, width, height);
        } catch (Exception e) {
            log.error("❌ CNN 推理异常: {}", e.getMessage());
            return new Player(false, 0);
        }
    }

    /**
     * 资源销毁
     */
    public void release() {
        if (arrowService != null) {
            arrowService.close();
        }
        log.info("✅ ArrowDetector 资源已释放");
    }
}