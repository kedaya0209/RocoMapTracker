package com.luoke.app.render;

import com.luoke.app.config.AppConfig;
import com.luoke.app.context.MapManager;
import com.luoke.app.macher.player.ArrowDetector;
import com.luoke.app.macher.player.Player;
import com.luoke.app.utils.ImageUtil;
import com.luoke.app.utils.ResourceUtils;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.opencv.opencv_core.Mat;

import java.io.InputStream;

/**
 * 识别角色朝向并进行渲染
 * 已优化：集成原画质压缩与透明度保护
 */
@Slf4j
public class PlayerRenderer {
    private Image processedIcon;
    private double baseAngle = 0.0;
    private final double LERP_FACTOR = AppConfig.PLAYER_ROTATE_LERP_FACTOR;
    private double iconDrawSize = AppConfig.PLAYER_ICON_DRAW_SIZE;
    private double smoothedAngle = 0.0;

    private PlayerRenderer() {}
    public static PlayerRenderer getInstance() { return Holder.INSTANCE; }

    public void initIcon(String resourcePath) {
        try (InputStream is = ResourceUtils.getResourceStream(resourcePath)) {
            initIcon(is);
        } catch (Exception e) {
            log.error("加载玩家图标失败: {}", resourcePath, e);
        }
    }

    /**
     * 实现无损压缩加载
     */
    public void initIcon(InputStream is) {
        try {
            Image rawIcon = new Image(is);
            this.processedIcon = ImageUtil.trimEmptyPixels(rawIcon);
            // 识别时，使用这个边缘锐利的图
            try (Mat iconMat = ImageUtil.imageToMat(processedIcon)) {
                Player result = ArrowDetector.detectPlayer(iconMat);
                if (result != null && result.isFound()) {
                    this.baseAngle = result.getAngle();
                    log.info("玩家素材基准角校准成功: {}°", baseAngle);
                }
            }
        } catch (Exception e) {
            log.error("加载并压缩玩家图标失败", e);
        }
    }

    public void draw(GraphicsContext gc) {
        if (processedIcon == null) return;

        MapManager mm = MapManager.getInstance();
        if (!mm.isPlayerInitialized()) return;

        double canvasX = mm.getPlayerCanvasX();
        double canvasY = mm.getPlayerCanvasY();
        double targetAngle = mm.getPlayerAngle();

        // 角度平滑插值逻辑
        double diff = targetAngle - smoothedAngle;
        if (diff < -180) diff += 360;
        if (diff > 180) diff -= 360;

        smoothedAngle += diff * LERP_FACTOR;
        smoothedAngle = (smoothedAngle + 360) % 360;

        // 绘图
        gc.save();
        gc.translate(canvasX, canvasY);
        // 补偿素材本身的基准角（如果有的话）
        gc.rotate(smoothedAngle - baseAngle);

        double ratio = processedIcon.getHeight() / processedIcon.getWidth();
        double drawW = iconDrawSize;
        double drawH = iconDrawSize * ratio;

        // 这里的 drawImage 内部也会进行一次平滑渲染
        gc.drawImage(processedIcon, -drawW / 2, -drawH / 2, drawW, drawH);
        gc.restore();
    }

    private static class Holder {
        private static final PlayerRenderer INSTANCE = new PlayerRenderer();
    }
}