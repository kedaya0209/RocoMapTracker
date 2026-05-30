package com.luoke.app.ui.component.setting;

import net.jcip.annotations.NotThreadSafe;
import com.luoke.app.capture.frame.CaptureFrameBuffer;
import javafx.animation.AnimationTimer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Group;
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
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * ROI 缩放弹窗 — 独立窗口显示全帧画面，ROI 线框可拖拽/缩放。
 * <p>
 * 从 RoiPreview 提取，管理弹窗生命周期、帧渲染和鼠标交互。
 */
@NotThreadSafe
@Slf4j
class RoiZoomPopup {

    private static final long FRAME_INTERVAL_NS = 33_000_000;
    private static final double HANDLE_SIZE = 8;
    private static final double MIN_ROI_PX = 10;

    private final int roiIndex;
    private final String roiPrefix;
    private final Color accentColor;
    private final Runnable onClose;

    @Setter
    private Runnable onRoiChanged;

    private Stage zoomStage;
    private AnimationTimer zoomTimer;
    private Pane zoomRoot;
    private ImageView zoomImageView;
    private Rectangle zoomRoiRect;
    private WritableImage zoomCachedImage;
    private int[] zoomArgbBuffer;
    private long zoomLastTimestamp;
    private Rectangle[] zoomHandles;

    private double zDragStartSceneX, zDragStartSceneY;
    private double zDragOrigRectX, zDragOrigRectY, zDragOrigRectW, zDragOrigRectH;
    private boolean zDraggingRect;
    private int zActiveCorner = -1;
    private double dragDialogStartX, dragDialogStartY;

    private double currentFw, currentFh;

    RoiZoomPopup(int roiIndex, String roiPrefix, Color accentColor, Runnable onClose) {
        this.roiIndex = roiIndex;
        this.roiPrefix = roiPrefix;
        this.accentColor = accentColor;
        this.onClose = onClose;
    }

    void open(Stage ownerStage, double screenX, double screenY) {
        if (zoomStage != null && zoomStage.isShowing()) {
            zoomStage.toFront();
            return;
        }

        String titleText = "ROI 调整 - " + (roiIndex == 0 ? "小地图" : "OCR");

        zoomStage = new Stage();
        zoomStage.initStyle(StageStyle.TRANSPARENT);
        if (ownerStage != null) zoomStage.initOwner(ownerStage);
        zoomStage.setMinWidth(500);
        zoomStage.setMinHeight(360);
        zoomStage.setWidth(880);
        zoomStage.setHeight(640);

        VBox dialogRoot = new VBox();
        dialogRoot.setStyle("-fx-background-color: -color-bg-default; " +
                "-fx-background-radius: 12; -fx-border-color: -color-border-muted; " +
                "-fx-border-radius: 12; -fx-border-width: 1.5;");

        HBox titleBar = new HBox();
        titleBar.setAlignment(Pos.CENTER_LEFT);
        titleBar.setCursor(Cursor.MOVE);
        titleBar.setPadding(new Insets(12, 15, 8, 18));
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
        closeBtn.setOnAction(_ -> close());

        titleBar.getChildren().addAll(titleLabel, spacer, closeBtn);

        zoomRoot = new Pane();
        zoomRoot.setStyle("-fx-background-color: #1a1a2e;");
        VBox.setVgrow(zoomRoot, Priority.ALWAYS);

        zoomImageView = new ImageView();
        zoomImageView.setPreserveRatio(true);
        zoomImageView.setSmooth(false);

        zoomRoiRect = new Rectangle();
        zoomRoiRect.setStroke(accentColor);
        zoomRoiRect.setFill(Color.TRANSPARENT);
        zoomRoiRect.setStrokeWidth(2);

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

        dialogRoot.getChildren().addAll(titleBar, zoomRoot);

        StackPane rootStack = new StackPane(dialogRoot);
        Rectangle clip = new Rectangle(0, 0, 880, 640);
        clip.setArcWidth(24);
        clip.setArcHeight(24);
        clip.widthProperty().bind(rootStack.widthProperty());
        clip.heightProperty().bind(rootStack.heightProperty());
        rootStack.setClip(clip);

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
        zoomStage.setOnHidden(_ -> cleanup());
        zoomTimer.start();

        if (screenX > 0 && screenY > 0) {
            zoomStage.setX(screenX - 440);
            zoomStage.setY(screenY - 320);
        }

        zoomStage.show();
    }

    void close() {
        if (zoomStage != null) {
            zoomStage.close();
        }
    }

    boolean isShowing() {
        return zoomStage != null && zoomStage.isShowing();
    }

    private void cleanup() {
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
        if (onClose != null) onClose.run();
    }

    // ==================== 帧渲染 ====================

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

        int pxCount = (int) (fw * fh);
        if (zoomArgbBuffer == null || zoomArgbBuffer.length < pxCount) {
            zoomArgbBuffer = new int[pxCount];
        }
        if (zoomCachedImage == null || zoomCachedImage.getWidth() != fw || zoomCachedImage.getHeight() != fh) {
            zoomCachedImage = new WritableImage((int) fw, (int) fh);
        }

        bgra2argb(pixels, pxCount, zoomArgbBuffer);

        zoomCachedImage.getPixelWriter().setPixels(0, 0, (int) fw, (int) fh,
                PixelFormat.getIntArgbPreInstance(), zoomArgbBuffer, 0, (int) fw);
        zoomImageView.setImage(zoomCachedImage);

        currentFw = fw;
        currentFh = fh;
        double scale = Math.min(w / fw, h / fh);
        double dw = fw * scale;
        double dh = fh * scale;
        double imgX = (w - dw) / 2.0;
        double imgY = (h - dh) / 2.0;

        zoomImageView.setFitWidth(dw);
        zoomImageView.setFitHeight(dh);
        contentGroup.setLayoutX(imgX);
        contentGroup.setLayoutY(imgY);

        if (roiPrefix != null) {
            setRect(fw, fh, scale, zoomRoiRect);

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

    private void setRect(double fw, double fh, double scale, Rectangle rect) {
        int roiX = RoiPreview.getRoiField(roiPrefix + "X", 8900);
        int roiY = RoiPreview.getRoiField(roiPrefix + "Y", 300);
        int roiW = RoiPreview.getRoiField(roiPrefix + "W", 1000);
        int roiH = RoiPreview.getRoiField(roiPrefix + "H", 0);

        double rrX = roiX * fw / 10000.0;
        double rrY = roiY * fh / 10000.0;
        double rrW = roiW * fw / 10000.0;
        double rrH = (roiH == 0) ? rrW : roiH * fh / 10000.0;

        rect.setX(rrX * scale);
        rect.setY(rrY * scale);
        rect.setWidth(rrW * scale);
        rect.setHeight(rrH * scale);
    }

    private static void bgra2argb(byte[] pixels, int pxCount, int[] argbBuffer) {
        int maxByteIdx = Math.min(pxCount * 4, pixels.length);
        for (int i = 0, off = 0; i < pxCount && off + 3 < maxByteIdx; i++, off += 4) {
            int b = pixels[off] & 0xFF;
            int g = pixels[off + 1] & 0xFF;
            int r = pixels[off + 2] & 0xFF;
            int a = pixels[off + 3] & 0xFF;
            argbBuffer[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
    }

    // ==================== ROI 交互 ====================

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

        RoiPreview.setRoiField(roiPrefix + "X", roiX);
        RoiPreview.setRoiField(roiPrefix + "Y", roiY);
        RoiPreview.setRoiField(roiPrefix + "W", roiW);
        RoiPreview.setRoiField(roiPrefix + "H", roiH);

        if (onRoiChanged != null) {
            onRoiChanged.run();
        }
    }

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
            case 0 -> {
                newX = Math.min(origX + origW - MIN_ROI_PX, origX + dx);
                newY = Math.min(origY + origH - MIN_ROI_PX, origY + dy);
                newW = origX + origW - newX;
                newH = origY + origH - newY;
            }
            case 1 -> {
                newY = Math.min(origY + origH - MIN_ROI_PX, origY + dy);
                newW = Math.max(MIN_ROI_PX, origW + dx);
                newH = origY + origH - newY;
            }
            case 2 -> {
                newX = Math.min(origX + origW - MIN_ROI_PX, origX + dx);
                newW = origX + origW - newX;
                newH = Math.max(MIN_ROI_PX, origH + dy);
            }
            case 3 -> {
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
}
