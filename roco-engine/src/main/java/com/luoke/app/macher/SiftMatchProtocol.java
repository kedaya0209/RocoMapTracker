package com.luoke.app.macher;

import com.luoke.app.config.SiftConfig;
import com.luoke.app.context.ResourceConfigContext;
import com.luoke.app.utils.FileUtil;
import com.luoke.app.utils.ResourceUtils;

import javax.imageio.ImageIO;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * SIFT 匹配协议编解码 — 纯函数工具类。
 * 负责 Java ↔ C++ (sift_match.exe) 二进制消息的序列化与反序列化。
 * 从 SiftMatchHandler 拆分，消除 ByteBuffer 操作与业务逻辑的耦合。
 */
public class SiftMatchProtocol {

    public static final int MSG_REQUEST_MAP = 200;
    public static final int MSG_MAP_DATA = 201;

    // ==================== 消息类型 ====================
    public static final int MSG_INIT_COMPLETE = 202;
    public static final int MSG_INIT_FAILED = 203;
    public static final int MSG_READY = 204;
    public static final int MSG_FRAME_DATA = 205;
    public static final int MSG_MATCH_RESULT = 206;
    public static final int MSG_SHUTDOWN = 207;
    public static final int MSG_REQUEST_CONFIG = 208;
    public static final int MSG_CONFIG_DATA = 209;
    private static final String cachePrefix = "cache/";
    private SiftMatchProtocol() {
    }

    // ==================== 编码 (Java → C++) ====================

    /**
     * 序列化 CONFIG_DATA 二进制格式 (Big-Endian):
     * <pre>
     *   [4B]siftVariant [4B]nfeatures [4B]nOctaveLayers
     *   [8B]contrastThreshold [8B]edgeThreshold [8B]sigma
     *   [8B]matchRatioThreshold [4B]matchMinCount [4B]searchRadius
     *   [4B]flannKDTreeCount [4B]flannSearchChecks
     *   [8B]ransacReprojThreshold [4B]ransacMaxIters [8B]ransacConfidence
     *   [4B]cacheFilePathLen [NB]cacheFilePath(UTF-8)
     * </pre>
     */
    public static byte[] encodeConfig(int variant, String cacheSuffix) {
        String siftMapPath = ResourceConfigContext.getSiftMap();
        String cacheFilePath = FileUtil.getExternalFile(cachePrefix + siftMapPath + cacheSuffix).getAbsolutePath();
        byte[] cachePathBytes = cacheFilePath.getBytes(StandardCharsets.UTF_8);

        int bodyLen = 4 + 4 + 4 + 8 + 8 + 8       // variant + SIFT
                + 8 + 4 + 4                          // MATCH
                + 4 + 4                              // FLANN
                + 8 + 4 + 8                          // RANSAC
                + 4 + cachePathBytes.length;         // cache path

        ByteBuffer buf = ByteBuffer.allocate(bodyLen).order(ByteOrder.BIG_ENDIAN);

        buf.putInt(variant);
        buf.putInt(SiftConfig.SIFT_N_FEATURES);
        buf.putInt(SiftConfig.SIFT_N_OCTAVE_LAYERS);
        buf.putDouble(SiftConfig.SIFT_CONTRAST_THRESHOLD);
        buf.putDouble(SiftConfig.SIFT_EDGE_THRESHOLD);
        buf.putDouble(SiftConfig.SIFT_SIGMA);

        buf.putDouble(SiftConfig.MATCH_RATIO_THRESHOLD);
        buf.putInt(SiftConfig.MATCH_MIN_COUNT);
        buf.putInt(SiftConfig.SEARCH_RADIUS);

        buf.putInt(1);  // KDTreeIndexParams(1)
        buf.putInt(24); // SearchParams(24, 0, true)

        buf.putDouble(SiftConfig.RANSAC_REPROJ_THRESHOLD);
        buf.putInt(SiftConfig.RANSAC_MAX_ITERS);
        buf.putDouble(SiftConfig.RANSAC_CONFIDENCE);

        buf.putInt(cachePathBytes.length);
        buf.put(cachePathBytes);

        return buf.array();
    }

    /**
     * 编码地图灰度数据。
     *
     * @return [w(4B)][h(4B)][pixelsLen(4B)][gray8]
     */
    public static byte[] encodeMapData(byte[] grayPixels, int w, int h) {
        ByteBuffer buf = ByteBuffer.allocate(12 + grayPixels.length).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(w);
        buf.putInt(h);
        buf.putInt(grayPixels.length);
        buf.put(grayPixels);
        return buf.array();
    }

    /**
     * 编码匹配帧数据。
     *
     * @return [w(4B)][h(4B)][hintX(8B)][hintY(8B)][pixelsLen(4B)][gray8]
     */
    public static byte[] encodeFrameData(byte[] grayData, int width, int height,
                                         double hintX, double hintY) {
        ByteBuffer buf = ByteBuffer.allocate(28 + grayData.length).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(width);
        buf.putInt(height);
        buf.putDouble(Double.isNaN(hintX) ? -1 : hintX);
        buf.putDouble(Double.isNaN(hintY) ? -1 : hintY);
        buf.putInt(grayData.length);
        buf.put(grayData);
        return buf.array();
    }

    // ==================== 解码 (C++ → Java) ====================

    /**
     * 解析 INIT_COMPLETE 体，返回特征点数
     */
    public static int decodeInitComplete(byte[] body) {
        if (body == null || body.length < 4) return 0;
        return ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN).getInt();
    }

    /**
     * 解析 INIT_FAILED 体，返回错误消息
     */
    public static String decodeInitFailed(byte[] body) {
        if (body == null || body.length <= 4) return "unknown error";
        return new String(body, 4, body.length - 4, StandardCharsets.UTF_8);
    }

    /**
     * 解析 MATCH_RESULT 体，返回 MatchResult（含耗时统计）
     * <pre>
     *   [1]success [8]x [8]y [8]angle [4]tMinimapMs [4]tExtractMs [4]tFlannMs
     * </pre>
     */
    public static SiftMatchHandler.MatchResult decodeMatchResult(byte[] body) {
        if (body == null || body.length < 25) return SiftMatchHandler.MatchResult.FAIL;
        ByteBuffer buf = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN);
        boolean success = buf.get() == 1;
        double x = buf.getDouble();
        double y = buf.getDouble();
        // skip angle (double, 8 bytes)
        buf.getDouble();
        float tMinimap = buf.getFloat();
        float tExtract = buf.getFloat();
        float tFlann = buf.getFloat();
        return new SiftMatchHandler.MatchResult(success, x, y, tMinimap, tExtract, tFlann);
    }

    // ==================== 工具方法 ====================

    /**
     * 从资源加载地图并转换为灰度像素
     */
    public static MapImageData loadMapGray() throws Exception {
        String mapPath = ResourceConfigContext.getSiftMap();
        java.awt.image.BufferedImage img;
        try (InputStream is = ResourceUtils.getResourceStream(mapPath)) {
            img = ImageIO.read(is);
        }
        if (img == null) {
            throw new java.io.IOException("Failed to decode map image");
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
        return new MapImageData(w, h, grayPixels);
    }

    /**
     * 地图灰度数据值对象
     */
    public record MapImageData(int width, int height, byte[] grayPixels) {
    }
}
