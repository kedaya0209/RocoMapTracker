package com.luoke.app.ui.component;

import atlantafx.base.theme.Styles;
import com.luoke.app.config.AppConfig;
import com.luoke.app.hook.AbstractGenericHook;
import com.luoke.app.hook.HookEventType;
import com.luoke.app.hook.event.MaterialCollectionEvent;
import com.luoke.app.hook.multicast.HookRegistry;
import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.Map;
import java.util.Set;

public class ResourceCounterPanel extends VBox {

    private static volatile ResourceCounterPanel instance;

    private ResourceCounterPanel() {
        super(10);
        setPadding(new Insets(15));
        setPrefWidth(AppConfig.RESOURCE_COUNTER_WIDTH);

        setStyle("-fx-background-color: -color-bg-default; " +
                "-fx-background-radius: 10; " +
                "-fx-border-color: -color-border-muted; " +
                "-fx-border-radius: 10;");
        setOpacity(AppConfig.RESOURCE_COUNTER_OPACITY);

        Label titleLabel = new Label("采集统计");
        titleLabel.getStyleClass().add(Styles.TEXT_BOLD);
        titleLabel.setStyle("-fx-text-fill: -color-fg-default;");

        getChildren().add(titleLabel);

        setVisible(false);
        setOpacity(0);

        // 订阅物资采集更新事件，替代 MaterialCollectionContext 的直接调用
        HookRegistry.INSTANCE.register(new AbstractGenericHook<MaterialCollectionEvent>() {
            @Override
            public void onEvent(HookEventType eventType, MaterialCollectionEvent data) {
                refreshData(data.summary());
            }

            @Override
            public Set<HookEventType> supportedEvents() {
                return Set.of(HookEventType.MATERIAL_COLLECTION_UPDATED);
            }
        });
    }

    public static ResourceCounterPanel getInstance() {
        if (instance == null) {
            synchronized (ResourceCounterPanel.class) {
                if (instance == null) {
                    instance = new ResourceCounterPanel();
                }
            }
        }
        return instance;
    }

    /**
     * 【关键改动】响应式刷新方法
     * 由 MaterialCollectionContext 在数据变动时调用
     */
    public void refreshData(Map<String, Integer> summary) {
        // 必须在 JavaFX UI 线程执行
        Platform.runLater(() -> {
            // 1. 保留标题，清理旧数据行
            Node title = getChildren().getFirst();
            getChildren().clear();
            getChildren().add(title);

            // 2. 如果没有数据，直接收起面板
            if (summary == null || summary.isEmpty()) {
                toggle(false);
                return;
            }

            // 3. 构建新数据行
            summary.forEach((name, total) -> {
                HBox row = new HBox(12);
                row.setAlignment(Pos.CENTER_LEFT);

                Label nameLabel = new Label(name + " :");
                nameLabel.setStyle("-fx-text-fill: -color-fg-muted;");

                Label countLabel = new Label(String.valueOf(total));
                countLabel.setStyle("-fx-text-fill: -color-accent-emphasis; -fx-font-weight: bold;");

                row.getChildren().addAll(nameLabel, countLabel);
                getChildren().add(row);
            });

            // 4. 如果面板当前是隐藏状态，且有了新数据，则自动滑出
            if (!isVisible() || getOpacity() < 1.0) {
                toggle(true);
            }
        });
    }

    public void toggle(boolean show) {
        // 防止动画冲突：如果目标状态已达到，则跳过
        if (show == (isVisible() && getOpacity() > 0.5)) return;

        FadeTransition ft = new FadeTransition(Duration.millis(300), this);
        if (show) {
            setVisible(true);
            ft.setFromValue(getOpacity());
            ft.setToValue(AppConfig.RESOURCE_COUNTER_OPACITY);
        } else {
            ft.setFromValue(getOpacity());
            ft.setToValue(0.0);
            ft.setOnFinished(_ -> setVisible(false));
        }
        ft.play();
    }
}