package com.luoke.app.render;

import com.luoke.app.context.MapManager;
import com.luoke.app.utils.ImageUtil;
import com.luoke.macher.player.ArrowDetector;
import com.luoke.macher.player.Player;
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
    private double iconDrawSize = 34.0;

    // --- 平滑处理新增属性 ---
    private double smoothedAngle = 0.0; // 记录平滑后的实时角度
    private final double LERP_FACTOR = 0.15; // 平滑系数 (0.0 到 1.0)，值越小越丝滑，值越大响应越快
    // -----------------------

    private PlayerRenderer() {}
    public static PlayerRenderer getInstance() { return Holder.INSTANCE; }

    public void initIcon(String resourcePath) {
        try (InputStream is = ImageUtil.readImageAsStream(resourcePath)) {
            Image rawIcon = new Image(is);
            this.processedIcon = ImageUtil.trimEmptyPixels(rawIcon);
            iconDrawSize = processedIcon.getWidth();
            try (Mat iconMat = ImageUtil.imageToMat(this.processedIcon)) {
                Player result = ArrowDetector.detectPlayer(iconMat);
                if (result != null && result.isFound()) {
                    this.baseAngle = result.getAngle();
                    this.smoothedAngle = 0; // 初始化
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
        double targetAngle = mm.getPlayerAngle(); // 游戏传回的实时目标角度

        // 1. 核心算法：处理 0/360 度边界的最短路径插值
        double diff = targetAngle - smoothedAngle;

        // 确保旋转路径始终小于 180 度（解决 350度转到 10度时反向转一圈的问题）
        if (diff < -180) diff += 360;
        if (diff > 180) diff -= 360;

        // 线性插值计算当前帧应当渲染的角度
        smoothedAngle += diff * LERP_FACTOR;

        // 将角度规范化到 0-360 范围内（可选，仅为了数值严谨）
        smoothedAngle = (smoothedAngle + 360) % 360;

        // 2. 保存当前画布状态
        gc.save();

        // 3. 变换与旋转
        gc.translate(canvasX, canvasY);

        // 使用平滑后的角度进行渲染
        gc.rotate(smoothedAngle);

        double ratio = processedIcon.getHeight() / processedIcon.getWidth();
        double drawW = iconDrawSize;
        double drawH = iconDrawSize * ratio;

        // 4. 绘制图像
        gc.drawImage(processedIcon, -drawW / 2, -drawH / 2, drawW, drawH);

        // 5. 恢复画布状态
        gc.restore();
    }

    private static class Holder { private static final PlayerRenderer INSTANCE = new PlayerRenderer(); }
}