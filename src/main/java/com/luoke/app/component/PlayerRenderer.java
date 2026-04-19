package com.luoke.app.component;

import com.luoke.app.context.MapManager;
import com.luoke.app.utils.ImageUtil;
import com.luoke.macher.player.Player;
import com.luoke.macher.player.RocoTrackerUtils;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.transform.Rotate;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.opencv.opencv_core.Mat;

import java.io.InputStream;

@Slf4j
public class PlayerRenderer {
    private Image processedIcon;
    private double baseAngle = 0.0;
    private final double iconDrawSize = 34.0;

    private PlayerRenderer() {}
    public static PlayerRenderer getInstance() { return Holder.INSTANCE; }

    public void initIcon(String resourcePath) {
        try (InputStream is = ImageUtil.readImageAsStream(resourcePath)) {
            Image rawIcon = new Image(is);
            this.processedIcon = ImageUtil.trimEmptyPixels(rawIcon);

            // 识别素材文件本身的朝向 (例如 player.png 里的箭头默认指向右边，那就是 0度)
            try (Mat iconMat = ImageUtil.imageToMat(this.processedIcon)) {
                Player result = RocoTrackerUtils.updatePlayerInfo(iconMat);
                if (result != null && result.isFound()) {
                    this.baseAngle = result.getAngle();
                    log.info("玩家素材基准角校准: {}°", baseAngle);
                }
            }
        } catch (Exception e) {
            log.error("加载玩家图标失败: {}", resourcePath, e);
        }
    }

    public void draw(GraphicsContext gc) {
        if (processedIcon == null) return;

        MapManager mm = MapManager.getInstance();
        double canvasX = mm.getPlayerCanvasX();
        double canvasY = mm.getPlayerCanvasY();
        double currentAngle = mm.getPlayerAngle(); // 游戏内实时角度

        gc.save();

        /**
         * 修正逻辑：
         * 如果箭头反了，通常是由于旋转方向定义不一致。
         * 尝试切换为：currentAngle - baseAngle
         * 如果左右反了，尝试：-(currentAngle - baseAngle)
         */
        double finalRotate = currentAngle - baseAngle;

        Rotate r = new Rotate(finalRotate, canvasX, canvasY);
        gc.setTransform(r.getMxx(), r.getMyx(), r.getMxy(), r.getMyy(), r.getTx(), r.getTy());

        double ratio = processedIcon.getHeight() / processedIcon.getWidth();
        double drawH = iconDrawSize * ratio;

        gc.drawImage(processedIcon, canvasX - iconDrawSize / 2, canvasY - drawH / 2, iconDrawSize, drawH);
        gc.restore();
    }

    private static class Holder { private static final PlayerRenderer INSTANCE = new PlayerRenderer(); }
}