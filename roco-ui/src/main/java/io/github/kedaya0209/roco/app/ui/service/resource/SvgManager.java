package io.github.kedaya0209.roco.app.ui.service.resource;

import net.jcip.annotations.ThreadSafe;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

import javafx.scene.Node;
import javafx.scene.image.Image;
import javafx.scene.layout.StackPane;
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

    /** 从外部 SVG 文件创建图标节点 */
    public static Node createIconFromFile(File svgFile, double size) {
        try (FileInputStream fis = new FileInputStream(svgFile)) {
            byte[] raw = fis.readAllBytes();
            StackPane box = new StackPane();
            box.setPrefSize(size, size);
            box.setMinSize(size, size);
            box.setMaxSize(size, size);
            box.getChildren().add(SvgIconBuilder.buildBaseGroup(raw, size));
            return box;
        } catch (Exception e) {
            return createLetterPlaceholder(size);
        }
    }

    private static Node createLetterPlaceholder(double size) {
        SVGPath sp = new SVGPath();
        sp.setContent("M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-2h2v2zm0-4h-2V7h2v6z");
        sp.setStyle("-fx-fill: -color-accent-fg;");
        StackPane box = new StackPane(sp);
        box.setPrefSize(size, size);
        box.setMinSize(size, size);
        box.setMaxSize(size, size);
        return box;
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
