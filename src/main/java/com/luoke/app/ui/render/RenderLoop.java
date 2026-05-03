package com.luoke.app.ui.render;

import com.luoke.app.context.CameraContext;
import com.luoke.app.context.MapContext;
import com.luoke.app.ui.component.InteractiveCanvas;
import com.luoke.app.ui.component.StatsOverlay;
import javafx.animation.AnimationTimer;
import javafx.scene.canvas.GraphicsContext;

public class RenderLoop extends AnimationTimer {
    private final GraphicsContext gc;

    public RenderLoop(GraphicsContext gc) {
        this.gc = gc;
    }

    @Override
    public void handle(long now) {
        CameraContext camera = CameraContext.getInstance();
        camera.updateViewport(); // 处理平滑移动逻辑

        double width = gc.getCanvas().getWidth();
        double height = gc.getCanvas().getHeight();

        // 1. 清空画布
        gc.clearRect(0, 0, width, height);

        // 2. 渲染世界层
        renderWorld();

        // 3. 渲染 UI 层 (StatsOverlay 内部自带频率控制)
        StatsOverlay.getInstance().update();
    }

    private void renderWorld() {
        MapContext mm = MapContext.getInstance();
        if (mm.getMapImage() == null) return;

        gc.save();
        // 统一应用相机变换
        gc.translate(mm.getOffsetX(), mm.getOffsetY());
        gc.scale(mm.getScale(), mm.getScale());

        // A. 绘制底图
        gc.drawImage(mm.getMapImage(), 0, 0);

        // B. 绘制资源图标 (带视口裁剪优化)
        if (gc.getCanvas() instanceof InteractiveCanvas canvas) {
            canvas.drawAllResourceIcons(gc);
        }

        // C. 绘制玩家图标
        PlayerRenderer.getInstance().draw(gc);

        gc.restore();
    }
}