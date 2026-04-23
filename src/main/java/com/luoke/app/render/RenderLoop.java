package com.luoke.app.render;

import com.luoke.app.component.InteractiveCanvas;
import com.luoke.app.config.AppConfig;
import com.luoke.app.context.CameraManager;
import com.luoke.app.context.MapManager;
import com.luoke.app.context.StatsManager;
import javafx.animation.AnimationTimer;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.text.Font;
import javafx.scene.text.Text;

public class RenderLoop extends AnimationTimer {

    // 和MainApp中topBar的padding保持一致
    private static final int TOP_BAR_HEIGHT = 40;
    private final GraphicsContext gc;
    private final Font font = Font.font("Microsoft YaHei", 14);
    private final Text textMeasurer = new Text();

    public RenderLoop(GraphicsContext gc) {
        this.gc = gc;
        textMeasurer.setFont(font);
    }

    @Override
    public void handle(long now) {
        CameraManager.getInstance().updateViewport();
        double canvasWidth = gc.getCanvas().getWidth();
        double canvasHeight = gc.getCanvas().getHeight();

        gc.clearRect(0, 0, canvasWidth, canvasHeight);
        renderMap();

        renderResourceIcons();
        renderStatsUI(canvasWidth);
        renderPlayer();
    }

    private void renderMap() {
        MapManager mm = MapManager.getInstance();
        if (mm.getMapImage() == null) return;

        gc.save();
        gc.translate(mm.getOffsetX(), mm.getOffsetY());
        gc.scale(mm.getScale(), mm.getScale());
        gc.drawImage(mm.getMapImage(), 0, 0);
        gc.restore();
    }

    private void renderPlayer() {
//        PlayerRenderer.getInstance().draw(gc);
        CutterPlayerRenderer.getInstance().draw(gc);

    }

    // ==========================
    // ✅ 绘制所有资源点位图标
    // ==========================
    private void renderResourceIcons() {
        if (!(gc.getCanvas() instanceof InteractiveCanvas canvas)) {
            return;
        }
        // 调用 InteractiveCanvas 里的绘制方法
        canvas.drawAllResourceIcons(gc);
    }

    private void renderStatsUI(double canvasWidth) {
        StatsManager stats = StatsManager.getInstance();
        gc.setFont(font);

        StringBuilder sb = new StringBuilder();

        // ====================== 根据配置动态拼接文本 ======================
        if (AppConfig.SHOW_STATS_MAP_TIME) {
            sb.append(String.format("小地图：%dms ", stats.getLastMapDetectMs()));
        }
        if (AppConfig.SHOW_STATS_MATCH_TIME) {
            sb.append(String.format("匹配：%dms ", stats.getLastMatchMs()));
        }
        if (AppConfig.SHOW_STATS_DIR_TIME) {
            sb.append(String.format("朝向：%dms ", stats.getLastDirectionMs()));
        }
        if (AppConfig.SHOW_STATS_FPS) {
            sb.append(String.format("频率：%d", stats.getFrequency()));
        }

        String text = sb.toString().trim();
        if (text.isBlank()) return;

        textMeasurer.setText(text);
        double textWidth = textMeasurer.getLayoutBounds().getWidth();
        double margin = 15;
        double textHeight = textMeasurer.getLayoutBounds().getHeight();

        // 垂直居中于顶部栏
        double bgY = (TOP_BAR_HEIGHT - textHeight) / 2;
        double textX = canvasWidth - textWidth - margin;
        double textY = bgY + textHeight / 2 + 4;

        gc.setFill(javafx.scene.paint.Color.BLACK);
        gc.fillText(text, textX, textY);
    }
}