package com.luoke.app.ui.service.resource;

import net.jcip.annotations.ThreadSafe;
import javafx.scene.Node;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;

/**
 * SVG 路径管理器 — 外观模式，委托给 SvgIconBuilder、SvgAnimator、SvgPathUtil。
 *
 * <p>外部消费者继续使用 SvgManager 的静态方法，内部实现已拆分为三个专用类。</p>
 */
@ThreadSafe
public class SvgManager {

    private SvgManager() {
    }

    /** 创建固定尺寸的 SVG 图标节点 */
    public static Node createIcon(String resourcePath, double size) {
        return SvgIconBuilder.createIcon(resourcePath, size);
    }

    /** 创建固定尺寸的 SVG 图标节点，允许指定样式 */
    public static Node createIcon(String resourcePath, double size, String style) {
        return SvgIconBuilder.createIcon(resourcePath, size, style);
    }

    /** 创建 hover 画线动画图标（指定颜色版） */
    public static Node createHoverDrawIcon(
            String resourcePath, double size,
            Color strokeColor, double strokeWidth, int durationMillis) {
        return SvgAnimator.createHoverDrawIcon(resourcePath, size, strokeColor, strokeWidth, durationMillis);
    }

    /** 创建 hover 画线动画图标（CSS 主题色版） */
    public static Node createHoverDrawIcon(
            String resourcePath, double size,
            double strokeWidth, int durationMillis) {
        return SvgAnimator.createHoverDrawIcon(resourcePath, size, strokeWidth, durationMillis);
    }

    /** 外部控制 hover 画线动画 */
    public static void animateHoverDrawIcon(Node iconNode, boolean enter, int durationMillis) {
        SvgAnimator.animateHoverDrawIcon(iconNode, enter, durationMillis);
    }

    /** 提取 SVG 路径的 d 属性 */
    public static String getPath(String resourcePath) {
        return SvgIconBuilder.getPath(resourcePath);
    }

    /** 计算 SVGPath 的路径总长度 */
    public static double computePathLength(SVGPath path) {
        return SvgPathUtil.computePathLength(path);
    }

    /** 创建固定尺寸的 SVG 图标 Image */
    public static Image createImage(String resourcePath, double size) {
        return SvgIconBuilder.createImage(resourcePath, size);
    }

    /** 清除缓存 */
    public static void clearCache() {
        SvgIconBuilder.clearCache();
        SvgPathUtil.clearCache();
    }
}
