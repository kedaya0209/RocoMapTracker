package com.luoke.app.macher;

import net.jcip.annotations.ThreadSafe;
import com.luoke.app.context.ResourceConfigContext;
import com.luoke.app.utils.ResourceUtils;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

/**
 * 地图资源加载器 — 从 classpath 加载地图图片并转换为灰度像素。
 *
 * <p>职责单一：纯 I/O + 图像处理，不含协议编解码或匹配逻辑。
 */
@ThreadSafe
@Slf4j
public final class MapImageLoader {

    private MapImageLoader() {
    }

    /**
     * 加载地图图片并转换为灰度像素数组。
     *
     * @return 灰度地图数据（宽度、高度、像素数组）
     * @throws Exception 资源加载或解码失败
     */
    public static SiftMatchProtocol.MapImageData load() throws Exception {
        String mapPath = ResourceConfigContext.getSiftMap();
        BufferedImage img;
        try (InputStream is = ResourceUtils.getResourceStream(mapPath)) {
            img = ImageIO.read(is);
        }
        if (img == null) {
            throw new IOException("Failed to decode map image");
        }

        int w = img.getWidth();
        int h = img.getHeight();
        byte[] grayPixels = new byte[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                grayPixels[y * w + x] = (byte) ((r * 299 + g * 587 + b * 114) / 1000);
            }
        }
        return new SiftMatchProtocol.MapImageData(w, h, grayPixels);
    }
}
