package com.luoke.app.ui.render;

import com.luoke.app.context.MapContext;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PlayerRenderer {

    private final double IMG_SIZE = 36;
    private Image playerImage;

    private PlayerRenderer() {}

    public static PlayerRenderer getInstance() { return Holder.INSTANCE; }

    public void init(Image image) {
        this.playerImage = image;
    }

    public void draw(GraphicsContext gc) {
        if (playerImage == null) return;

        MapContext mm = MapContext.getInstance();
        // 确保玩家位置已初始化
        if (!mm.isPlayerInitialized()) return;

        // 获取玩家在世界地图上的【逻辑坐标】
        // 注意：不是 CanvasX，因为 RenderLoop 的 gc.translate 已经帮你处理了平移和缩放
        double worldX = mm.getPlayerX();
        double worldY = mm.getPlayerY();
        double rawAngle = mm.getPlayerAngle();

        gc.save();

        // 1. 将画笔移到玩家的世界坐标位置
        gc.translate(worldX, worldY);

        // 2. 旋转角度
        gc.rotate(rawAngle);

        // 3. 居中绘制图标
        // 因为已经在变换矩阵内，这里直接以 (0,0) 为中心即可
        // 注意：IMG_SIZE 如果不受地图缩放影响，可以除以 scale，
        // 但通常玩家图标随地图缩放更自然。
        gc.drawImage(playerImage, -IMG_SIZE / 2, -IMG_SIZE / 2, IMG_SIZE, IMG_SIZE);

        gc.restore();
    }

    private static class Holder {
        private static final PlayerRenderer INSTANCE = new PlayerRenderer();
    }
}