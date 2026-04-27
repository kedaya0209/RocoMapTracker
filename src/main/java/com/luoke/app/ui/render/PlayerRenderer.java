package com.luoke.app.ui.render;

import com.luoke.app.context.MapContext;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PlayerRenderer {

    private final double IMG_SIZE = 72;
    private Image playerImage;

    private PlayerRenderer() {}

    public static PlayerRenderer getInstance() { return Holder.INSTANCE; }

    /**
     * 智能初始化：自动识别素材箭头的朝向
     */
    public void init(Image image) {
        this.playerImage = image;
    }

    public void draw(GraphicsContext gc) {
        if (playerImage == null) return;

        MapContext mm = MapContext.getInstance();
        if (!mm.isPlayerInitialized()) return;

        double rawAngle = mm.getPlayerAngle(); // 玩家实时角度

        gc.save();
        gc.translate(mm.getPlayerCanvasX(), mm.getPlayerCanvasY());

        // ⚡ 修正 JavaFX 坐标系偏移
        double angleToDraw = rawAngle;
        gc.rotate(angleToDraw);

        gc.drawImage(playerImage, -IMG_SIZE / 2, -IMG_SIZE / 2, IMG_SIZE, IMG_SIZE);

        gc.restore();
    }

    private static class Holder {
        private static final PlayerRenderer INSTANCE = new PlayerRenderer();
    }
}