package io.github.kedaya0209.roco.app.match;

import net.jcip.annotations.ThreadSafe;
import io.github.kedaya0209.roco.app.config.SiftConfig;
import io.github.kedaya0209.roco.app.context.ResourceConfigContext;
import io.github.kedaya0209.roco.app.utils.FilePathUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * SIFT 匹配协议编解码 — 纯函数工具类。
 * 负责 Java ↔ C++ (sift_match.exe) 二进制消息的序列化与反序列化。
 * 从 SiftMatchHandler 拆分，消除 ByteBuffer 操作与业务逻辑的耦合。
 */
@ThreadSafe
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
    private static final String CACHE_PREFIX = "cache/";
    private SiftMatchProtocol() {
    }

    // ==================== 编码 (Java → C++) ====================

    /**
     * 序列化 CONFIG_DATA 二进制格式 (Big-Endian):
     * <pre>
     *   [4B]kind(AlgoKind) [4B]siftVariant [4B]nfeatures [4B]nOctaveLayers
     *   [8B]contrastThreshold [8B]edgeThreshold [8B]sigma
     *   [8B]matchRatioThreshold [4B]matchMinCount [4B]searchRadius
     *   [4B]flannKDTreeCount [4B]flannSearchChecks
     *   [8B]ransacReprojThreshold [4B]ransacMaxIters [8B]ransacConfidence
     *   [4B]tileSize [4B]tileOverlap [8B]largeMapThreshold [4B]dedupDistance
     *   [4B]cacheFilePathLen [NB]cacheFilePath(UTF-8)
     * </pre>
     */
    public static byte[] encodeConfig(int variant, String cacheSuffix, int algoKind) {
        String siftMapPath = ResourceConfigContext.getSiftMap();
        String cacheFilePath = FilePathUtil.getExternalFile(CACHE_PREFIX + siftMapPath + cacheSuffix).getAbsolutePath();
        byte[] cachePathBytes = cacheFilePath.getBytes(StandardCharsets.UTF_8);

        int bodyLen = 4 + 4 + 4 + 4 + 8 + 8 + 8    // kind + variant + SIFT
                + 8 + 4 + 4                          // MATCH
                + 4 + 4                              // FLANN
                + 8 + 4 + 8                          // RANSAC
                + 4 + 4 + 8 + 4                      // TILE
                + 4 + cachePathBytes.length;         // cache path

        ByteBuffer buf = ByteBuffer.allocate(bodyLen).order(ByteOrder.BIG_ENDIAN);

        buf.putInt(algoKind);
        buf.putInt(variant);
        buf.putInt(SiftConfig.SIFT_N_FEATURES);
        buf.putInt(SiftConfig.SIFT_N_OCTAVE_LAYERS);
        buf.putDouble(SiftConfig.SIFT_CONTRAST_THRESHOLD);
        buf.putDouble(SiftConfig.SIFT_EDGE_THRESHOLD);
        buf.putDouble(SiftConfig.SIFT_SIGMA);

        buf.putDouble(SiftConfig.MATCH_RATIO_THRESHOLD);
        buf.putInt(SiftConfig.MATCH_MIN_COUNT);
        buf.putInt(SiftConfig.SEARCH_RADIUS);

        buf.putInt(SiftConfig.FLANN_KD_TREES);
        buf.putInt(SiftConfig.FLANN_SEARCH_CHECKS);

        buf.putDouble(SiftConfig.RANSAC_REPROJ_THRESHOLD);
        buf.putInt(SiftConfig.RANSAC_MAX_ITERS);
        buf.putDouble(SiftConfig.RANSAC_CONFIDENCE);

        // Tile training params
        buf.putInt(SiftConfig.SIFT_TILE_SIZE);
        buf.putInt(SiftConfig.SIFT_TILE_OVERLAP);
        buf.putLong(SiftConfig.SIFT_LARGE_MAP_THRESHOLD);
        buf.putFloat(SiftConfig.SIFT_DEDUP_DISTANCE);

        buf.putInt(cachePathBytes.length);
        buf.put(cachePathBytes);

        return buf.array();
    }

    /**
     * 编码匹配帧数据（全彩 BGRA）。
     *
     * @return [w(4B)][h(4B)][hintX(8B)][hintY(8B)][pixelsLen(4B)][BGRA32]
     */
    public static byte[] encodeFrameData(byte[] bgraData, int width, int height,
                                         double hintX, double hintY) {
        ByteBuffer buf = ByteBuffer.allocate(28 + bgraData.length).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(width);
        buf.putInt(height);
        buf.putDouble(Double.isNaN(hintX) ? -1 : hintX);
        buf.putDouble(Double.isNaN(hintY) ? -1 : hintY);
        buf.putInt(bgraData.length);
        buf.put(bgraData);
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
    public static MatchResult decodeMatchResult(byte[] body) {
        if (body == null || body.length < 37) return MatchResult.FAIL;
        ByteBuffer buf = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN);
        boolean success = buf.get() == 1;
        double x = buf.getDouble();
        double y = buf.getDouble();
        double angle = buf.getDouble();
        float tMinimap = buf.getFloat();
        float tExtract = buf.getFloat();
        float tFlann = buf.getFloat();
        float tArrow = body.length >= 41 ? buf.getFloat() : 0;
        return new MatchResult(success, x, y, angle, tMinimap, tExtract, tFlann, tArrow);
    }

    // ==================== 值对象 ====================

    /**
     * 匹配结果值对象（含耗时统计）
     */
    @ThreadSafe
    public record MatchResult(boolean success, double x, double y, double angle,
                               float tMinimapMs, float tExtractMs, float tFlannMs, float tArrowMs) {
        public static final MatchResult FAIL = new MatchResult(false, 0, 0, 0, 0, 0, 0, 0);
    }
}
