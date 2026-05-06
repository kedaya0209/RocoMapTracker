package com.luoke.app.macher.map.sift;

import com.github.luben.zstd.Zstd;
import com.luoke.app.config.AppConfig;
import com.luoke.app.macher.map.MapMatcher;
import com.luoke.app.utils.ResourceUtils;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacpp.DoublePointer;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * SIFT + PCA-64 + 量化匹配器 (JavaCPP 版本)
 */
@Slf4j
public class SiftPCAUltraMapMatcher implements MapMatcher {

    private static volatile SiftPCAUltraMapMatcher instance;

    private final SIFT sift = SIFT.create(
            AppConfig.SIFT_N_FEATURES,
            AppConfig.SIFT_N_OCTAVE_LAYERS,
            AppConfig.SIFT_CONTRAST_THRESHOLD,
            AppConfig.SIFT_EDGE_THRESHOLD,
            AppConfig.SIFT_SIGMA,
            false);

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "pca-matcher-rebuilder");
        t.setDaemon(true);
        return t;
    });

    private final Mat pcaEigenvectors = new Mat();
    private final Mat projectedMean = new Mat();
    private final Mat mapDescriptors8U = new Mat();
    private final Mat emptyMask = new Mat();
    private final Mat emptyMat = new Mat();
    private final Mat srcCenter = new Mat(1, 1, opencv_core.CV_32FC2);
    private final Mat dstCenter = new Mat(1, 1, opencv_core.CV_32FC2);
    private final Mat ransacMask = new Mat();

    private volatile DescriptorMatcher activeMatcher;

    private ByteBuffer mapKeyPointsDirectBuffer;
    private int mapPointsCount;

    private float qMin;
    private float qScale;

    private Mat sceneImg;
    private int currentWidth = -1, currentHeight = -1;

    private float[] srcBuf = new float[0];
    private float[] dstBuf = new float[0];

    private volatile boolean initialized;
    private int matchCount;

    private SiftPCAUltraMapMatcher() {
    }

    public static SiftPCAUltraMapMatcher getInstance() {
        if (instance == null) {
            synchronized (SiftPCAUltraMapMatcher.class) {
                if (instance == null) instance = new SiftPCAUltraMapMatcher();
            }
        }
        return instance;
    }

    @Override
    public boolean init(String mapPath) {
        if (initialized) return true;
        synchronized (this) {
            if (initialized) return true;
            File cacheFile = ResourceUtils.getExternalFile(mapPath + ".pca64.ultra.feat");
            if (loadFromCache(cacheFile.getAbsolutePath())) {
                log.info("SIFT-PCA-ULTRA (JavaCPP) 缓存载入成功");
                initMatcher();
                initialized = true;
                return true;
            }
            return trainAndSave(mapPath, cacheFile.getAbsolutePath());
        }
    }

    @Override
    public double[][] match(byte[] grayData, int width, int height) {
        if (!initialized || grayData == null) return null;

        // sceneImg 是字段级长期 Mat，必须在 scope 外创建/更新
        prepareSceneMat(width, height);
        sceneImg.data().put(grayData);

        DescriptorMatcher currentMatcher = this.activeMatcher;
        if (currentMatcher == null) return null;

        try (PointerScope scope = new PointerScope()) {

            KeyPointVector sceneKeyPoints = new KeyPointVector();
            Mat sceneDescriptors = new Mat();

            sift.detectAndCompute(sceneImg, emptyMask, sceneKeyPoints, sceneDescriptors);
            if (sceneDescriptors.empty()) return null;

            // 确保 CV_32F 并 PCA 投影
            Mat sceneProjected;
            if (sceneDescriptors.type() == opencv_core.CV_32F) {
                sceneProjected = projectDescriptors(sceneDescriptors);
            } else {
                Mat tmp = new Mat();
                sceneDescriptors.convertTo(tmp, opencv_core.CV_32F);
                sceneProjected = projectDescriptors(tmp);
            }

            // 量化
            Mat scene8U = new Mat();
            sceneProjected.convertTo(scene8U, opencv_core.CV_8U, qScale, -qMin * qScale);
            Mat queryFloat = new Mat();
            scene8U.convertTo(queryFloat, opencv_core.CV_32F);

            DMatchVectorVector rawMatches = new DMatchVectorVector();
            currentMatcher.knnMatch(queryFloat, rawMatches, 2);

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

            if (goodMatches.size() >= AppConfig.MATCH_MIN_COUNT) {
                double[][] result = executeRansac(goodMatches, sceneKeyPoints, width, height);
                if (++matchCount % 300 == 0) asyncRebuildMatcher();
                return result;
            }
            if (++matchCount % 300 == 0) asyncRebuildMatcher();
        } catch (Exception e) {
            log.error("匹配异常", e);
        }
        return null;
    }

    private Mat projectDescriptors(Mat src) {
        Mat dst = new Mat();
        opencv_core.gemm(src, pcaEigenvectors, 1.0, emptyMat, 0, dst, opencv_core.CV_HAL_GEMM_2_T);
        Mat repeatedMean = new Mat();
        opencv_core.repeat(projectedMean, dst.rows(), 1, repeatedMean);
        opencv_core.subtract(dst, repeatedMean, dst);
        return dst;
    }

    private double[][] executeRansac(List<DMatch> goodMatches, KeyPointVector sceneKps, int w, int h) {
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

        Mat H = opencv_calib3d.findHomography(srcPts, dstPts, opencv_calib3d.RANSAC,
                AppConfig.RANSAC_REPROJ_THRESHOLD, ransacMask,
                AppConfig.RANSAC_MAX_ITERS, AppConfig.RANSAC_CONFIDENCE);

        if (!H.empty() && H.rows() == 3) {
            new FloatPointer(srcCenter.data()).put((float) (w >> 1), (float) (h >> 1));
            opencv_core.perspectiveTransform(srcCenter, dstCenter, H);
            float[] res = new float[2];
            new FloatPointer(dstCenter.data()).get(res);
            return new double[][]{{(double) res[0], (double) res[1]}};
        }
        return null;
    }

    private void prepareSceneMat(int w, int h) {
        if (w != currentWidth || h != currentHeight) {
            if (sceneImg != null) sceneImg.close();
            currentWidth = w;
            currentHeight = h;
            sceneImg = new Mat(h, w, opencv_core.CV_8UC1);
        }
    }

    private void initMatcher() {
        FlannBasedMatcher newMatcher = new FlannBasedMatcher(
                new KDTreeIndexParams(1),
                new SearchParams(24, 0, true));

        try (PointerScope scope = new PointerScope()) {
            Mat tempFloat = new Mat();
            mapDescriptors8U.convertTo(tempFloat, opencv_core.CV_32F);
            MatVector trainDescs = new MatVector(1);
            trainDescs.put(0, tempFloat);
            newMatcher.add(trainDescs);
            newMatcher.train();
        }

        DescriptorMatcher old = this.activeMatcher;
        this.activeMatcher = newMatcher;
        if (old != null) old.clear();
    }

    private synchronized void asyncRebuildMatcher() {
        final DescriptorMatcher oldMatcher = this.activeMatcher;
        scheduler.execute(() -> {
            try {
                FlannBasedMatcher newMatcher = new FlannBasedMatcher(
                        new KDTreeIndexParams(1),
                        new SearchParams(24, 0, true));

                try (PointerScope scope = new PointerScope()) {
                    Mat tempFloat = new Mat();
                    mapDescriptors8U.convertTo(tempFloat, opencv_core.CV_32F);
                    MatVector trainDescs = new MatVector(1);
                    trainDescs.put(0, tempFloat);
                    newMatcher.add(trainDescs);
                    newMatcher.train();
                }

                this.activeMatcher = newMatcher;
                log.debug("异步重建 PCA-ULTRA 匹配器完成");

                if (oldMatcher != null) {
                    scheduler.schedule(() -> oldMatcher.clear(), 1, TimeUnit.SECONDS);
                }
            } catch (Exception e) {
                log.error("异步重建 PCA-ULTRA 匹配器失败", e);
            }
        });
    }

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

            KeyPointVector kps = new KeyPointVector();
            Mat rawDescriptors = new Mat();
            sift.detectAndCompute(mapGray, emptyMask, kps, rawDescriptors);
            if (rawDescriptors.type() != opencv_core.CV_32F) {
                Mat tmp = new Mat();
                rawDescriptors.convertTo(tmp, opencv_core.CV_32F);
                rawDescriptors = tmp;
            }

            // PCA
            Mat rawMean = new Mat();
            Mat fullEigenvectors = new Mat();
            opencv_core.PCACompute(rawDescriptors, rawMean, fullEigenvectors);
            int dim = Math.min(64, fullEigenvectors.rows());
            fullEigenvectors.rowRange(0, dim).copyTo(pcaEigenvectors);
            opencv_core.gemm(rawMean, pcaEigenvectors, 1, emptyMat, 0, projectedMean, opencv_core.CV_HAL_GEMM_2_T);

            // 投影并量化
            Mat projected = projectDescriptors(rawDescriptors);
            DoublePointer minVal = new DoublePointer(1);
            DoublePointer maxVal = new DoublePointer(1);
            opencv_core.minMaxLoc(projected, minVal, maxVal, null, null, emptyMat);
            qMin = (float) minVal.get();
            qScale = 255.0f / ((float) maxVal.get() - qMin + 1e-6f);

            projected.convertTo(mapDescriptors8U, opencv_core.CV_8U, qScale, -qMin * qScale);

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

            log.info("SIFT-PCA-ULTRA 训练完成: {} 地图特征点 (PCA 64维, 量化8bit)", mapPointsCount);

            saveToCache(cachePath);

        } catch (Exception e) {
            log.error("训练失败", e);
            return false;
        }

        initMatcher();
        initialized = true;
        return true;
    }

    private void saveToCache(String path) {
        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(path))) {
            writeMat(dos, pcaEigenvectors);
            writeMat(dos, projectedMean);
            writeMat(dos, mapDescriptors8U);
            dos.writeFloat(qMin);
            dos.writeFloat(qScale);
            dos.writeInt(mapPointsCount);
            FloatBuffer fb = mapKeyPointsDirectBuffer.asFloatBuffer();
            for (int i = 0; i < mapPointsCount * 2; i++) {
                dos.writeFloat(fb.get(i));
            }
        } catch (Exception e) {
            log.error("存储缓存失败", e);
        }
    }

    private boolean loadFromCache(String path) {
        File f = new File(path);
        if (!f.exists()) return false;
        try (PointerScope scope = new PointerScope();
             DataInputStream dis = new DataInputStream(new FileInputStream(f))) {
            Mat m1 = readMat(dis);
            m1.copyTo(pcaEigenvectors);
            Mat m2 = readMat(dis);
            m2.copyTo(projectedMean);
            Mat m3 = readMat(dis);
            m3.copyTo(mapDescriptors8U);
            qMin = dis.readFloat();
            qScale = dis.readFloat();
            mapPointsCount = dis.readInt();
            mapKeyPointsDirectBuffer = ByteBuffer.allocateDirect(mapPointsCount * 2 * 4)
                    .order(ByteOrder.nativeOrder());
            FloatBuffer fb = mapKeyPointsDirectBuffer.asFloatBuffer();
            for (int i = 0; i < mapPointsCount * 2; i++) {
                fb.put(i, dis.readFloat());
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void writeMat(DataOutputStream dos, Mat m) throws IOException {
        int r = m.rows(), c = m.cols(), t = m.type();
        dos.writeInt(r);
        dos.writeInt(c);
        dos.writeInt(t);
        byte[] data;
        if (t == opencv_core.CV_32F) {
            float[] f = new float[(int) (m.total() * m.channels())];
            new FloatPointer(m.data()).get(f);
            data = new byte[f.length * 4];
            ByteBuffer.wrap(data).order(ByteOrder.nativeOrder()).asFloatBuffer().put(f);
        } else {
            data = new byte[(int) (m.total() * m.channels())];
            m.data().get(data);
        }
        byte[] compressed = Zstd.compress(data);
        dos.writeInt(compressed.length);
        dos.writeInt(data.length);
        dos.write(compressed);
    }

    private Mat readMat(DataInputStream dis) throws IOException {
        int r = dis.readInt(), c = dis.readInt(), t = dis.readInt();
        int cLen = dis.readInt(), rLen = dis.readInt();
        byte[] cData = new byte[cLen];
        dis.readFully(cData);
        byte[] rData = Zstd.decompress(cData, rLen);
        Mat m = new Mat(r, c, t);
        if (t == opencv_core.CV_32F) {
            float[] f = new float[rLen / 4];
            ByteBuffer.wrap(rData).order(ByteOrder.nativeOrder()).asFloatBuffer().get(f);
            new FloatPointer(m.data()).put(f);
        } else {
            m.data().put(rData);
        }
        return m;
    }

    @Override
    public void destroy() {
        pcaEigenvectors.close();
        projectedMean.close();
        mapDescriptors8U.close();
        emptyMask.close();
        emptyMat.close();
        srcCenter.close();
        dstCenter.close();
        ransacMask.close();
        if (sceneImg != null) sceneImg.close();
        if (activeMatcher != null) activeMatcher.clear();
        sift.close();
        mapKeyPointsDirectBuffer = null;
        scheduler.shutdown();
        initialized = false;
    }
}
