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
public class SiftUltraMapMatcher implements MapMatcher {

    private static volatile SiftUltraMapMatcher instance;

    private final SIFT sift = SIFT.create(
            AppConfig.SIFT_N_FEATURES,
            AppConfig.SIFT_N_OCTAVE_LAYERS,
            AppConfig.SIFT_CONTRAST_THRESHOLD,
            AppConfig.SIFT_EDGE_THRESHOLD,
            AppConfig.SIFT_SIGMA);

    // 使用 FLANNBASED，并在 rebuild 时手动转换类型避开 OpenCV 4.9 的 Bug
    private final DescriptorMatcher matcher = DescriptorMatcher.create(DescriptorMatcher.FLANNBASED);

    private final Mat pcaEigenvectors = new Mat();
    private final Mat projectedMean = new Mat();
    private final Mat mapDescriptors = new Mat(); // 核心：CV_8U 存储
    private final MatOfKeyPoint sceneKeyPoints = new MatOfKeyPoint();
    private final Mat sceneDescriptors = new Mat();
    private final Mat reducedSceneDescriptors = new Mat();
    private final Mat quantizedSceneDescriptors = new Mat(); // 场景量化 Mat
    private final Mat repeatedMean = new Mat();
    private final Mat emptyMask = new Mat();
    private final MatOfPoint2f mSrc = new MatOfPoint2f();
    private final MatOfPoint2f mDst = new MatOfPoint2f();
    private final Mat ransacMask = new Mat();
    private FloatBuffer mapKeyPointsDirectBuffer;
    private int mapPointsCount = 0;
    private float qMin = 0f;
    private float qScale = 1f;
    // 复用容器
    private ByteBuffer sceneRawPixelBuffer;
    private Mat sceneImg;
    private float[] sceneKeyPointsData = new float[AppConfig.SIFT_N_FEATURES * 7];
    private int currentWidth = -1, currentHeight = -1;
    private volatile boolean initialized = false;

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
            // 每次修改逻辑建议更换后缀强制刷新
            File cacheFile = ResourceUtils.getExternalFile(mapPath + ".sift.ultra.feat");
            if (loadFromCache(cacheFile.getAbsolutePath())) {
                log.info("🚀 载入成功: SIFT + ULTRA");
                initialized = true;
                return true;
            }
            return trainAndSave(mapPath, cacheFile.getAbsolutePath());
        }
    }

    private boolean trainAndSave(String mapPath, String cachePath) {
        Mat mapGray = new Mat();
        MatOfKeyPoint kps = new MatOfKeyPoint();
        Mat rawDescriptors = new Mat();
        Mat fullEigenvectors = new Mat();
        Mat rawMean = new Mat();

        try (InputStream is = ResourceUtils.getResourceStream(mapPath)) {
            if (is == null) return false;
            byte[] bytes = is.readAllBytes();
            Mat mapColor = Imgcodecs.imdecode(new MatOfByte(bytes), Imgcodecs.IMREAD_UNCHANGED);
            Imgproc.cvtColor(mapColor, mapGray, Imgproc.COLOR_BGR2GRAY);

            sift.detectAndCompute(mapGray, emptyMask, kps, rawDescriptors);
            if (rawDescriptors.type() != CvType.CV_32F) rawDescriptors.convertTo(rawDescriptors, CvType.CV_32F);

            // 1. PCA 降维 (建议 64 维起步)
            Core.PCACompute(rawDescriptors, rawMean, fullEigenvectors);
            int dim = Math.min(64, fullEigenvectors.rows());
            fullEigenvectors.rowRange(0, dim).copyTo(pcaEigenvectors);
            Core.gemm(rawMean, pcaEigenvectors, 1, new Mat(), 0, projectedMean, Core.GEMM_2_T);

            // 2. 投影并计算量化参数
            Mat floatProj = new Mat();
            projectDescriptors(rawDescriptors, floatProj);
            Core.MinMaxLocResult mm = Core.minMaxLoc(floatProj);
            this.qMin = (float) mm.minVal;
            this.qScale = 255.0f / ((float) mm.maxVal - qMin + 1e-6f);

            // 3. 存储量化后的地图 (核心内存优化)
            Core.subtract(floatProj, new Scalar(qMin), floatProj);
            floatProj.convertTo(mapDescriptors, CvType.CV_8U, qScale);

            // 4. 坐标存储
            this.mapPointsCount = kps.rows();
            this.mapKeyPointsDirectBuffer = ByteBuffer.allocateDirect(mapPointsCount * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            KeyPoint[] kpsArr = kps.toArray();
            for (int i = 0; i < mapPointsCount; i++) {
                mapKeyPointsDirectBuffer.put(i * 2, (float) kpsArr[i].pt.x);
                mapKeyPointsDirectBuffer.put(i * 2 + 1, (float) kpsArr[i].pt.y);
            }

            saveToCache(cachePath);
            rebuildMatcher();
            initialized = true;
            floatProj.release();
            mapColor.release();
        } catch (Exception e) {
            log.error("训练异常", e);
            return false;
        } finally {
            rawDescriptors.release();
            fullEigenvectors.release();
            rawMean.release();
            mapGray.release();
            kps.release();
        }
        return true;
    }

    private void rebuildMatcher() {
        matcher.clear();
        // 匹配前动态转回 float 喂给 FLANN
        Mat tempFloat = new Mat();
        mapDescriptors.convertTo(tempFloat, CvType.CV_32F);
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

            // 1. 场景点 PCA 降维
            projectDescriptors(sceneDescriptors, reducedSceneDescriptors);

            // 2. 关键对齐：场景点必须使用和地图一样的 qMin/qScale 进行量化，再转回 float
            // 这样才能保证场景描述子和地图描述子在同一个数值量级
            Core.subtract(reducedSceneDescriptors, new Scalar(qMin), reducedSceneDescriptors);
            reducedSceneDescriptors.convertTo(quantizedSceneDescriptors, CvType.CV_8U, qScale);
            quantizedSceneDescriptors.convertTo(reducedSceneDescriptors, CvType.CV_32F);

            // 3. 匹配
            matcher.knnMatch(reducedSceneDescriptors, knnMatches, 2);

            List<DMatch> goodMatches = new ArrayList<>();
            float ratio = AppConfig.MATCH_RATIO_THRESHOLD;
            for (MatOfDMatch m : knnMatches) {
                DMatch[] dms = m.toArray();
                if (dms.length >= 2 && dms[0].distance < ratio * dms[1].distance) {
                    goodMatches.add(dms[0]);
                }
                m.release();
            }

            if (goodMatches.size() >= AppConfig.MATCH_MIN_COUNT) {
                return executeRansac(goodMatches, width, height);
            }
        } catch (Exception e) {
            log.error("匹配异常", e);
        }
        return null;
    }

    private void projectDescriptors(Mat src, Mat dst) {
        if (src.type() != CvType.CV_32F) src.convertTo(src, CvType.CV_32F);
        // PCA 投影
        Core.gemm(src, pcaEigenvectors, 1.0, new Mat(), 0, dst, Core.GEMM_2_T);
        // 减去均值：这一步极其重要，否则特征空间是对不齐的
        Core.repeat(projectedMean, dst.rows(), 1, repeatedMean);
        Core.subtract(dst, repeatedMean, dst);
    }

    private double[][] executeRansac(List<DMatch> goodMatches, int w, int h) {
        int count = goodMatches.size();
        float[] srcData = new float[count * 2];
        float[] dstData = new float[count * 2];

        int totalScenePoints = sceneKeyPoints.rows();
        if (sceneKeyPointsData.length < totalScenePoints * 7) sceneKeyPointsData = new float[totalScenePoints * 7];
        sceneKeyPoints.get(0, 0, sceneKeyPointsData);

        for (int i = 0; i < count; i++) {
            DMatch dm = goodMatches.get(i);
            srcData[i * 2] = sceneKeyPointsData[dm.queryIdx * 7];
            srcData[i * 2 + 1] = sceneKeyPointsData[dm.queryIdx * 7 + 1];
            dstData[i * 2] = mapKeyPointsDirectBuffer.get(dm.trainIdx * 2);
            dstData[i * 2 + 1] = mapKeyPointsDirectBuffer.get(dm.trainIdx * 2 + 1);
        }

        mSrc.alloc(count);
        mDst.alloc(count);
        mSrc.put(0, 0, srcData);
        mDst.put(0, 0, dstData);

        Mat H = Calib3d.findHomography(mSrc, mDst, Calib3d.RANSAC, AppConfig.RANSAC_REPROJ_THRESHOLD, ransacMask, 2000, 0.995);

        if (H != null && !H.empty() && H.rows() == 3) {
            Mat sCenter = new Mat(1, 1, CvType.CV_32FC2);
            Mat dCenter = new Mat(1, 1, CvType.CV_32FC2);
            sCenter.put(0, 0, new float[]{(float) (w >> 1), (float) (h >> 1)});
            Core.perspectiveTransform(sCenter, dCenter, H);
            float[] res = new float[2];
            dCenter.get(0, 0, res);
            sCenter.release();
            dCenter.release();
            H.release();
            return new double[][]{{(double) res[0], (double) res[1]}};
        }
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

    // --- 缓存与序列化 ---

    private void saveToCache(String path) {
        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(path))) {
            writeMat(dos, pcaEigenvectors);
            writeMat(dos, projectedMean);
            writeMat(dos, mapDescriptors);
            dos.writeFloat(qMin);
            dos.writeFloat(qScale);
            dos.writeInt(mapPointsCount);
            for (int i = 0; i < mapPointsCount * 2; i++) dos.writeFloat(mapKeyPointsDirectBuffer.get(i));
        } catch (Exception e) {
            log.error("Cache Save Err", e);
        }
    }

    private boolean loadFromCache(String path) {
        File f = new File(path);
        if (!f.exists()) return false;
        try (DataInputStream dis = new DataInputStream(new FileInputStream(f))) {
            readMat(dis).copyTo(pcaEigenvectors);
            readMat(dis).copyTo(projectedMean);
            readMat(dis).copyTo(mapDescriptors);
            this.qMin = dis.readFloat();
            this.qScale = dis.readFloat();
            this.mapPointsCount = dis.readInt();
            this.mapKeyPointsDirectBuffer = ByteBuffer.allocateDirect(mapPointsCount * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            for (int i = 0; i < mapPointsCount * 2; i++) mapKeyPointsDirectBuffer.put(i, dis.readFloat());
            rebuildMatcher();
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
        reducedSceneDescriptors.release();
        quantizedSceneDescriptors.release();
        repeatedMean.release();
        emptyMask.release();
        mSrc.release();
        mDst.release();
        ransacMask.release();
        initialized = false;
    }
}