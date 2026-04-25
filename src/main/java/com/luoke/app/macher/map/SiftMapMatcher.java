package com.luoke.app.macher.map;

import com.luoke.app.config.AppConfig;
import com.luoke.app.macher.utils.CacheUtil;
import com.luoke.app.utils.ImageUtil;
import com.luoke.app.utils.ResourceUtils;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.indexer.FloatIndexer;
import org.bytedeco.javacv.Frame;
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

    // --- 算法核心（全程复用） ---
    private final SIFT sift;
    private final FlannBasedMatcher matcher;

    // --- 静态大图缓存（全程复用） ---
    private final KeyPointVector cachedKp2 = new KeyPointVector();
    private final Mat cachedDes2 = new Mat();
    private final Mat mask = new Mat(); // 空掩码，无需特殊释放，跟随类消亡

    // --- 转换器（轻量级无状态，可复用） ---
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
            String indexPath = cachePath + ".idx";

            if (cacheFile.exists()) {
                log.info("检测到特征缓存，正在并发加载...");
                if (CacheUtil.loadFeatures(cachePath, cachedDes2, cachedKp2)) {
                    loadOrBuildMatcher(indexPath);
                    this.isInitialized = true;
                    return;
                }
                log.warn("缓存损坏，重新提取特征...");
            }

            log.info("提取大图特征 (Scale: {})...", SCALE_FACTOR);
            // 优化点：使用 try-with-resources 严格管理大图内存
            try (InputStream is = ResourceUtils.getResourceStream(largeMapPath);
                 Mat img2 = ImageUtil.loadToMat(is, IMREAD_GRAYSCALE);
                 Mat resizedImg = new Mat()) {

                if (img2.empty()) throw new RuntimeException("无法读取大图: " + largeMapPath);

                resize(img2, resizedImg, new Size((int) (img2.cols() * SCALE_FACTOR), (int) (img2.rows() * SCALE_FACTOR)));
                sift.detectAndCompute(resizedImg, mask, cachedKp2, cachedDes2);

                CacheUtil.saveFeatures(cachePath, cachedDes2, cachedKp2);
                loadOrBuildMatcher(indexPath);

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
        try (InputStream is = ResourceUtils.getResourceStream(smallImgPath);
             Mat img = ImageUtil.loadToMat(is, IMREAD_GRAYSCALE)) {
            return processMat(img);
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
        // 优化点：修复 Frame 转换时可能引发的隐性泄漏
        try (Frame cvFrame = j2dConverter.convert(image);
             Mat colorMat = matConverter.convert(cvFrame);
             Mat grayMat = new Mat()) {
            if (colorMat == null || colorMat.empty()) return null;
            cvtColor(colorMat, grayMat, COLOR_BGRA2GRAY);
            return processMat(grayMat);
        }
    }

    private double[][] processMat(Mat img1) {
        if (img1 == null || img1.empty() || !isInitialized) return null;

        // 优化点：所有的中间变量全改为局部变量，配合 try-with-resources 实现帧级释放
        try (Mat processedSmall = new Mat();
             KeyPointVector localKp1 = new KeyPointVector();
             Mat localDes1 = new Mat();
             DMatchVectorVector localKnnMatches = new DMatchVectorVector();
             DMatchVector localGoodMatches = new DMatchVector()) {

            // 1. 缩放小图
            resize(img1, processedSmall, new Size((int) (img1.cols() * SCALE_FACTOR), (int) (img1.rows() * SCALE_FACTOR)));

            // 2. 特征提取
            sift.detectAndCompute(processedSmall, mask, localKp1, localDes1);
            if (localDes1.empty()) return null;

            // 3. KNN 匹配
            matcher.knnMatch(localDes1, localKnnMatches, 2);

            // 4. Lowe's Ratio Test 筛选 (注意内层 Vector 和 Match 对象的释放)
            for (long i = 0; i < localKnnMatches.size(); i++) {
                try (DMatchVector m = localKnnMatches.get(i)) {
                    if (m.size() >= 2) {
                        try (DMatch m1 = m.get(0);
                             DMatch m2 = m.get(1)) {
                            if (m1.distance() < RATIO_THRESHOLD * m2.distance()) {
                                localGoodMatches.push_back(m1);
                            }
                        }
                    }
                }
            }

            // 5. 坐标计算
            if (localGoodMatches.size() >= MIN_MATCH_COUNT) {
                return calculateCoordinates(processedSmall, localKp1, localGoodMatches);
            }
        } catch (Exception e) {
            log.error("SIFT 匹配过程异常", e);
        }
        return null;
    }

    private double[][] calculateCoordinates(Mat img1, KeyPointVector localKp1, DMatchVector localGoodMatches) {
        int n = (int) localGoodMatches.size();

        // 优化点：局部申请矩阵，并在闭包内使用 FloatIndexer
        try (Mat localObjPoints = new Mat(n, 1, CV_32FC2);
             Mat localScenePoints = new Mat(n, 1, CV_32FC2);
             FloatIndexer objIdx = localObjPoints.createIndexer();
             FloatIndexer sceneIdx = localScenePoints.createIndexer()) {

            for (long i = 0; i < n; i++) {
                // 致命漏洞修复：防止循环中产生的 KeyPoint 和 Point2f 指针堆积
                try (DMatch m = localGoodMatches.get(i);
                     KeyPoint k1 = localKp1.get(m.queryIdx());
                     Point2f p1 = k1.pt();
                     KeyPoint k2 = cachedKp2.get(m.trainIdx());
                     Point2f p2 = k2.pt()) {

                    objIdx.put(i, 0, 0, p1.x());
                    objIdx.put(i, 0, 1, p1.y());
                    sceneIdx.put(i, 0, 0, p2.x());
                    sceneIdx.put(i, 0, 1, p2.y());
                }
            }

            // 7. 查找单应性矩阵 (RANSAC)
            try (Mat inliers = new Mat(); // Ransac 的中间变量也需释放
                 Mat H = opencv_calib3d.findHomography(localObjPoints, localScenePoints, opencv_calib3d.RANSAC,
                         AppConfig.RANSAC_REPROJ_THRESHOLD, inliers, AppConfig.RANSAC_MAX_ITERS, AppConfig.RANSAC_CONFIDENCE)) {

                if (H == null || H.empty()) return null;

                // 定义小图四个角点并执行透视变换
                try (Mat localObjCorners = new Mat(4, 1, CV_32FC2);
                     Mat localSceneCorners = new Mat(4, 1, CV_32FC2);
                     FloatIndexer cIdx = localObjCorners.createIndexer()) {

                    cIdx.put(0, 0, 0, 0);
                    cIdx.put(0, 0, 1, 0);
                    cIdx.put(1, 0, 0, 0);
                    cIdx.put(1, 0, 1, img1.rows());
                    cIdx.put(2, 0, 0, img1.cols());
                    cIdx.put(2, 0, 1, img1.rows());
                    cIdx.put(3, 0, 0, img1.cols());
                    cIdx.put(3, 0, 1, 0);

                    opencv_core.perspectiveTransform(localObjCorners, localSceneCorners, H);

                    double[][] result = new double[4][2];
                    try (FloatIndexer sIdx = localSceneCorners.createIndexer()) {
                        for (int i = 0; i < 4; i++) {
                            // 还原回原始大图尺寸坐标 (除以缩放因子)
                            result[i][0] = sIdx.get(i, 0, 0) / SCALE_FACTOR;
                            result[i][1] = sIdx.get(i, 0, 1) / SCALE_FACTOR;
                        }
                    }
                    return result;
                }
            }
        } catch (Exception e) {
            log.error("计算单应性矩阵异常", e);
            return null;
        }
    }

    @Override
    public void destroy() {
        // 只需释放一直存活的静态资源
        if (cachedDes2 != null) cachedDes2.close();
        if (cachedKp2 != null) cachedKp2.close();
        if (mask != null) mask.close();
        if (sift != null) sift.close();
        if (matcher != null) matcher.close();
        if (j2dConverter != null) j2dConverter.close();
        if (matConverter != null) matConverter.close();

        isInitialized = false;
        log.info("SIFT 静态资源已彻底释放");
    }
}