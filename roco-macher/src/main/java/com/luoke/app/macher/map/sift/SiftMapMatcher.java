package com.luoke.app.macher.map.sift;

import com.luoke.app.config.AppConfig;
import com.luoke.app.macher.map.MapMatcher;
import com.luoke.app.utils.ResourceUtils;
import lombok.extern.slf4j.Slf4j;
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
 *
 * nopointergc=true: 显式内存管理，零 GC 追踪开销。
 * KDTreeIndexParams(1): 单树 + checks=24，适配 33W 特征点。
 */
@Slf4j
public class SiftMapMatcher implements MapMatcher {

    // ==================== 工厂方法 ====================

    /** 默认单例 (PCA_ULTRA) */
    private static volatile SiftMapMatcher defaultInstance;

    /** 默认单例 (PCA_ULTRA)，兼容旧调用方 */
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

    /** 创建指定变体的新实例（每次调用新建，用于热切换） */
    public static SiftMapMatcher create(DescriptorTransform.Variant variant) {
        return new SiftMapMatcher(variant);
    }

    // ==================== 实例字段 ====================

    private final DescriptorTransform transform;
    private final String logName;

    private final SIFT sift = SIFT.create(
            AppConfig.SIFT_N_FEATURES,
            AppConfig.SIFT_N_OCTAVE_LAYERS,
            AppConfig.SIFT_CONTRAST_THRESHOLD,
            AppConfig.SIFT_EDGE_THRESHOLD,
            AppConfig.SIFT_SIGMA,
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

    // 空间过滤半径（参考图像素），用于剔除距离预测位置过远的匹配点
    private static final int SEARCH_RADIUS = 500;

    // RANSAC 内点率 (调试用)
    private double lastInlierRatio;

    private SiftMapMatcher(DescriptorTransform.Variant variant) {
        this.transform = new DescriptorTransform(variant);
        this.logName = variant.name();
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
            float ratio = AppConfig.MATCH_RATIO_THRESHOLD;
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

            if (filteredMatches.size() >= AppConfig.MATCH_MIN_COUNT) {
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
            if (dist <= SEARCH_RADIUS) {
                kept.add(dm);
            }
        }
        // 过滤后数量不足则返回原始列表，保证匹配不中断
        return kept.size() >= AppConfig.MATCH_MIN_COUNT ? kept : matches;
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

        if (validCount < AppConfig.MATCH_MIN_COUNT) return null;

        Mat srcPts = new Mat(validCount, 1, opencv_core.CV_32FC2);
        Mat dstPts = new Mat(validCount, 1, opencv_core.CV_32FC2);
        new FloatPointer(srcPts.data()).put(srcBuf, 0, validCount * 2);
        new FloatPointer(dstPts.data()).put(dstBuf, 0, validCount * 2);

        // 局部 Mat 避免与 destroy() 竞态
        Mat ransacMask = new Mat();
        Mat H = opencv_calib3d.findHomography(srcPts, dstPts, opencv_calib3d.RANSAC,
                AppConfig.RANSAC_REPROJ_THRESHOLD, ransacMask,
                AppConfig.RANSAC_MAX_ITERS, AppConfig.RANSAC_CONFIDENCE);

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
                new KDTreeIndexParams(1),
                new SearchParams(24, 0, true));

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

            // SIFT 检测
            KeyPointVector kps = new KeyPointVector();
            Mat rawDescriptors = new Mat();
            sift.detectAndCompute(mapGray, emptyMask, kps, rawDescriptors);
            if (rawDescriptors.type() != opencv_core.CV_32F) {
                Mat tmp = new Mat();
                rawDescriptors.convertTo(tmp, opencv_core.CV_32F);
                rawDescriptors = tmp;
            }

            // 描述符变换 (PCA + 量化)
            transform.train(rawDescriptors);

            // 关键点坐标
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
