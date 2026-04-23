package com.luoke.app.macher.map;

import com.luoke.app.config.AppConfig;
import com.luoke.app.macher.utils.CacheUtil;
import com.luoke.app.utils.ImageUtil;
import com.luoke.app.utils.ResourceUtils;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.indexer.FloatIndexer;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.opencv.global.opencv_calib3d;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.opencv_core.*;
import org.bytedeco.opencv.opencv_features2d.FlannBasedMatcher;
import org.bytedeco.opencv.opencv_features2d.SIFT;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;

import static org.bytedeco.opencv.global.opencv_core.CV_32FC2;
import static org.bytedeco.opencv.global.opencv_core.CV_8UC4;
import static org.bytedeco.opencv.global.opencv_imgcodecs.IMREAD_GRAYSCALE;
import static org.bytedeco.opencv.global.opencv_imgproc.*;

@Slf4j
public class SiftMapMatcher implements MapMatcher {

    private static final double SCALE_FACTOR = AppConfig.SCALE_FACTOR;
    private static final float RATIO_THRESHOLD = AppConfig.MATCH_RATIO_THRESHOLD;
    private static final int MIN_MATCH_COUNT = AppConfig.MATCH_MIN_COUNT;

    private final SIFT sift;
    private final FlannBasedMatcher matcher;

    // 缓存的大图数据
    private final KeyPointVector cachedKp2 = new KeyPointVector();
    private final Mat cachedDes2 = new Mat();
    private final Mat mask = new Mat();

    // 实时计算复用对象（避免频繁 GC）
    private final KeyPointVector kp1 = new KeyPointVector();
    private final Mat des1 = new Mat();
    private final DMatchVectorVector knnMatches = new DMatchVectorVector();
    private final DMatchVector goodMatches = new DMatchVector();
    private final Mat objPoints = new Mat();
    private final Mat scenePoints = new Mat();
    private final Mat inliers = new Mat();
    private final Mat objCorners = new Mat(4, 1, CV_32FC2);
    private final Mat sceneCorners = new Mat(4, 1, CV_32FC2);

    private final Java2DFrameConverter j2dConverter = new Java2DFrameConverter();
    private final OpenCVFrameConverter.ToMat matConverter = new OpenCVFrameConverter.ToMat();

    private boolean isInitialized = false;

    public SiftMapMatcher() {
        this.sift = SIFT.create(
                AppConfig.SIFT_N_FEATURES,
                AppConfig.SIFT_N_OCTAVE_LAYERS,
                AppConfig.SIFT_CONTRAST_THRESHOLD,
                AppConfig.SIFT_EDGE_THRESHOLD,
                AppConfig.SIFT_SIGMA,
                AppConfig.SIFT_ENABLE_128
        );
        this.matcher = new FlannBasedMatcher();
    }

    @Override
    public void init(String largeMapPath) {
        long start = System.currentTimeMillis();
        try {
            File cacheFile = ResourceUtils.getExternalFile(largeMapPath + ".sift.zst");
            String cachePath = cacheFile.getAbsolutePath();
            String indexPath = cachePath + ".idx"; // FLANN 树索引缓存

            // 优先从缓存加载
            if (cacheFile.exists()) {
                log.info("检测到特征缓存，正在并发加载...");
                if (CacheUtil.loadFeatures(cachePath, cachedDes2, cachedKp2)) {
                    loadOrBuildMatcher(indexPath);
                    this.isInitialized = true;
                    return;
                }
                log.warn("缓存损坏，重新提取特征...");
            }

            // 提取新特征
            log.info("提取大图特征 (Scale: {})...", SCALE_FACTOR);
            try (InputStream is = ResourceUtils.getResourceStream(largeMapPath)) {
                Mat img2 = ImageUtil.loadToMat(is, IMREAD_GRAYSCALE);
                if (img2.empty()) throw new RuntimeException("无法读取大图: " + largeMapPath);

                Mat resizedImg = new Mat();
                resize(img2, resizedImg, new Size((int) (img2.cols() * SCALE_FACTOR), (int) (img2.rows() * SCALE_FACTOR)));

                sift.detectAndCompute(resizedImg, mask, cachedKp2, cachedDes2);

                // 保存特征点到 Zstd
                CacheUtil.saveFeatures(cachePath, cachedDes2, cachedKp2);
                // 构建并保存 FLANN 索引
                loadOrBuildMatcher(indexPath);

                img2.release();
                resizedImg.release();
                this.isInitialized = true;
            }
        } catch (Exception e) {
            log.error("SIFT 初始化失败", e);
        } finally {
            log.info("SIFT 初始化总耗时：{}ms", System.currentTimeMillis() - start);
        }
    }

    private void loadOrBuildMatcher(String indexPath) {
        matcher.clear();
        // 必须先 add 再进行读取或训练
        try (MatVector desVector = new MatVector(cachedDes2)) {
            matcher.add(desVector);
            File idxFile = new File(indexPath);
            if (idxFile.exists()) {
                log.info("加载预构建的索引文件: {}", idxFile.getName());
                matcher.read(indexPath);
            } else {
                log.info("构建 FLANN 索引树 (这可能需要一些时间)...");
                matcher.train();
                matcher.write(indexPath);
            }
        }
    }

    @Override
    public double[][] match(String smallImgPath) {
        try (InputStream is = ResourceUtils.getResourceStream(smallImgPath)) {
            Mat img = ImageUtil.loadToMat(is, IMREAD_GRAYSCALE);
            double[][] result = processMat(img);
            if (img != null) img.release();
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public double[][] match(byte[] imageBytes, int width, int height) {
        if (imageBytes == null) return null;
        try (BytePointer ptr = new BytePointer(imageBytes);
             Mat bgraMat = new Mat(height, width, CV_8UC4, ptr);
             Mat grayMat = new Mat()) {
            cvtColor(bgraMat, grayMat, COLOR_BGRA2GRAY);
            return processMat(grayMat);
        }
    }

    @Override
    public double[][] match(BufferedImage image) {
        if (image == null) return null;
        try (Mat colorMat = matConverter.convert(j2dConverter.convert(image));
             Mat grayMat = new Mat()) {
            if (colorMat == null) return null;
            cvtColor(colorMat, grayMat, COLOR_BGRA2GRAY);
            return processMat(grayMat);
        }
    }

    private double[][] processMat(Mat img1) {
        if (img1 == null || img1.empty() || !isInitialized) return null;

        // 1. 缩放小图
        Mat processedSmall = new Mat();
        resize(img1, processedSmall, new Size((int) (img1.cols() * SCALE_FACTOR), (int) (img1.rows() * SCALE_FACTOR)));

        // 2. 清理上一轮的数据
        kp1.resize(0);
        des1.release();
        knnMatches.clear();
        goodMatches.clear();

        // 3. 特征提取
        sift.detectAndCompute(processedSmall, mask, kp1, des1);

        double[][] result = null;
        if (!des1.empty()) {
            // 4. KNN 匹配
            matcher.knnMatch(des1, knnMatches, 2);

            // 5. Lowe's Ratio Test 筛选
            for (long i = 0; i < knnMatches.size(); i++) {
                DMatchVector m = knnMatches.get(i);
                if (m.size() >= 2) {
                    DMatch m1 = m.get(0);
                    DMatch m2 = m.get(1);
                    if (m1.distance() < RATIO_THRESHOLD * m2.distance()) {
                        goodMatches.push_back(m1);
                    }
                }
                m.close(); // 释放 DMatchVector
            }

            // 6. 坐标计算
            if (goodMatches.size() >= MIN_MATCH_COUNT) {
                result = calculateCoordinates(processedSmall, kp1, goodMatches);
            }
        }

        processedSmall.release();
        return result;
    }

    private double[][] calculateCoordinates(Mat img1, KeyPointVector kp1, DMatchVector goodMatches) {
        int n = (int) goodMatches.size();
        objPoints.create(n, 1, CV_32FC2);
        scenePoints.create(n, 1, CV_32FC2);

        try (FloatIndexer objIdx = objPoints.createIndexer();
             FloatIndexer sceneIdx = scenePoints.createIndexer()) {

            for (long i = 0; i < n; i++) {
                DMatch m = goodMatches.get(i);
                Point2f p1 = kp1.get(m.queryIdx()).pt();
                Point2f p2 = cachedKp2.get(m.trainIdx()).pt();
                objIdx.put(i, 0, 0, p1.x());
                objIdx.put(i, 0, 1, p1.y());
                sceneIdx.put(i, 0, 0, p2.x());
                sceneIdx.put(i, 0, 1, p2.y());
                m.close();
            }

            // 7. 查找单应性矩阵 (RANSAC)
            try (Mat H = opencv_calib3d.findHomography(objPoints, scenePoints, opencv_calib3d.RANSAC,
                    AppConfig.RANSAC_REPROJ_THRESHOLD, inliers, AppConfig.RANSAC_MAX_ITERS, AppConfig.RANSAC_CONFIDENCE)) {

                if (H == null || H.empty()) return null;

                // 定义小图四个角点
                try (FloatIndexer cIdx = objCorners.createIndexer()) {
                    cIdx.put(0, 0, 0, 0);
                    cIdx.put(0, 0, 1, 0);
                    cIdx.put(1, 0, 0, 0);
                    cIdx.put(1, 0, 1, img1.rows());
                    cIdx.put(2, 0, 0, img1.cols());
                    cIdx.put(2, 0, 1, img1.rows());
                    cIdx.put(3, 0, 0, img1.cols());
                    cIdx.put(3, 0, 1, 0);
                }

                // 投影到大图坐标系
                opencv_core.perspectiveTransform(objCorners, sceneCorners, H);

                double[][] result = new double[4][2];
                try (FloatIndexer sIdx = sceneCorners.createIndexer()) {
                    for (int i = 0; i < 4; i++) {
                        // 还原回原始大图尺寸坐标 (除以缩放因子)
                        result[i][0] = sIdx.get(i, 0, 0) / SCALE_FACTOR;
                        result[i][1] = sIdx.get(i, 0, 1) / SCALE_FACTOR;
                    }
                }
                return result;
            }
        }
    }

    @Override
    public void destroy() {
        // 释放成员变量
        if (cachedDes2 != null) cachedDes2.release();
        if (mask != null) mask.release();
        if (des1 != null) des1.release();
        if (objPoints != null) objPoints.release();
        if (scenePoints != null) scenePoints.release();
        if (inliers != null) inliers.release();
        if (objCorners != null) objCorners.release();
        if (sceneCorners != null) sceneCorners.release();

        // 释放 Vector 和算法对象
        kp1.close();
        knnMatches.close();
        goodMatches.close();
        cachedKp2.close();
        sift.close();
        matcher.close();

        // 释放转换器
        j2dConverter.close();
        matConverter.close();

        isInitialized = false;
        log.info("SIFT 资源已释放");
    }
}