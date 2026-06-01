package io.github.kedaya0209.roco.app.capture.pipeline;

import net.jcip.annotations.ThreadSafe;

/**
 * OpenCV → JavaFX 角度转换工具。
 *
 * <p>OpenCV atan2(Y-down) 0°=右，JavaFX 0°=上、+90°=右。
 * 转换公式：{@code javaFx = (opencv + 90) % 360}。
 */
@ThreadSafe
public final class AngleConverter {

    private AngleConverter() {
    }

    /**
     * OpenCV 角度 → JavaFX 角度。
     *
     * @param opencvAngle OpenCV atan2 角度（度），NaN 时原样返回
     * @return JavaFX 角度（度）
     */
    public static double toJavaFX(double opencvAngle) {
        if (Double.isNaN(opencvAngle)) {
            return Double.NaN;
        }
        return (opencvAngle + 90) % 360;
    }
}
