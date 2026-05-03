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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 洛克地图匹配器 - 高性能稳定版 (JNI 帧管理 + 异步重建 + 零泄漏终版)
 * 专为 GraalVM 25 Native Image + Serial GC 设计
 * 优化点：零分配单应性变换、严谨的 Native 资源回收、后台无阻塞重建
 */
@Slf4j
public class SiftMapMatcher implements MapMatcher {

    private static volatile SiftMapMatcher instance;

    private final SIFT sift = SIFT.create(
            AppConfig.SIFT_N_FEATURES,
            AppConfig.SIFT_N_OCTAVE_LAYERS,
            AppConfig.SIFT_CONTRAST_THRESHOLD,
            AppConfig.SIFT_EDGE_THRESHOLD,
            AppConfig.SIFT_SIGMA);

    // 异步重建调度器
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "matcher-rebuilder");
        t.setDaemon(true);
        return t;
    });
    // --- 静态资源复用池 ---
    private final Mat mapDescriptors = new Mat();
    private final Mat emptyMat = new Mat();
    private final MatOfKeyPoint sceneKeyPoints = new MatOfKeyPoint();
    private final Mat sceneDescriptors = new Mat();
    private final Mat emptyMask = new Mat();
    private final MatOfPoint2f srcPointsMat = new MatOfPoint2f();
    private final MatOfPoint2f dstPointsMat = new MatOfPoint2f();
    private final Mat srcPointTemp = new Mat(1, 1, CvType.CV_32FC2);
    private final Mat ransacMask = new Mat();
    private final Mat dstPointTemp = new Mat(1, 1, CvType.CV_32FC2);
    // 活跃匹配器，volatile 保证异步重建时 match 线程可见
    private volatile DescriptorMatcher activeMatcher = DescriptorMatcher.create(DescriptorMatcher.FLANNBASED);
    private ByteBuffer mapKeyPointsBuffer;
    private int mapPointsCount = 0;
    private int matchCount = 0;
    private long lastGcTime = 0;

    // 图像缓冲区（必须 DirectByteBuffer）
    private ByteBuffer scenePixelBuf;
    private Mat sceneImg;
    private float[] sceneKpsData = new float[0];
    private int lastW = -1, lastH = -1;
    private volatile boolean initialized = false;

    private SiftMapMatcher() {
    }

    public static SiftMapMatcher getInstance() {
        if (instance == null) {
            synchronized (SiftMapMatcher.class) {
                if (instance == null) instance = new SiftMapMatcher();
            }
        }
        return instance;
    }

    @Override
    public boolean init(String mapPath) {
        if (initialized) return true;
        synchronized (this) {
            if (initialized) return true;
            String cachePath = mapPath + ".v2.feat";
            File cacheFile = ResourceUtils.getExternalFile(cachePath);
            if (loadFromCache(cacheFile.getAbsolutePath())) {
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

        ensureSceneReady(width, height);
        scenePixelBuf.clear();
        scenePixelBuf.put(grayData);

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

            currentMatcher.knnMatch(sceneDescriptors, rawMatches, 2);

            List<DMatch> goodMatches = new ArrayList<>(128);
            float ratio = AppConfig.MATCH_RATIO_THRESHOLD;

            for (MatOfDMatch m : rawMatches) {
                DMatch[] dms = m.toArray();
                if (dms.length >= 2 && dms[0].distance < ratio * dms[1].distance) {
                    goodMatches.add(dms[0]);
                }
            }

            if (goodMatches.size() >= AppConfig.MATCH_MIN_COUNT) {
                return solveLocation(goodMatches, width, height);
            }
        } catch (Exception e) {
            log.error("Match Execution Error", e);
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

    private double[][] solveLocation(List<DMatch> goodMatches, int w, int h) {
        int count = goodMatches.size();
        if (srcPointsMat.rows() != count) {
            srcPointsMat.release();
            srcPointsMat.alloc(count);
            dstPointsMat.release();
            dstPointsMat.alloc(count);
        }

        int sceneSize = sceneKeyPoints.rows();
        if (sceneKpsData.length < sceneSize * 7) {
            sceneKpsData = new float[sceneSize * 7];
        }
        sceneKeyPoints.get(0, 0, sceneKpsData);

        float[] srcArr = new float[count * 2];
        float[] dstArr = new float[count * 2];

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
            dstArr[i * 2] = mapKeyPointsBuffer.getFloat(trainIdx * 8);
            dstArr[i * 2 + 1] = mapKeyPointsBuffer.getFloat(trainIdx * 8 + 4);
        }

        srcPointsMat.put(0, 0, srcArr);
        dstPointsMat.put(0, 0, dstArr);

        Mat H = Calib3d.findHomography(srcPointsMat, dstPointsMat, Calib3d.RANSAC,
                AppConfig.RANSAC_REPROJ_THRESHOLD, ransacMask,
                AppConfig.RANSAC_MAX_ITERS, AppConfig.RANSAC_CONFIDENCE);

        if (!H.empty()) {
            if (H.rows() == 3) {
                // 零分配点转换逻辑
                srcPointTemp.put(0, 0, (float) (w >> 1), (float) (h >> 1));

                // 确保 H 为 CV_32F 以匹配 srcPointTemp 深度
                if (H.type() != CvType.CV_32F) {
                    H.convertTo(H, CvType.CV_32F);
                }

                Core.perspectiveTransform(srcPointTemp, dstPointTemp, H);

                float[] res = new float[2];
                dstPointTemp.get(0, 0, res);
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
     * 同时主动清空旧 DirectBuffer 引用，帮助 GC 回收其 native 内存。
     */
    private void ensureSceneReady(int w, int h) {
        if (w != lastW || h != lastH) {
            if (sceneImg != null) {
                sceneImg.release();
            }
            // 显式释放旧 DirectByteBuffer 的 native 内存
            NativeCleaner.freeDirectBuffer(scenePixelBuf);
            // 丢弃旧引用，帮助 GC 回收
            scenePixelBuf = null;
            System.gc();

            this.lastW = w;
            this.lastH = h;
            this.scenePixelBuf = ByteBuffer.allocateDirect(w * h).order(ByteOrder.nativeOrder());
            this.sceneImg = new Mat(h, w, CvType.CV_8UC1, scenePixelBuf);
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
                log.debug("异步重建匹配器完成");

                // 延迟 1 秒释放旧匹配器（确保 match() 已完成，但不过久占用内存）
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

    private boolean trainAndSave(String mapPath, String cachePath) {
        Mat mapGray = new Mat();
        MatOfKeyPoint kps = new MatOfKeyPoint();
        Mat rawDesc = new Mat();
        Mat color = null;

        try (InputStream is = ResourceUtils.getResourceStream(mapPath)) {
            byte[] bytes = is.readAllBytes();
            MatOfByte mob = new MatOfByte(bytes);
            color = Imgcodecs.imdecode(mob, Imgcodecs.IMREAD_UNCHANGED);
            mob.release();
            Imgproc.cvtColor(color, mapGray, Imgproc.COLOR_BGR2GRAY);

            sift.detectAndCompute(mapGray, emptyMask, kps, rawDesc);
            rawDesc.copyTo(mapDescriptors);

            KeyPoint[] kpsArr = kps.toArray();
            this.mapPointsCount = kpsArr.length;
            this.mapKeyPointsBuffer = ByteBuffer.allocateDirect(mapPointsCount * 2 * 4)
                    .order(ByteOrder.nativeOrder());

            for (int i = 0; i < mapPointsCount; i++) {
                mapKeyPointsBuffer.putFloat(i * 8, (float) kpsArr[i].pt.x);
                mapKeyPointsBuffer.putFloat(i * 8 + 4, (float) kpsArr[i].pt.y);
            }

            saveToCache(cachePath);
            initMatcher();
            initialized = true;
            return true;
        } catch (Exception e) {
            log.error("SIFT Training Failed", e);
            return false;
        } finally {
            if (color != null) color.release();
            mapGray.release();
            kps.release();
            rawDesc.release();
        }
    }

    @Override
    public void destroy() {
        mapDescriptors.release();
        sceneKeyPoints.release();
        sceneDescriptors.release();
        emptyMask.release();
        emptyMat.release();
        srcPointsMat.release();
        dstPointsMat.release();
        ransacMask.release();
        srcPointTemp.release();
        dstPointTemp.release();
        if (sceneImg != null) sceneImg.release();
        if (activeMatcher != null) activeMatcher.clear();
        NativeCleaner.freeDirectBuffer(mapKeyPointsBuffer);
        NativeCleaner.freeDirectBuffer(scenePixelBuf);
        scheduler.shutdown();
        initialized = false;
    }

    // --- 序列化优化 (Zstd 压缩) ---

    private void saveToCache(String path) throws IOException {
        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(path))) {
            writeMatToStream(dos, mapDescriptors);
            dos.writeInt(mapPointsCount);
            for (int i = 0; i < mapPointsCount * 2; i++) {
                dos.writeFloat(mapKeyPointsBuffer.getFloat(i * 4));
            }
        }
    }

    private boolean loadFromCache(String path) {
        File f = new File(path);
        if (!f.exists()) return false;
        try (DataInputStream dis = new DataInputStream(new FileInputStream(f))) {
            Mat loaded = readMatFromStream(dis);
            loaded.copyTo(mapDescriptors);
            loaded.release();
            this.mapPointsCount = dis.readInt();
            this.mapKeyPointsBuffer = ByteBuffer.allocateDirect(mapPointsCount * 2 * 4)
                    .order(ByteOrder.nativeOrder());
            for (int i = 0; i < mapPointsCount * 2; i++) {
                mapKeyPointsBuffer.putFloat(i * 4, dis.readFloat());
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void writeMatToStream(DataOutputStream dos, Mat m) throws IOException {
        dos.writeInt(m.rows());
        dos.writeInt(m.cols());
        dos.writeInt(m.type());
        float[] data = new float[(int) m.total() * m.channels()];
        m.get(0, 0, data);
        byte[] b = new byte[data.length * 4];
        ByteBuffer.wrap(b).order(ByteOrder.nativeOrder()).asFloatBuffer().put(data);
        byte[] comp = Zstd.compress(b);
        dos.writeInt(comp.length);
        dos.writeInt(b.length);
        dos.write(comp);
    }

    private Mat readMatFromStream(DataInputStream dis) throws IOException {
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
}