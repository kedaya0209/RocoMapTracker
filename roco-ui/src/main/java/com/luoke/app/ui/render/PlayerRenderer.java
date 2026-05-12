package com.luoke.app.ui.render;

import com.luoke.app.context.MapContext;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PlayerRenderer {

    private static final double IMG_SIZE = 36;
    private static final double DOT_RADIUS = 6;
    private Image playerImage;

    private PlayerRenderer() {}

    public static PlayerRenderer getInstance() { return Holder.INSTANCE; }

    public void init(Image image) {
        this.playerImage = image;
    }

    public void draw(GraphicsContext gc) {
        MapContext mm = MapContext.getInstance();
        if (!mm.isPlayerInitialized()) return;

        double worldX = mm.getPlayerX();
        double worldY = mm.getPlayerY();

        gc.save();
        gc.translate(worldX, worldY);

        if (playerImage != null && mm.isHasAngle()) {
            // 有朝向数据：绘制带旋转的箭头图标
            gc.rotate(mm.getPlayerAngle());
            gc.drawImage(playerImage, -IMG_SIZE / 2, -IMG_SIZE / 2, IMG_SIZE, IMG_SIZE);
        } else {
            // 无朝向数据：绘制小圆点
            gc.setFill(Color.rgb(0, 180, 255));
            gc.fillOval(-DOT_RADIUS, -DOT_RADIUS, DOT_RADIUS * 2, DOT_RADIUS * 2);
            gc.setStroke(Color.WHITE);
            gc.setLineWidth(1.5);
            gc.strokeOval(-DOT_RADIUS, -DOT_RADIUS, DOT_RADIUS * 2, DOT_RADIUS * 2);
        }

        gc.restore();
    }

    private static class Holder {
        private static final PlayerRenderer INSTANCE = new PlayerRenderer();
    }
}