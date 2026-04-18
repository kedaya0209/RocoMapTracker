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
import org.bytedeco.opencv.opencv_features2d.BFMatcher;
import org.bytedeco.opencv.opencv_features2d.SIFT;

import java.awt.image.BufferedImage;
import java.io.File;

import static org.bytedeco.opencv.global.opencv_core.*;
import static org.bytedeco.opencv.global.opencv_imgcodecs.IMREAD_GRAYSCALE;
import static org.bytedeco.opencv.global.opencv_imgcodecs.imread;
import static org.bytedeco.opencv.global.opencv_imgproc.*;

/**
 * 修复版 SiftMapMatcherOpenCL
 * 增加特征提取数量日志输出
 */
@Slf4j
public class SiftMapMatcherOpenCL implements MapMatcher {
    private static final float RATIO_THRESHOLD = 0.7f;
    private static final int MIN_MATCH_COUNT = 8;
    private static final int PROCESS_WIDTH = 400;

    private final UMat cachedUDes2 = new UMat();
    private final KeyPointVector cachedKp2 = new KeyPointVector();
    private final UMat uImgProcessed = new UMat();
    private final UMat uDes1 = new UMat();
    private final UMat uMask = new UMat();
    private final UMat uGrayTemp = new UMat();

    private final SIFT sift;
    private final BFMatcher matcher;

    private final Java2DFrameConverter j2dConverter = new Java2DFrameConverter();
    private final OpenCVFrameConverter.ToMat matConverter = new OpenCVFrameConverter.ToMat();
    private boolean isInitialized = false;

    public SiftMapMatcherOpenCL(int maxFeatures) {
        if (opencv_core.haveOpenCL()) {
            opencv_core.setUseOpenCL(true);
            log.info("OpenCL 加速已启用。设备: {}", Device.getDefault().name().getString());
        }
        this.sift = SIFT.create(maxFeatures, 3, 0.01, 12.0, .8, false);
        this.matcher = new BFMatcher(NORM_L2, false);
    }

    @Override
    public void init(String largeMapPath) {
        String cachePath = largeMapPath + ".sift.xml";
        if (new File(cachePath).exists() && loadCacheToUMat(cachePath)) {
            this.isInitialized = true;
            log.info("从缓存加载大图特征完成，特征点数: {}", cachedKp2.size());
            return;
        }

        log.info("GPU(OpenCL) 提取大图特征并建立索引...");
        try (Mat img2 = imread(largeMapPath, IMREAD_GRAYSCALE);
             UMat uImg2 = img2.getUMat(ACCESS_READ);
             UMat uDes2Temp = new UMat()) {

            if (uImg2.empty()) throw new RuntimeException("图片加载失败: " + largeMapPath);

            sift.detectAndCompute(uImg2, new UMat(), cachedKp2, uDes2Temp, false);
            uDes2Temp.copyTo(cachedUDes2);

            try (Mat cpuDes = new Mat()) {
                uDes2Temp.copyTo(cpuDes);
                saveCache(cachePath, cpuDes);
            }
            this.isInitialized = true;
            log.info("大图初始化完成，提取特征点数: {}", cachedKp2.size());
        }
    }

    private synchronized double[][] processUMat(UMat uImg1) {
        if (uImg1 == null || uImg1.empty() || !isInitialized) return null;

        double scale = (uImg1.cols() > PROCESS_WIDTH) ? (double) PROCESS_WIDTH / uImg1.cols() : 1.0;

        try (KeyPointVector kp1 = new KeyPointVector();
             DMatchVectorVector knnMatches = new DMatchVectorVector()) {

            if (scale < 1.0) {
                Size sz = new Size((int) (uImg1.cols() * scale), (int) (uImg1.rows() * scale));
                resize(uImg1, uImgProcessed, sz);
            } else {
                uImg1.copyTo(uImgProcessed);
            }

            // 提取特征
            sift.detectAndCompute(uImgProcessed, uMask, kp1, uDes1, false);

            long kpCount = kp1.size();
            if (kpCount == 0) return null;

            // 匹配
            matcher.knnMatch(uDes1, cachedUDes2, knnMatches, 2);

            double[][] result = filterAndCalculate(uImg1, kp1, knnMatches, scale);

            if (result != null) {
                log.debug("匹配成功: 当前帧提取特征点数={}, 最终匹配点数={}", kpCount, knnMatches.size());
            }

            return result;
        }
    }

    private double[][] filterAndCalculate(UMat original, KeyPointVector kp1, DMatchVectorVector knnMatches, double scale) {
        try (DMatchVector goodMatches = new DMatchVector()) {
            for (long i = 0; i < knnMatches.size(); i++) {
                DMatchVector m = knnMatches.get(i);
                if (m.size() >= 2) {
                    if (m.get(0).distance() < RATIO_THRESHOLD * m.get(1).distance()) {
                        goodMatches.push_back(m.get(0));
                    }
                }
            }

            if (goodMatches.size() < MIN_MATCH_COUNT) return null;

            int rows = (int) goodMatches.size();
            try (Mat objPoints = new Mat(rows, 1, CV_32FC2);
                 Mat scenePoints = new Mat(rows, 1, CV_32FC2)) {

                FloatIndexer objIdx = objPoints.createIndexer();
                FloatIndexer sceneIdx = scenePoints.createIndexer();

                for (long i = 0; i < rows; i++) {
                    DMatch m = goodMatches.get(i);
                    Point2f p1 = kp1.get(m.queryIdx()).pt();
                    Point2f p2 = cachedKp2.get(m.trainIdx()).pt();
                    objIdx.put(i, 0, 0, (float) (p1.x() / scale));
                    objIdx.put(i, 0, 1, (float) (p1.y() / scale));
                    sceneIdx.put(i, 0, 0, p2.x());
                    sceneIdx.put(i, 0, 1, p2.y());
                }

                try (Mat mask = new Mat();
                     Mat H = opencv_calib3d.findHomography(objPoints, scenePoints, opencv_calib3d.RANSAC, 3.0, mask, 2000, 0.995)) {

                    if (H.empty()) return null;

                    double[][] res = new double[4][2];
                    try (Mat objCorners = new Mat(4, 1, CV_32FC2);
                         Mat sceneCorners = new Mat(4, 1, CV_32FC2)) {
                        FloatIndexer cIdx = objCorners.createIndexer();
                        float w = original.cols(), h = original.rows();
                        cIdx.put(0, 0, 0, 0); cIdx.put(0, 0, 1, 0);
                        cIdx.put(1, 0, 0, 0); cIdx.put(1, 0, 1, h);
                        cIdx.put(2, 0, 0, w); cIdx.put(2, 0, 1, h);
                        cIdx.put(3, 0, 0, w); cIdx.put(3, 0, 1, 0);

                        opencv_core.perspectiveTransform(objCorners, sceneCorners, H);

                        FloatIndexer sIdx = sceneCorners.createIndexer();
                        for (int i = 0; i < 4; i++) {
                            res[i][0] = sIdx.get(i, 0, 0);
                            res[i][1] = sIdx.get(i, 0, 1);
                        }
                    }
                    return res;
                }
            }
        }
    }

    @Override
    public double[][] run(byte[] imageBytes, int width, int height) {
        try (BytePointer ptr = new BytePointer(imageBytes);
             Mat bgraMat = new Mat(height, width, CV_8UC4, ptr);
             UMat uBgra = bgraMat.getUMat(ACCESS_READ)) {
            cvtColor(uBgra, uGrayTemp, COLOR_BGRA2GRAY);
            return processUMat(uGrayTemp);
        }
    }

    @Override
    public double[][] run(BufferedImage image) {
        if (image == null) return null;
        Mat colorMat = matConverter.convert(j2dConverter.convert(image));
        try (UMat uColor = colorMat.getUMat(ACCESS_READ)) {
            cvtColor(uColor, uGrayTemp, COLOR_BGRA2GRAY);
            return processUMat(uGrayTemp);
        }
    }

    @Override
    public double[][] run(String path) {
        try (Mat img = imread(path, IMREAD_GRAYSCALE);
             UMat uImg = img.getUMat(ACCESS_READ)) {
            return processUMat(uImg);
        }
    }

    private boolean loadCacheToUMat(String path) {
        try (FileStorage fs = new FileStorage(path, FileStorage.READ)) {
            if (!fs.isOpened()) return false;
            try (Mat tempDes = fs.get("descriptors").mat()) {
                tempDes.copyTo(cachedUDes2);
            }
            try (Mat kpMat = fs.get("keypoints").mat()) {
                int kpCount = kpMat.rows();
                cachedKp2.resize(kpCount);
                FloatIndexer idx = kpMat.createIndexer();
                for (int i = 0; i < kpCount; i++) {
                    KeyPoint kp = cachedKp2.get(i);
                    kp.pt().x(idx.get(i, 0)); kp.pt().y(idx.get(i, 1));
                    kp.size(idx.get(i, 2)); kp.angle(idx.get(i, 3));
                    kp.response(idx.get(i, 4)); kp.octave((int)idx.get(i, 5));
                    kp.class_id((int)idx.get(i, 6));
                }
            }
            return true;
        } catch (Exception e) { return false; }
    }

    private void saveCache(String path, Mat des2) {
        try (FileStorage fs = new FileStorage(path, FileStorage.WRITE)) {
            fs.write("descriptors", des2);
            int kpCount = (int) cachedKp2.size();
            try (Mat kpMat = new Mat(kpCount, 7, CV_32FC1)) {
                FloatIndexer idx = kpMat.createIndexer();
                for (int i = 0; i < kpCount; i++) {
                    KeyPoint kp = cachedKp2.get(i);
                    idx.put(i, 0, kp.pt().x()); idx.put(i, 1, kp.pt().y());
                    idx.put(i, 2, kp.size()); idx.put(i, 3, kp.angle());
                    idx.put(i, 4, kp.response()); idx.put(i, 5, (float)kp.octave());
                    idx.put(i, 6, (float)kp.class_id());
                }
                fs.write("keypoints", kpMat);
            }
        }
    }

    @Override
    public void destroy() {
        uImgProcessed.release(); uDes1.release(); uMask.release(); uGrayTemp.release();
        cachedUDes2.release(); cachedKp2.close(); sift.close(); matcher.close();
        j2dConverter.close(); matConverter.close();
        opencv_core.setUseOpenCL(false);
    }
}