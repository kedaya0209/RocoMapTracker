package com.luoke.app.ui.render;

import com.luoke.app.context.CameraContext;
import com.luoke.app.context.MapContext;
import com.luoke.app.context.MaterialCollectionContext;
import com.luoke.app.ui.component.InteractiveCanvas;
import com.luoke.app.ui.component.ResourceCounterPanel;
import com.luoke.app.ui.component.StatsOverlay;
import javafx.animation.AnimationTimer;
import javafx.geometry.Pos;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.Text;

import java.util.Map;

public class RenderLoop extends AnimationTimer {

    private static final int TOP_BAR_HEIGHT = 40;
    private final GraphicsContext gc;
    private final Font font = Font.font("Microsoft YaHei", 14);
    private final Text textMeasurer;

    // 用于缓存上次的数据，避免每帧都刷新 UI 控件
    private Map<String, Integer> lastSummaryMap = null;

    public RenderLoop(GraphicsContext gc) {
        this.gc = gc;
        textMeasurer = new Text();
        textMeasurer.setFont(font);
    }

    @Override
    public void handle(long now) {
        // 1. 更新逻辑状态
        CameraContext camera = CameraContext.getInstance();
        camera.updateViewport();
        if (camera.hasValidPlayerPosition()) {
            camera.updateViewport();
        }

        double canvasWidth = gc.getCanvas().getWidth();
        double canvasHeight = gc.getCanvas().getHeight();

        // 2. 清空画布
        gc.clearRect(0, 0, canvasWidth, canvasHeight);

        // 3. 渲染世界层（受缩放和平移影响）
        renderMap();
        renderResourceIcons();
        renderPlayer();

        // 4. 渲染 UI 层（强制不受缩放影响）
        StatsOverlay.getInstance().update();


        // 5. 更新资源面板（逻辑优化：仅在数据变化时更新 DOM）
        updateResourceCountPanel();
    }

    private void renderMap() {
        MapContext mm = MapContext.getInstance();
        if (mm.getMapImage() == null) return;

        gc.save(); // 保存初始状态
        // 应用变换
        gc.translate(mm.getOffsetX(), mm.getOffsetY());
        gc.scale(mm.getScale(), mm.getScale());

        gc.drawImage(mm.getMapImage(), 0, 0);
        gc.restore(); // 必须恢复，否则会污染后面的绘制
    }

    private void renderPlayer() {
        // PlayerRenderer 内部也应该有自己的 save/restore
        PlayerRenderer.getInstance().draw(gc);
    }

    private void renderResourceIcons() {
        if (gc.getCanvas() instanceof InteractiveCanvas canvas) {
            canvas.drawAllResourceIcons(gc);
        }
    }

    /**
     * 更新资源面板：改为增量更新，提升性能
     */
    private void updateResourceCountPanel() {
        MaterialCollectionContext collectionContext = MaterialCollectionContext.getInstance();
        Map<String, Integer> currentSummary = collectionContext.getSummaryMap();

        // 如果数据没变，直接跳过，不要动 UI 树
        if (currentSummary.equals(lastSummaryMap)) {
            return;
        }
        lastSummaryMap = Map.copyOf(currentSummary);

        ResourceCounterPanel resourcePanel = ResourceCounterPanel.getInstance();

        // 清理旧数据（保留标题）
        int childCount = resourcePanel.getChildren().size();
        if (childCount > 1) {
            resourcePanel.getChildren().remove(1, childCount);
        }

        // 构建新列表
        currentSummary.forEach((name, total) -> {
            HBox row = new HBox(12);
            row.setAlignment(Pos.CENTER_LEFT);

            Label nameLabel = new Label(name + " :");
            nameLabel.setTextFill(Color.web("#CCCCCC"));

            Label countLabel = new Label(String.valueOf(total));
            countLabel.setTextFill(Color.web("#00BFFF"));
            countLabel.setStyle("-fx-font-weight: bold;"); // 替换样式类以减少依赖

            row.getChildren().addAll(nameLabel, countLabel);
            resourcePanel.getChildren().add(row);
        });

        if (!currentSummary.isEmpty() && !resourcePanel.isVisible()) {
            resourcePanel.toggle(true);
        }
    }
}