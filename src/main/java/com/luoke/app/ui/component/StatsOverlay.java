package com.luoke.app.ui.component;

import com.luoke.app.config.AppConfig;
import com.luoke.app.context.StatsContext;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;

public class StatsOverlay extends StackPane {

    // 单例实例
    private static StatsOverlay instance;

    private final Label statsLabel;
    private final Rectangle background;

    // 私有构造函数
    private StatsOverlay() {
        // 1. 初始化文字
        statsLabel = new Label();
        statsLabel.setFont(Font.font("Microsoft YaHei", 13));
        statsLabel.setTextFill(Color.WHITE);

        // 2. 初始化半透明背景
        background = new Rectangle();
        background.setFill(Color.rgb(0, 0, 0, 0)); // 50% 透明黑
        background.setArcWidth(10);
        background.setArcHeight(10);

        // 3. 绑定背景尺寸：背景宽/高 = 文字宽/高 + 边距
        background.widthProperty().bind(statsLabel.widthProperty().add(24));
        background.heightProperty().bind(statsLabel.heightProperty().add(12));

        // 4. 组装容器
        this.getChildren().addAll(background, statsLabel);
        this.setAlignment(Pos.CENTER);
        this.setPickOnBounds(false); // 允许点击穿透，不拦截鼠标操作地图
        this.setPadding(new Insets(5));

        // 初始设为隐藏
        this.setVisible(false);
        this.setManaged(true); // 不参与父容器的自动布局计算，避免引起抖动
    }

    // 获取单例的方法
    public static StatsOverlay getInstance() {
        if (instance == null) {
            instance = new StatsOverlay();
        }
        return instance;
    }

    /**
     * 更新数据并控制显示逻辑
     */
    public void update() {
        StatsContext stats = StatsContext.getInstance();
        StringBuilder sb = new StringBuilder();

        // 根据配置组合文本
        if (AppConfig.SHOW_STATS_MAP_TIME) sb.append(String.format("小地图:%dms  ", stats.getLastMapDetectMs()));
        if (AppConfig.SHOW_STATS_MATCH_TIME) sb.append(String.format("匹配:%dms  ", stats.getLastMatchMs()));
        if (AppConfig.SHOW_STATS_DIR_TIME) sb.append(String.format("朝向:%dms  ", stats.getLastDirectionMs()));
        if (AppConfig.SHOW_STATS_FPS) sb.append(String.format("FPS:%d", stats.getFrequency()));

        String text = sb.toString().trim();

        if (text.isEmpty()) {
            if (this.isVisible()) {
                this.setVisible(false);
                this.setManaged(false);
            }
        } else {
            statsLabel.setText(text);
            if (!this.isVisible()) {
                this.setVisible(true);
                this.setManaged(true);
            }
        }
    }
}