package com.luoke.macher.map.sift;

import com.luoke.macher.map.MapMatcher;
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

import static org.bytedeco.opencv.global.opencv_core.*;
import static org.bytedeco.opencv.global.opencv_imgcodecs.IMREAD_GRAYSCALE;
import static org.bytedeco.opencv.global.opencv_imgcodecs.imread;
import static org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGRA2GRAY;
import static org.bytedeco.opencv.global.opencv_imgproc.cvtColor;

/**
 * CPU版本
 */
@Slf4j
public class SiftMapMatcher implements MapMatcher {
    private static final float RATIO_THRESHOLD = 0.75f;
    private static final int MIN_MATCH_COUNT = 10;

    private Mat cachedDes2; // 注意：由于 mat() 返回新对象，这里不加 final 方便赋值
    private final KeyPointVector cachedKp2;
    private final SIFT sift;
    private final FlannBasedMatcher matcher;

    private final Java2DFrameConverter j2dConverter = new Java2DFrameConverter();
    private final OpenCVFrameConverter.ToMat matConverter = new OpenCVFrameConverter.ToMat();
    private boolean isInitialized = false;

    public SiftMapMatcher(int maxFeatures) {
        // maxFeatures 建议设为 500-1000 以平衡速度和精度
        this.sift = SIFT.create(maxFeatures, 3, 0.01, 12.0, .8, false);
        this.matcher = new FlannBasedMatcher();
        this.cachedDes2 = new Mat();
        this.cachedKp2 = new KeyPointVector();
    }

    @Override
    public void init(String largeMapPath) {
        String cachePath = largeMapPath + ".sift.xml";
        File cacheFile = new File(cachePath);

        if (cacheFile.exists()) {
            log.info("从缓存加载特征: {}", cachePath);
            if (loadCache(cachePath)) {
                buildMatcher();
                this.isInitialized = true;
                return;
            }
        }

        log.info("提取大图特征...");
        try (Mat img2 = imread(largeMapPath, IMREAD_GRAYSCALE)) {
            if (img2.empty()) throw new RuntimeException("加载失败: " + largeMapPath);
            sift.detectAndCompute(img2, new Mat(), cachedKp2, cachedDes2);
            saveCache(cachePath);
            buildMatcher();
            this.isInitialized = true;
        }
    }

    private void buildMatcher() {
        matcher.clear();
        try (MatVector desVector = new MatVector(cachedDes2)) {
            matcher.add(desVector);
        }
        matcher.train();
    }

    private void saveCache(String path) {
        try (FileStorage fs = new FileStorage(path, FileStorage.WRITE)) {
            // 根据 FileStorage 源码，直接 write(String, Mat)
            fs.write("descriptors", cachedDes2);

            // 手动将 KeyPointVector 转为 Mat 保存
            int kpCount = (int) cachedKp2.size();
            try (Mat kpMat = new Mat(kpCount, 7, CV_32FC1)) {
                FloatIndexer idx = kpMat.createIndexer();
                for (int i = 0; i < kpCount; i++) {
                    KeyPoint kp = cachedKp2.get(i);
                    idx.put(i, 0, kp.pt().x());
                    idx.put(i, 1, kp.pt().y());
                    idx.put(i, 2, kp.size());
                    idx.put(i, 3, kp.angle());
                    idx.put(i, 4, kp.response());
                    idx.put(i, 5, (float)kp.octave());
                    idx.put(i, 6, (float)kp.class_id());
                }
                fs.write("keypoints", kpMat);
            }
            log.info("特征持久化已保存");
        } catch (Exception e) {
            log.error("保存失败", e);
        }
    }

    private boolean loadCache(String path) {
        try (FileStorage fs = new FileStorage(path, FileStorage.READ)) {
            if (!fs.isOpened()) return false;

            // 核心修复点 1：根据 FileNode 源码使用 .mat() 方法
            if (this.cachedDes2 != null) this.cachedDes2.release();
            this.cachedDes2 = fs.get("descriptors").mat();

            // 核心修复点 2：读取关键点矩阵
            try (Mat kpMat = fs.get("keypoints").mat()) {
                if (cachedDes2.empty() || kpMat.empty()) return false;

                int kpCount = kpMat.rows();
                cachedKp2.resize(kpCount);
                FloatIndexer idx = kpMat.createIndexer();
                for (int i = 0; i < kpCount; i++) {
                    KeyPoint kp = cachedKp2.get(i);
                    kp.pt().x(idx.get(i, 0));
                    kp.pt().y(idx.get(i, 1));
                    kp.size(idx.get(i, 2));
                    kp.angle(idx.get(i, 3));
                    kp.response(idx.get(i, 4));
                    kp.octave((int)idx.get(i, 5));
                    kp.class_id((int)idx.get(i, 6));
                }
            }
            log.info("特征点加载成功: {}", cachedKp2.size());
            return true;
        } catch (Exception e) {
            log.warn("缓存加载失败: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public double[][] run(String smallImgPath) {
        try (Mat img = imread(smallImgPath, IMREAD_GRAYSCALE)) {
            return processMat(img);
        }
    }

    @Override
    public double[][] run(byte[] imageBytes, int width, int height) {
        if (imageBytes == null) return null;
        try (BytePointer ptr = new BytePointer(imageBytes);
             Mat bgraMat = new Mat(height, width, CV_8UC4, ptr);
             Mat grayMat = new Mat()) {
            cvtColor(bgraMat, grayMat, COLOR_BGRA2GRAY);
            return processMat(grayMat);
        }
    }

    @Override
    public double[][] run(BufferedImage image) {
        if (image == null) return null;
        Mat colorMat = matConverter.convert(j2dConverter.convert(image));
        try (Mat grayMat = new Mat()) {
            cvtColor(colorMat, grayMat, COLOR_BGRA2GRAY);
            return processMat(grayMat);
        }
    }

    private double[][] processMat(Mat img1) {
        if (img1 == null || img1.empty() || !isInitialized) return null;

        try (KeyPointVector kp1 = new KeyPointVector();
             Mat des1 = new Mat();
             DMatchVectorVector knnMatches = new DMatchVectorVector();
             DMatchVector goodMatches = new DMatchVector()) {

            sift.detectAndCompute(img1, new Mat(), kp1, des1);
            matcher.knnMatch(des1, knnMatches, 2);

            for (long i = 0; i < knnMatches.size(); i++) {
                DMatchVector m = knnMatches.get(i);
                if (m.size() >= 2) {
                    DMatch m1 = m.get(0);
                    DMatch m2 = m.get(1);
                    if (m1.distance() < RATIO_THRESHOLD * m2.distance()) {
                        goodMatches.push_back(m1);
                    }
                }
            }

            if (goodMatches.size() >= MIN_MATCH_COUNT) {
                return calculateCoordinates(img1, kp1, goodMatches);
            }
        }
        return null;
    }

    private double[][] calculateCoordinates(Mat img1, KeyPointVector kp1, DMatchVector goodMatches) {
        int rows = (int) goodMatches.size();
        try (Mat objPoints = new Mat(rows, 1, CV_32FC2);
             Mat scenePoints = new Mat(rows, 1, CV_32FC2)) {

            FloatIndexer objIdx = objPoints.createIndexer();
            FloatIndexer sceneIdx = scenePoints.createIndexer();

            for (long i = 0; i < rows; i++) {
                DMatch m = goodMatches.get(i);
                Point2f p1 = kp1.get(m.queryIdx()).pt();
                Point2f p2 = cachedKp2.get(m.trainIdx()).pt();
                objIdx.put(i, 0, 0, p1.x());
                objIdx.put(i, 0, 1, p1.y());
                sceneIdx.put(i, 0, 0, p2.x());
                sceneIdx.put(i, 0, 1, p2.y());
            }

            try (Mat H = opencv_calib3d.findHomography(objPoints, scenePoints, opencv_calib3d.RANSAC, 3.0, new Mat(), 300, 0.995)) {
                if (H.empty()) return null;

                try (Mat objCorners = new Mat(4, 1, CV_32FC2);
                     Mat sceneCorners = new Mat(4, 1, CV_32FC2)) {

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
        }
    }

    @Override
    public void destroy() {
        log.info("释放资源...");
        if (cachedDes2 != null) cachedDes2.release();
        cachedKp2.close();
        sift.close();
        matcher.close();
        j2dConverter.close();
        matConverter.close();
    }
}