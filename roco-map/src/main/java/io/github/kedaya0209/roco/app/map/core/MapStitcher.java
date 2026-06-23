package io.github.kedaya0209.roco.app.map.core;

import io.github.kedaya0209.roco.app.map.MapResourceUpdater;
import io.github.kedaya0209.roco.app.map.dto.Tile;
import io.github.kedaya0209.roco.app.utils.FilePathUtil;
import lombok.extern.slf4j.Slf4j;
import net.jcip.annotations.NotThreadSafe;

import io.github.kedaya0209.roco.app.map.util.PngImage;
import io.github.kedaya0209.roco.app.map.util.PngImageData;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.stream.IntStream;

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

            int[] canvas = new int[width * height];

            // 分批并行解码瓦片 PNG — 各瓦片写入画布的非重叠区域，无需同步
            int batchSize = Runtime.getRuntime().availableProcessors() * 2;
            int fMinX = minX, fMinY = minY, fTw = tw, fTh = th;
            for (int batchStart = 0; batchStart < tiles.size(); batchStart += batchSize) {
                int end = Math.min(batchStart + batchSize, tiles.size());
                IntStream.range(batchStart, end).parallel().forEach(i -> {
                    Tile t = tiles.get(i);
                    try {
                        PngImageData tileData = PngImage.readPng(t.data());
                        int dx = (t.x() - fMinX) * fTw;
                        int dy = (t.y() - fMinY) * fTh;
                        PngImage.blit1to1(tileData.pixels(), tileData.w(), tileData.h(),
                                canvas, width, dx, dy);
                    } catch (IOException ignored) {
                        // 跳过损坏瓦片
                    }
                });
            }

            File outFile = FilePathUtil.getRelativeFile(String.format(MapResourceUpdater.OUTPUT_FILE, tag));
            outFile.getParentFile().mkdirs();
            PngImage.writePng(canvas, width, height, outFile);

            log.info("✅ 地图 [{}] 拼接完成，文件路径：{}", tag, outFile.getAbsolutePath());

        } catch (IOException e) {
            log.error("❌ 地图 [{}] 拼接失败", tag, e);
        }
    }
}
