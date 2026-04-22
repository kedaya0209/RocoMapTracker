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
 * 识别角色朝向,绝大部分情况功能正常
 */
@Slf4j
public class PlayerRenderer {
    private Image processedIcon;
    private double baseAngle = 0.0;
    private final double LERP_FACTOR = AppConfig.PLAYER_ROTATE_LERP_FACTOR;
    private double iconDrawSize = AppConfig.PLAYER_ICON_DRAW_SIZE;
    // --- 平滑处理新增属性 ---
    private double smoothedAngle = 0.0;
    // -----------------------

    private PlayerRenderer() {}
    public static PlayerRenderer getInstance() { return Holder.INSTANCE; }

    // -------------------------------------------------------------------------
    // 【旧方法保留兼容】
    // -------------------------------------------------------------------------
    public void initIcon(String resourcePath) {
        try (InputStream is = ResourceUtils.getResourceStream(resourcePath)) {
            initIcon(is);
        } catch (Exception e) {
            log.error("加载玩家图标失败: {}", resourcePath, e);
        }
    }

    // -------------------------------------------------------------------------
    // 【新方法：直接传入 InputStream，给 MainApp 调用】
    // -------------------------------------------------------------------------
    public void initIcon(InputStream is) {
        try {
            Image rawIcon = new Image(is);
            this.processedIcon = ImageUtil.trimEmptyPixels(rawIcon);
            iconDrawSize = processedIcon.getWidth();

            try (Mat iconMat = ImageUtil.imageToMat(this.processedIcon)) {
                Player result = ArrowDetector.detectPlayer(iconMat);
                if (result != null && result.isFound()) {
                    this.baseAngle = result.getAngle();
                    this.smoothedAngle = 0;
                    log.info("玩家素材基准角校准: {}°", baseAngle);
                }
            }
        } catch (Exception e) {
            log.error("加载玩家图标失败", e);
        }
    }

    public void draw(GraphicsContext gc) {
        if (processedIcon == null) return;

        MapManager mm = MapManager.getInstance();

        // 从未找到过玩家 → 不渲染
        if (!mm.isPlayerInitialized()) {
            return;
        }

        double canvasX = mm.getPlayerCanvasX();
        double canvasY = mm.getPlayerCanvasY();
        double targetAngle = mm.getPlayerAngle();

        double diff = targetAngle - smoothedAngle;
        if (diff < -180) diff += 360;
        if (diff > 180) diff -= 360;

        smoothedAngle += diff * LERP_FACTOR;
        smoothedAngle = (smoothedAngle + 360) % 360;

        gc.save();
        gc.translate(canvasX, canvasY);
        gc.rotate(smoothedAngle);

        double ratio = processedIcon.getHeight() / processedIcon.getWidth();
        double drawW = iconDrawSize;
        double drawH = iconDrawSize * ratio;

        gc.drawImage(processedIcon, -drawW / 2, -drawH / 2, drawW, drawH);
        gc.restore();
    }

    private static class Holder {
        private static final PlayerRenderer INSTANCE = new PlayerRenderer();
    }
}