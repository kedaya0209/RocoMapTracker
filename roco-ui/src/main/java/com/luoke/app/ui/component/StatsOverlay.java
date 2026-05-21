package com.luoke.app.ui.component;

import net.jcip.annotations.NotThreadSafe;
import com.luoke.app.config.StatsConfig;
import com.luoke.app.config.UiConfig;
import com.luoke.app.context.StatsContext;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

@NotThreadSafe
public class StatsOverlay extends StackPane {

    private final Label statsLabel;
    // 缓存可见状态，避免每帧调用 isVisible()/setVisible()/setManaged()
    private boolean shown;
    /** 文本更新节流：只每 N 帧重建字符串，降低 CPU/GC 开销 */
    private int textThrottle;
    private static final int TEXT_UPDATE_INTERVAL = 5; // ~6 FPS @ 30fps base

    private StatsOverlay() {
        statsLabel = new Label();
        statsLabel.setFont(Font.font(UiConfig.STATS_FONT_NAME, UiConfig.STATS_FONT_SIZE));
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
        this.setPadding(new Insets(UiConfig.STATS_PADDING));
        this.setVisible(false);
        this.setManaged(true);
    }

    public static StatsOverlay getInstance() {
        return Holder.INSTANCE;
    }

    public void update() {
        boolean active = StatsConfig.SHOW_STATS_MATCH_TIME || StatsConfig.SHOW_STATS_DIR_TIME
                || StatsConfig.SHOW_STATS_FPS
                || StatsConfig.SHOW_STATS_SIFT_MINIMAP_TIME
                || StatsConfig.SHOW_STATS_SIFT_EXTRACT_TIME
                || StatsConfig.SHOW_STATS_SIFT_FLANN_TIME;

        if (active != shown) {
            shown = active;
            setVisible(active);
            setManaged(active);
        }

        if (!active) {
            textThrottle = 0;
            return;
        }

        // 文本节流：可见性切换必须每帧执行，文本渲染可以降低频率
        textThrottle++;
        if ((textThrottle % TEXT_UPDATE_INTERVAL) != 0) {
            return;
        }

        StatsContext stats = StatsContext.getInstance();
        StringBuilder sb = new StringBuilder(96);
        if (StatsConfig.SHOW_STATS_FPS) sb.append("FPS:").append(stats.getFrequency()).append("\n");
        if (StatsConfig.SHOW_STATS_MATCH_TIME) sb.append("匹配:").append(stats.getLastMatchMs()).append("ms  ");
        if (StatsConfig.SHOW_STATS_DIR_TIME) sb.append("朝向:").append(stats.getLastDirectionMs()).append("ms\n");
        if (StatsConfig.SHOW_STATS_SIFT_MINIMAP_TIME) sb.append("小地图:").append(stats.getLastSiftMinimapMs()).append("ms  ");
        if (StatsConfig.SHOW_STATS_SIFT_EXTRACT_TIME) sb.append("提取:").append(stats.getLastSiftExtractMs()).append("ms  ");
        if (StatsConfig.SHOW_STATS_SIFT_FLANN_TIME) sb.append("FLANN:").append(stats.getLastSiftFlannMs()).append("ms");
        statsLabel.setTextAlignment(TextAlignment.RIGHT);
        statsLabel.setText(sb.toString().trim());
    }

    private static class Holder {
        private static final StatsOverlay INSTANCE = new StatsOverlay();
    }
}
