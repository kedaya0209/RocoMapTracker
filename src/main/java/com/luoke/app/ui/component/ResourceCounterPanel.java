package com.luoke.app.ui.component;

import atlantafx.base.theme.Styles;
import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.Duration;

import java.util.Map;

public class ResourceCounterPanel extends VBox {

    private static volatile ResourceCounterPanel instance;
    private final Label titleLabel;

    private ResourceCounterPanel() {
        super(10);
        setPadding(new Insets(15));
        setPrefWidth(220); // 稍微加宽一点，防止文字溢出

        setStyle("-fx-background-color: rgba(30, 30, 30, 0.85); " +
                "-fx-background-radius: 10; " +
                "-fx-border-color: rgba(255, 255, 255, 0.1); " +
                "-fx-border-radius: 10;");

        titleLabel = new Label("采集统计");
        titleLabel.getStyleClass().add(Styles.TEXT_BOLD);
        titleLabel.setTextFill(Color.WHITE);

        getChildren().add(titleLabel);

        setVisible(false);
        setOpacity(0);
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
            Node title = getChildren().get(0);
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
                nameLabel.setTextFill(Color.web("#CCCCCC"));

                Label countLabel = new Label(String.valueOf(total));
                countLabel.setTextFill(Color.web("#00BFFF"));
                countLabel.setStyle("-fx-font-weight: bold;");

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
            ft.setToValue(1.0);
        } else {
            ft.setFromValue(getOpacity());
            ft.setToValue(0.0);
            ft.setOnFinished(e -> setVisible(false));
        }
        ft.play();
    }
}