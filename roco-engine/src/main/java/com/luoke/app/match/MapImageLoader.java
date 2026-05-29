package com.luoke.app.match;

import net.jcip.annotations.ThreadSafe;
import com.luoke.app.context.ResourceConfigContext;
import com.luoke.app.utils.ResourceUtils;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelReader;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 地图资源加载器 — 从 classpath 加载地图图片并流式转换为灰度像素。
 *
 * <p>职责单一：纯 I/O + 图像处理，不含协议编解码或匹配逻辑。
 * <p>逐行读取像素转灰度直写 OutputStream，避免全图 int[] + byte[] 大缓冲（8192×8192 可节省 ~384MB 峰值内存）。
 */
@ThreadSafe
@Slf4j
public final class MapImageLoader {

    private MapImageLoader() {
    }

    /**
     * 加载地图图片（仅解码，不提取像素）。
     *
     * @return 图片元信息（含 Image 引用，调用方流式写入后应释放）
     * @throws Exception 资源加载或解码失败
     */
    public static ImageInfo loadImage() throws Exception {
        String mapPath = ResourceConfigContext.getSiftMap();
        Image img;
        try (InputStream is = ResourceUtils.getResourceStream(mapPath)) {
            img = new Image(is);
        }
        if (img.isError() || img.getWidth() <= 0) {
            throw new IOException("地图图片解码失败");
        }
        return new ImageInfo(img, (int) img.getWidth(), (int) img.getHeight());
    }

    /**
     * 流式写入协议头 + 灰度像素到 OutputStream（逐行，无全图缓冲）。
     *
     * <p>协议格式：[w(4B)][h(4B)][pixelsLen(4B)][gray8...]
     *
     * @param info 由 {@link #loadImage()} 返回的图片元信息
     * @param out  目标输出流（通常是 Socket OutputStream）
     */
    public static void writeStreaming(ImageInfo info, OutputStream out) throws IOException {
        int w = info.width();
        int h = info.height();

        ByteBuffer header = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN);
        header.putInt(w).putInt(h).putInt(w * h);
        out.write(header.array());

        PixelReader reader = info.image().getPixelReader();
        int[] rowArgb = new int[w];
        byte[] rowGray = new byte[w];

        for (int y = 0; y < h; y++) {
            reader.getPixels(0, y, w, 1, PixelFormat.getIntArgbInstance(), rowArgb, 0, w);
            for (int x = 0; x < w; x++) {
                int rgb = rowArgb[x];
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                rowGray[x] = (byte) ((r * 299 + g * 587 + b * 114) / 1000);
            }
            out.write(rowGray);
        }
    }

    /**
     * 地图图片元信息。
     */
    public record ImageInfo(Image image, int width, int height) {
    }
}
