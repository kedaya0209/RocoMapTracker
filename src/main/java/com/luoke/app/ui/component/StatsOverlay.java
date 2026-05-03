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

    private final Label statsLabel;
    private final Rectangle background;

    private StatsOverlay() {
        statsLabel = new Label();
        statsLabel.setFont(Font.font("Microsoft YaHei", 13));
        statsLabel.setTextFill(Color.WHITE);

        background = new Rectangle();
        background.setFill(Color.rgb(0, 0, 0, 0));
        background.setArcWidth(10);
        background.setArcHeight(10);

        background.widthProperty().bind(statsLabel.widthProperty().add(24));
        background.heightProperty().bind(statsLabel.heightProperty().add(12));

        this.getChildren().addAll(background, statsLabel);
        this.setAlignment(Pos.CENTER);
        this.setPickOnBounds(false);
        this.setPadding(new Insets(5));
        this.setVisible(false);
        this.setManaged(true);
    }

    public static StatsOverlay getInstance() {
        return Holder.INSTANCE;
    }

    /**
     * 每帧由 RenderLoop 调用。仅当统计项启用时才构建文本，避免不必要的字符串操作。
     */
    public void update() {
        StatsContext stats = StatsContext.getInstance();

        boolean showMap = AppConfig.SHOW_STATS_MAP_TIME;
        boolean showMatch = AppConfig.SHOW_STATS_MATCH_TIME;
        boolean showDir = AppConfig.SHOW_STATS_DIR_TIME;
        boolean showFps = AppConfig.SHOW_STATS_FPS;

        if (!showMap && !showMatch && !showDir && !showFps) {
            if (isVisible()) {
                setVisible(false);
                setManaged(false);
            }
            return;
        }

        StringBuilder sb = new StringBuilder(64);
        if (showMap) sb.append("小地图:").append(stats.getLastMapDetectMs()).append("ms  ");
        if (showMatch) sb.append("匹配:").append(stats.getLastMatchMs()).append("ms  ");
        if (showDir) sb.append("朝向:").append(stats.getLastDirectionMs()).append("ms  ");
        if (showFps) sb.append("FPS:").append(stats.getFrequency());

        statsLabel.setText(sb.toString());

        if (!isVisible()) {
            setVisible(true);
            setManaged(true);
        }
    }

    private static class Holder {
        private static final StatsOverlay INSTANCE = new StatsOverlay();
    }
}
