package com.luoke.app.ui.component;

import net.jcip.annotations.NotThreadSafe;
import com.luoke.app.config.ViewConfig;
import com.luoke.app.context.CameraContext;
import com.luoke.app.ui.service.SvgManager;
import com.luoke.app.ui.service.VersionManager;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.scene.transform.Scale;
import javafx.util.Duration;

@NotThreadSafe
public class FloatToolbox extends VBox {
    private boolean resourcePanelVisible = false;
    private final StackPane collectBtn;
    private final ResourceCounterPanel resourcePanel;

    public FloatToolbox(ResourceCounterPanel resourcePanel, String unifiedBlueColor) {
        super(12);
        this.resourcePanel = resourcePanel;
        setPadding(new Insets(10));
        setAlignment(Pos.TOP_CENTER);
        setPickOnBounds(false);
        setStyle("-fx-background-color: transparent;");

        // 按钮 1：自动跟随（从 follow.svg 加载，1024 规格，需 Group 包裹修正布局尺寸）
        StackPane followBtn = createFollowButton(unifiedBlueColor);

        // 按钮 2：资源计数切换（仅在高级版显示）
        collectBtn = createVectorIconButton(
                "资源采集计数",
                "M9 7V5h6v2h2V5a2 2 0 0 0-2-2H9a2 2 0 0 0-2 2v2h2zm11 8V9a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v6a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2zm-11-4h4v2h-4v-2z",
                false, resourcePanel, unifiedBlueColor
        );

        getChildren().add(followBtn);
        if (VersionManager.getInstance().getCurrentMode() == VersionMode.ADVANCED) {
            getChildren().add(collectBtn);
        }
    }

    public void setCollectButtonVisible(boolean visible) {
        if (visible && !getChildren().contains(collectBtn)) {
            getChildren().add(collectBtn);
        } else if (!visible) {
            getChildren().remove(collectBtn);
        }
    }

    /**
     * 创建跟随模式按钮（从 follow.svg 加载，自动 Group 包裹处理 1024 规格）
     */
    private StackPane createFollowButton(String unifiedBlueColor) {
        StackPane btn = new StackPane();
        btn.setCursor(Cursor.HAND);
        btn.setPadding(new Insets(10));

        SVGPath icon = new SVGPath();
        icon.setContent(SvgManager.getPath("/icon/follow.svg"));
        icon.setFill(Color.WHITE);
        icon.setStroke(Color.BLACK);
        icon.setStrokeWidth(0.2);
        // follow.svg viewBox=1024，缩放到与原图钉图标相同视觉尺寸（~31px）
        double scale = 31.0 / 1024.0;
        icon.getTransforms().add(new Scale(scale, scale));

        // Group 包裹使 layoutBounds = 变换后尺寸，StackPane 按钮不会撑大
        Group wrapper = new Group(icon);

        btn.setOnMouseEntered(_ -> btn.setStyle("-fx-background-color: rgba(255,255,255,0.15); -fx-background-radius: 8;"));
        btn.setOnMouseExited(_ -> btn.setStyle("-fx-background-color: transparent;"));

        Tooltip tooltip = new Tooltip("自动跟随模式 (Space)");
        tooltip.setShowDelay(Duration.millis(150));
        Tooltip.install(btn, tooltip);
        btn.getChildren().add(wrapper);

        CameraContext cameraCtx = CameraContext.getInstance();
        btn.setOnMouseClicked(_ -> cameraCtx.setFollowMode(!cameraCtx.isFollowMode()));
        cameraCtx.onFollowModeChange(() ->
                Platform.runLater(() -> icon.setFill(cameraCtx.isFollowMode() ? Color.web(unifiedBlueColor) : Color.WHITE)));

        return btn;
    }

    private StackPane createVectorIconButton(String hint, String svgPath, boolean isFollowLogic, ResourceCounterPanel panel, String unifiedBlueColor) {
        StackPane btn = new StackPane();
        btn.setCursor(Cursor.HAND);
        btn.setPadding(new Insets(10));

        SVGPath icon = new SVGPath();
        icon.setContent(svgPath);
        icon.setFill(Color.WHITE);
        icon.setStroke(Color.BLACK);
        icon.setStrokeWidth(0.2);
        icon.setScaleX(1.3);
        icon.setScaleY(1.3);

        btn.setOnMouseEntered(_ -> btn.setStyle("-fx-background-color: rgba(255,255,255,0.15); -fx-background-radius: 8;"));
        btn.setOnMouseExited(_ -> btn.setStyle("-fx-background-color: transparent;"));

        Tooltip tooltip = new Tooltip(hint);
        tooltip.setShowDelay(Duration.millis(150));
        Tooltip.install(btn, tooltip);
        btn.getChildren().add(icon);

        if (isFollowLogic) {
            CameraContext cameraCtx = CameraContext.getInstance();
            btn.setOnMouseClicked(_ -> cameraCtx.setFollowMode(!cameraCtx.isFollowMode()));
            cameraCtx.onFollowModeChange(() -> Platform.runLater(() -> icon.setFill(cameraCtx.isFollowMode() ? Color.web(unifiedBlueColor) : Color.WHITE)));
        } else if (panel != null) {
            // 切换面板显示的逻辑
            btn.setOnMouseClicked(_ -> {
                resourcePanelVisible = !resourcePanelVisible;
                panel.toggle(resourcePanelVisible);
                icon.setFill(resourcePanelVisible ? Color.web(unifiedBlueColor) : Color.WHITE);
                //获取图标当前点击状态
                ViewConfig.MATERIAL_COLLECTION = resourcePanelVisible;
            });
        }
        return btn;
    }
}