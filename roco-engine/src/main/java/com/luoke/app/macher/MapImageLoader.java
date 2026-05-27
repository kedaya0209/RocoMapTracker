package com.luoke.app.macher;

import net.jcip.annotations.ThreadSafe;
import com.luoke.app.context.ResourceConfigContext;
import com.luoke.app.utils.ResourceUtils;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelReader;
import lombok.extern.slf4j.Slf4j;

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
        Image img;
        try (InputStream is = ResourceUtils.getResourceStream(mapPath)) {
            img = new Image(is);
        }
        if (img.isError() || img.getWidth() <= 0) {
            throw new IOException("地图图片解码失败");
        }

        int w = (int) img.getWidth();
        int h = (int) img.getHeight();
        byte[] grayPixels = new byte[w * h];

        PixelReader reader = img.getPixelReader();
        int[] argb = new int[w * h];
        reader.getPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), argb, 0, w);

        for (int i = 0; i < w * h; i++) {
            int rgb = argb[i];
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;
            grayPixels[i] = (byte) ((r * 299 + g * 587 + b * 114) / 1000);
        }
        return new SiftMatchProtocol.MapImageData(w, h, grayPixels);
    }
}
