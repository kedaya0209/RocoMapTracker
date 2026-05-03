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
 * SIFT + PCA-64 + 量化优化版 (JNI 帧管理 + 异步重建 + 零泄漏终版)
 * 专为 GraalVM 25 Native Image + Serial GC 深度适配
 * 彻底杜绝 JNI 局部引用、DirectBuffer、Flann 索引泄漏，匹配流程零阻塞
 */
@Slf4j
public class SiftPCAUltraMapMatcher implements MapMatcher {

    private static volatile SiftPCAUltraMapMatcher instance;

    private final SIFT sift = SIFT.create(
            AppConfig.SIFT_N_FEATURES,
            AppConfig.SIFT_N_OCTAVE_LAYERS,
            AppConfig.SIFT_CONTRAST_THRESHOLD,
            AppConfig.SIFT_EDGE_THRESHOLD,
            AppConfig.SIFT_SIGMA);

    // 异步重建专用调度器
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "pca-matcher-rebuilder");
        t.setDaemon(true);
        return t;
    });
    // --- 核心成员变量池：训练阶段固化，match 阶段只复用不新建 ---
    private final Mat pcaEigenvectors = new Mat();
    private final Mat sceneDescFloat = new Mat();       // sceneDescriptors 转 32F 专用
    private final Mat projectedMean = new Mat();
    private final Mat mapDescriptors8U = new Mat();
    private final MatOfKeyPoint sceneKeyPoints = new MatOfKeyPoint();
    private final Mat sceneDescriptors = new Mat();
    private final Mat sceneProjected = new Mat();       // 投影结果
    private final Mat scene8U = new Mat();              // 量化 8U
    private final Mat sceneQuery32F = new Mat();        // 查询用 32F（量化后）
    private final Mat repeatedMean = new Mat();         // 投影时均值扩展
    private final Mat emptyMat = new Mat();
    private final Mat emptyMask = new Mat();
    private final Mat srcCenter = new Mat(1, 1, CvType.CV_32FC2);
    private final Mat dstCenter = new Mat(1, 1, CvType.CV_32FC2);
    private final MatOfPoint2f srcPoints = new MatOfPoint2f();
    private final MatOfPoint2f dstPoints = new MatOfPoint2f();
    private final Mat ransacMask = new Mat();
    private final int PCA_DIM = 64;
    // 活跃匹配器，volatile 保证多线程可见性，异步重建时不阻塞
    private volatile DescriptorMatcher activeMatcher = DescriptorMatcher.create(DescriptorMatcher.FLANNBASED);
    private float qMin = 0f;
    private float qScale = 1f;
    // 图像缓冲区（必须 DirectByteBuffer，OpenCV Mat 依赖）
    private ByteBuffer sceneRawPixelBuffer;
    private Mat sceneImg;
    private float[] sceneKpsData = new float[0];
    private int currentWidth = -1;
    private int currentHeight = -1;
    private int matchCount = 0;
    private long lastGcTime = 0;

    // 地图点数据
    private ByteBuffer mapKeyPointsDirectBuffer;
    private int mapPointsCount = 0;
    private volatile boolean initialized = false;

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
                log.info("🚀 SIFT-PCA 缓存载入成功");
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

        prepareSceneMat(width, height);
        sceneRawPixelBuffer.clear();
        sceneRawPixelBuffer.put(grayData);

        // JNI 局部帧管理，上限 65535（JVM 允许的最大值）
        int pushResult = JNIFrameNative.push(65535);
        if (pushResult != 0) {
            log.warn("JNI PushLocalFrame failed, code={}", pushResult);
        }

        // 获取当前活跃匹配器快照，防止异步重建时引用被替换
        DescriptorMatcher currentMatcher = this.activeMatcher;
        List<MatOfDMatch> rawMatches = new ArrayList<>();

        try {
            // 清除上一帧的特征点/描述子 native 数据
            sceneKeyPoints.release();
            sceneDescriptors.release();

            sift.detectAndCompute(sceneImg, emptyMask, sceneKeyPoints, sceneDescriptors);
            if (sceneDescriptors.empty()) return null;

            // 确保描述子为 CV_32F，复用 sceneDescFloat
            sceneDescriptors.convertTo(sceneDescFloat, CvType.CV_32F);
            // 投影到 PCA 空间
            projectDescriptors(sceneDescFloat, sceneProjected);

            // 合并减法与缩放（利用 convertTo 的 alpha, beta）
            sceneProjected.convertTo(scene8U, CvType.CV_8U, qScale, -qMin * qScale);
            scene8U.convertTo(sceneQuery32F, CvType.CV_32F);

            currentMatcher.knnMatch(sceneQuery32F, rawMatches, 2);

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
            // 释放整个 JNI 局部帧，清除所有局部引用
            JNIFrameNative.pop();

            // 显式释放本帧所有 MatOfDMatch 的 native 资源
            for (MatOfDMatch m : rawMatches) {
                if (m != null) m.release();
            }

            // 每 30 秒触发一次 GC，辅助回收 native 内存
            if (System.currentTimeMillis() - lastGcTime > 30_000) {
                System.gc();
                lastGcTime = System.currentTimeMillis();
            }
            // 每 300 帧异步重建匹配器（无阻塞）
            if (++matchCount % 300 == 0) {
                asyncRebuildMatcher();
            }
        }
        return null;
    }

    /**
     * PCA 投影：src 必须为 CV_32F，结果存入 dst
     */
    private void projectDescriptors(Mat src, Mat dst) {
        SiftUtils.projectDescriptors(src, pcaEigenvectors, projectedMean, repeatedMean, emptyMat, dst);
    }

    private double[][] executeRansac(List<DMatch> goodMatches, int w, int h) {
        int count = goodMatches.size();
        if (srcPoints.rows() != count) {
            srcPoints.release();
            srcPoints.alloc(count);
            dstPoints.release();
            dstPoints.alloc(count);
        }

        int totalScenePoints = sceneKeyPoints.rows();
        if (sceneKpsData.length < totalScenePoints * 7) {
            sceneKpsData = new float[totalScenePoints * 7];
        }
        sceneKeyPoints.get(0, 0, sceneKpsData);

        float[] srcArr = new float[count * 2];
        float[] dstArr = new float[count * 2];
        FloatBuffer mapFb = mapKeyPointsDirectBuffer.asFloatBuffer();

        for (int i = 0; i < count; i++) {
            DMatch dm = goodMatches.get(i);
            int trainIdx = dm.trainIdx;
            // 安全检查：防止异常索引导致崩溃
            if (trainIdx < 0 || trainIdx >= mapPointsCount) {
                log.warn("Invalid trainIdx: {} (mapPoints: {}), skipping.", trainIdx, mapPointsCount);
                continue;
            }
            srcArr[i * 2] = sceneKpsData[dm.queryIdx * 7];
            srcArr[i * 2 + 1] = sceneKpsData[dm.queryIdx * 7 + 1];
            dstArr[i * 2] = mapFb.get(trainIdx * 2);
            dstArr[i * 2 + 1] = mapFb.get(trainIdx * 2 + 1);
        }

        srcPoints.put(0, 0, srcArr);
        dstPoints.put(0, 0, dstArr);

        Mat H = Calib3d.findHomography(srcPoints, dstPoints, Calib3d.RANSAC,
                AppConfig.RANSAC_REPROJ_THRESHOLD, ransacMask,
                AppConfig.RANSAC_MAX_ITERS, AppConfig.RANSAC_CONFIDENCE);

        if (!H.empty()) {
            srcCenter.put(0, 0, (float) w / 2, (float) h / 2);
            Core.perspectiveTransform(srcCenter, dstCenter, H);
            float[] res = new float[2];
            dstCenter.get(0, 0, res);
            H.release();
            return new double[][]{{(double) res[0], (double) res[1]}};
        }
        return null;
    }

    /**
     * 准备场景图像 Mat，分辨率变化时释放旧缓冲并新建。
     * 必须使用 DirectByteBuffer，否则 OpenCV 构造 Mat 会失败。
     */
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

    /**
     * 同步初始化匹配器（训练或缓存加载后调用）
     */
    private void initMatcher() {
        Mat tempFloat = new Mat();
        mapDescriptors8U.convertTo(tempFloat, CvType.CV_32F);
        DescriptorMatcher newMatcher = DescriptorMatcher.create(DescriptorMatcher.FLANNBASED);
        newMatcher.add(Collections.singletonList(tempFloat));
        newMatcher.train();
        tempFloat.release();
        DescriptorMatcher old = this.activeMatcher;
        this.activeMatcher = newMatcher;
        if (old != null) old.clear();
    }

    /**
     * 异步重建匹配器：后台训练新匹配器，完成后原子替换，旧匹配器延迟清理。
     * 全程不阻塞 match 线程。
     */
    private synchronized void asyncRebuildMatcher() {
        final DescriptorMatcher oldMatcher = this.activeMatcher;
        scheduler.execute(() -> {
            try {
                DescriptorMatcher newMatcher = DescriptorMatcher.create(DescriptorMatcher.FLANNBASED);
                Mat tempFloat = new Mat();
                mapDescriptors8U.convertTo(tempFloat, CvType.CV_32F);
                newMatcher.add(Collections.singletonList(tempFloat));
                newMatcher.train();
                tempFloat.release();

                this.activeMatcher = newMatcher;
                log.debug("异步重建 PCA 匹配器完成");

                if (oldMatcher != null) {
                    scheduler.schedule(() -> {
                        oldMatcher.clear();
                        System.gc();
                    }, 1, TimeUnit.SECONDS);
                }
            } catch (Exception e) {
                log.error("异步重建 PCA 匹配器失败", e);
            }
        });
    }

    // ================== 训练与缓存 ==================
    private boolean trainAndSave(String mapPath, String cachePath) {
        Mat mapGray = new Mat();
        MatOfKeyPoint kps = new MatOfKeyPoint();
        Mat rawDescriptors = new Mat();
        Mat rawMean = new Mat();
        Mat fullEigenvectors = new Mat();
        Mat projected = new Mat();
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
            int actualDim = Math.min(PCA_DIM, fullEigenvectors.rows());
            fullEigenvectors.rowRange(0, actualDim).copyTo(pcaEigenvectors);
            Core.gemm(rawMean, pcaEigenvectors, 1, emptyMat, 0, projectedMean, Core.GEMM_2_T);

            projectDescriptors(rawDescriptors, projected);
            Core.MinMaxLocResult mm = Core.minMaxLoc(projected);
            this.qMin = (float) mm.minVal;
            this.qScale = 255.0f / ((float) mm.maxVal - qMin + 1e-6f);
            projected.convertTo(mapDescriptors8U, CvType.CV_8U, qScale, -qMin * qScale);

            KeyPoint[] kpsArray = kps.toArray();
            this.mapPointsCount = kpsArray.length;
            this.mapKeyPointsDirectBuffer = ByteBuffer.allocateDirect(mapPointsCount * 2 * 4).order(ByteOrder.nativeOrder());
            FloatBuffer fb = mapKeyPointsDirectBuffer.asFloatBuffer();
            for (int i = 0; i < mapPointsCount; i++) {
                fb.put(i * 2, (float) kpsArray[i].pt.x);
                fb.put(i * 2 + 1, (float) kpsArray[i].pt.y);
            }
            saveToCache(cachePath);
            initMatcher();
            initialized = true;
            return true;
        } catch (Exception e) {
            log.error("训练失败", e);
            return false;
        } finally {
            if (mapColor != null) mapColor.release();
            mapGray.release();
            kps.release();
            rawDescriptors.release();
            rawMean.release();
            fullEigenvectors.release();
            projected.release();
        }
    }

    private void saveToCache(String path) throws IOException {
        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(path))) {
            writeMat(dos, pcaEigenvectors);
            writeMat(dos, projectedMean);
            writeMat(dos, mapDescriptors8U);
            dos.writeFloat(qMin);
            dos.writeFloat(qScale);
            dos.writeInt(mapPointsCount);
            FloatBuffer fb = mapKeyPointsDirectBuffer.asFloatBuffer();
            for (int i = 0; i < mapPointsCount * 2; i++) dos.writeFloat(fb.get(i));
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
            m3.copyTo(mapDescriptors8U);
            m3.release();
            this.qMin = dis.readFloat();
            this.qScale = dis.readFloat();
            this.mapPointsCount = dis.readInt();
            this.mapKeyPointsDirectBuffer = ByteBuffer.allocateDirect(mapPointsCount * 2 * 4).order(ByteOrder.nativeOrder());
            FloatBuffer fb = mapKeyPointsDirectBuffer.asFloatBuffer();
            for (int i = 0; i < mapPointsCount * 2; i++) fb.put(i, dis.readFloat());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void writeMat(DataOutputStream dos, Mat m) throws IOException {
        dos.writeInt(m.rows());
        dos.writeInt(m.cols());
        dos.writeInt(m.type());
        byte[] data;
        if (m.type() == CvType.CV_32F) {
            float[] f = new float[(int) m.total() * m.channels()];
            m.get(0, 0, f);
            data = new byte[f.length * 4];
            ByteBuffer.wrap(data).order(ByteOrder.nativeOrder()).asFloatBuffer().put(f);
        } else {
            data = new byte[(int) m.total()];
            m.get(0, 0, data);
        }
        byte[] comp = Zstd.compress(data);
        dos.writeInt(comp.length);
        dos.writeInt(data.length);
        dos.write(comp);
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
        mapDescriptors8U.release();
        if (sceneImg != null) sceneImg.release();
        sceneKeyPoints.release();
        sceneDescriptors.release();
        sceneDescFloat.release();
        sceneProjected.release();
        scene8U.release();
        sceneQuery32F.release();
        repeatedMean.release();
        emptyMask.release();
        emptyMat.release();
        srcCenter.release();
        dstCenter.release();
        srcPoints.release();
        dstPoints.release();
        ransacMask.release();
        if (activeMatcher != null) activeMatcher.clear();
        NativeCleaner.freeDirectBuffer(mapKeyPointsDirectBuffer);
        NativeCleaner.freeDirectBuffer(sceneRawPixelBuffer);
        scheduler.shutdown();
        initialized = false;
    }
}