package com.luoke.app.macher.map;

import com.luoke.app.config.AppConfig;
import com.luoke.app.macher.utils.CacheUtil;
import com.luoke.app.utils.FileUtil;
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

    private final KeyPointVector cachedKp2;
    private final SIFT sift;
    private final FlannBasedMatcher matcher;
    private final Mat mask = new Mat();

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

    private Mat cachedDes2;
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
        this.cachedDes2 = new Mat();
        this.cachedKp2 = new KeyPointVector();
    }

    @Override
    public void init(String largeMapPath) {
        long start = System.currentTimeMillis();
        try {
            // 注意：因为缩放倍率变了，建议删除旧缓存
            String cacheFileName = largeMapPath + ".sift.zst";
            File cacheFile = FileUtil.getRelativeFile(cacheFileName);
            String absolutePath = cacheFile.getAbsolutePath();

            if (cacheFile.exists()) {
                log.info("从缓存加载特征: {}", absolutePath);
                if (CacheUtil.loadFeatures(absolutePath, cachedDes2, cachedKp2)) {
                    buildMatcher();
                    this.isInitialized = true;
                    return;
                }
                cacheFile.delete();
            }

            log.info("提取大图特征 (缩放倍率: {})...", SCALE_FACTOR);
            try (InputStream is = ResourceUtils.getResourceStream(largeMapPath)) {
                Mat img2 = ImageUtil.loadToMat(is, IMREAD_GRAYSCALE);
                if (img2.empty()) throw new RuntimeException("加载失败: " + largeMapPath);

                // 【步骤1】大图强制缩放
                Mat resizedImg = new Mat();
                resize(img2, resizedImg, new Size((int) (img2.cols() * SCALE_FACTOR), (int) (img2.rows() * SCALE_FACTOR)));
                img2.release();

                // 【步骤2】在缩放后的图上提取特征
                sift.detectAndCompute(resizedImg, mask, cachedKp2, cachedDes2);

                CacheUtil.saveFeatures(absolutePath, cachedDes2, cachedKp2);
                buildMatcher();
                this.isInitialized = true;

                resizedImg.release();
            } catch (Exception e) {
                log.error("初始化失败", e);
            }
        } finally {
            log.info("Sift初始化耗时：{}ms", System.currentTimeMillis() - start);
        }
    }

    private void buildMatcher() {
        if (cachedDes2 == null || cachedDes2.empty()) return;
        matcher.clear();
        try (MatVector desVector = new MatVector(cachedDes2)) {
            matcher.add(desVector);
            matcher.train();
        }
    }

    private double[][] processMat(Mat img1) {
        if (img1 == null || img1.empty() || !isInitialized) return null;

        // 【步骤3】实时抓取的小图也必须按相同倍率缩放，否则特征描述符无法对齐
        Mat processedSmall = new Mat();
        resize(img1, processedSmall, new Size((int) (img1.cols() * SCALE_FACTOR), (int) (img1.rows() * SCALE_FACTOR)));

        kp1.resize(0);
        des1.release();
        knnMatches.resize(0);
        goodMatches.resize(0);

        sift.detectAndCompute(processedSmall, mask, kp1, des1);

        double[][] result = null;
        if (!des1.empty()) {
            matcher.knnMatch(des1, knnMatches, 2);
            for (long i = 0; i < knnMatches.size(); i++) {
                try (DMatchVector m = knnMatches.get(i)) {
                    if (m.size() >= 2) {
                        DMatch m1 = m.get(0);
                        DMatch m2 = m.get(1);
                        if (m1.distance() < RATIO_THRESHOLD * m2.distance()) {
                            goodMatches.push_back(m1);
                        }
                    }
                }
            }

            if (goodMatches.size() >= MIN_MATCH_COUNT) {
                result = calculateCoordinates(processedSmall, kp1, goodMatches);
            }
        }

        processedSmall.release();
        return result;
    }

    @Override
    public double[][] match(String smallImgPath) {
        try (InputStream is = ResourceUtils.getResourceStream(smallImgPath)) {
            Mat img = ImageUtil.loadToMat(is, IMREAD_GRAYSCALE);
            double[][] result = processMat(img);
            img.release();
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public double[][] match(byte[] imageBytes, int width, int height) {
        if (imageBytes == null) return null;
        BytePointer ptr = null;
        Mat bgraMat = null;
        Mat grayMat = null;
        try {
            ptr = new BytePointer(imageBytes);
            bgraMat = new Mat(height, width, CV_8UC4, ptr);
            grayMat = new Mat();
            cvtColor(bgraMat, grayMat, COLOR_BGRA2GRAY);
            return processMat(grayMat);
        } finally {
            if (grayMat != null) grayMat.release();
            if (bgraMat != null) bgraMat.release();
            if (ptr != null) ptr.deallocate();
        }
    }

    @Override
    public double[][] match(BufferedImage image) {
        if (image == null) return null;
        Mat colorMat = null;
        Mat grayMat = null;
        try {
            colorMat = matConverter.convert(j2dConverter.convert(image));
            if (colorMat == null) return null;
            grayMat = new Mat();
            cvtColor(colorMat, grayMat, COLOR_BGRA2GRAY);
            return processMat(grayMat);
        } finally {
            if (grayMat != null) grayMat.release();
            if (colorMat != null) colorMat.release();
        }
    }

    private double[][] calculateCoordinates(Mat img1, KeyPointVector kp1, DMatchVector goodMatches) {
        int n = (int) goodMatches.size();
        objPoints.create(n, 1, CV_32FC2);
        scenePoints.create(n, 1, CV_32FC2);
        FloatIndexer objIdx = objPoints.createIndexer();
        FloatIndexer sceneIdx = scenePoints.createIndexer();

        for (long i = 0; i < n; i++) {
            try (DMatch m = goodMatches.get(i)) {
                Point2f p1 = kp1.get(m.queryIdx()).pt();
                Point2f p2 = cachedKp2.get(m.trainIdx()).pt();
                objIdx.put(i, 0, 0, p1.x());
                objIdx.put(i, 0, 1, p1.y());
                sceneIdx.put(i, 0, 0, p2.x());
                sceneIdx.put(i, 0, 1, p2.y());
            }
        }

        try (Mat H = opencv_calib3d.findHomography(objPoints, scenePoints, opencv_calib3d.RANSAC,
                AppConfig.RANSAC_REPROJ_THRESHOLD, inliers, AppConfig.RANSAC_MAX_ITERS, AppConfig.RANSAC_CONFIDENCE)) {
            if (H == null || H.empty()) return null;

            FloatIndexer cIdx = objCorners.createIndexer();
            cIdx.put(0, 0, 0, 0);
            cIdx.put(0, 0, 1, 0);
            cIdx.put(1, 0, 0, 0);
            cIdx.put(1, 0, 1, img1.rows());
            cIdx.put(2, 0, 0, img1.cols());
            cIdx.put(2, 0, 1, img1.rows());
            cIdx.put(3, 0, 0, img1.cols());
            cIdx.put(3, 0, 1, 0);

            opencv_core.perspectiveTransform(objCorners, sceneCorners, H);

            double[][] result = new double[4][2];
            FloatIndexer sIdx = sceneCorners.createIndexer();
            for (int i = 0; i < 4; i++) {
                // 【步骤4】坐标还原：计算结果除以倍率，回到原始大图空间
                result[i][0] = sIdx.get(i, 0, 0) / SCALE_FACTOR;
                result[i][1] = sIdx.get(i, 0, 1) / SCALE_FACTOR;
            }
            return result;
        }
    }

    @Override
    public void destroy() {
        if (cachedDes2 != null) {
            cachedDes2.release();
            cachedDes2 = null;
        }
        mask.release();
        des1.release();
        objPoints.release();
        scenePoints.release();
        inliers.release();
        objCorners.release();
        sceneCorners.release();
        kp1.close();
        knnMatches.close();
        goodMatches.close();
        cachedKp2.close();
        sift.close();
        matcher.close();
        j2dConverter.close();
        matConverter.close();
    }
}