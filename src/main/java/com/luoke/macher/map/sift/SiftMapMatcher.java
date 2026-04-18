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

import static org.bytedeco.opencv.global.opencv_core.CV_32FC2;
import static org.bytedeco.opencv.global.opencv_core.CV_8UC4;
import static org.bytedeco.opencv.global.opencv_imgcodecs.IMREAD_GRAYSCALE;
import static org.bytedeco.opencv.global.opencv_imgcodecs.imread;
import static org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGRA2GRAY;
import static org.bytedeco.opencv.global.opencv_imgproc.cvtColor;

/**
 * CPU版本
 * 处理时间一般在30ms之内
 * 测试CPU: R7-5800H
 */
@Slf4j
public class SiftMapMatcher implements MapMatcher {
    private static final float RATIO_THRESHOLD = 0.75f;
    private static final int MIN_MATCH_COUNT = 10;
    private final Mat cachedDes2;
    private final KeyPointVector cachedKp2;
    private final SIFT sift;
    private final FlannBasedMatcher matcher;
    // 复用转换器以提升性能
    private final Java2DFrameConverter j2dConverter = new Java2DFrameConverter();
    private final OpenCVFrameConverter.ToMat matConverter = new OpenCVFrameConverter.ToMat();
    private boolean isInitialized = false;

    public SiftMapMatcher(int maxFeatures) {
        // maxFeatures 建议设为 500-1000 以平衡速度和精度
        this.sift = SIFT.create(maxFeatures, 3, 0.04, 10.0, 1.6, false);
        this.matcher = new FlannBasedMatcher();
        this.cachedDes2 = new Mat();
        this.cachedKp2 = new KeyPointVector();
    }

    @Override
    public void init(String largeMapPath) {
        log.info("预热大图特征并构建 FLANN 索引...");
        try (Mat img2 = imread(largeMapPath, IMREAD_GRAYSCALE)) {
            if (img2.empty()) throw new RuntimeException("图片加载失败: " + largeMapPath);

            sift.detectAndCompute(img2, new Mat(), cachedKp2, cachedDes2);

            // 核心性能优化：将描述符提前加入并训练索引
            matcher.clear();
            try (MatVector desVector = new MatVector(cachedDes2)) {
                matcher.add(desVector);
            }
            matcher.train(); // 预训练索引树

            this.isInitialized = true;
            log.info("大图特征点提取成功: {}", cachedKp2.size());
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
        // 使用成员变量转换器，减少分配开销
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

            // 性能优化：直接使用训练好的 matcher
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
        cachedDes2.release();
        cachedKp2.close();
        sift.close();
        matcher.close();
        j2dConverter.close();
        matConverter.close();
    }
}