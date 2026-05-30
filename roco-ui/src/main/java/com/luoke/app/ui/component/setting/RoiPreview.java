package com.luoke.app.ui.component.setting;

import net.jcip.annotations.NotThreadSafe;
import com.luoke.app.capture.frame.CaptureFrameBuffer;
import com.luoke.app.ui.service.resource.SvgManager;
import javafx.animation.AnimationTimer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * ROI 截帧预览 — 在设置面板分类顶部实时显示指定 ROI 的截图画面。
 * 视频流模式：复用 WritableImage + int[] 缓冲区，避免每帧创建 8MB+ 临时对象。
 * 支持两种模式：
 * - 普通模式：从 CaptureFrameBuffer 读取 ROI 裁剪帧（灰度）
 * - 全帧模式：从 CaptureFrameBuffer 读取全帧（BGRA）并叠加 ROI 矩形框
 *
 * <p>全帧模式下点击「放大」按钮弹出独立窗口，ROI 线框可拖拽移动位置、
 */
@NotThreadSafe
@Slf4j
public class RoiPreview {

    private static final double PREVIEW_HEIGHT = 180;
    private static final double PADDING = 12;
    private static final long FRAME_INTERVAL_NS = 33_000_000; // 30fps

    private final int roiIndex;
    private final Pane root;
    private final ImageView imageView;
    private final Rectangle roiRect;
    private final AnimationTimer timer;

    // ---- 全帧模式 ----
    private boolean fullFrameMode;
    private String roiPrefix;
    private final Color accentColor;

    // ---- 布局状态 ----
    private final Pane imageArea;
    private final Button zoomBtn;
    private double currentFw;
    private double currentFh;

    // ---- 复用缓冲区 ----
    private WritableImage cachedImage;
    private int[] argbBuffer;
    private long lastRenderedTimestamp;

    // ==================== 弹出窗口（放大调整） ====================

    private RoiZoomPopup zoomPopup;
    @Setter
    private Runnable onRoiChanged;

    // ---- 拖拽撕离状态 ----
    private double tearStartScreenX, tearStartScreenY;
    private boolean tearDetected;

    /** 所属父窗口（弹出窗口需稳定在其之上）
     * -- SETTER --
     *  设置所属父窗口，弹出窗口会设置 owner 确保始终在其之上。
     */
    @Setter
    private Stage ownerStage;
    /** 全屏/还原图标节点缓存 */
    private final Node zoomIcon;
    private final Node zoomExitIcon;

    /**
     * @param roiIndex    ROI 索引（0=小地图, 1=OCR）
     * @param labelText   显示标签
     * @param accentColor ROI 矩形框颜色
     */
    public RoiPreview(int roiIndex, String labelText, Color accentColor) {
        this.roiIndex = roiIndex;
        this.accentColor = accentColor;
        this.fullFrameMode = false;

        root = new Pane();
        root.setStyle("-fx-background-color: -color-bg-default; -fx-background-radius: 8; " +
                "-fx-border-color: -color-border-muted; -fx-border-width: 0 0 1 0;");
        root.setPrefHeight(PREVIEW_HEIGHT);
        root.setMinHeight(120);
        root.setMaxHeight(220);

        // --- 图像区域容器 ---
        imageArea = new Pane();
        imageArea.setManaged(false);
        imageArea.setPickOnBounds(true);

        // --- 图像 ---
        imageView = new ImageView();
        imageView.setPreserveRatio(true);
        imageView.setSmooth(false);
        imageView.setMouseTransparent(true);

        // --- ROI 线框（仅显示，不可交互） ---
        roiRect = new Rectangle();
        roiRect.setStroke(accentColor);
        roiRect.setFill(Color.TRANSPARENT);
        roiRect.setStrokeWidth(2);
        roiRect.setMouseTransparent(true);
        roiRect.setVisible(false);

        Group roiGroup = new Group(roiRect);
        Group contentGroup = new Group(imageView, roiGroup);
        imageArea.getChildren().add(contentGroup);

        // --- 放大按钮（SvgManager 图标） ---
        zoomBtn = new Button();
        zoomIcon = SvgManager.createIcon("/icon/fullscreen.svg", 16);
        zoomExitIcon = SvgManager.createIcon("/icon/fullscreen-exit.svg", 16);
        zoomBtn.setGraphic(zoomIcon);
        zoomBtn.setStyle("-fx-cursor: hand; -fx-background-color: -color-bg-default; " +
                "-fx-background-radius: 4; -fx-padding: 4; -fx-font-size: 11px; " +
                "-fx-border-color: -color-border-muted; -fx-border-radius: 4; -fx-border-width: 0.5;");
        zoomBtn.setFocusTraversable(false);
        zoomBtn.setVisible(false);
        zoomBtn.setOnAction(_ -> toggleZoom());

        // --- 顶部栏（标签 + 放大按钮，HBox 自动对齐） ---
        Text label = new Text(labelText);
        label.setStyle("-fx-fill: -color-fg-default; -fx-font-weight: bold; -fx-font-size: 12px;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox topBar = new HBox(8, label, spacer, zoomBtn);
        topBar.setPadding(new Insets(PADDING, PADDING, 0, PADDING));
        topBar.prefWidthProperty().bind(root.widthProperty());
        topBar.setAlignment(Pos.CENTER);

        root.getChildren().addAll(imageArea, topBar);

        // ==================== AnimationTimer ====================

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

        // ==================== 拖拽撕离（tear-off） ====================

        imageArea.setOnMousePressed(e -> {
            if (fullFrameMode) {
                tearStartScreenX = e.getScreenX();
                tearStartScreenY = e.getScreenY();
                tearDetected = false;
            }
        });

        imageArea.setOnMouseDragged(e -> {
            if (fullFrameMode && !tearDetected) {
                double dx = e.getScreenX() - tearStartScreenX;
                double dy = e.getScreenY() - tearStartScreenY;
                if (Math.abs(dx) > 20 || Math.abs(dy) > 20) {
                    tearDetected = true;
                    openZoomPopupAt(e.getScreenX(), e.getScreenY());
                }
            }
        });
    }

    // ==================== 配置读写 ====================

    static int getRoiField(String key, int defaultVal) {
        SettingDef def = SettingDefinitions.findDef(key);
        if (def == null || def.getter() == null) return defaultVal;
        Object val = def.getter().get();
        return val instanceof Number n ? n.intValue() : defaultVal;
    }

    static void setRoiField(String key, int value) {
        SettingDef def = SettingDefinitions.findDef(key);
        if (def != null && def.setter() != null) {
            def.setter().accept(value);
        }
    }

    // ==================== 模式切换 ====================

    public void setFullFrameMode(boolean enabled, String roiPrefix) {
        this.fullFrameMode = enabled;
        this.roiPrefix = enabled ? roiPrefix : null;
        roiRect.setVisible(enabled);
        zoomBtn.setVisible(enabled);
        imageArea.setCursor(enabled ? Cursor.MOVE : Cursor.DEFAULT);
        if (!enabled) {
            closeZoomPopup();
            cachedImage = null;
            argbBuffer = null;
        }
    }

    // ==================== 弹出窗口管理 ====================

    private void toggleZoom() {
        if (zoomPopup != null && zoomPopup.isShowing()) {
            zoomPopup.close();
        } else {
            openZoomPopupAt(-1, -1);
        }
    }

    private void openZoomPopupAt(double screenX, double screenY) {
        zoomPopup = new RoiZoomPopup(roiIndex, roiPrefix, accentColor,
                () -> zoomBtn.setGraphic(zoomIcon));
        zoomPopup.setOnRoiChanged(onRoiChanged);
        zoomPopup.open(ownerStage, screenX, screenY);
        zoomBtn.setGraphic(zoomExitIcon);
    }

    private void closeZoomPopup() {
        if (zoomPopup != null) {
            zoomPopup.close();
        }
    }

    // ==================== 帧渲染（内联预览） ====================

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

        if (frame.timestamp() <= lastRenderedTimestamp) return;
        lastRenderedTimestamp = frame.timestamp();

        ensureBuffers(fw, fh);

        int len = Math.min(fw * fh, pixels.length);
        for (int i = 0; i < len; i++) {
            int gray = pixels[i] & 0xFF;
            argbBuffer[i] = (0xFF << 24) | (gray << 16) | (gray << 8) | gray;
        }

        updateAndLayout(fw, fh, w, h);
    }

    private void renderFullFrame(double w, double h) {
        CaptureFrameBuffer.RoiFrame frame = CaptureFrameBuffer.getInstance().getFullFrame();
        if (frame == null || frame.pixels().length == 0) {
            imageView.setVisible(false);
            return;
        }

        currentFw = frame.width();
        currentFh = frame.height();
        byte[] pixels = frame.pixels();

        if (frame.timestamp() <= lastRenderedTimestamp) return;
        lastRenderedTimestamp = frame.timestamp();

        ensureBuffers((int) currentFw, (int) currentFh);

        int pxCount = (int) (currentFw * currentFh);
        bgra2argb(pixels, pxCount, argbBuffer);

        // 缩放适配
        double availW = w - PADDING * 2;
        double availH = h - PADDING * 2 - 16;
        double currentScale = Math.min(1.0, Math.min(availW / currentFw, availH / currentFh));
        double dw = currentFw * currentScale;
        double dh = currentFh * currentScale;
        double imgX = (w - dw) / 2.0;
        double imgY = (h - dh) / 2.0;

        cachedImage.getPixelWriter().setPixels(0, 0, (int) currentFw, (int) currentFh,
                PixelFormat.getIntArgbPreInstance(), argbBuffer, 0, (int) currentFw);
        imageView.setImage(cachedImage);

        imageArea.setLayoutX(imgX);
        imageArea.setLayoutY(imgY);
        imageArea.setPrefSize(dw, dh);
        imageArea.setMaxSize(dw, dh);

        imageView.setFitWidth(dw);
        imageView.setFitHeight(dh);
        imageView.setVisible(true);

        // ROI 矩形框（仅显示）
        if (roiPrefix != null) {
            setRect(currentFw, currentFh, currentScale, roiRect);
            roiRect.setVisible(true);
        }
    }

    // ==================== 缓冲区 ====================

    private void ensureBuffers(int fw, int fh) {
        int pxCount = fw * fh;
        if (argbBuffer == null || argbBuffer.length < pxCount) {
            argbBuffer = new int[pxCount];
        }
        if (cachedImage == null || cachedImage.getWidth() != fw || cachedImage.getHeight() != fh) {
            cachedImage = new WritableImage(fw, fh);
        }
    }

    private void updateAndLayout(int fw, int fh, double pw, double ph) {
        cachedImage.getPixelWriter().setPixels(0, 0, fw, fh,
                PixelFormat.getIntArgbPreInstance(), argbBuffer, 0, fw);
        imageView.setImage(cachedImage);

        double availW = pw - PADDING * 2;
        double availH = ph - PADDING * 2 - 16;
        double scale = Math.min(1.0, Math.min(availW / fw, availH / fh));
        double dw = fw * scale;
        double dh = fh * scale;

        imageArea.setLayoutX((pw - dw) / 2.0);
        imageArea.setLayoutY((ph - dh) / 2.0);
        imageArea.setPrefSize(dw, dh);
        imageArea.setMaxSize(dw, dh);

        imageView.setFitWidth(dw);
        imageView.setFitHeight(dh);
        imageView.setVisible(true);
    }

    // ==================== 像素转换 ====================

    private void bgra2argb(byte[] pixels, int pxCount, int[] argbBuffer) {
        int maxByteIdx = Math.min(pxCount * 4, pixels.length);
        for (int i = 0, off = 0; i < pxCount && off + 3 < maxByteIdx; i++, off += 4) {
            int b = pixels[off] & 0xFF;
            int g = pixels[off + 1] & 0xFF;
            int r = pixels[off + 2] & 0xFF;
            int a = pixels[off + 3] & 0xFF;
            argbBuffer[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
    }

    private void setRect(double fw, double fh, double scale, Rectangle rect) {
        int roiX = getRoiField(roiPrefix + "X", 8900);
        int roiY = getRoiField(roiPrefix + "Y", 300);
        int roiW = getRoiField(roiPrefix + "W", 1000);
        int roiH = getRoiField(roiPrefix + "H", 0);

        double rrX = roiX * fw / 10000.0;
        double rrY = roiY * fh / 10000.0;
        double rrW = roiW * fw / 10000.0;
        double rrH = (roiH == 0) ? rrW : roiH * fh / 10000.0;

        rect.setX(rrX * scale);
        rect.setY(rrY * scale);
        rect.setWidth(rrW * scale);
        rect.setHeight(rrH * scale);
    }

    // ==================== 公开 API ====================

    public Pane getNode() {
        return root;
    }

    public void start() {
        timer.start();
    }

    public void stop() {
        timer.stop();
        closeZoomPopup();
        zoomBtn.setText("放大");
        imageView.setImage(null);
        cachedImage = null;
        argbBuffer = null;
    }
}
