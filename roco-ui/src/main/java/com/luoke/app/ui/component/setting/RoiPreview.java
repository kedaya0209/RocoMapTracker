package com.luoke.app.ui.component.setting;

import com.luoke.app.capture.CaptureFrameBuffer;
import com.luoke.app.ui.service.SvgManager;
import javafx.animation.AnimationTimer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
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
 * 拖拽角落调整大小，修改实时写回 AppConfig 并通过 SettingDef.setter() 持久化。</p>
 */
@Slf4j
public class RoiPreview {

    private static final double PREVIEW_HEIGHT = 180;
    private static final double PADDING = 12;
    private static final long FRAME_INTERVAL_NS = 125_000_000; // 8fps
    private static final double HANDLE_SIZE = 8;
    private static final double MIN_ROI_PX = 10;

    private final int roiIndex;
    private final Pane root;
    private final ImageView imageView;
    private final Rectangle roiRect;
    private final Text label;
    private final AnimationTimer timer;

    // ---- 全帧模式 ----
    private boolean fullFrameMode;
    private String roiPrefix;
    private final Color accentColor;

    // ---- 布局状态 ----
    private final Pane imageArea;
    private final Group contentGroup;
    private final Group roiGroup;
    private final Button zoomBtn;
    private double currentScale;
    private double currentFw;
    private double currentFh;

    // ---- 复用缓冲区 ----
    private WritableImage cachedImage;
    private int[] argbBuffer;
    private long lastRenderedTimestamp;

    // ==================== 弹出窗口（放大调整） ====================

    private Stage zoomStage;
    private AnimationTimer zoomTimer;
    private Pane zoomRoot;
    private ImageView zoomImageView;
    private Rectangle zoomRoiRect;
    private WritableImage zoomCachedImage;
    private int[] zoomArgbBuffer;
    private long zoomLastTimestamp;
    /** 弹出窗口内 ROI 四角手柄 */
    private Rectangle[] zoomHandles;
    /** 弹出窗口拖拽状态 */
    private double zDragStartSceneX, zDragStartSceneY;
    private double zDragOrigRectX, zDragOrigRectY, zDragOrigRectW, zDragOrigRectH;
    private boolean zDraggingRect;
    private int zActiveCorner = -1;
    /** 对话框拖拽偏移 */
    private double dragDialogStartX, dragDialogStartY;
    /** ROI 坐标变更回调（让 SettingsStage 同步 Spinner 控件） */
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

        roiGroup = new Group(roiRect);
        contentGroup = new Group(imageView, roiGroup);
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
        label = new Text(labelText);
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

    private static int getRoiField(String key, int defaultVal) {
        SettingDef def = SettingDefinitions.findDef(key);
        if (def == null || def.getter() == null) return defaultVal;
        Object val = def.getter().get();
        return val instanceof Number n ? n.intValue() : defaultVal;
    }

    private static void setRoiField(String key, int value) {
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
        if (zoomStage != null && zoomStage.isShowing()) {
            closeZoomPopup();
        } else {
            openZoomPopup();
        }
    }

    private void openZoomPopup() {
        openZoomPopupAt(-1, -1);
    }

    private void openZoomPopupAt(double screenX, double screenY) {
        String titleText = "ROI 调整 - " + (roiIndex == 0 ? "小地图" : "OCR");

        // --- Stage ---
        zoomStage = new Stage();
        zoomStage.initStyle(StageStyle.TRANSPARENT);
        if (ownerStage != null) zoomStage.initOwner(ownerStage);
        zoomStage.setMinWidth(500);
        zoomStage.setMinHeight(360);
        zoomStage.setWidth(880);
        zoomStage.setHeight(640);

        // --- 统一 Dialog 布局（与 SettingsStage 一致） ---
        VBox dialogRoot = new VBox();
        dialogRoot.setStyle("-fx-background-color: -color-bg-default; " +
                "-fx-background-radius: 12; -fx-border-color: -color-border-muted; " +
                "-fx-border-radius: 12; -fx-border-width: 1.5;");

        // 标题栏（可拖拽）
        HBox titleBar = new HBox();
        titleBar.setAlignment(Pos.CENTER_LEFT);
        titleBar.setCursor(Cursor.MOVE);
        titleBar.setPadding(new javafx.geometry.Insets(12, 15, 8, 18));
        // 拖拽移动窗口
        titleBar.setOnMousePressed(e -> {
            dragDialogStartX = e.getScreenX() - zoomStage.getX();
            dragDialogStartY = e.getScreenY() - zoomStage.getY();
        });
        titleBar.setOnMouseDragged(e -> {
            zoomStage.setX(e.getScreenX() - dragDialogStartX);
            zoomStage.setY(e.getScreenY() - dragDialogStartY);
        });

        Label titleLabel = new Label(titleText);
        titleLabel.setStyle("-fx-text-fill: -color-fg-default; -fx-font-weight: bold; -fx-font-size: 14px;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button closeBtn = new Button();
        closeBtn.getStyleClass().addAll("button-circle", "flat");
        SVGPath closeIcon = new SVGPath();
        closeIcon.setContent("M1 1 L9 9 M9 1 L1 9");
        closeIcon.setStyle("-fx-stroke: -color-fg-default; -fx-stroke-width: 2;");
        closeBtn.setGraphic(closeIcon);
        closeBtn.setStyle("-fx-cursor: hand; -fx-padding: 6; -fx-background-color: transparent;");
        closeBtn.setOnAction(_ -> closeZoomPopup());

        titleBar.getChildren().addAll(titleLabel, spacer, closeBtn);

        // --- 内容容器（图像 + ROI 叠加） ---
        zoomRoot = new Pane();
        zoomRoot.setStyle("-fx-background-color: #1a1a2e;");
        VBox.setVgrow(zoomRoot, Priority.ALWAYS);

        // --- ImageView ---
        zoomImageView = new ImageView();
        zoomImageView.setPreserveRatio(true);
        zoomImageView.setSmooth(false);

        // --- ROI 线框 ---
        zoomRoiRect = new Rectangle();
        zoomRoiRect.setStroke(accentColor);
        zoomRoiRect.setFill(Color.TRANSPARENT);
        zoomRoiRect.setStrokeWidth(2);

        // --- 四角手柄 ---
        zoomHandles = new Rectangle[4];
        for (int i = 0; i < 4; i++) {
            zoomHandles[i] = new Rectangle(HANDLE_SIZE, HANDLE_SIZE);
            zoomHandles[i].setFill(Color.WHITE);
            zoomHandles[i].setStroke(accentColor);
            zoomHandles[i].setStrokeWidth(1.5);
            zoomHandles[i].setCursor(Cursor.HAND);
        }

        Group zoomRoiGroup = new Group(zoomRoiRect);
        zoomRoiGroup.getChildren().addAll(zoomHandles);
        Group zoomContentGroup = new Group(zoomImageView, zoomRoiGroup);
        zoomRoot.getChildren().add(zoomContentGroup);

        // --- 组装 ---
        dialogRoot.getChildren().addAll(titleBar, zoomRoot);

        StackPane rootStack = new StackPane(dialogRoot);
        // 圆角裁剪
        Rectangle clip = new Rectangle(0, 0, 880, 640);
        clip.setArcWidth(24);
        clip.setArcHeight(24);
        clip.widthProperty().bind(rootStack.widthProperty());
        clip.heightProperty().bind(rootStack.heightProperty());
        rootStack.setClip(clip);

        // ==================== 鼠标事件 ====================

        zoomRoiRect.setOnMousePressed(this::onZRectPressed);
        zoomRoiRect.setOnMouseDragged(this::onZRectDragged);
        zoomRoiRect.setOnMouseReleased(this::onZRectReleased);
        zoomRoiRect.setOnMouseEntered(_ -> zoomRoot.setCursor(Cursor.MOVE));
        zoomRoiRect.setOnMouseExited(_ -> zoomRoot.setCursor(Cursor.DEFAULT));

        for (int i = 0; i < 4; i++) {
            final int idx = i;
            zoomHandles[i].setOnMousePressed(e -> {
                zActiveCorner = idx;
                zDragStartSceneX = e.getSceneX();
                zDragStartSceneY = e.getSceneY();
                zDragOrigRectX = zoomRoiRect.getX();
                zDragOrigRectY = zoomRoiRect.getY();
                zDragOrigRectW = zoomRoiRect.getWidth();
                zDragOrigRectH = zoomRoiRect.getHeight();
                e.consume();
            });
            zoomHandles[i].setOnMouseDragged(this::onZHandleDragged);
            zoomHandles[i].setOnMouseReleased(e -> {
                zActiveCorner = -1;
                e.consume();
            });
        }

        // ==================== AnimationTimer ====================

        zoomTimer = new AnimationTimer() {
            private long last = 0;

            @Override
            public void handle(long now) {
                if (last == 0) { last = now; return; }
                if (now - last < FRAME_INTERVAL_NS) return;
                last = now;
                renderZoomFrame(zoomContentGroup);
            }
        };

        Scene scene = new Scene(rootStack);
        scene.setFill(Color.TRANSPARENT);
        zoomStage.setScene(scene);
        zoomStage.setOnHidden(_ -> cleanupZoomPopup());
        zoomTimer.start();

        // 定位到拖拽位置（如有）
        if (screenX > 0 && screenY > 0) {
            zoomStage.setX(screenX - 440);
            zoomStage.setY(screenY - 320);
        }

        // 入口动画：缩放 + 淡入（从中心弹出效果）
        zoomStage.show();
        zoomBtn.setGraphic(zoomExitIcon);
    }

    private void closeZoomPopup() {
        if (zoomStage != null) {
            zoomStage.close(); // onHidden → cleanupZoomPopup
        }
    }

    private void cleanupZoomPopup() {
        if (zoomTimer != null) {
            zoomTimer.stop();
            zoomTimer = null;
        }
        zoomStage = null;
        zoomRoot = null;
        zoomImageView = null;
        zoomRoiRect = null;
        zoomHandles = null;
        zoomCachedImage = null;
        zoomArgbBuffer = null;
        zoomLastTimestamp = 0;
        zActiveCorner = -1;
        zDraggingRect = false;
        zoomBtn.setGraphic(zoomIcon);
    }

    // ==================== 弹出窗口帧渲染 ====================

    private void renderZoomFrame(Group contentGroup) {
        double w = zoomRoot.getWidth();
        double h = zoomRoot.getHeight();
        if (w < 10 || h < 10) return;

        CaptureFrameBuffer.RoiFrame frame = CaptureFrameBuffer.getInstance().getFullFrame();
        if (frame == null || frame.pixels().length == 0) return;

        double fw = frame.width();
        double fh = frame.height();
        byte[] pixels = frame.pixels();

        if (frame.timestamp() <= zoomLastTimestamp) return;
        zoomLastTimestamp = frame.timestamp();

        // 缓冲区
        int pxCount = (int) (fw * fh);
        if (zoomArgbBuffer == null || zoomArgbBuffer.length < pxCount) {
            zoomArgbBuffer = new int[pxCount];
        }
        if (zoomCachedImage == null || zoomCachedImage.getWidth() != fw || zoomCachedImage.getHeight() != fh) {
            zoomCachedImage = new WritableImage((int) fw, (int) fh);
        }

        // BGRA → ARGB
        int maxByteIdx = Math.min(pxCount * 4, pixels.length);
        for (int i = 0, off = 0; i < pxCount && off + 3 < maxByteIdx; i++, off += 4) {
            int b = pixels[off] & 0xFF;
            int g = pixels[off + 1] & 0xFF;
            int r = pixels[off + 2] & 0xFF;
            int a = pixels[off + 3] & 0xFF;
            zoomArgbBuffer[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }

        zoomCachedImage.getPixelWriter().setPixels(0, 0, (int) fw, (int) fh,
                PixelFormat.getIntArgbPreInstance(), zoomArgbBuffer, 0, (int) fw);
        zoomImageView.setImage(zoomCachedImage);

        // 缩放适配窗口（同步 currentFw/Fh 供 writeZoomRoiPosition 坐标转换）
        currentFw = fw;
        currentFh = fh;
        double scale = Math.min(w / fw, h / fh);
        double dw = fw * scale;
        double dh = fh * scale;
        double imgX = (w - dw) / 2.0;
        double imgY = (h - dh) / 2.0;

        // 保存缩放参数供鼠标事件使用
        zoomImageView.setFitWidth(dw);
        zoomImageView.setFitHeight(dh);
        contentGroup.setLayoutX(imgX);
        contentGroup.setLayoutY(imgY);

        // ROI 矩形框
        if (roiPrefix != null) {
            int roiX = getRoiField(roiPrefix + "X", 8900);
            int roiY = getRoiField(roiPrefix + "Y", 300);
            int roiW = getRoiField(roiPrefix + "W", 1000);
            int roiH = getRoiField(roiPrefix + "H", 0);

            double rrX = roiX * fw / 10000.0;
            double rrY = roiY * fh / 10000.0;
            double rrW = roiW * fw / 10000.0;
            double rrH = (roiH == 0) ? rrW : roiH * fh / 10000.0;

            zoomRoiRect.setX(rrX * scale);
            zoomRoiRect.setY(rrY * scale);
            zoomRoiRect.setWidth(rrW * scale);
            zoomRoiRect.setHeight(rrH * scale);

            // 更新手柄位置
            double hw = HANDLE_SIZE / 2;
            double rx = zoomRoiRect.getX();
            double ry = zoomRoiRect.getY();
            double rw = zoomRoiRect.getWidth();
            double rh = zoomRoiRect.getHeight();
            zoomHandles[0].setX(rx - hw);
            zoomHandles[0].setY(ry - hw);
            zoomHandles[1].setX(rx + rw - hw);
            zoomHandles[1].setY(ry - hw);
            zoomHandles[2].setX(rx - hw);
            zoomHandles[2].setY(ry + rh - hw);
            zoomHandles[3].setX(rx + rw - hw);
            zoomHandles[3].setY(ry + rh - hw);
        }
    }

    // ==================== 弹出窗口 ROI 交互 ====================

    private void writeZoomRoiPosition(double scale) {
        if (roiPrefix == null) return;
        double xImg = zoomRoiRect.getX() / scale;
        double yImg = zoomRoiRect.getY() / scale;
        double wImg = zoomRoiRect.getWidth() / scale;
        double hImg = zoomRoiRect.getHeight() / scale;

        int roiX = (int) Math.round(xImg / currentFw * 10000);
        int roiY = (int) Math.round(yImg / currentFh * 10000);
        int roiW = (int) Math.round(wImg / currentFw * 10000);
        int roiH = (int) Math.round(hImg / currentFh * 10000);

        roiX = Math.clamp(roiX, 0, 10000);
        roiY = Math.clamp(roiY, 0, 10000);
        roiW = Math.clamp(roiW, 1, 10000 - roiX);
        roiH = Math.clamp(roiH, 1, 10000 - roiY);

        setRoiField(roiPrefix + "X", roiX);
        setRoiField(roiPrefix + "Y", roiY);
        setRoiField(roiPrefix + "W", roiW);
        setRoiField(roiPrefix + "H", roiH);

        if (onRoiChanged != null) {
            onRoiChanged.run();
        }
    }

    /** 获取弹出窗口当前缩放比 */
    private double getZoomScale() {
        return Math.min(zoomRoot.getWidth() / currentFw, zoomRoot.getHeight() / currentFh);
    }

    private void onZRectPressed(MouseEvent e) {
        zDraggingRect = true;
        zDragStartSceneX = e.getSceneX();
        zDragStartSceneY = e.getSceneY();
        zDragOrigRectX = zoomRoiRect.getX();
        zDragOrigRectY = zoomRoiRect.getY();
        e.consume();
    }

    private void onZRectDragged(MouseEvent e) {
        if (!zDraggingRect) return;
        double scale = getZoomScale();
        double dx = (e.getSceneX() - zDragStartSceneX) / scale;
        double dy = (e.getSceneY() - zDragStartSceneY) / scale;

        double newImgX = zDragOrigRectX / scale + dx;
        double newImgY = zDragOrigRectY / scale + dy;
        double roiImgW = zDragOrigRectW / scale;
        double roiImgH = zDragOrigRectH / scale;

        newImgX = Math.clamp(newImgX, 0, currentFw - roiImgW);
        newImgY = Math.clamp(newImgY, 0, currentFh - roiImgH);

        zoomRoiRect.setX(newImgX * scale);
        zoomRoiRect.setY(newImgY * scale);
        writeZoomRoiPosition(scale);
        e.consume();
    }

    private void onZRectReleased(MouseEvent e) {
        if (zDraggingRect) {
            zDraggingRect = false;
            writeZoomRoiPosition(getZoomScale());
            e.consume();
        }
    }

    private void onZHandleDragged(MouseEvent e) {
        if (zActiveCorner < 0) return;
        double scale = getZoomScale();
        double dx = (e.getSceneX() - zDragStartSceneX) / scale;
        double dy = (e.getSceneY() - zDragStartSceneY) / scale;

        double origX = zDragOrigRectX / scale;
        double origY = zDragOrigRectY / scale;
        double origW = zDragOrigRectW / scale;
        double origH = zDragOrigRectH / scale;

        double newX = origX, newY = origY, newW = origW, newH = origH;

        switch (zActiveCorner) {
            case 0 -> { // TL
                newX = Math.min(origX + origW - MIN_ROI_PX, origX + dx);
                newY = Math.min(origY + origH - MIN_ROI_PX, origY + dy);
                newW = origX + origW - newX;
                newH = origY + origH - newY;
            }
            case 1 -> { // TR
                newY = Math.min(origY + origH - MIN_ROI_PX, origY + dy);
                newW = Math.max(MIN_ROI_PX, origW + dx);
                newH = origY + origH - newY;
            }
            case 2 -> { // BL
                newX = Math.min(origX + origW - MIN_ROI_PX, origX + dx);
                newW = origX + origW - newX;
                newH = Math.max(MIN_ROI_PX, origH + dy);
            }
            case 3 -> { // BR
                newW = Math.max(MIN_ROI_PX, origW + dx);
                newH = Math.max(MIN_ROI_PX, origH + dy);
            }
        }

        newX = Math.max(0, newX);
        newY = Math.max(0, newY);
        newW = Math.clamp(newW, MIN_ROI_PX, currentFw - newX);
        newH = Math.clamp(newH, MIN_ROI_PX, currentFh - newY);

        zoomRoiRect.setX(newX * scale);
        zoomRoiRect.setY(newY * scale);
        zoomRoiRect.setWidth(newW * scale);
        zoomRoiRect.setHeight(newH * scale);

        double hw = HANDLE_SIZE / 2;
        zoomHandles[0].setX(newX * scale - hw);
        zoomHandles[0].setY(newY * scale - hw);
        zoomHandles[1].setX((newX + newW) * scale - hw);
        zoomHandles[1].setY(newY * scale - hw);
        zoomHandles[2].setX(newX * scale - hw);
        zoomHandles[2].setY((newY + newH) * scale - hw);
        zoomHandles[3].setX((newX + newW) * scale - hw);
        zoomHandles[3].setY((newY + newH) * scale - hw);

        writeZoomRoiPosition(scale);
        e.consume();
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
        int maxByteIdx = Math.min(pxCount * 4, pixels.length);
        for (int i = 0, off = 0; i < pxCount && off + 3 < maxByteIdx; i++, off += 4) {
            int b = pixels[off] & 0xFF;
            int g = pixels[off + 1] & 0xFF;
            int r = pixels[off + 2] & 0xFF;
            int a = pixels[off + 3] & 0xFF;
            argbBuffer[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }

        // 缩放适配
        double availW = w - PADDING * 2;
        double availH = h - PADDING * 2 - 16;
        currentScale = Math.min(1.0, Math.min(availW / currentFw, availH / currentFh));
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
            int roiX = getRoiField(roiPrefix + "X", 8900);
            int roiY = getRoiField(roiPrefix + "Y", 300);
            int roiW = getRoiField(roiPrefix + "W", 1000);
            int roiH = getRoiField(roiPrefix + "H", 0);

            double rrX = roiX * currentFw / 10000.0;
            double rrY = roiY * currentFh / 10000.0;
            double rrW = roiW * currentFw / 10000.0;
            double rrH = (roiH == 0) ? rrW : roiH * currentFh / 10000.0;

            roiRect.setX(rrX * currentScale);
            roiRect.setY(rrY * currentScale);
            roiRect.setWidth(rrW * currentScale);
            roiRect.setHeight(rrH * currentScale);
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

    // ==================== 回调 ====================

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
        cachedImage = null;
        argbBuffer = null;
    }
}
