package com.luoke.macher.map.sift;

import com.luoke.macher.map.MapMatcher;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.indexer.FloatIndexer;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.bytedeco.opencv.global.opencv_calib3d;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.opencv_core.*;
import org.bytedeco.opencv.opencv_features2d.BFMatcher;
import org.bytedeco.opencv.opencv_features2d.SIFT;

import java.awt.image.BufferedImage;

import static org.bytedeco.opencv.global.opencv_core.*;
import static org.bytedeco.opencv.global.opencv_imgcodecs.IMREAD_GRAYSCALE;
import static org.bytedeco.opencv.global.opencv_imgcodecs.imread;
import static org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGRA2GRAY;
import static org.bytedeco.opencv.global.opencv_imgproc.cvtColor;

/**
 * GPU版本
 * 测试效果不佳，耗时普遍在60ms以上
 * 测试GPU: RTX 3060 laptop 6G
 */
@Slf4j
public class SiftMapMatcherOpenCL implements MapMatcher {
    private static final float RATIO_THRESHOLD = 0.75f;
    private static final int MIN_MATCH_COUNT = 10;
    private final UMat cachedUDes2; // 显存中的大图描述子
    private final KeyPointVector cachedKp2;
    private final SIFT sift;
    private final BFMatcher matcher;
    private boolean isInitialized = false;

    public SiftMapMatcherOpenCL(int maxFeatures) {
        // 开启 OpenCL 加速
        if (org.bytedeco.opencv.global.opencv_core.haveOpenCL()) {
            org.bytedeco.opencv.global.opencv_core.setUseOpenCL(true);
            // 修改为以下方式获取设备名称
            Device device = Device.getDefault();
            log.info("OpenCL 加速已启用。当前设备: {}", device.name().getString());
        } else {
            log.warn("当前环境不支持 OpenCL，将回退至 CPU 模式");
        }

        this.sift = SIFT.create(maxFeatures, 3, 0.04, 10.0, 1.6, false);
        // GPU 模式下，BFMatcher 通常比 FlannBasedMatcher 并行效率更高
        this.matcher = new BFMatcher(NORM_L2, false);
        this.cachedUDes2 = new UMat();
        this.cachedKp2 = new KeyPointVector();
    }

    @Override
    public void init(String largeMapPath) {
        log.info("加载大图并上传至显存...");
        try (Mat img = imread(largeMapPath, IMREAD_GRAYSCALE);
             UMat uImg = img.getUMat(ACCESS_READ)) {

            if (uImg.empty()) throw new RuntimeException("读取大图失败");

            // 在 GPU 上执行特征提取
            sift.detectAndCompute(uImg, new UMat(), cachedKp2, cachedUDes2);
            this.isInitialized = true;
            log.info("大图初始化完成，特征点数: {}", cachedKp2.size());
        }
    }

    @Override
    public double[][] run(String smallImgPath) {
        try (Mat img = imread(smallImgPath, IMREAD_GRAYSCALE);
             UMat uImg = img.getUMat(ACCESS_READ)) {
            return processUMat(uImg);
        }
    }

    @Override
    public double[][] run(byte[] imageBytes, int width, int height) {
        if (imageBytes == null) return null;
        try (BytePointer ptr = new BytePointer(imageBytes);
             Mat bgraMat = new Mat(height, width, CV_8UC4, ptr);
             UMat uBgra = bgraMat.getUMat(ACCESS_READ);
             UMat uGray = new UMat()) {

            cvtColor(uBgra, uGray, COLOR_BGRA2GRAY);
            return processUMat(uGray);
        }
    }

    @Override
    public double[][] run(BufferedImage image) {
        if (image == null) return null;
        try (Java2DFrameConverter converter = new Java2DFrameConverter();
             org.bytedeco.javacv.OpenCVFrameConverter.ToMat matConverter = new org.bytedeco.javacv.OpenCVFrameConverter.ToMat()) {

            Mat colorMat = matConverter.convert(converter.convert(image));
            try (UMat uColor = colorMat.getUMat(ACCESS_READ);
                 UMat uGray = new UMat()) {
                cvtColor(uColor, uGray, COLOR_BGRA2GRAY);
                return processUMat(uGray);
            }
        }
    }

    private double[][] processUMat(UMat uImg1) {
        if (uImg1 == null || uImg1.empty() || !isInitialized) return null;

        try (KeyPointVector kp1 = new KeyPointVector();
             UMat uDes1 = new UMat();
             DMatchVectorVector knnMatches = new DMatchVectorVector();
             DMatchVector goodMatches = new DMatchVector()) {

            // GPU 提取小图特征
            sift.detectAndCompute(uImg1, new UMat(), kp1, uDes1);

            // GPU 暴力匹配
            matcher.knnMatch(uDes1, cachedUDes2, knnMatches, 2);

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
                return calculateCoordinates(uImg1, kp1, goodMatches);
            }
        }
        return null;
    }

    // 坐标计算依然在 CPU 上进行，因为单应性变换的数据量极小
    private double[][] calculateCoordinates(UMat uImg1, KeyPointVector kp1, DMatchVector goodMatches) {
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

            try (Mat H = opencv_calib3d.findHomography(objPoints, scenePoints, opencv_calib3d.RANSAC, 3.0, new Mat(), 2000, 0.995)) {
                if (H.empty()) return null;

                try (Mat objCorners = new Mat(4, 1, CV_32FC2);
                     Mat sceneCorners = new Mat(4, 1, CV_32FC2)) {

                    FloatIndexer cIdx = objCorners.createIndexer();
                    cIdx.put(0, 0, 0, 0);
                    cIdx.put(0, 0, 1, 0);
                    cIdx.put(1, 0, 0, 0);
                    cIdx.put(1, 0, 1, uImg1.rows());
                    cIdx.put(2, 0, 0, uImg1.cols());
                    cIdx.put(2, 0, 1, uImg1.rows());
                    cIdx.put(3, 0, 0, uImg1.cols());
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
        log.info("销毁 OpenGL 匹配器...");
        cachedUDes2.release();
        cachedKp2.close();
        sift.close();
        matcher.close();
        org.bytedeco.opencv.global.opencv_core.setUseOpenCL(false);
    }
}