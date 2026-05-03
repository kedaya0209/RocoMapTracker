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
 * SIFT + PCA-64 深度优化版 (JNI 帧管理 + 异步重建 + 零泄漏终版)
 * 专为 GraalVM 25 Native Image + Serial GC 设计（5800H 环境）
 * 彻底杜绝 JNI 局部引用、DirectBuffer、Flann 索引泄漏，匹配流程零阻塞
 */
@Slf4j
public class SiftPCAMapMatcher implements MapMatcher {

    private static volatile SiftPCAMapMatcher instance;

    private final SIFT sift = SIFT.create(
            AppConfig.SIFT_N_FEATURES,
            AppConfig.SIFT_N_OCTAVE_LAYERS,
            AppConfig.SIFT_CONTRAST_THRESHOLD,
            AppConfig.SIFT_EDGE_THRESHOLD,
            AppConfig.SIFT_SIGMA);

    // 异步重建调度器
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "pca-matcher-rebuilder");
        t.setDaemon(true);
        return t;
    });
    // --- 核心成员变量池：训练阶段固化，match 阶段只复用不新建 ---
    private final Mat pcaEigenvectors = new Mat();
    private final Mat mapDescriptors = new Mat();          // 地图 PCA 描述子 (CV_32F)
    private final Mat projectedMean = new Mat();
    private final Mat sceneDescriptors = new Mat();       // 场景原始描述子 (CV_8U)
    private final int PCA_DIM = 64;

    private final MatOfKeyPoint sceneKeyPoints = new MatOfKeyPoint();
    private final Mat sceneDescFloat = new Mat();         // 场景描述子转 32F (复用)
    private final Mat sceneProjected = new Mat();         // 场景投影结果 (CV_32F)
    private final Mat repeatedMean = new Mat();
    private final Mat emptyMat = new Mat();
    private final Mat emptyMask = new Mat();
    private final MatOfPoint2f srcPoints = new MatOfPoint2f();
    private final MatOfPoint2f dstPoints = new MatOfPoint2f();
    private final Mat srcCenter = new Mat(1, 1, CvType.CV_32FC2);
    private final Mat ransacMask = new Mat();
    private final Mat dstCenter = new Mat(1, 1, CvType.CV_32FC2);
    // 活跃匹配器，volatile 保证异步重建时 match 线程可见
    private volatile DescriptorMatcher activeMatcher = DescriptorMatcher.create(DescriptorMatcher.FLANNBASED);
    private ByteBuffer mapKeyPointsDirectBuffer;
    private int mapPointsCount = 0;
    private int matchCount = 0;
    private long lastGcTime = 0;

    // 图像缓冲区（必须 DirectByteBuffer）
    private ByteBuffer sceneRawPixelBuffer;
    private Mat sceneImg;
    private float[] sceneKpsData = new float[0];
    private int currentWidth = -1;
    private int currentHeight = -1;
    private volatile boolean initialized = false;

    private SiftPCAMapMatcher() {
    }

    public static SiftPCAMapMatcher getInstance() {
        if (instance == null) {
            synchronized (SiftPCAMapMatcher.class) {
                if (instance == null) instance = new SiftPCAMapMatcher();
            }
        }
        return instance;
    }

    @Override
    public boolean init(String mapPath) {
        if (initialized) return true;
        synchronized (this) {
            if (initialized) return true;
            File cacheFile = ResourceUtils.getExternalFile(mapPath + ".pca64.feat");
            if (loadFromCache(cacheFile.getAbsolutePath())) {
                log.info("🚀 SIFT-PCA 缓存载入成功 (Native Image 优化版)");
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

        // 获取当前活跃匹配器快照，避免异步重建造成引用突变
        DescriptorMatcher currentMatcher = this.activeMatcher;
        List<MatOfDMatch> rawMatches = new ArrayList<>();

        try {
            // 清除上一帧的原生数据
            sceneKeyPoints.release();
            sceneDescriptors.release();

            sift.detectAndCompute(sceneImg, emptyMask, sceneKeyPoints, sceneDescriptors);
            if (sceneDescriptors.empty()) return null;

            // 确保描述子为 CV_32F 并进行 PCA 投影
            sceneDescriptors.convertTo(sceneDescFloat, CvType.CV_32F);
            projectDescriptors(sceneDescFloat, sceneProjected);

            currentMatcher.knnMatch(sceneProjected, rawMatches, 2);

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
            log.error("SIFT Match Error", e);
        } finally {
            // 释放整个 JNI 局部帧
            JNIFrameNative.pop();

            // 释放本帧所有 MatOfDMatch 的 native 内存
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
     * PCA 投影：输入 src 必须为 CV_32F，结果存入 dst
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
            int qOff = dm.queryIdx * 7;
            srcArr[i * 2] = sceneKpsData[qOff];
            srcArr[i * 2 + 1] = sceneKpsData[qOff + 1];
            dstArr[i * 2] = mapFb.get(trainIdx * 2);
            dstArr[i * 2 + 1] = mapFb.get(trainIdx * 2 + 1);
        }

        srcPoints.put(0, 0, srcArr);
        dstPoints.put(0, 0, dstArr);

        Mat H = Calib3d.findHomography(srcPoints, dstPoints, Calib3d.RANSAC,
                AppConfig.RANSAC_REPROJ_THRESHOLD, ransacMask,
                AppConfig.RANSAC_MAX_ITERS, AppConfig.RANSAC_CONFIDENCE);

        if (!H.empty()) {
            if (H.rows() == 3) {
                srcCenter.put(0, 0, (float) (w >> 1), (float) (h >> 1));
                Core.perspectiveTransform(srcCenter, dstCenter, H);
                float[] res = new float[2];
                dstCenter.get(0, 0, res);
                H.release();
                return new double[][]{{(double) res[0], (double) res[1]}};
            }
            H.release();
        }
        return null;
    }

    /**
     * 准备场景图像 Mat：分辨率变化时释放旧缓冲并新建。
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
        DescriptorMatcher newMatcher = DescriptorMatcher.create(DescriptorMatcher.FLANNBASED);
        newMatcher.add(Collections.singletonList(mapDescriptors));
        newMatcher.train();
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
                newMatcher.add(Collections.singletonList(mapDescriptors));
                newMatcher.train();

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

            // 投影到 PCA 空间并保存为 mapDescriptors
            projectDescriptors(rawDescriptors, mapDescriptors);

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
            log.error("Training failed", e);
            return false;
        } finally {
            if (mapColor != null) mapColor.release();
            mapGray.release();
            kps.release();
            rawDescriptors.release();
            rawMean.release();
            fullEigenvectors.release();
        }
    }

    private void saveToCache(String path) throws IOException {
        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(path))) {
            writeMat(dos, pcaEigenvectors);
            writeMat(dos, projectedMean);
            writeMat(dos, mapDescriptors);
            dos.writeInt(mapPointsCount);
            for (int i = 0; i < mapPointsCount * 2; i++) dos.writeFloat(mapKeyPointsDirectBuffer.getFloat(i * 4));
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
            this.mapPointsCount = dis.readInt();
            this.mapKeyPointsDirectBuffer = ByteBuffer.allocateDirect(mapPointsCount * 2 * 4)
                    .order(ByteOrder.nativeOrder());
            for (int i = 0; i < mapPointsCount * 2; i++) mapKeyPointsDirectBuffer.putFloat(i * 4, dis.readFloat());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void writeMat(DataOutputStream dos, Mat m) throws IOException {
        dos.writeInt(m.rows());
        dos.writeInt(m.cols());
        dos.writeInt(m.type());
        float[] data = new float[(int) m.total() * m.channels()];
        m.get(0, 0, data);
        byte[] bytes = new byte[data.length * 4];
        ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).asFloatBuffer().put(data);
        byte[] comp = Zstd.compress(bytes);
        dos.writeInt(comp.length);
        dos.writeInt(bytes.length);
        dos.write(comp);
    }

    private Mat readMat(DataInputStream dis) throws IOException {
        int r = dis.readInt(), c = dis.readInt(), t = dis.readInt();
        int cLen = dis.readInt(), rLen = dis.readInt();
        byte[] cData = new byte[cLen];
        dis.readFully(cData);
        byte[] rData = Zstd.decompress(cData, rLen);
        float[] f = new float[rLen / 4];
        ByteBuffer.wrap(rData).order(ByteOrder.nativeOrder()).asFloatBuffer().get(f);
        Mat m = new Mat(r, c, t);
        m.put(0, 0, f);
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
        sceneDescFloat.release();
        sceneProjected.release();
        repeatedMean.release();
        emptyMask.release();
        emptyMat.release();
        srcPoints.release();
        dstPoints.release();
        ransacMask.release();
        srcCenter.release();
        dstCenter.release();
        if (activeMatcher != null) activeMatcher.clear();
        NativeCleaner.freeDirectBuffer(mapKeyPointsDirectBuffer);
        NativeCleaner.freeDirectBuffer(sceneRawPixelBuffer);
        scheduler.shutdown();
        initialized = false;
    }
}