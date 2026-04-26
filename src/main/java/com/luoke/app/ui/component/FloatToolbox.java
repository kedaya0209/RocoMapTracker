package com.luoke.app.ui.component;

import com.luoke.app.config.AppConfig;
import com.luoke.app.context.CameraContext;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;

public class FloatToolbox extends VBox {
    private boolean resourcePanelVisible = false;

    public FloatToolbox(ResourceCounterPanel resourcePanel, String unifiedBlueColor) {
        super(12);
        setPadding(new Insets(10));
        setAlignment(Pos.TOP_CENTER);
        setPickOnBounds(false);
        setStyle("-fx-background-color: transparent;");

        // 按钮 1：自动跟随
        StackPane followBtn = createVectorIconButton(
                "自动跟随模式 (Space)",
                "M12 2C8.13 2 5 5.13 5 9c0 5.25 7 13 7 13s7-7.75 7-13c0-3.87-3.13-7-7-7zm0 9.5c-1.38 0-2.5-1.12-2.5-2.5s1.12-2.5 2.5-2.5 2.5 1.12 2.5 2.5-1.12 2.5-2.5 2.5z",
                true, null, unifiedBlueColor
        );

        // 按钮 2：资源计数切换
        StackPane collectBtn = createVectorIconButton(
                "资源采集计数",
                "M9 7V5h6v2h2V5a2 2 0 0 0-2-2H9a2 2 0 0 0-2 2v2h2zm11 8V9a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v6a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2zm-11-4h4v2h-4v-2z",
                false, resourcePanel, unifiedBlueColor
        );

        getChildren().addAll(followBtn, collectBtn);
    }

    private StackPane createVectorIconButton(String hint, String svgPath, boolean isFollowLogic, ResourceCounterPanel panel, String unifiedBlueColor) {
        StackPane btn = new StackPane();
        btn.setCursor(javafx.scene.Cursor.HAND);
        btn.setPadding(new Insets(10));

        SVGPath icon = new SVGPath();
        icon.setContent(svgPath);
        icon.setFill(Color.WHITE);
        icon.setStroke(Color.BLACK);
        icon.setStrokeWidth(0.2);
        icon.setScaleX(1.3);
        icon.setScaleY(1.3);

        btn.setOnMouseEntered(e -> btn.setStyle("-fx-background-color: rgba(255,255,255,0.15); -fx-background-radius: 8;"));
        btn.setOnMouseExited(e -> btn.setStyle("-fx-background-color: transparent;"));

        Tooltip tooltip = new Tooltip(hint);
        tooltip.setShowDelay(Duration.millis(150));
        Tooltip.install(btn, tooltip);
        btn.getChildren().add(icon);

        if (isFollowLogic) {
            btn.setOnMouseClicked(e -> CameraContext.getInstance().setFollowMode(!CameraContext.getInstance().isFollowMode()));
            CameraContext.getInstance().followModeProperty().addListener((obs, old, newVal) -> {
                Platform.runLater(() -> icon.setFill(newVal ? Color.web(unifiedBlueColor) : Color.web("#C0C0C0")));
            });
        } else if (panel != null) {
            // 切换面板显示的逻辑
            btn.setOnMouseClicked(e -> {
                resourcePanelVisible = !resourcePanelVisible;
                panel.toggle(resourcePanelVisible);
                icon.setFill(resourcePanelVisible ? Color.web(unifiedBlueColor) : Color.WHITE);
                //获取图标当前点击状态
                AppConfig.MATERIAL_COLLECTION = resourcePanelVisible;
            });
        }
        return btn;
    }
}