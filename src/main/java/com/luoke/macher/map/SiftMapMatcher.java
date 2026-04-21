package com.luoke.macher.map;

import com.luoke.app.config.AppConfig;
import com.luoke.app.utils.FileUtil;
import com.luoke.app.utils.ImageUtil;
import com.luoke.macher.utils.CacheUtil;
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

import static org.bytedeco.opencv.global.opencv_core.CV_32FC2;
import static org.bytedeco.opencv.global.opencv_core.CV_8UC4;
import static org.bytedeco.opencv.global.opencv_imgcodecs.IMREAD_GRAYSCALE;
import static org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGRA2GRAY;
import static org.bytedeco.opencv.global.opencv_imgproc.cvtColor;

@Slf4j
public class SiftMapMatcher implements MapMatcher {

    // 从配置读取
    private static final float RATIO_THRESHOLD = AppConfig.MATCH_RATIO_THRESHOLD;
    private static final int MIN_MATCH_COUNT = AppConfig.MATCH_MIN_COUNT;
    private final KeyPointVector cachedKp2;
    private final SIFT sift;
    private final FlannBasedMatcher matcher;
    // 预分配掩码（复用）
    private final Mat mask = new Mat();
    // ====================== 全局预分配对象，避免每次match都new ======================
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

    // ==============================================================================
    private final OpenCVFrameConverter.ToMat matConverter = new OpenCVFrameConverter.ToMat();
    private Mat cachedDes2;
    private boolean isInitialized = false;

    public SiftMapMatcher() {
        // ====================== 从配置创建 SIFT ======================
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
        String cacheFileName = largeMapPath + ".sift.zst";
        File cacheFile = FileUtil.getRelativeFile(cacheFileName);
        String absolutePath = cacheFile.getAbsolutePath();

        if (cacheFile.exists()) {
            log.info("从压缩缓存加载特征: {}", absolutePath);
            if (CacheUtil.loadFeatures(absolutePath, cachedDes2, cachedKp2)) {
                buildMatcher();
                this.isInitialized = true;
                return;
            }
            cacheFile.delete();
        }

        log.info("提取大图特征 (耗时操作)...");
        try (Mat img2 = ImageUtil.loadResourceToMat(largeMapPath, IMREAD_GRAYSCALE)) {
            if (img2.empty()) throw new RuntimeException("加载失败: " + largeMapPath);
            sift.detectAndCompute(img2, mask, cachedKp2, cachedDes2);
            log.info("特征提取完成，特征点数: {}", cachedKp2.size());
            CacheUtil.saveFeatures(absolutePath, cachedDes2, cachedKp2);
            buildMatcher();
            this.isInitialized = true;
        } catch (Exception e) {
            log.error("初始化失败", e);
        }
    }

    private void buildMatcher() {
        matcher.clear();
        try (MatVector desVector = new MatVector(cachedDes2)) {
            matcher.add(desVector);
        }
        matcher.train();
    }

    @Override
    public double[][] match(String smallImgPath) {
        try (Mat img = ImageUtil.loadResourceToMat(smallImgPath, IMREAD_GRAYSCALE)) {
            return processMat(img);
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

    private double[][] processMat(Mat img1) {
        if (img1 == null || img1.empty() || !isInitialized)
            return null;

        // 清空复用对象，不释放native内存
        kp1.resize(0);
        des1.release();
        knnMatches.resize(0);
        goodMatches.resize(0);

        sift.detectAndCompute(img1, mask, kp1, des1);
        if (des1.empty())
            return null;

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
            return calculateCoordinates(img1, kp1, goodMatches);
        }
        return null;
    }

    private double[][] calculateCoordinates(Mat img1, KeyPointVector kp1, DMatchVector goodMatches) {
        int n = (int) goodMatches.size();

        // 复用Mat，只重新创建尺寸，不重复new
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

        // ====================== 从配置读取 RANSAC 参数 ======================
        try (Mat H = opencv_calib3d.findHomography(
                objPoints,
                scenePoints,
                opencv_calib3d.RANSAC,
                AppConfig.RANSAC_REPROJ_THRESHOLD,
                inliers,
                AppConfig.RANSAC_MAX_ITERS,
                AppConfig.RANSAC_CONFIDENCE
        )) {
            if (H == null || H.empty())
                return null;

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
                result[i][0] = sIdx.get(i, 0, 0);
                result[i][1] = sIdx.get(i, 0, 1);
            }
            return result;
        }
    }

    @Override
    public void destroy() {
        log.info("释放 SiftMapMatcher 关键资源...");

        // 统一释放所有预分配Mat
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

        // 关闭所有vector
        kp1.close();
        knnMatches.close();
        goodMatches.close();
        cachedKp2.close();

        sift.close();
        matcher.close();
        j2dConverter.close();
        matConverter.close();

        log.info("资源释放完毕");
    }
}