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
    // 缓存可见状态，避免每帧调用 isVisible()/setVisible()/setManaged()
    // 这些 JavaFX Node 方法在高频热路径上累积原生资源导致 5GB 泄漏
    private boolean shown;

    private StatsOverlay() {
        statsLabel = new Label();
        statsLabel.setFont(Font.font(AppConfig.STATS_FONT_NAME, AppConfig.STATS_FONT_SIZE));
        statsLabel.setTextFill(Color.WHITE);

        Rectangle background = new Rectangle();
        background.setFill(Color.rgb(0, 0, 0, 0));
        background.setArcWidth(10);
        background.setArcHeight(10);

        background.widthProperty().bind(statsLabel.widthProperty().add(24));
        background.heightProperty().bind(statsLabel.heightProperty().add(12));

        this.getChildren().addAll(background, statsLabel);
        this.setAlignment(Pos.CENTER);
        this.setPickOnBounds(false);
        this.setPadding(new Insets(AppConfig.STATS_PADDING));
        this.setVisible(false);
        this.setManaged(true);
    }

    public static StatsOverlay getInstance() {
        return Holder.INSTANCE;
    }

    public void update() {
        boolean active = AppConfig.SHOW_STATS_MATCH_TIME || AppConfig.SHOW_STATS_DIR_TIME
                || AppConfig.SHOW_STATS_FPS;

        if (active != shown) {
            shown = active;
            if (active) {
                setVisible(true);
                setManaged(true);
            } else {
                setVisible(false);
                setManaged(false);
            }
        }

        if (!active) {
            return;
        }

        StatsContext stats = StatsContext.getInstance();
        StringBuilder sb = new StringBuilder(64);
        if (AppConfig.SHOW_STATS_MATCH_TIME) sb.append("匹配:").append(stats.getLastMatchMs()).append("ms  ");
        if (AppConfig.SHOW_STATS_DIR_TIME) sb.append("朝向:").append(stats.getLastDirectionMs()).append("ms  ");
        if (AppConfig.SHOW_STATS_FPS) sb.append("FPS:").append(stats.getFrequency());

        statsLabel.setText(sb.toString());
    }

    private static class Holder {
        private static final StatsOverlay INSTANCE = new StatsOverlay();
    }
}
