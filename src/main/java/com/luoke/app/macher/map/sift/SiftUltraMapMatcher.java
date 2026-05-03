package com.luoke.app.macher.map.sift;

import com.github.luben.zstd.Zstd;
import com.luoke.app.config.AppConfig;
import com.luoke.app.macher.map.MapMatcher;
import com.luoke.app.utils.JNIFrameNative;
import com.luoke.app.utils.NativeCleaner;
import com.luoke.app.utils.ResourceUtils;
import lombok.extern.slf4j.Slf4j;
import org.opencv.calib3d.Calib3d;
import org.opencv.core.*;
import org.opencv.features2d.DescriptorMatcher;
import org.opencv.features2d.SIFT;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * SIFT + PCA + 量化匹配器 (JNI 帧管理 + 异步重建 + 零泄漏终版)
 * 专为 GraalVM 25 Native Image + Serial GC 设计
 * 彻底杜绝 JNI 局部引用、DirectBuffer、Flann 索引泄漏
 */
@Slf4j
public class SiftUltraMapMatcher implements MapMatcher {

    private static volatile SiftUltraMapMatcher instance;

    private final SIFT sift = SIFT.create(
            AppConfig.SIFT_N_FEATURES,
            AppConfig.SIFT_N_OCTAVE_LAYERS,
            AppConfig.SIFT_CONTRAST_THRESHOLD,
            AppConfig.SIFT_EDGE_THRESHOLD,
            AppConfig.SIFT_SIGMA);

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "matcher-rebuilder");
        t.setDaemon(true);
        return t;
    });
    // 成员矩阵池
    private final Mat pcaEigenvectors = new Mat();
    private final Mat mapDescriptors = new Mat();
    private final Mat projectedMean = new Mat();
    private final Mat sceneDescriptorsFloat = new Mat();
    private final MatOfKeyPoint sceneKeyPoints = new MatOfKeyPoint();
    private final Mat sceneDescriptors = new Mat();
    private final Mat emptyMat = new Mat();
    private final Mat reducedSceneDescriptors = new Mat();
    private final Mat emptyMask = new Mat();
    private final Mat sCenter = new Mat(1, 1, CvType.CV_32FC2);
    private final MatOfPoint2f mSrc = new MatOfPoint2f();
    private final MatOfPoint2f mDst = new MatOfPoint2f();
    private final Mat dCenter = new Mat(1, 1, CvType.CV_32FC2);
    private final Scalar qMinScalar = new Scalar(0);
    // 复用成员变量，杜绝每帧 new Mat()
    private final Mat quantized8U = new Mat();
    private final Mat queryFloat = new Mat();
    private final Mat repeatMean = new Mat();
    private final Mat ransacMask = new Mat();
    private volatile DescriptorMatcher activeMatcher = DescriptorMatcher.create(DescriptorMatcher.FLANNBASED);

    private ByteBuffer mapKeyPointsDirectBuffer;
    private int mapPointsCount = 0;
    private float qMin = 0f;
    private float qScale = 1f;

    private ByteBuffer sceneRawPixelBuffer;
    private Mat sceneImg;
    private float[] sceneKpsData = new float[0];
    private int currentWidth = -1, currentHeight = -1;
    private volatile boolean initialized = false;
    private int matchCount = 0;
    private long lastGcTime = 0;

    private SiftUltraMapMatcher() {
    }

    public static SiftUltraMapMatcher getInstance() {
        if (instance == null) {
            synchronized (SiftUltraMapMatcher.class) {
                if (instance == null) instance = new SiftUltraMapMatcher();
            }
        }
        return instance;
    }

    @Override
    public boolean init(String mapPath) {
        if (initialized) return true;
        synchronized (this) {
            if (initialized) return true;
            File cacheFile = ResourceUtils.getExternalFile(mapPath + ".sift.ultra.feat");
            if (loadFromCache(cacheFile.getAbsolutePath())) {
                log.info("🚀 载入缓存成功: SIFT-ULTRA (PCA+Quant)");
                initialized = true;
                return true;
            }
            return trainAndSave(mapPath, cacheFile.getAbsolutePath());
        }
    }

    @Override
    public double[][] match(byte[] grayData, int width, int height) {
        if (!initialized || grayData == null) return null;

        prepareSceneMat(width, height);
        sceneRawPixelBuffer.clear();
        sceneRawPixelBuffer.put(grayData);

        int pushResult = JNIFrameNative.push(65535);
        if (pushResult != 0) {
            log.warn("JNI PushLocalFrame failed, code={}", pushResult);
        }

        DescriptorMatcher currentMatcher = this.activeMatcher;
        List<MatOfDMatch> rawMatches = new ArrayList<>();

        try {
            sceneKeyPoints.release();
            sceneDescriptors.release();

            sift.detectAndCompute(sceneImg, emptyMask, sceneKeyPoints, sceneDescriptors);
            if (sceneDescriptors.empty()) return null;

            sceneDescriptors.convertTo(sceneDescriptorsFloat, CvType.CV_32F);
            projectDescriptors(sceneDescriptorsFloat, reducedSceneDescriptors);

            // 合并减法与缩放，避免单独的 Core.subtract 调用
            reducedSceneDescriptors.convertTo(quantized8U, CvType.CV_8U, qScale, -qMin * qScale);
            quantized8U.convertTo(queryFloat, CvType.CV_32F);

            currentMatcher.knnMatch(queryFloat, rawMatches, 2);

            List<DMatch> goodMatches = new ArrayList<>(128);
            float ratio = AppConfig.MATCH_RATIO_THRESHOLD;
            for (MatOfDMatch m : rawMatches) {
                DMatch[] dms = m.toArray();
                if (dms.length >= 2 && dms[0].distance < ratio * dms[1].distance) {
                    goodMatches.add(dms[0]);
                }
            }

            if (goodMatches.size() >= AppConfig.MATCH_MIN_COUNT) {
                return executeRansac(goodMatches, width, height);
            }
        } catch (Exception e) {
            log.error("匹配异常", e);
        } finally {
            JNIFrameNative.pop();

            // 释放本帧 MatOfDMatch
            for (MatOfDMatch m : rawMatches) {
                if (m != null) m.release();
            }

            if (System.currentTimeMillis() - lastGcTime > 30_000) {
                System.gc();
                lastGcTime = System.currentTimeMillis();
            }
            if (++matchCount % 300 == 0) {
                asyncRebuildMatcher();
            }
        }
        return null;
    }

    private void projectDescriptors(Mat src, Mat dst) {
        SiftUtils.projectDescriptors(src, pcaEigenvectors, projectedMean, repeatMean, emptyMat, dst);
    }

    private double[][] executeRansac(List<DMatch> goodMatches, int w, int h) {
        int count = goodMatches.size();
        float[] srcData = new float[count * 2];
        float[] dstData = new float[count * 2];

        // 复用 sceneKpsData，避免每帧 new float[N*7]
        int totalScenePoints = sceneKeyPoints.rows();
        if (sceneKpsData.length < totalScenePoints * 7) {
            sceneKpsData = new float[totalScenePoints * 7];
        }
        sceneKeyPoints.get(0, 0, sceneKpsData);

        FloatBuffer mapFb = mapKeyPointsDirectBuffer.asFloatBuffer();
        for (int i = 0; i < count; i++) {
            DMatch dm = goodMatches.get(i);
            int trainIdx = dm.trainIdx;
            if (trainIdx < 0 || trainIdx >= mapPointsCount) {
                continue;
            }
            int qOff = dm.queryIdx * 7;
            srcData[i * 2] = sceneKpsData[qOff];
            srcData[i * 2 + 1] = sceneKpsData[qOff + 1];
            int tOff = trainIdx * 2;
            dstData[i * 2] = mapFb.get(tOff);
            dstData[i * 2 + 1] = mapFb.get(tOff + 1);
        }

        // 只在点数变化时重新分配 mSrc/mDst
        if (mSrc.rows() != count) {
            mSrc.release();
            mSrc.alloc(count);
            mDst.release();
            mDst.alloc(count);
        }
        mSrc.put(0, 0, srcData);
        mDst.put(0, 0, dstData);

        Mat H = null;
        try {
            H = Calib3d.findHomography(mSrc, mDst, Calib3d.RANSAC,
                    AppConfig.RANSAC_REPROJ_THRESHOLD, ransacMask,
                    AppConfig.RANSAC_MAX_ITERS, AppConfig.RANSAC_CONFIDENCE);

            if (!H.empty() && H.rows() == 3) {
                sCenter.put(0, 0, new float[]{(float) (w >> 1), (float) (h >> 1)});
                Core.perspectiveTransform(sCenter, dCenter, H);
                float[] res = new float[2];
                dCenter.get(0, 0, res);
                return new double[][]{{(double) res[0], (double) res[1]}};
            }
        } catch (Exception ex) {
            log.error("RANSAC 异常", ex);
        } finally {
            if (H != null) H.release();
        }
        return null;
    }

    private void prepareSceneMat(int w, int h) {
        if (w != currentWidth || h != currentHeight) {
            if (sceneImg != null) {
                sceneImg.release();
            }
            NativeCleaner.freeDirectBuffer(sceneRawPixelBuffer);
            sceneRawPixelBuffer = null;
            System.gc();

            this.currentWidth = w;
            this.currentHeight = h;
            this.sceneRawPixelBuffer = ByteBuffer.allocateDirect(w * h).order(ByteOrder.nativeOrder());
            this.sceneImg = new Mat(h, w, CvType.CV_8UC1, sceneRawPixelBuffer);
        }
    }

    private synchronized void asyncRebuildMatcher() {
        final DescriptorMatcher oldMatcher = this.activeMatcher;
        scheduler.execute(() -> {
            try {
                DescriptorMatcher newMatcher = DescriptorMatcher.create(DescriptorMatcher.FLANNBASED);
                Mat tempFloat = new Mat();
                mapDescriptors.convertTo(tempFloat, CvType.CV_32F);
                newMatcher.add(Collections.singletonList(tempFloat));
                newMatcher.train();
                tempFloat.release();

                this.activeMatcher = newMatcher;
                log.debug("异步重建匹配器完成");

                if (oldMatcher != null) {
                    scheduler.schedule(() -> {
                        oldMatcher.clear();
                        System.gc();
                    }, 1, TimeUnit.SECONDS);
                }
            } catch (Exception e) {
                log.error("异步重建匹配器失败", e);
            }
        });
    }

    // ================== 训练与缓存 ==================
    private boolean trainAndSave(String mapPath, String cachePath) {
        Mat mapGray = new Mat();
        MatOfKeyPoint kps = new MatOfKeyPoint();
        Mat rawDescriptors = new Mat();
        Mat fullEigenvectors = new Mat();
        Mat rawMean = new Mat();
        Mat floatProj = null;
        Mat mapColor = null;
        try (InputStream is = ResourceUtils.getResourceStream(mapPath)) {
            byte[] bytes = is.readAllBytes();
            MatOfByte mob = new MatOfByte(bytes);
            mapColor = Imgcodecs.imdecode(mob, Imgcodecs.IMREAD_UNCHANGED);
            mob.release();
            Imgproc.cvtColor(mapColor, mapGray, Imgproc.COLOR_BGR2GRAY);
            sift.detectAndCompute(mapGray, emptyMask, kps, rawDescriptors);
            if (rawDescriptors.type() != CvType.CV_32F) rawDescriptors.convertTo(rawDescriptors, CvType.CV_32F);

            Core.PCACompute(rawDescriptors, rawMean, fullEigenvectors);
            int dim = Math.min(64, fullEigenvectors.rows());
            fullEigenvectors.rowRange(0, dim).copyTo(pcaEigenvectors);
            Core.gemm(rawMean, pcaEigenvectors, 1, emptyMat, 0, projectedMean, Core.GEMM_2_T);

            floatProj = new Mat();
            projectDescriptors(rawDescriptors, floatProj);
            Core.MinMaxLocResult mm = Core.minMaxLoc(floatProj);
            this.qMin = (float) mm.minVal;
            this.qScale = 255.0f / ((float) mm.maxVal - qMin + 1e-6f);

            Core.subtract(floatProj, new Scalar(qMin), floatProj);
            floatProj.convertTo(mapDescriptors, CvType.CV_8U, qScale);

            this.mapPointsCount = kps.rows();
            this.mapKeyPointsDirectBuffer = ByteBuffer.allocateDirect(mapPointsCount * 2 * 4)
                    .order(ByteOrder.nativeOrder());
            KeyPoint[] kpsArr = kps.toArray();
            for (int i = 0; i < mapPointsCount; i++) {
                mapKeyPointsDirectBuffer.putFloat(i * 8, (float) kpsArr[i].pt.x);
                mapKeyPointsDirectBuffer.putFloat(i * 8 + 4, (float) kpsArr[i].pt.y);
            }
            saveToCache(cachePath);
            initMatcher();
            initialized = true;
            return true;
        } catch (Exception e) {
            log.error("训练异常", e);
            return false;
        } finally {
            if (floatProj != null) floatProj.release();
            if (mapColor != null) mapColor.release();
            rawDescriptors.release();
            fullEigenvectors.release();
            rawMean.release();
            mapGray.release();
            kps.release();
        }
    }

    private void initMatcher() {
        Mat tempFloat = new Mat();
        mapDescriptors.convertTo(tempFloat, CvType.CV_32F);
        DescriptorMatcher newMatcher = DescriptorMatcher.create(DescriptorMatcher.FLANNBASED);
        newMatcher.add(Collections.singletonList(tempFloat));
        newMatcher.train();
        tempFloat.release();
        // 清理旧的空匹配器（字段初始化时创建的无数据匹配器）
        DescriptorMatcher old = this.activeMatcher;
        this.activeMatcher = newMatcher;
        if (old != null) old.clear();
    }

    private void saveToCache(String path) {
        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(path))) {
            writeMat(dos, pcaEigenvectors);
            writeMat(dos, projectedMean);
            writeMat(dos, mapDescriptors);
            dos.writeFloat(qMin);
            dos.writeFloat(qScale);
            dos.writeInt(mapPointsCount);
            for (int i = 0; i < mapPointsCount * 2; i++) dos.writeFloat(mapKeyPointsDirectBuffer.getFloat(i * 4));
        } catch (Exception e) {
            log.error("存储失败", e);
        }
    }

    private boolean loadFromCache(String path) {
        File f = new File(path);
        if (!f.exists()) return false;
        try (DataInputStream dis = new DataInputStream(new FileInputStream(f))) {
            Mat m1 = readMat(dis);
            m1.copyTo(pcaEigenvectors);
            m1.release();
            Mat m2 = readMat(dis);
            m2.copyTo(projectedMean);
            m2.release();
            Mat m3 = readMat(dis);
            m3.copyTo(mapDescriptors);
            m3.release();
            this.qMin = dis.readFloat();
            this.qScale = dis.readFloat();
            this.mapPointsCount = dis.readInt();
            this.mapKeyPointsDirectBuffer = ByteBuffer.allocateDirect(mapPointsCount * 2 * 4)
                    .order(ByteOrder.nativeOrder());
            for (int i = 0; i < mapPointsCount * 2; i++) mapKeyPointsDirectBuffer.putFloat(i * 4, dis.readFloat());
            initMatcher();
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
        if (t == CvType.CV_32F) {
            float[] f = new float[(int) (m.total() * m.channels())];
            m.get(0, 0, f);
            data = new byte[f.length * 4];
            ByteBuffer.wrap(data).order(ByteOrder.nativeOrder()).asFloatBuffer().put(f);
        } else {
            data = new byte[(int) (m.total() * m.channels())];
            m.get(0, 0, data);
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
        if (t == CvType.CV_32F) {
            float[] f = new float[rLen / 4];
            ByteBuffer.wrap(rData).order(ByteOrder.nativeOrder()).asFloatBuffer().get(f);
            m.put(0, 0, f);
        } else {
            m.put(0, 0, rData);
        }
        return m;
    }

    @Override
    public void destroy() {
        pcaEigenvectors.release();
        projectedMean.release();
        mapDescriptors.release();
        if (sceneImg != null) sceneImg.release();
        sceneKeyPoints.release();
        sceneDescriptors.release();
        sceneDescriptorsFloat.release();
        reducedSceneDescriptors.release();
        emptyMask.release();
        emptyMat.release();
        mSrc.release();
        mDst.release();
        sCenter.release();
        dCenter.release();
        quantized8U.release();
        queryFloat.release();
        repeatMean.release();
        ransacMask.release();
        if (activeMatcher != null) {
            activeMatcher.clear();
        }
        NativeCleaner.freeDirectBuffer(mapKeyPointsDirectBuffer);
        NativeCleaner.freeDirectBuffer(sceneRawPixelBuffer);
        scheduler.shutdown();
        initialized = false;
    }
}