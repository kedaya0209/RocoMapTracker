package com.luoke.app.macher.map.sift;

import com.luoke.app.config.SiftConfig;
import com.luoke.app.macher.map.MapMatcher;
import com.luoke.app.utils.ResourceUtils;
import lombok.extern.slf4j.Slf4j;
import net.jcip.annotations.NotThreadSafe;
import org.bytedeco.javacpp.FloatPointer;
import org.bytedeco.javacpp.PointerScope;
import org.bytedeco.opencv.global.opencv_calib3d;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.*;
import org.bytedeco.opencv.opencv_features2d.DescriptorMatcher;
import org.bytedeco.opencv.opencv_features2d.FlannBasedMatcher;
import org.bytedeco.opencv.opencv_features2d.SIFT;
import org.bytedeco.opencv.opencv_flann.KDTreeIndexParams;
import org.bytedeco.opencv.opencv_flann.SearchParams;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * SIFT 地图匹配器 (JavaCPP 版本)。
 * 通过组合 {@link DescriptorTransform} 覆盖四种变体:
 *
 * <pre>
 *   STANDARD  — 原始 SIFT 128维
 *   PCA       — PCA 降维 64维
 *   ULTRA     — 8-bit 量化 (无 PCA)
 *   PCA_ULTRA — PCA 降维 64维 + 8-bit 量化 (默认)
 * </pre>
 * <p>
 * nopointergc=true: 显式内存管理，零 GC 追踪开销。
 * KDTreeIndexParams(1): 单树 + checks=24，适配 33W 特征点。
 */
@NotThreadSafe
@Slf4j
public class SiftMapMatcher implements MapMatcher {

    // ==================== 工厂方法 ====================

    /**
     * 默认单例 (PCA_ULTRA)
     */
    private static volatile SiftMapMatcher defaultInstance;
    private final DescriptorTransform transform;
    private final String logName;

    // ==================== 实例字段 ====================
    private final SIFT sift = SIFT.create(
            SiftConfig.SIFT_N_FEATURES,
            SiftConfig.SIFT_N_OCTAVE_LAYERS,
            SiftConfig.SIFT_CONTRAST_THRESHOLD,
            SiftConfig.SIFT_EDGE_THRESHOLD,
            SiftConfig.SIFT_SIGMA,
            false);
    // --- 持久化 Mat (scope 外创建, destroy 时关闭) ---
    private final Mat emptyMask = new Mat();
    private volatile DescriptorMatcher activeMatcher;
    private ByteBuffer mapKeyPointsDirectBuffer;
    private int mapPointsCount;
    private Mat sceneImg;
    private int currentWidth = -1, currentHeight = -1;
    private float[] srcBuf = new float[0];
    private float[] dstBuf = new float[0];
    private volatile boolean initialized;
    private volatile boolean destroyed;
    // RANSAC 内点率 (调试用)
    private double lastInlierRatio;
    private SiftMapMatcher(DescriptorTransform.Variant variant) {
        this.transform = new DescriptorTransform(variant);
        this.logName = variant.name();
    }

    // 空间过滤半径（参考图像素），用于剔除距离预测位置过远的匹配点

    /**
     * 默认单例 (PCA_ULTRA)，兼容旧调用方
     */
    public static SiftMapMatcher getInstance() {
        if (defaultInstance == null) {
            synchronized (SiftMapMatcher.class) {
                if (defaultInstance == null) {
                    defaultInstance = new SiftMapMatcher(DescriptorTransform.Variant.PCA_ULTRA);
                }
            }
        }
        return defaultInstance;
    }

    /**
     * 创建指定变体的新实例（每次调用新建，用于热切换）
     */
    public static SiftMapMatcher create(DescriptorTransform.Variant variant) {
        return new SiftMapMatcher(variant);
    }

    // ==================== MapMatcher 接口 ====================

    @Override
    public boolean init(String mapPath) {
        if (initialized) return true;
        synchronized (this) {
            if (initialized) return true;
            File cacheFile = ResourceUtils.getExternalFile(mapPath + transform.cacheSuffix());
            if (loadFromCache(cacheFile.getAbsolutePath())) {
                log.info("{} 缓存载入成功", logName);
                initMatcher();
                initialized = true;
                return true;
            }
            return trainAndSave(mapPath, cacheFile.getAbsolutePath());
        }
    }

    @Override
    public double[][] match(byte[] grayData, int width, int height) {
        return match(grayData, width, height, null, null);
    }

    @Override
    public double[][] match(byte[] grayData, int width, int height, Double hintX, Double hintY) {
        if (destroyed || !initialized || grayData == null) return null;

        prepareSceneMat(width, height);
        sceneImg.data().put(grayData);

        // 全图 FLANN: KD-tree O(log N) 搜索，比 BFMatcher O(N) 暴力匹配快得多
        DescriptorMatcher currentMatcher = this.activeMatcher;
        if (currentMatcher == null) return null;

        try (PointerScope scope = new PointerScope()) {

            KeyPointVector sceneKeyPoints = new KeyPointVector();
            Mat sceneDescriptors = new Mat();

            sift.detectAndCompute(sceneImg, emptyMask, sceneKeyPoints, sceneDescriptors);
            if (sceneDescriptors.empty()) return null;

            Mat sceneFloat;
            if (sceneDescriptors.type() == opencv_core.CV_32F) {
                sceneFloat = sceneDescriptors;
            } else {
                sceneFloat = new Mat();
                sceneDescriptors.convertTo(sceneFloat, opencv_core.CV_32F);
            }

            Mat queryDesc = transform.process(sceneFloat);

            DMatchVectorVector rawMatches = new DMatchVectorVector();
            currentMatcher.knnMatch(queryDesc, rawMatches, 2);

            List<DMatch> goodMatches = new ArrayList<>(128);
            float ratio = SiftConfig.MATCH_RATIO_THRESHOLD;
            long matchSize = rawMatches.size();
            for (long i = 0; i < matchSize; i++) {
                DMatchVector dmv = rawMatches.get(i);
                if (dmv.size() >= 2) {
                    DMatch d0 = dmv.get(0);
                    DMatch d1 = dmv.get(1);
                    if (d0.distance() < ratio * d1.distance()) {
                        goodMatches.add(d0);
                    }
                }
            }

            // 空间过滤：有 hint 时剔除距离预测位置过远的匹配点，消除重复纹理干扰
            boolean hasHint = hintX != null && hintY != null;
            List<DMatch> filteredMatches = hasHint
                    ? filterByProximity(goodMatches, hintX, hintY)
                    : goodMatches;

            if (filteredMatches.size() >= SiftConfig.MATCH_MIN_COUNT) {
                double[][] result = executeRansac(filteredMatches, sceneKeyPoints, width, height);
                return result;
            }
        } catch (Exception e) {
            log.error("{} 匹配异常", logName, e);
        }
        return null;
    }

    /**
     * 空间邻近过滤：剔除 trainIdx 对应参考关键点距离 hint 超过 SEARCH_RADIUS 的匹配。
     */
    private List<DMatch> filterByProximity(List<DMatch> matches, double hintX, double hintY) {
        ByteBuffer buf = mapKeyPointsDirectBuffer;
        if (buf == null || mapPointsCount <= 0) return matches;
        FloatBuffer fb = buf.asFloatBuffer();
        int count = matches.size();
        List<DMatch> kept = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            DMatch dm = matches.get(i);
            int trainIdx = dm.trainIdx();
            if (trainIdx < 0 || trainIdx >= mapPointsCount) continue;
            float kx = fb.get(trainIdx * 2);
            float ky = fb.get(trainIdx * 2 + 1);
            double dist = Math.hypot(kx - hintX, ky - hintY);
            if (dist <= SiftConfig.SEARCH_RADIUS) {
                kept.add(dm);
            }
        }
        // 过滤后数量不足则返回原始列表，保证匹配不中断
        return kept.size() >= SiftConfig.MATCH_MIN_COUNT ? kept : matches;
    }

    // ==================== RANSAC ====================

    private double[][] executeRansac(List<DMatch> goodMatches, KeyPointVector sceneKps, int w, int h) {
        if (destroyed) return null;

        int count = goodMatches.size();

        if (srcBuf.length < count * 2) {
            srcBuf = new float[count * 2];
            dstBuf = new float[count * 2];
        }

        FloatBuffer mapFb = mapKeyPointsDirectBuffer.asFloatBuffer();
        int validCount = 0;
        for (int i = 0; i < count; i++) {
            DMatch dm = goodMatches.get(i);
            int trainIdx = dm.trainIdx();
            if (trainIdx < 0 || trainIdx >= mapPointsCount) continue;

            KeyPoint kp = sceneKps.get(dm.queryIdx());
            srcBuf[validCount * 2] = kp.pt().x();
            srcBuf[validCount * 2 + 1] = kp.pt().y();
            dstBuf[validCount * 2] = mapFb.get(trainIdx * 2);
            dstBuf[validCount * 2 + 1] = mapFb.get(trainIdx * 2 + 1);
            validCount++;
        }

        if (validCount < SiftConfig.MATCH_MIN_COUNT) return null;

        Mat srcPts = new Mat(validCount, 1, opencv_core.CV_32FC2);
        Mat dstPts = new Mat(validCount, 1, opencv_core.CV_32FC2);
        new FloatPointer(srcPts.data()).put(srcBuf, 0, validCount * 2);
        new FloatPointer(dstPts.data()).put(dstBuf, 0, validCount * 2);

        // 局部 Mat 避免与 destroy() 竞态
        Mat ransacMask = new Mat();
        Mat H = opencv_calib3d.findHomography(srcPts, dstPts, opencv_calib3d.RANSAC,
                SiftConfig.RANSAC_REPROJ_THRESHOLD, ransacMask,
                SiftConfig.RANSAC_MAX_ITERS, SiftConfig.RANSAC_CONFIDENCE);

        if (!H.empty() && H.rows() == 3) {
            // 计算 RANSAC 内点率，作为匹配置信度
            int inliers = 0;
            for (int i = 0; i < validCount; i++) {
                if (ransacMask.ptr(i).get() != 0) inliers++;
            }
            lastInlierRatio = (double) inliers / validCount;

            Mat srcCenter = new Mat(1, 1, opencv_core.CV_32FC2);
            Mat dstCenter = new Mat(1, 1, opencv_core.CV_32FC2);
            new FloatPointer(srcCenter.data()).put((float) (w >> 1), (float) (h >> 1));
            opencv_core.perspectiveTransform(srcCenter, dstCenter, H);
            float[] res = new float[2];
            new FloatPointer(dstCenter.data()).get(res);
            return new double[][]{{res[0], res[1]}};
        }
        lastInlierRatio = 0;
        return null;
    }

    // ==================== 场景准备 ====================

    private void prepareSceneMat(int w, int h) {
        if (w != currentWidth || h != currentHeight) {
            if (sceneImg != null) sceneImg.close();
            currentWidth = w;
            currentHeight = h;
            sceneImg = new Mat(h, w, opencv_core.CV_8UC1);
        }
    }

    // ==================== 匹配器管理 ====================

    private void initMatcher() {
        FlannBasedMatcher newMatcher = new FlannBasedMatcher(
                new KDTreeIndexParams(SiftConfig.FLANN_KD_TREES),
                new SearchParams(SiftConfig.FLANN_SEARCH_CHECKS, 0, true));

        try (PointerScope scope = new PointerScope()) {
            Mat tempFloat = new Mat();
            transform.persistentMat.convertTo(tempFloat, opencv_core.CV_32F);
            MatVector trainDescs = new MatVector(1);
            trainDescs.put(0, tempFloat);
            newMatcher.add(trainDescs);
            newMatcher.train();
        }

        DescriptorMatcher old = this.activeMatcher;
        this.activeMatcher = newMatcher;
        if (old != null) old.clear();
    }

    // ==================== 训练与缓存 ====================

    private boolean trainAndSave(String mapPath, String cachePath) {
        try (PointerScope scope = new PointerScope()) {

            byte[] bytes;
            try (InputStream is = ResourceUtils.getResourceStream(mapPath)) {
                bytes = is.readAllBytes();
            }

            Mat rawData = new Mat(bytes.length, 1, opencv_core.CV_8UC1);
            rawData.data().put(bytes);
            Mat mapColor = opencv_imgcodecs.imdecode(rawData, opencv_imgcodecs.IMREAD_UNCHANGED);

            Mat mapGray = new Mat();
            opencv_imgproc.cvtColor(mapColor, mapGray, opencv_imgproc.COLOR_BGR2GRAY);

            int mapH = mapGray.rows();
            int mapW = mapGray.cols();
            long totalPixels = (long) mapW * mapH;

            if (totalPixels >= SiftConfig.SIFT_LARGE_MAP_THRESHOLD) {
                log.info("{} 地图较大({}x{}={}px), 启用重叠分块特征提取", logName, mapW, mapH, totalPixels);
                trainTiled(mapGray, mapW, mapH);
            } else {
                trainDirect(mapGray);
            }

            log.info("{} 训练完成: {} 地图特征点", logName, mapPointsCount);

            saveToCache(cachePath);

        } catch (Exception e) {
            log.error("{} 训练异常", logName, e);
            return false;
        }

        // ★ 必须在 scope 外创建 FlannBasedMatcher（长期存活），否则会被 scope 回收
        initMatcher();
        initialized = true;
        return true;
    }

    /**
     * 小图直接训练（≤9Mpx 走此路径，保持原有行为）。
     */
    private void trainDirect(Mat mapGray) {
        try (PointerScope scope = new PointerScope()) {
            KeyPointVector kps = new KeyPointVector();
            Mat rawDescriptors = new Mat();
            sift.detectAndCompute(mapGray, emptyMask, kps, rawDescriptors);
            if (rawDescriptors.type() != opencv_core.CV_32F) {
                Mat tmp = new Mat();
                rawDescriptors.convertTo(tmp, opencv_core.CV_32F);
                rawDescriptors = tmp;
            }

            transform.train(rawDescriptors);

            long kpsCount = kps.size();
            mapPointsCount = (int) kpsCount;
            mapKeyPointsDirectBuffer = ByteBuffer.allocateDirect(mapPointsCount * 2 * 4)
                    .order(ByteOrder.nativeOrder());
            FloatBuffer fb = mapKeyPointsDirectBuffer.asFloatBuffer();
            for (long i = 0; i < kpsCount; i++) {
                KeyPoint kp = kps.get(i);
                fb.put((int) i * 2, kp.pt().x());
                fb.put((int) i * 2 + 1, kp.pt().y());
            }
        }
    }

    /**
     * 大图重叠分块训练（Overlapping Tiling）。
     *
     * <p>将地图切分为 SiftConfig.SIFT_TILE_SIZE×SiftConfig.SIFT_TILE_SIZE 的瓦片，
     * 相邻瓦片重叠 SiftConfig.SIFT_TILE_OVERLAP 像素。每块独立执行 SIFT 检测，
     * 然后通过空间网格去重合并结果。
     */
    private void trainTiled(Mat mapGray, int mapW, int mapH) {
        int stride = SiftConfig.SIFT_TILE_SIZE - SiftConfig.SIFT_TILE_OVERLAP;
        int cols = (int) Math.ceil((double) (mapW - SiftConfig.SIFT_TILE_OVERLAP) / stride);
        int rows = (int) Math.ceil((double) (mapH - SiftConfig.SIFT_TILE_OVERLAP) / stride);
        log.info("{} 瓦片布局: {}x{} ({} tiles)", logName, cols, rows, cols * rows);

        // 收集所有瓦片的特征点
        List<float[]> kpCoords = new ArrayList<>();
        List<Float> kpResponses = new ArrayList<>();
        List<float[]> kpDescs = new ArrayList<>();
        int descDim = -1;

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int tileX = c * stride;
                int tileY = r * stride;
                int tileW = Math.min(SiftConfig.SIFT_TILE_SIZE, mapW - tileX);
                int tileH = Math.min(SiftConfig.SIFT_TILE_SIZE, mapH - tileY);

                try (PointerScope scope = new PointerScope()) {
                    Rect roi = new Rect(tileX, tileY, tileW, tileH);
                    Mat tileGray = new Mat(mapGray, roi);

                    KeyPointVector tileKps = new KeyPointVector();
                    Mat tileDescs = new Mat();
                    sift.detectAndCompute(tileGray, emptyMask, tileKps, tileDescs);

                    long n = tileKps.size();
                    if (n == 0 || tileDescs.empty()) continue;

                    // 确保 CV_32F
                    Mat descFloat;
                    if (tileDescs.type() != opencv_core.CV_32F) {
                        descFloat = new Mat();
                        tileDescs.convertTo(descFloat, opencv_core.CV_32F);
                    } else {
                        descFloat = tileDescs;
                    }

                    descDim = descFloat.cols(); // 128 for SIFT
                    long totalVals = descFloat.total() * descFloat.channels();
                    float[] descData = new float[(int) totalVals];
                    new FloatPointer(descFloat.data()).get(descData);

                    for (long i = 0; i < n; i++) {
                        KeyPoint kp = tileKps.get(i);
                        // ★ 坐标还原：加上瓦片偏移量得到全图坐标
                        kpCoords.add(new float[]{kp.pt().x() + tileX, kp.pt().y() + tileY});
                        kpResponses.add(kp.response());

                        float[] row = new float[descDim];
                        System.arraycopy(descData, (int) i * descDim, row, 0, descDim);
                        kpDescs.add(row);
                    }
                }
            }
        }

        if (kpCoords.isEmpty()) {
            log.warn("{} 未检测到任何特征点", logName);
            mapPointsCount = 0;
            return;
        }

        // ========== 重复特征去重 ==========
        int totalCount = kpCoords.size();
        boolean[] keep = new boolean[totalCount];
        int keepCount = 0;

        // 按 response 降序排序（响应越强的特征越优先保留）
        Integer[] sortedIndices = new Integer[totalCount];
        for (int i = 0; i < totalCount; i++) sortedIndices[i] = i;
        Arrays.sort(sortedIndices, (a, b) -> Float.compare(kpResponses.get(b), kpResponses.get(a)));

        // 空间网格索引，O(1) 近邻查询重复点
        int cellSize = (int) Math.ceil(SiftConfig.SIFT_DEDUP_DISTANCE);
        int gridCols = mapW / cellSize + 1;
        int gridRows = mapH / cellSize + 1;
        int[] grid = new int[gridCols * gridRows];
        Arrays.fill(grid, -1);

        for (int idx : sortedIndices) {
            float[] coord = kpCoords.get(idx);
            int cx = (int) (coord[0] / cellSize);
            int cy = (int) (coord[1] / cellSize);

            // 检查 3×3 邻域是否已有保留的重复点
            boolean duplicate = false;
            outer:
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    int nx = cx + dx;
                    int ny = cy + dy;
                    if (nx >= 0 && nx < gridCols && ny >= 0 && ny < gridRows) {
                        int existing = grid[ny * gridCols + nx];
                        if (existing >= 0) {
                            float[] ekp = kpCoords.get(existing);
                            float dist = (float) Math.hypot(coord[0] - ekp[0], coord[1] - ekp[1]);
                            if (dist < SiftConfig.SIFT_DEDUP_DISTANCE) {
                                duplicate = true;
                                break outer;
                            }
                        }
                    }
                }
            }

            if (!duplicate) {
                grid[cy * gridCols + cx] = idx;
                keep[idx] = true;
                keepCount++;
            }
        }

        log.info("{} 去重: {} → {} (移除 {} 个重叠重复点)",
                logName, totalCount, keepCount, totalCount - keepCount);

        // ========== 构建描述符矩阵 ==========
        Mat allDescs = new Mat(keepCount, descDim, opencv_core.CV_32F);
        float[] allDescData = new float[keepCount * descDim];
        int pos = 0;
        for (int i = 0; i < totalCount; i++) {
            if (keep[i]) {
                float[] row = kpDescs.get(i);
                System.arraycopy(row, 0, allDescData, pos, descDim);
                pos += descDim;
            }
        }
        new FloatPointer(allDescs.data()).put(allDescData);

        // 训练变换 (PCA + 量化)
        transform.train(allDescs);

        // ========== 构建关键点坐标缓冲区 ==========
        mapPointsCount = keepCount;
        mapKeyPointsDirectBuffer = ByteBuffer.allocateDirect(keepCount * 2 * 4)
                .order(ByteOrder.nativeOrder());
        FloatBuffer fb = mapKeyPointsDirectBuffer.asFloatBuffer();
        pos = 0;
        for (int i = 0; i < totalCount; i++) {
            if (keep[i]) {
                float[] coord = kpCoords.get(i);
                fb.put(pos * 2, coord[0]);
                fb.put(pos * 2 + 1, coord[1]);
                pos++;
            }
        }
    }

    private void saveToCache(String path) {
        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(path))) {
            transform.saveToCache(dos, mapKeyPointsDirectBuffer, mapPointsCount);
        } catch (Exception e) {
            log.error("{} 存储缓存失败", logName, e);
        }
    }

    private boolean loadFromCache(String path) {
        File f = new File(path);
        if (!f.exists()) return false;
        try (PointerScope scope = new PointerScope();
             DataInputStream dis = new DataInputStream(new FileInputStream(f))) {
            transform.loadFromCache(dis, (buf, count) -> {
                this.mapKeyPointsDirectBuffer = buf;
                this.mapPointsCount = count;
            });
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== 销毁 ====================

    @Override
    public void destroy() {
        destroyed = true;
        initialized = false;
        transform.destroy();
        emptyMask.close();
        if (sceneImg != null) sceneImg.close();
        if (activeMatcher != null) activeMatcher.clear();
        sift.close();
        mapKeyPointsDirectBuffer = null;
    }
}
