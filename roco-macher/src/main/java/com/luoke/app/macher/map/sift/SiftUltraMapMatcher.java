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
 * SIFT + 8-bit 量化匹配器 (无 PCA，JavaCPP 版本)
 * nopointergc=true: 显式内存管理，零 GC 追踪开销
 * KDTreeIndexParams(1): 单树 + checks=24，适配 33W 特征点
 */
@Slf4j
public class SiftUltraMapMatcher implements MapMatcher {

    private static volatile SiftUltraMapMatcher instance;

    private final SIFT sift = SIFT.create(
            AppConfig.SIFT_N_FEATURES,
            AppConfig.SIFT_N_OCTAVE_LAYERS,
            AppConfig.SIFT_CONTRAST_THRESHOLD,
            AppConfig.SIFT_EDGE_THRESHOLD,
            AppConfig.SIFT_SIGMA,
            false);

    // 异步重建专用调度器
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "matcher-rebuilder");
        t.setDaemon(true);
        return t;
    });

    // --- 持久化 Mat (不在 PointerScope 中创建，destroy 时手动关闭) ---
    private final Mat mapDescriptors8U = new Mat();
    private final Mat emptyMask = new Mat();
    private final Mat emptyMat = new Mat();
    private final Mat srcCenter = new Mat(1, 1, opencv_core.CV_32FC2);
    private final Mat dstCenter = new Mat(1, 1, opencv_core.CV_32FC2);
    private final Mat ransacMask = new Mat();

    // 活跃匹配器 (volatile 保证异步重建可见性)
    private volatile DescriptorMatcher activeMatcher;

    // 地图数据
    private ByteBuffer mapKeyPointsDirectBuffer;
    private int mapPointsCount;

    // 量化参数
    private float qMin;
    private float qScale;

    // 场景图像 (Mat 自管理内存, 不包裹外部 ByteBuffer, 避免 double-free)
    private Mat sceneImg;
    private int currentWidth = -1, currentHeight = -1;

    // RANSAC 缓冲区复用
    private float[] srcBuf = new float[0];
    private float[] dstBuf = new float[0];

    private volatile boolean initialized;
    private int matchCount;

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
                log.info("SIFT-ULTRA (JavaCPP) 缓存载入成功");
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

        // PointerScope 只管理本帧临时对象 (sceneKeyPoints, sceneDescriptors 等)
        // sceneImg/mapDescriptors 等字段级 Mat 在 scope 外，不受影响
        try (PointerScope scope = new PointerScope()) {

            KeyPointVector sceneKeyPoints = new KeyPointVector();
            Mat sceneDescriptors = new Mat();

            sift.detectAndCompute(sceneImg, emptyMask, sceneKeyPoints, sceneDescriptors);
            if (sceneDescriptors.empty()) return null;

            // 确保 CV_32F
            Mat sceneFloat;
            if (sceneDescriptors.type() == opencv_core.CV_32F) {
                sceneFloat = sceneDescriptors;
            } else {
                sceneFloat = new Mat();
                sceneDescriptors.convertTo(sceneFloat, opencv_core.CV_32F);
            }

            // 量化: 直接对原始 SIFT 描述符 (128维) 做 8-bit 量化
            Mat scene8U = new Mat();
            sceneFloat.convertTo(scene8U, opencv_core.CV_8U, qScale, -qMin * qScale);
            Mat queryFloat = new Mat();
            scene8U.convertTo(queryFloat, opencv_core.CV_32F);

            // KNN 匹配
            DMatchVectorVector rawMatches = new DMatchVectorVector();
            currentMatcher.knnMatch(queryFloat, rawMatches, 2);

            // Lowe's ratio test
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
                if (++matchCount % 300 == 0) {
                    asyncRebuildMatcher();
                }
                return result;
            }

            if (++matchCount % 300 == 0) {
                asyncRebuildMatcher();
            }
        } catch (Exception e) {
            log.error("匹配异常", e);
        }
        return null;
    }

    private double[][] executeRansac(List<DMatch> goodMatches, KeyPointVector sceneKps, int w, int h) {
        int count = goodMatches.size();

        // 复用缓冲区
        if (srcBuf.length < count * 2) {
            srcBuf = new float[count * 2];
            dstBuf = new float[count * 2];
        }

        FloatBuffer mapFb = mapKeyPointsDirectBuffer.asFloatBuffer();

        // 只提取有效匹配的 keypoint 坐标，避免残留值传入 findHomography
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
            return new double[][]{{res[0], res[1]}};
        }
        return null;
    }

    /**
     * 确保 sceneImg 尺寸匹配，Mat 自管理内存不包裹外部 buffer，
     * 避免 BytePointer(ByteBuffer) 分配的原生内存与 Mat 释放冲突导致 double-free。
     */
    private void prepareSceneMat(int w, int h) {
        if (w != currentWidth || h != currentHeight) {
            if (sceneImg != null) sceneImg.close();
            currentWidth = w;
            currentHeight = h;
            sceneImg = new Mat(h, w, opencv_core.CV_8UC1);
        }
    }

    // ================== 匹配器管理 ==================

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
                log.debug("异步重建 FLANN 匹配器完成");

                if (oldMatcher != null) {
                    scheduler.schedule(() -> {
                        oldMatcher.clear();
                    }, 1, TimeUnit.SECONDS);
                }
            } catch (Exception e) {
                log.error("异步重建匹配器失败", e);
            }
        });
    }

    // ================== 训练与缓存 ==================

    private boolean trainAndSave(String mapPath, String cachePath) {
        try (PointerScope scope = new PointerScope()) {

            // 读取地图图片
            byte[] bytes;
            try (InputStream is = ResourceUtils.getResourceStream(mapPath)) {
                bytes = is.readAllBytes();
            }

            Mat rawData = new Mat(bytes.length, 1, opencv_core.CV_8UC1);
            rawData.data().put(bytes);
            Mat mapColor = opencv_imgcodecs.imdecode(rawData, opencv_imgcodecs.IMREAD_UNCHANGED);

            Mat mapGray = new Mat();
            opencv_imgproc.cvtColor(mapColor, mapGray, opencv_imgproc.COLOR_BGR2GRAY);

            // SIFT 检测
            KeyPointVector kps = new KeyPointVector();
            Mat rawDescriptors = new Mat();
            sift.detectAndCompute(mapGray, emptyMask, kps, rawDescriptors);
            if (rawDescriptors.type() != opencv_core.CV_32F) {
                Mat tmp = new Mat();
                rawDescriptors.convertTo(tmp, opencv_core.CV_32F);
                rawDescriptors = tmp;
            }

            // 直接对原始 SIFT 描述符 (128维) 做 8-bit 量化，不做 PCA 降维
            DoublePointer minVal = new DoublePointer(1);
            DoublePointer maxVal = new DoublePointer(1);
            opencv_core.minMaxLoc(rawDescriptors, minVal, maxVal, null, null, emptyMat);
            qMin = (float) minVal.get();
            qScale = 255.0f / ((float) maxVal.get() - qMin + 1e-6f);

            rawDescriptors.convertTo(mapDescriptors8U, opencv_core.CV_8U, qScale, -qMin * qScale);

            // 保存地图关键点坐标
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

            log.info("SIFT-ULTRA 训练完成: {} 地图特征点 (128维, 量化8bit)", mapPointsCount);

            saveToCache(cachePath);

        } catch (Exception e) {
            log.error("训练异常", e);
            return false;
        }

        // ★ 必须在 scope 外创建 FlannBasedMatcher（长期存活），否则会被 scope 回收
        initMatcher();
        initialized = true;
        return true;
    }

    // ================== 缓存序列化 ==================

    private void saveToCache(String path) {
        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(path))) {
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
            m1.copyTo(mapDescriptors8U);
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
