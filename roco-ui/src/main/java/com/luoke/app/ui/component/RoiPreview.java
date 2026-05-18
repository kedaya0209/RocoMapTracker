package com.luoke.app.ui.component;

import com.luoke.app.capture.CaptureFrameBuffer;
import com.luoke.app.config.AppConfig;
import javafx.animation.AnimationTimer;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Text;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;

/**
 * ROI 截帧预览 — 在设置面板分类顶部实时显示指定 ROI 的截图画面。
 * 视频流模式：复用 WritableImage + int[] 缓冲区，避免每帧创建 8MB+ 临时对象。
 * 支持两种模式：
 * - 普通模式：从 CaptureFrameBuffer 读取 ROI 裁剪帧（灰度）
 * - 全帧模式：从 CaptureFrameBuffer 读取全帧（BGRA）并叠加 ROI 矩形框
 */
@Slf4j
public class RoiPreview {

    private static final double PREVIEW_HEIGHT = 180;
    private static final double PADDING = 12;
    /**
     * 预览帧率上限，避免高频分配
     */
    private static final long FRAME_INTERVAL_NS = 125_000_000; // 8fps

    private final int roiIndex;
    private final Pane root;
    private final ImageView imageView;
    private final Rectangle roiRect;
    private final Text label;
    private final AnimationTimer timer;

    /**
     * 全帧模式开关
     */
    private boolean fullFrameMode;
    /**
     * ROI 坐标的 AppConfig 字段前缀，如 "ROI_MAP_" 或 "ROI_OCR_"
     */
    private String roiPrefix;

    // ---- 复用缓冲区，避免每帧创建 8MB+ 临时对象 ----
    private WritableImage cachedImage;
    private int[] argbBuffer;

    // ---- 跳帧：仅在新帧到达时渲染 ----
    private long lastRenderedTimestamp;

    /**
     * @param roiIndex    ROI 索引（0=小地图, 1=OCR）
     * @param labelText   显示标签
     * @param accentColor ROI 矩形框颜色
     */
    public RoiPreview(int roiIndex, String labelText, Color accentColor) {
        this.roiIndex = roiIndex;
        this.fullFrameMode = false;

        root = new Pane();
        root.setStyle("-fx-background-color: -color-bg-default; -fx-background-radius: 8; " +
                "-fx-border-color: -color-border-muted; -fx-border-width: 0 0 1 0;");
        root.setPrefHeight(PREVIEW_HEIGHT);
        root.setMinHeight(120);
        root.setMaxHeight(220);

        imageView = new ImageView();
        imageView.setPreserveRatio(true);
        imageView.setSmooth(false);
        imageView.setMouseTransparent(true);

        // ROI 矩形叠加层
        roiRect = new Rectangle();
        roiRect.setStroke(accentColor);
        roiRect.setFill(Color.TRANSPARENT);
        roiRect.setStrokeWidth(2);
        roiRect.setMouseTransparent(true);
        roiRect.setVisible(false);

        label = new Text(labelText);
        label.setStyle("-fx-fill: -color-fg-default; -fx-font-size: 12px;");

        root.getChildren().addAll(imageView, roiRect, label);

        timer = new AnimationTimer() {
            private long lastFrame = 0;

            @Override
            public void handle(long now) {
                if (lastFrame == 0) {
                    lastFrame = now;
                    return;
                }
                if (now - lastFrame < FRAME_INTERVAL_NS) return;
                lastFrame = now;
                tick();
            }
        };
    }

    /**
     * 读取 AppConfig 中的 ROI 字段
     */
    private static int getRoiField(String name, int defaultVal) {
        try {
            Field field = AppConfig.class.getDeclaredField(name);
            return field.getInt(null);
        } catch (Exception e) {
            return defaultVal;
        }
    }

    /**
     * 切换全帧模式
     */
    public void setFullFrameMode(boolean enabled, String roiPrefix) {
        this.fullFrameMode = enabled;
        this.roiPrefix = enabled ? roiPrefix : null;
        roiRect.setVisible(enabled);
        // 切换模式时清除旧帧缓存，避免显示过期画面
        if (!enabled) {
            cachedImage = null;
            argbBuffer = null;
        }
    }

    private void tick() {
        double w = root.getWidth();
        double h = root.getHeight();
        if (w < 10 || h < 10) return;

        if (fullFrameMode) {
            renderFullFrame(w, h);
        } else {
            renderRoiFrame(w, h);
        }
    }

    /**
     * 普通模式：显示 ROI 裁剪帧（灰度）
     */
    private void renderRoiFrame(double w, double h) {
        roiRect.setVisible(false);

        CaptureFrameBuffer.RoiFrame frame = CaptureFrameBuffer.getInstance().getFrame(roiIndex);
        if (frame == null || frame.pixels().length == 0) {
            imageView.setVisible(false);
            return;
        }

        int fw = frame.width();
        int fh = frame.height();
        byte[] pixels = frame.pixels();

        // 跳帧：帧未更新则跳过渲染
        if (frame.timestamp() <= lastRenderedTimestamp) return;
        lastRenderedTimestamp = frame.timestamp();

        ensureBuffers(fw, fh);

        // 灰度 byte[] → ARGB int[]
        int len = Math.min(fw * fh, pixels.length);
        for (int i = 0; i < len; i++) {
            int gray = pixels[i] & 0xFF;
            argbBuffer[i] = (0xFF << 24) | (gray << 16) | (gray << 8) | gray;
        }

        updateAndLayout(fw, fh, w, h);
    }

    /**
     * 全帧模式：显示完整画面 + ROI 矩形框（BGRA）
     */
    private void renderFullFrame(double w, double h) {
        CaptureFrameBuffer.RoiFrame frame = CaptureFrameBuffer.getInstance().getFullFrame();
        if (frame == null || frame.pixels().length == 0) {
            imageView.setVisible(false);
            return;
        }

        int fw = frame.width();
        int fh = frame.height();
        byte[] pixels = frame.pixels();

        // 跳帧：帧未更新则跳过渲染
        if (frame.timestamp() <= lastRenderedTimestamp) return;
        lastRenderedTimestamp = frame.timestamp();

        ensureBuffers(fw, fh);

        // BGRA byte[] → ARGB int[]
        int pxCount = fw * fh;
        int maxByteIdx = Math.min(pxCount * 4, pixels.length);
        for (int i = 0, off = 0; i < pxCount && off + 3 < maxByteIdx; i++, off += 4) {
            int b = pixels[off] & 0xFF;
            int g = pixels[off + 1] & 0xFF;
            int r = pixels[off + 2] & 0xFF;
            int a = pixels[off + 3] & 0xFF;
            argbBuffer[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }

        updateAndLayout(fw, fh, w, h);

        // ROI 矩形框
        if (roiPrefix != null) {
            double availW = w - PADDING * 2;
            double availH = h - PADDING * 2 - 16;
            double scale = Math.min(1.0, Math.min(availW / fw, availH / fh));
            double dw = fw * scale;
            double dh = fh * scale;
            double imgX = (w - dw) / 2.0;
            double imgY = (h - dh) / 2.0;

            int roiX = getRoiField(roiPrefix + "X", 8900);
            int roiY = getRoiField(roiPrefix + "Y", 300);
            int roiW = getRoiField(roiPrefix + "W", 1000);
            int roiH = getRoiField(roiPrefix + "H", 0);

            // 万分比 → 像素
            double rrX = roiX * fw / 10000.0;
            double rrY = roiY * fh / 10000.0;
            double rrW = roiW * fw / 10000.0;
            double rrH = (roiH == 0) ? rrW : roiH * fh / 10000.0;

            roiRect.setX(imgX + rrX * scale);
            roiRect.setY(imgY + rrY * scale);
            roiRect.setWidth(rrW * scale);
            roiRect.setHeight(rrH * scale);
            roiRect.setVisible(true);
        }
    }

    /**
     * 确保 argbBuffer 和 WritableImage 容量足够（懒分配 + 复用）
     */
    private void ensureBuffers(int fw, int fh) {
        int pxCount = fw * fh;
        if (argbBuffer == null || argbBuffer.length < pxCount) {
            argbBuffer = new int[pxCount];
        }
        if (cachedImage == null || cachedImage.getWidth() != fw || cachedImage.getHeight() != fh) {
            cachedImage = new WritableImage(fw, fh);
        }
    }

    /**
     * 将 argbBuffer 写入缓存图像并布局 ImageView
     */
    private void updateAndLayout(int fw, int fh, double pw, double ph) {
        cachedImage.getPixelWriter().setPixels(0, 0, fw, fh,
                PixelFormat.getIntArgbPreInstance(), argbBuffer, 0, fw);
        imageView.setImage(cachedImage);

        double availW = pw - PADDING * 2;
        double availH = ph - PADDING * 2 - 16;
        double scale = Math.min(1.0, Math.min(availW / fw, availH / fh));
        double dw = fw * scale;
        double dh = fh * scale;
        imageView.setLayoutX((pw - dw) / 2.0);
        imageView.setLayoutY((ph - dh) / 2.0);
        imageView.setFitWidth(dw);
        imageView.setFitHeight(dh);
        imageView.setVisible(true);

        label.setLayoutX(PADDING);
        label.setLayoutY(14);
    }

    public Pane getNode() {
        return root;
    }

    public void start() {
        timer.start();
    }

    public void stop() {
        timer.stop();
        cachedImage = null;
        argbBuffer = null;
    }
}
