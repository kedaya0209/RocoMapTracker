package io.github.kedaya0209.roco.app.map.util;

import ar.com.hjg.pngj.FilterType;
import ar.com.hjg.pngj.IImageLine;
import ar.com.hjg.pngj.ImageInfo;
import ar.com.hjg.pngj.ImageLineByte;
import ar.com.hjg.pngj.ImageLineInt;
import ar.com.hjg.pngj.PngReader;
import ar.com.hjg.pngj.PngWriter;
import net.jcip.annotations.ThreadSafe;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * 纯 int[] + PNGJ 像素操作工具，完全替代 AWT BufferedImage/Graphics2D/ImageIO。
 *
 * <p>像素格式：ARGB int[], 每个 int = (A&lt;&lt;24)|(R&lt;&lt;16)|(G&lt;&lt;8)|B
 */
@ThreadSafe
public final class PngImage {

    private PngImage() {
    }

    // ==================== I/O ====================

    public static PngImageData readPng(byte[] data) throws IOException {
        return readPng(new ByteArrayInputStream(data));
    }

    public static PngImageData readPng(File file) throws IOException {
        PngReader reader = new PngReader(file);
        try {
            return readPngInternal(reader);
        } finally {
            reader.end();
        }
    }

    public static PngImageData readPng(InputStream is) throws IOException {
        PngReader reader = new PngReader(is);
        try {
            return readPngInternal(reader);
        } finally {
            reader.end();
        }
    }

    private static PngImageData readPngInternal(PngReader reader) {
        int w = reader.imgInfo.cols;
        int h = reader.imgInfo.rows;
        int[] pixels = new int[w * h];
        for (int y = 0; y < h; y++) {
            int[] rgba = readRowRgba(reader.readRow(), w);
            int rowOff = y * w;
            for (int x = 0; x < w; x++) {
                int off = x * 4;
                pixels[rowOff + x] = (rgba[off + 3] << 24)
                        | ((rgba[off] & 0xFF) << 16)
                        | ((rgba[off + 1] & 0xFF) << 8)
                        | (rgba[off + 2] & 0xFF);
            }
        }
        return new PngImageData(w, h, pixels);
    }

    public static void writePng(int[] pixels, int w, int h, File file) throws IOException {
        PngWriter writer = new PngWriter(file, new ImageInfo(w, h, 8, true, false, false));
        writer.setFilterType(FilterType.FILTER_NONE);
        writer.setCompLevel(1);
        try {
            ImageLineInt line = new ImageLineInt(writer.imgInfo);
            for (int y = 0; y < h; y++) {
                int[] scan = line.getScanline();
                int rowOff = y * w;
                for (int x = 0; x < w; x++) {
                    int argb = pixels[rowOff + x];
                    int off = x * 4;
                    scan[off] = (argb >> 16) & 0xFF;
                    scan[off + 1] = (argb >> 8) & 0xFF;
                    scan[off + 2] = argb & 0xFF;
                    scan[off + 3] = (argb >> 24) & 0xFF;
                }
                writer.writeRow(line, y);
            }
        } finally {
            writer.end();
        }
    }

    // ==================== 像素拷贝 ====================

    /** 将 src 全部像素 1:1 拷贝到 dst 的 (dx, dy) 位置 */
    public static void blit1to1(int[] src, int srcW, int srcH, int[] dst, int dstW, int dx, int dy) {
        for (int y = 0; y < srcH; y++) {
            int dstOff = (dy + y) * dstW + dx;
            System.arraycopy(src, y * srcW, dst, dstOff, srcW);
        }
    }

    // ==================== 缩放 ====================

    /**
     * 双线性缩放 + Porter-Duff SRC_OVER 混合。
     * 将整个 src 缩放到 dst 的 (dx, dy, dw, dh) 区域。
     */
    public static void blitScaled(int[] src, int srcW, int srcH,
                                   int[] dst, int dstW, int dstH,
                                   int dx, int dy, int dw, int dh) {
        blitScaledInternal(src, srcW, srcH, dst, dstW, dstH, dx, dy, dw, dh, 255);
    }

    /** blitScaled + 全局 alpha (0..255)，255=完全不透明 */
    public static void blitScaledAlpha(int[] src, int srcW, int srcH,
                                        int[] dst, int dstW, int dstH,
                                        int dx, int dy, int dw, int dh, int alpha) {
        blitScaledInternal(src, srcW, srcH, dst, dstW, dstH, dx, dy, dw, dh, alpha);
    }

    private static void blitScaledInternal(int[] src, int srcW, int srcH,
                                            int[] dst, int dstW, int dstH,
                                            int dx, int dy, int dw, int dh, int globalAlpha) {
        if (dw <= 0 || dh <= 0) return;

        // 裁剪目标区域
        int x1 = Math.max(dx, 0);
        int y1 = Math.max(dy, 0);
        int x2 = Math.min(dx + dw, dstW);
        int y2 = Math.min(dy + dh, dstH);

        long xScale = ((long) srcW << 16) / dw;
        long yScale = ((long) srcH << 16) / dh;

        for (int yi = y1; yi < y2; yi++) {
            long syFixed = (yi - dy) * yScale;
            int sy = (int) (syFixed >> 16);
            int syFrac = (int) ((syFixed >> 8) & 0xFF);
            int sy2 = Math.min(sy + 1, srcH - 1);

            for (int xi = x1; xi < x2; xi++) {
                long sxFixed = (xi - dx) * xScale;
                int sx = (int) (sxFixed >> 16);
                int sxFrac = (int) ((sxFixed >> 8) & 0xFF);
                int sx2 = Math.min(sx + 1, srcW - 1);

                // 采样 4 个角
                int p00 = src[sy * srcW + sx];
                int p10 = src[sy * srcW + sx2];
                int p01 = src[sy2 * srcW + sx];
                int p11 = src[sy2 * srcW + sx2];

                // 双线性插值 (premultiplied 空间)
                int w00 = (256 - sxFrac) * (256 - syFrac);
                int w10 = sxFrac * (256 - syFrac);
                int w01 = (256 - sxFrac) * syFrac;
                int w11 = sxFrac * syFrac;

                int sa = ((p00 >>> 24) * w00 + (p10 >>> 24) * w10
                        + (p01 >>> 24) * w01 + (p11 >>> 24) * w11);
                // 使用 long 防止 R*Alpha*weight 乘积溢出 int (255*255*65536=4.26B > 2.14B)
                long srAcc = ((long)((p00 >> 16) & 0xFF) * (p00 >>> 24) * w00
                        + (long)((p10 >> 16) & 0xFF) * (p10 >>> 24) * w10
                        + (long)((p01 >> 16) & 0xFF) * (p01 >>> 24) * w01
                        + (long)((p11 >> 16) & 0xFF) * (p11 >>> 24) * w11);
                long sgAcc = ((long)((p00 >> 8) & 0xFF) * (p00 >>> 24) * w00
                        + (long)((p10 >> 8) & 0xFF) * (p10 >>> 24) * w10
                        + (long)((p01 >> 8) & 0xFF) * (p01 >>> 24) * w01
                        + (long)((p11 >> 8) & 0xFF) * (p11 >>> 24) * w11);
                long sbAcc = ((long)(p00 & 0xFF) * (p00 >>> 24) * w00
                        + (long)(p10 & 0xFF) * (p10 >>> 24) * w10
                        + (long)(p01 & 0xFF) * (p01 >>> 24) * w01
                        + (long)(p11 & 0xFF) * (p11 >>> 24) * w11);

                // 归一化 (除以总权重 65536)
                sa = (sa + 32768) >>> 16;
                int sr = (int)((srAcc + 32768) >>> 16);
                int sg = (int)((sgAcc + 32768) >>> 16);
                int sb = (int)((sbAcc + 32768) >>> 16);

                // 应用全局 alpha
                if (globalAlpha < 255) {
                    sa = sa * globalAlpha / 255;
                    sr = sr * globalAlpha / 255;
                    sg = sg * globalAlpha / 255;
                    sb = sb * globalAlpha / 255;
                }

                if (sa <= 0) continue;

                // 转回非 premultiplied: sr=R*A, sa=A → sr/sa=R
                sr = Math.min(255, sr / sa);
                sg = Math.min(255, sg / sa);
                sb = Math.min(255, sb / sa);

                // 写入目标
                int dstIdx = yi * dstW + xi;
                int dstPix = dst[dstIdx];
                int da = (dstPix >>> 24);

                // 目标透明时快速路径：无需 SRC_OVER 混合
                if (da == 0) {
                    dst[dstIdx] = (sa << 24) | (sr << 16) | (sg << 8) | sb;
                } else {
                    int dr = (dstPix >> 16) & 0xFF;
                    int dg = (dstPix >> 8) & 0xFF;
                    int db = dstPix & 0xFF;

                    int outA = sa + (da * (255 - sa) / 255);
                    int outR = (sr * sa + dr * da * (255 - sa) / 255) / outA;
                    int outG = (sg * sa + dg * da * (255 - sa) / 255) / outA;
                    int outB = (sb * sa + db * da * (255 - sa) / 255) / outA;

                    dst[dstIdx] = (outA << 24) | (outR << 16) | (outG << 8) | outB;
                }
            }
        }
    }

    // ==================== 2x 降采样 ====================

    /** 快速 2x 降采样，返回宽高各一半的新 int[] */
    public static int[] downscaleHalf(int[] src, int srcW, int srcH) {
        int dw = srcW / 2;
        int dh = srcH / 2;
        if (dw <= 0 || dh <= 0) return new int[0];
        int[] dst = new int[dw * dh];
        for (int y = 0; y < dh; y++) {
            int srcRow0 = y * 2 * srcW;
            int srcRow1 = srcRow0 + srcW;
            int dstRow = y * dw;
            for (int x = 0; x < dw; x++) {
                int x2 = x * 2;
                int p00 = src[srcRow0 + x2];
                int p10 = src[srcRow0 + x2 + 1];
                int p01 = src[srcRow1 + x2];
                int p11 = src[srcRow1 + x2 + 1];

                int a00 = p00 >>> 24, a10 = p10 >>> 24, a01 = p01 >>> 24, a11 = p11 >>> 24;
                int r00 = (p00 >> 16) & 0xFF, r10 = (p10 >> 16) & 0xFF;
                int r01 = (p01 >> 16) & 0xFF, r11 = (p11 >> 16) & 0xFF;
                int g00 = (p00 >> 8) & 0xFF, g10 = (p10 >> 8) & 0xFF;
                int g01 = (p01 >> 8) & 0xFF, g11 = (p11 >> 8) & 0xFF;
                int b00 = p00 & 0xFF, b10 = p10 & 0xFF;
                int b01 = p01 & 0xFF, b11 = p11 & 0xFF;

                // premultiplied 平均
                int sumA = a00 + a10 + a01 + a11;
                int sumR = r00 * a00 + r10 * a10 + r01 * a01 + r11 * a11;
                int sumG = g00 * a00 + g10 * a10 + g01 * a01 + g11 * a11;
                int sumB = b00 * a00 + b10 * a10 + b01 * a01 + b11 * a11;

                int outA = sumA / 4;
                int outR, outG, outB;
                if (outA > 0) {
                    outR = Math.min(255, sumR / sumA);
                    outG = Math.min(255, sumG / sumA);
                    outB = Math.min(255, sumB / sumA);
                } else {
                    outR = outG = outB = 0;
                }
                dst[dstRow + x] = (outA << 24) | (outR << 16) | (outG << 8) | outB;
            }
        }
        return dst;
    }

    // ==================== 子图提取 ====================

    /** 从 src 中提取 (x, y, w, h) 子区域 */
    public static int[] extractSubImage(int[] src, int srcW, int x, int y, int w, int h) {
        int[] dst = new int[w * h];
        for (int row = 0; row < h; row++) {
            System.arraycopy(src, (y + row) * srcW + x, dst, row * w, w);
        }
        return dst;
    }

    // ==================== 行读取 ====================

    /**
     * 将 PNGJ 行转为 RGBA byte[] (每像素 4 字节, 值 0..255)。
     * 兼容 ImageLineByte 和 ImageLineInt。
     */
    public static int[] readRowRgba(IImageLine line, int w) {
        int[] rgba = new int[w * 4];
        if (line instanceof ImageLineByte byteLine) {
            byte[] src = byteLine.getScanlineByte();
            for (int i = 0; i < w * 4; i++) rgba[i] = src[i] & 0xFF;
        } else if (line instanceof ImageLineInt intLine) {
            int[] src = intLine.getScanline();
            System.arraycopy(src, 0, rgba, 0, w * 4);
        } else {
            Arrays.fill(rgba, 0);
        }
        return rgba;
    }
}
