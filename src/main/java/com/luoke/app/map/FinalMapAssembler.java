package com.luoke.app.map;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.LinkedList;
import java.util.Queue;

/**
 * 洛克王国：世界 - 全图最终合成工具
 * 功能：彩色地图拼接 + 迷雾遮罩覆盖（边缘还原版）
 */
public class FinalMapAssembler {
    private static final int TILE_SIZE = 2048;
    private static final int GRID_SIZE = 4;
    private static final int FULL_SIZE = 8192;
    private static final Color FOG_COLOR = new Color(205, 186, 150); // 羊皮纸颜色

    public static void main(String[] args) throws IOException {
        String basePath = "C:\\Users\\tangh\\Desktop\\map\\";
        String mapDir = basePath + "bigmap\\";
        String maskDir = basePath + "mask\\";

        // 1. 创建最终大画布
        BufferedImage finalResult = new BufferedImage(FULL_SIZE, FULL_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D gFinal = finalResult.createGraphics();

        // 2. 创建用于处理 Mask 的中间画布
        BufferedImage rawMaskStitched = new BufferedImage(FULL_SIZE, FULL_SIZE, BufferedImage.TYPE_INT_ARGB);
        BufferedImage thickMaskStitched = new BufferedImage(FULL_SIZE, FULL_SIZE, BufferedImage.TYPE_INT_ARGB);

        System.out.println("开始同步处理地图瓦片与遮罩瓦片...");

        for (int x = 0; x < GRID_SIZE; x++) {
            for (int y = 0; y < GRID_SIZE; y++) {
                int index = y * GRID_SIZE + x + 1;
                String idxStr = String.format("%02d", index);

                // --- 处理彩色地图 ---
                File mapFile = new File(mapDir + idxStr + ".png");
                if (mapFile.exists()) {
                    BufferedImage mapTile = ImageIO.read(mapFile);
                    gFinal.drawImage(mapTile, x * TILE_SIZE, y * TILE_SIZE, null);
                }

                // --- 处理遮罩 (Mask) ---
                File maskFile = new File(maskDir + "T_BigMap_Mask_" + idxStr + ".png");
                if (maskFile.exists()) {
                    BufferedImage maskTile = ImageIO.read(maskFile);
                    // 清洗并保存原始细线
                    BufferedImage cleanTile = cleanAlpha(maskTile, 160);
                    rawMaskStitched.getGraphics().drawImage(cleanTile, x * TILE_SIZE, y * TILE_SIZE, null);
                    // 膨胀生成粗线围栏
                    BufferedImage thickTile = dilate(cleanTile, 15);
                    thickMaskStitched.getGraphics().drawImage(thickTile, x * TILE_SIZE, y * TILE_SIZE, null);
                }
                System.out.println("瓦片 " + idxStr + " 处理完毕");
            }
        }

        // 3. 生成填充好的羊皮纸层
        System.out.println("正在生成外围填充...");
        BufferedImage paperLayer = floodFillOuter(thickMaskStitched);

        // 4. 抹除纸层中的粗白线
        System.out.println("正在清理临时边缘...");
        for (int py = 0; py < FULL_SIZE; py++) {
            for (int px = 0; px < FULL_SIZE; px++) {
                if ((paperLayer.getRGB(px, py) & 0x00FFFFFF) == 0x00FFFFFF) {
                    paperLayer.setRGB(px, py, 0x00000000);
                }
            }
        }

        // 5. 组装最终图像
        System.out.println("正在进行最后的图层叠加...");
        // 此时 finalResult 里已经是拼好的彩色地图，现在把处理好的遮罩盖上去
        gFinal.drawImage(paperLayer, 0, 0, null);        // 盖上羊皮纸镂空块
        gFinal.drawImage(rawMaskStitched, 0, 0, null);   // 盖上原始精细线条

        gFinal.dispose();

        // 6. 保存最终成品
        System.out.println("正在写入文件...");
        ImageIO.write(finalResult, "PNG", new File(basePath + "Full_World_Map_Final.png"));
        System.out.println("========================================");
        System.out.println("合成圆满成功！查看文件: Full_World_Map_Final.png");
    }

    // --- 辅助算法函数 ---

    private static BufferedImage cleanAlpha(BufferedImage src, int threshold) {
        BufferedImage res = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < src.getHeight(); y++) {
            for (int x = 0; x < src.getWidth(); x++) {
                int argb = src.getRGB(x, y);
                if (((argb >> 24) & 0xFF) > threshold) res.setRGB(x, y, 0xFFFFFFFF);
            }
        }
        return res;
    }

    private static BufferedImage dilate(BufferedImage src, int r) {
        BufferedImage res = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = res.createGraphics();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                if (dx * dx + dy * dy <= r * r) g.drawImage(src, dx, dy, null);
            }
        }
        g.dispose();
        return res;
    }

    private static BufferedImage floodFillOuter(BufferedImage mask) {
        int w = mask.getWidth(), h = mask.getHeight();
        BufferedImage res = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = res.createGraphics();
        g.drawImage(mask, 0, 0, null);
        g.dispose();

        int fillRGB = FOG_COLOR.getRGB();
        Queue<Point> q = new LinkedList<>();
        boolean[][] visited = new boolean[w][h];

        Point[] seeds = {new Point(0, 0), new Point(w - 1, 0), new Point(0, h - 1), new Point(w - 1, h - 1)};
        for (Point s : seeds) {
            if ((res.getRGB(s.x, s.y) >> 24) == 0) {
                q.add(s);
                visited[s.x][s.y] = true;
            }
        }

        int[] dx = {1, -1, 0, 0}, dy = {0, 0, 1, -1};
        while (!q.isEmpty()) {
            Point p = q.poll();
            res.setRGB(p.x, p.y, fillRGB);
            for (int i = 0; i < 4; i++) {
                int nx = p.x + dx[i], ny = p.y + dy[i];
                if (nx >= 0 && nx < w && ny >= 0 && ny < h && !visited[nx][ny] && (res.getRGB(nx, ny) >> 24) == 0) {
                    visited[nx][ny] = true;
                    q.add(new Point(nx, ny));
                }
            }
        }
        return res;
    }
}