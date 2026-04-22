package com.luoke.app.map.core;

import com.luoke.app.map.MapResourceUpdater;
import com.luoke.app.map.entity.Tile;
import com.luoke.app.utils.FileUtil;
import lombok.extern.slf4j.Slf4j;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.List;

@Slf4j
public class MapStitcher {

    public static void stitch(List<Tile> tiles, String tag, int tw, int th) {
        try {
            if (tiles.isEmpty()) {
                log.warn("⚠️ 地图 [{}] 无瓦片数据，跳过拼接", tag);
                return;
            }

            log.info("开始拼接地图 [{}]，有效瓦片数量：{}", tag, tiles.size());

            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
            int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;

            for (Tile t : tiles) {
                minX = Math.min(minX, t.getX());
                maxX = Math.max(maxX, t.getX());
                minY = Math.min(minY, t.getY());
                maxY = Math.max(maxY, t.getY());
            }

            tw = tw > 0 ? tw : 256;
            th = th > 0 ? th : 256;
            log.info("地图 [{}] 瓦片范围 X:[{}~{}] Y:[{}~{}]，单瓦片大小：{}x{}",
                    tag, minX, maxX, minY, maxY, tw, th);

            int width = (maxX - minX + 1) * tw;
            int height = (maxY - minY + 1) * th;
            log.info("地图 [{}] 最终生成图片尺寸：{}x{}", tag, width, height);

            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2d = image.createGraphics();

            // ========== 优化：高质量绘图 ==========
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

            for (Tile t : tiles) {
                int dx = (t.getX() - minX) * tw;
                int dy = (t.getY() - minY) * th;

                try (ByteArrayInputStream bais = new ByteArrayInputStream(t.getData())) {
                    BufferedImage tileImg = javax.imageio.ImageIO.read(bais);
                    if (tileImg != null) {
                        g2d.drawImage(tileImg, dx, dy, null);
                        tileImg.flush(); // 释放瓦片图片
                    }
                }
            }

            g2d.dispose();

            File outFile = FileUtil.getRelativeFile(String.format(MapResourceUpdater.OUTPUT_FILE, tag));
            javax.imageio.ImageIO.write(image, "png", outFile);
            image.flush(); // 释放主图

            log.info("✅ 地图 [{}] 拼接完成，文件路径：{}", tag, outFile.getAbsolutePath());

        } catch (Exception e) {
            log.error("❌ 地图 [{}] 拼接失败", tag, e);
        }
    }
}