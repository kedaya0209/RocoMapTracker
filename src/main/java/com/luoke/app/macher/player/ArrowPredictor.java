package com.luoke.app.macher.player;

import com.luoke.app.model.cnn.ArrowPredictService;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.File;
import java.io.IOException;

/**
 * 箭头方向预测 - 批量测试工具
 */
public class ArrowPredictor {

    public static void main(String[] args) throws Exception {
        // 1. 设置输入和输出路径
        String imagesDirPath = "C:\\Users\\tangh\\Desktop\\test\\arrow";
        String resultDirPath = imagesDirPath + File.separator + "result";

        File resultDir = new File(resultDirPath);
        if (!resultDir.exists()) resultDir.mkdirs();

        // 2. 初始化高效率 Service
        try (ArrowPredictService arrowService = new ArrowPredictService()) {
            arrowService.init();

            File dir = new File(imagesDirPath);
            File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".png"));

            if (files == null || files.length == 0) {
                System.out.println("❌ 在指定目录下未找到图片文件！");
                return;
            }

            System.out.println("🚀 开始批量预测，图片数量: " + files.length);

            for (File imgFile : files) {
                try {
                    // 读取图片
                    BufferedImage original = ImageIO.read(imgFile);
                    if (original == null) continue;

                    // 3. 获取灰度字节流 (模拟 Rust/C++ 传入的原始内存)
                    byte[] grayData = getGrayscaleBytes(original);

                    // 4. 调用 Service 进行预测
                    // Service 内部会自动进行中心 64x64 裁剪和归一化
                    Player player = arrowService.predict(grayData, original.getWidth(), original.getHeight());

                    if (player.isFound()) {
                        double angle = player.getAngle();
                        String outPath = resultDir + File.separator + "res_" + imgFile.getName();

                        // 绘制结果到图片上，方便肉眼校验
                        drawAndSave(original, angle, outPath);
                        System.out.printf("✅ 处理完成: %s -> 角度: %.1f°\n", imgFile.getName(), angle);
                    } else {
                        System.err.println("⚠️ 预测失败: " + imgFile.getName());
                    }
                } catch (Exception e) {
                    System.err.println("❌ 运行异常 " + imgFile.getName() + ": " + e.getMessage());
                }
            }
        }
        System.out.println("🏁 全部任务已完成，请在 result 文件夹查看结果。");
    }

    /**
     * 将 BufferedImage 转为灰度字节数组
     * 保持与游戏截图原始数据格式一致
     */
    private static byte[] getGrayscaleBytes(BufferedImage img) {
        if (img.getType() == BufferedImage.TYPE_BYTE_GRAY) {
            return ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
        }
        BufferedImage gray = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        Graphics g = gray.getGraphics();
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return ((DataBufferByte) gray.getRaster().getDataBuffer()).getData();
    }

    /**
     * 结果可视化：在图上画出预测的红线
     */
    public static void drawAndSave(BufferedImage srcImg, double angle, String savePath) throws IOException {
        int w = srcImg.getWidth();
        int h = srcImg.getHeight();
        BufferedImage canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = canvas.createGraphics();
        g2d.drawImage(srcImg, 0, 0, null);

        // 设置画笔：红色、抗锯齿
        g2d.setColor(Color.RED);
        g2d.setStroke(new BasicStroke(3.0f));
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int centerX = w / 2;
        int centerY = h / 2;

        // 弧度转换
        double rad = Math.toRadians(angle);
        int len = (int) (w * 0.4); // 线条长度取宽度的 40%

        // 计算终点坐标
        // 逻辑对齐：0度朝上 (sin=0, cos=1) -> endX = center, endY = center - len
        int endX = (int) (centerX + len * Math.sin(rad));
        int endY = (int) (centerY - len * Math.cos(rad));

        // 画预测线
        g2d.drawLine(centerX, centerY, endX, endY);

        // 画个中心圆点
        g2d.fillOval(centerX - 3, centerY - 3, 6, 6);

        // 写角度文字
        g2d.setColor(Color.YELLOW);
        g2d.setFont(new Font("Consolas", Font.BOLD, 16));
        g2d.drawString(String.format("%.1f deg", angle), 10, 25);

        g2d.dispose();
        ImageIO.write(canvas, "PNG", new File(savePath));
    }
}