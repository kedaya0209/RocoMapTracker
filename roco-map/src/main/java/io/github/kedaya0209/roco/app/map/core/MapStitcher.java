package io.github.kedaya0209.roco.app.map.core;

import io.github.kedaya0209.roco.app.map.MapResourceUpdater;
import io.github.kedaya0209.roco.app.map.dto.Tile;
import io.github.kedaya0209.roco.app.utils.FilePathUtil;
import lombok.extern.slf4j.Slf4j;
import net.jcip.annotations.NotThreadSafe;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * 地图瓦片拼接器
 * 负责将下载的地图瓦片拼接成完整的地图图片
 */
@Slf4j
@NotThreadSafe
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
                minX = Math.min(minX, t.x());
                maxX = Math.max(maxX, t.x());
                minY = Math.min(minY, t.y());
                maxY = Math.max(maxY, t.y());
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

            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

            for (Tile t : tiles) {
                int dx = (t.x() - minX) * tw;
                int dy = (t.y() - minY) * th;

                try (ByteArrayInputStream bais = new ByteArrayInputStream(t.data())) {
                    BufferedImage tileImg = ImageIO.read(bais);
                    if (tileImg != null) {
                        g2d.drawImage(tileImg, dx, dy, null);
                        tileImg.flush();
                    }
                }
            }

            g2d.dispose();

            File outFile = FilePathUtil.getRelativeFile(String.format(MapResourceUpdater.OUTPUT_FILE, tag));
            ImageIO.write(image, "png", outFile);
            image.flush();

            log.info("✅ 地图 [{}] 拼接完成，文件路径：{}", tag, outFile.getAbsolutePath());

        } catch (IOException e) {
            log.error("❌ 地图 [{}] 拼接失败", tag, e);
        }
    }
}
