package com.luoke.app.macher.map.sift;

import com.github.luben.zstd.Zstd;
import com.luoke.app.config.AppConfig;
import com.luoke.app.macher.map.MapMatcher;
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
import java.util.List;

@Slf4j
public class SiftPCAUltraMapMatcher implements MapMatcher {

    private static volatile SiftPCAUltraMapMatcher instance;

    private final SIFT sift = SIFT.create(
            AppConfig.SIFT_N_FEATURES,
            AppConfig.SIFT_N_OCTAVE_LAYERS,
            AppConfig.SIFT_CONTRAST_THRESHOLD,
            AppConfig.SIFT_EDGE_THRESHOLD,
            AppConfig.SIFT_SIGMA);

    private final DescriptorMatcher matcher = DescriptorMatcher.create(DescriptorMatcher.FLANNBASED);

    private final Mat pcaEigenvectors = new Mat();
    private final Mat projectedMean = new Mat();
    private final Mat mapDescriptors8U = new Mat();
    private final int PCA_DIM = 64;
    private final MatOfKeyPoint sceneKeyPoints = new MatOfKeyPoint();
    private final Mat sceneDescriptors = new Mat();
    private final Mat repeatedMean = new Mat();
    private final Mat emptyMask = new Mat();
    private float qMin = 0f;
    private float qScale = 1f;
    private ByteBuffer mapKeyPointsDirectBuffer;
    private int mapPointsCount = 0;
    private ByteBuffer sceneRawPixelBuffer;
    private Mat sceneImg;
    private int currentWidth = -1;
    private int currentHeight = -1;
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
            // 缓存文件建议增加版本号，防止旧数据干扰
            File cacheFile = ResourceUtils.getExternalFile(mapPath + ".pca64.ultra.feat");
            if (loadFromCache(cacheFile.getAbsolutePath())) {
                log.info("🚀 SIFT-PCA 载入成功");
                rebuildMatcher();
                initialized = true;
                return true;
            }
            return trainAndSave(mapPath, cacheFile.getAbsolutePath());
        }
    }

    private boolean trainAndSave(String mapPath, String cachePath) {
        try (InputStream is = ResourceUtils.getResourceStream(mapPath)) {
            if (is == null) return false;
            byte[] bytes = is.readAllBytes();
            Mat mapColor = Imgcodecs.imdecode(new MatOfByte(bytes), Imgcodecs.IMREAD_UNCHANGED);
            Mat mapGray = new Mat();
            Imgproc.cvtColor(mapColor, mapGray, Imgproc.COLOR_BGR2GRAY);

            MatOfKeyPoint kps = new MatOfKeyPoint();
            Mat rawDescriptors = new Mat();
            sift.detectAndCompute(mapGray, emptyMask, kps, rawDescriptors);
            if (rawDescriptors.type() != CvType.CV_32F) rawDescriptors.convertTo(rawDescriptors, CvType.CV_32F);

            // 1. PCA 计算
            Mat rawMean = new Mat();
            Mat fullEigenvectors = new Mat();
            Core.PCACompute(rawDescriptors, rawMean, fullEigenvectors);
            int actualDim = Math.min(PCA_DIM, fullEigenvectors.rows());
            fullEigenvectors.rowRange(0, actualDim).copyTo(pcaEigenvectors);
            Core.gemm(rawMean, pcaEigenvectors, 1, new Mat(), 0, projectedMean, Core.GEMM_2_T);

            // 2. 投影
            Mat projected = new Mat();
            projectDescriptors(rawDescriptors, projected);

            // 3. 核心修复：计算全局量化参数并持久化
            Core.MinMaxLocResult mm = Core.minMaxLoc(projected);
            this.qMin = (float) mm.minVal;
            this.qScale = 255.0f / ((float) mm.maxVal - qMin + 1e-6f);

            projected.convertTo(mapDescriptors8U, CvType.CV_8U, qScale, -qMin * qScale);

            // 4. 坐标存储
            KeyPoint[] kpsArray = kps.toArray();
            this.mapPointsCount = kpsArray.length;
            this.mapKeyPointsDirectBuffer = ByteBuffer.allocateDirect(mapPointsCount * 2 * 4).order(ByteOrder.nativeOrder());
            FloatBuffer fb = mapKeyPointsDirectBuffer.asFloatBuffer();
            for (int i = 0; i < mapPointsCount; i++) {
                fb.put(i * 2, (float) kpsArray[i].pt.x);
                fb.put(i * 2 + 1, (float) kpsArray[i].pt.y);
            }

            saveToCache(cachePath);
            rebuildMatcher();
            initialized = true;

            // 释放临时内存
            projected.release();
            mapColor.release();
            mapGray.release();
            kps.release();
            rawDescriptors.release();
            rawMean.release();
            fullEigenvectors.release();
        } catch (Exception e) {
            log.error("训练失败", e);
            return false;
        }
        return true;
    }

    private void projectDescriptors(Mat src, Mat dst) {
        Core.gemm(src, pcaEigenvectors, 1.0, new Mat(), 0, dst, Core.GEMM_2_T);
        Core.repeat(projectedMean, dst.rows(), 1, repeatedMean);
        Core.subtract(dst, repeatedMean, dst);
    }

    private void rebuildMatcher() {
        matcher.clear();
        Mat tempFloat = new Mat();
        mapDescriptors8U.convertTo(tempFloat, CvType.CV_32F); // 还原回 0-255 的浮点用于 FLANN
        matcher.add(List.of(tempFloat));
        matcher.train();
        tempFloat.release();
    }

    @Override
    public double[][] match(byte[] grayData, int width, int height) {
        if (!initialized || grayData == null) return null;

        prepareSceneMat(width, height);
        sceneRawPixelBuffer.clear();
        sceneRawPixelBuffer.put(grayData);

        List<MatOfDMatch> knnMatches = new ArrayList<>();
        try {
            sift.detectAndCompute(sceneImg, emptyMask, sceneKeyPoints, sceneDescriptors);
            if (sceneDescriptors.empty()) return null;

            // 1. 场景点投影
            Mat sceneProjected = new Mat();
            projectDescriptors(sceneDescriptors, sceneProjected);

            // 2. 核心修复：使用地图同样的 qMin/qScale 进行量化和对齐
            Mat scene8U = new Mat();
            sceneProjected.convertTo(scene8U, CvType.CV_8U, qScale, -qMin * qScale);

            // 3. 还原到 0-255 浮点空间进行匹配
            Mat sceneQuery32F = new Mat();
            scene8U.convertTo(sceneQuery32F, CvType.CV_32F);

            matcher.knnMatch(sceneQuery32F, knnMatches, 2);

            List<DMatch> goodMatches = new ArrayList<>();
            float ratio = AppConfig.MATCH_RATIO_THRESHOLD;
            for (MatOfDMatch m : knnMatches) {
                DMatch[] dms = m.toArray();
                if (dms.length >= 2 && dms[0].distance < ratio * dms[1].distance) {
                    goodMatches.add(dms[0]);
                }
                m.release();
            }

            sceneProjected.release();
            scene8U.release();
            sceneQuery32F.release();

            if (goodMatches.size() >= AppConfig.MATCH_MIN_COUNT) {
                return executeRansac(goodMatches, width, height);
            }
        } catch (Exception e) {
            log.error("Match error", e);
        }
        return null;
    }

    private double[][] executeRansac(List<DMatch> goodMatches, int w, int h) {
        int count = goodMatches.size();
        MatOfPoint2f srcPoints = new MatOfPoint2f();
        MatOfPoint2f dstPoints = new MatOfPoint2f();
        srcPoints.alloc(count);
        dstPoints.alloc(count);

        float[] sceneKpsData = new float[sceneKeyPoints.rows() * 7];
        sceneKeyPoints.get(0, 0, sceneKpsData);

        float[] srcArr = new float[count * 2];
        float[] dstArr = new float[count * 2];
        FloatBuffer mapFb = mapKeyPointsDirectBuffer.asFloatBuffer();

        for (int i = 0; i < count; i++) {
            DMatch dm = goodMatches.get(i);
            srcArr[i * 2] = sceneKpsData[dm.queryIdx * 7];
            srcArr[i * 2 + 1] = sceneKpsData[dm.queryIdx * 7 + 1];
            dstArr[i * 2] = mapFb.get(dm.trainIdx * 2);
            dstArr[i * 2 + 1] = mapFb.get(dm.trainIdx * 2 + 1);
        }
        srcPoints.put(0, 0, srcArr);
        dstPoints.put(0, 0, dstArr);

        Mat mask = new Mat();
        Mat H = Calib3d.findHomography(srcPoints, dstPoints, Calib3d.RANSAC, AppConfig.RANSAC_REPROJ_THRESHOLD, mask, 2000, 0.995);

        if (H != null && !H.empty()) {
            Mat srcCenter = new Mat(1, 1, CvType.CV_32FC2);
            Mat dstCenter = new Mat(1, 1, CvType.CV_32FC2);
            srcCenter.put(0, 0, (float) w / 2, (float) h / 2);
            Core.perspectiveTransform(srcCenter, dstCenter, H);
            float[] res = new float[2];
            dstCenter.get(0, 0, res);

            srcCenter.release();
            dstCenter.release();
            H.release();
            mask.release();
            srcPoints.release();
            dstPoints.release();
            return new double[][]{{(double) res[0], (double) res[1]}};
        }

        srcPoints.release();
        dstPoints.release();
        mask.release();
        return null;
    }

    private void prepareSceneMat(int w, int h) {
        if (w != currentWidth || h != currentHeight) {
            if (sceneImg != null) sceneImg.release();
            this.currentWidth = w;
            this.currentHeight = h;
            this.sceneRawPixelBuffer = ByteBuffer.allocateDirect(w * h).order(ByteOrder.nativeOrder());
            this.sceneImg = new Mat(h, w, CvType.CV_8UC1, sceneRawPixelBuffer);
        }
    }

    // --- 持久化 ---
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
            readMat(dis).copyTo(pcaEigenvectors);
            readMat(dis).copyTo(projectedMean);
            readMat(dis).copyTo(mapDescriptors8U);
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
        repeatedMean.release();
        emptyMask.release();
        initialized = false;
    }
}