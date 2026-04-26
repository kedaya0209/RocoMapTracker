package com.luoke.app.ui.component;

import atlantafx.base.theme.Styles;
import javafx.animation.FadeTransition;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.Duration;

public class ResourceCounterPanel extends VBox {

    // --- 单例实现开始 ---
    private static volatile ResourceCounterPanel instance;

    // 将构造函数设为私有
    private ResourceCounterPanel() {
        super(10);
        setPadding(new Insets(15));
        setPrefWidth(200);

        // 样式：半透明背景 + 细边框
        setStyle("-fx-background-color: rgba(30, 30, 30, 0.85); " +
                "-fx-background-radius: 10; " +
                "-fx-border-color: rgba(255, 255, 255, 0.1); " +
                "-fx-border-radius: 10;");

        Label title = new Label("采集统计");
        title.getStyleClass().add(Styles.TEXT_BOLD);
        title.setTextFill(Color.WHITE);

        getChildren().add(title);

        // 默认状态为隐藏且透明
        setVisible(false);
        setOpacity(0);
    }
    // --- 单例实现结束 ---

    /**
     * 获取单例实例
     * 使用双重检查锁定确保线程安全
     */
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
     * 切换面板显示状态的动画方法
     */
    public void toggle(boolean show) {
        // 防止重复触发相同状态的动画
        if (show == isVisible() && getOpacity() > 0 == show) return;

        FadeTransition ft = new FadeTransition(Duration.millis(200), this);
        if (show) {
            setVisible(true);
            ft.setToValue(1.0);
        } else {
            ft.setToValue(0.0);
            ft.setOnFinished(e -> setVisible(false));
        }
        ft.play();
    }
}