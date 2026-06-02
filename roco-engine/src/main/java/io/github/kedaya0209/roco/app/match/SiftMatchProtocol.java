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
     *   [4B]caveCachePathLen [NB]caveCachePath(UTF-8)  // 0 length = no cave cache
     *   [4B]subImageCount                                  // 0 = not multi-map
     *   [subImageCount * 4B] subImageHeights[]              // per-sub-image pixel heights
     *   [4B]overrideCount                                   // per-sub-image SIFT params
     *   overrideCount * {
     *     [4B]subImageIndex
     *     [8B]contrastThreshold    // 0.0 = 不覆盖
     *     [8B]edgeThreshold        // 0.0 = 不覆盖
     *     [4B]nfeatures            // -1 = 不覆盖
     *     [4B]nOctaveLayers        // -1 = 不覆盖
     *     [8B]sigma               // 0.0 = 不覆盖
     *   }
     * </pre>
     */
    public static byte[] encodeConfig(int variant, String cacheSuffix, String caveCacheSuffix, int algoKind,
                                       int[] subImageHeights,
                                       SubImageSiftOverride[] subImageOverrides,
                                       SubImageSiftOverride matchingSift) {
        String siftMapPath = ResourceConfigContext.getSiftMap();
        String cacheFilePath = FilePathUtil.getExternalFile(CACHE_PREFIX + siftMapPath + cacheSuffix).getAbsolutePath();
        byte[] cachePathBytes = cacheFilePath.getBytes(StandardCharsets.UTF_8);

        byte[] cavePathBytes = null;
        if (caveCacheSuffix != null && !caveCacheSuffix.isEmpty()) {
            String caveFilePath = FilePathUtil.getExternalFile(CACHE_PREFIX + siftMapPath + caveCacheSuffix).getAbsolutePath();
            cavePathBytes = caveFilePath.getBytes(StandardCharsets.UTF_8);
        }

        int subCount = (subImageHeights != null) ? subImageHeights.length : 0;
        int overrideCount = (subImageOverrides != null) ? subImageOverrides.length : 0;
        int bodyLen = 4 + 4 + 4 + 4 + 8 + 8 + 8    // kind + variant + SIFT
                + 8 + 4 + 4                          // MATCH
                + 4 + 4                              // FLANN
                + 8 + 4 + 8                          // RANSAC
                + 4 + 4 + 8 + 4                      // TILE
                + 4 + cachePathBytes.length          // cache path
                + 4 + (cavePathBytes != null ? cavePathBytes.length : 0) // cave cache path
                + 4 + subCount * 4                   // subImageCount + subImageHeights
                + 4 + overrideCount * (4 + 8 + 8 + 4 + 4 + 8); // per-sub-image SIFT overrides

        ByteBuffer buf = ByteBuffer.allocate(bodyLen).order(ByteOrder.BIG_ENDIAN);

        buf.putInt(algoKind);
        buf.putInt(variant);

        // 匹配侧 SIFT 参数：优先使用元数据 matchingSift 覆盖，否则 fallback 到 SiftConfig
        int nf = (matchingSift != null && matchingSift.nfeatures() != null) ? matchingSift.nfeatures() : SiftConfig.SIFT_N_FEATURES;
        int nol = (matchingSift != null && matchingSift.nOctaveLayers() != null) ? matchingSift.nOctaveLayers() : SiftConfig.SIFT_N_OCTAVE_LAYERS;
        double ct = (matchingSift != null && matchingSift.contrastThreshold() != null) ? matchingSift.contrastThreshold() : SiftConfig.SIFT_CONTRAST_THRESHOLD;
        double et = (matchingSift != null && matchingSift.edgeThreshold() != null) ? matchingSift.edgeThreshold() : SiftConfig.SIFT_EDGE_THRESHOLD;
        double sg = (matchingSift != null && matchingSift.sigma() != null) ? matchingSift.sigma() : SiftConfig.SIFT_SIGMA;
        buf.putInt(nf);
        buf.putInt(nol);
        buf.putDouble(ct);
        buf.putDouble(et);
        buf.putDouble(sg);

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

        // Second cache path (cave-only, 0 length = no cave cache)
        int caveLen = cavePathBytes != null ? cavePathBytes.length : 0;
        buf.putInt(caveLen);
        if (cavePathBytes != null) {
            buf.put(cavePathBytes);
        }

        // Plan B: sub-image heights for unified multi-map index
        buf.putInt(subCount);
        if (subImageHeights != null) {
            for (int h : subImageHeights) {
                buf.putInt(h);
            }
        }

        // Per-sub-image SIFT param overrides
        buf.putInt(overrideCount);
        if (subImageOverrides != null) {
            for (SubImageSiftOverride o : subImageOverrides) {
                buf.putInt(o.index);
                buf.putDouble(o.contrastThreshold != null ? o.contrastThreshold : 0.0);
                buf.putDouble(o.edgeThreshold != null ? o.edgeThreshold : 0.0);
                buf.putInt(o.nfeatures != null ? o.nfeatures : -1);
                buf.putInt(o.nOctaveLayers != null ? o.nOctaveLayers : -1);
                buf.putDouble(o.sigma != null ? o.sigma : 0.0);
            }
        }

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
     * 解析 MATCH_RESULT 体，返回 MatchResult（含耗时统计 + 子图 map_id）
     * <pre>
     *   [1]success [8]x [8]y [8]angle [4]tMinimapMs [4]tExtractMs [4]tFlannMs [4]tArrowMs [1]mapId
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
        int mapId = body.length >= 42 ? Byte.toUnsignedInt(buf.get()) : -1;
        return new MatchResult(success, x, y, angle, tMinimap, tExtract, tFlann, tArrow, mapId);
    }

    // ==================== 值对象 ====================

    /**
     * 匹配结果值对象（含耗时统计）
     */
    @ThreadSafe
    public record MatchResult(boolean success, double x, double y, double angle,
                               float tMinimapMs, float tExtractMs, float tFlannMs, float tArrowMs,
                               int mapId) {
        public static final MatchResult FAIL = new MatchResult(false, 0, 0, 0, 0, 0, 0, 0, -1);
    }

    /**
     * 子图训练 SIFT 参数覆盖（所有字段可空，null = 不覆盖）。
     */
    @ThreadSafe
    public record SubImageSiftOverride(
            int index,
            Double contrastThreshold,
            Double edgeThreshold,
            Integer nfeatures,
            Integer nOctaveLayers,
            Double sigma
    ) {}
}
