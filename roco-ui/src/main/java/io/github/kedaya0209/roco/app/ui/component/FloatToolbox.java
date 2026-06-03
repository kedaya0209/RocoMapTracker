package io.github.kedaya0209.roco.app.ui.component;

import net.jcip.annotations.NotThreadSafe;
import io.github.kedaya0209.roco.app.ui.command.AppCommands.ToggleMaterialCollectionCommand;
import io.github.kedaya0209.roco.app.ui.command.AppCommands.SetFollowModeCommand;
import io.github.kedaya0209.roco.app.ui.command.CommandBus;
import io.github.kedaya0209.roco.app.ui.state.AppState;
import javafx.beans.binding.Bindings;
import io.github.kedaya0209.roco.app.ui.service.VersionMode;
import io.github.kedaya0209.roco.app.ui.service.resource.SvgManager;
import io.github.kedaya0209.roco.app.ui.service.ui.VersionManager;
import io.github.kedaya0209.roco.app.ui.state.ViewportState;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
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
    private static volatile FloatToolbox instance;

    private boolean resourcePanelVisible = false;
    private final StackPane collectBtn;
    private final ResourceCounterPanel resourcePanel;

    public static FloatToolbox getInstance() {
        return instance;
    }

    public FloatToolbox(ResourceCounterPanel resourcePanel, String unifiedBlueColor) {
        super(12);
        instance = this;
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
                resourcePanel, unifiedBlueColor
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
        btn.getStyleClass().add("float-toolbox-btn");

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

        Tooltip tooltip = new Tooltip("自动跟随模式 (Space)");
        tooltip.setShowDelay(Duration.millis(150));
        Tooltip.install(btn, tooltip);
        btn.getChildren().add(wrapper);

        btn.setOnMouseClicked(_ ->
                CommandBus.dispatch(new SetFollowModeCommand(!ViewportState.getInstance().isFollowMode())));
        icon.fillProperty().bind(Bindings
                .when(ViewportState.getInstance().followModeProperty())
                .then(Color.web(unifiedBlueColor))
                .otherwise(Color.WHITE));

        return btn;
    }

    private StackPane createVectorIconButton(String hint, String svgPath, ResourceCounterPanel panel, String unifiedBlueColor) {
        StackPane btn = new StackPane();
        btn.getStyleClass().add("float-toolbox-btn");

        SVGPath icon = new SVGPath();
        icon.setContent(svgPath);
        icon.setFill(Color.WHITE);
        icon.setStroke(Color.BLACK);
        icon.setStrokeWidth(0.2);
        icon.setScaleX(1.3);
        icon.setScaleY(1.3);

        Tooltip tooltip = new Tooltip(hint);
        tooltip.setShowDelay(Duration.millis(150));
        Tooltip.install(btn, tooltip);
        btn.getChildren().add(icon);

        if (panel != null) {
            // Property listener 驱动面板和图标（handler 只 dispatch command）
            AppState.getInstance().materialCollectionProperty().addListener((_, _, now) -> {
                resourcePanelVisible = now;
                panel.toggle(now);
                icon.setFill(now ? Color.web(unifiedBlueColor) : Color.WHITE);
            });
            btn.setOnMouseClicked(_ ->
                    CommandBus.dispatch(new ToggleMaterialCollectionCommand()));
        }
        return btn;
    }
}