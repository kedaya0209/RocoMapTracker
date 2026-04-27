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
public class SiftPCAMapMatcher implements MapMatcher {

    private static volatile SiftPCAMapMatcher instance;

    // --- 算法引擎 ---
    private final SIFT sift = SIFT.create(
            AppConfig.SIFT_N_FEATURES,
            AppConfig.SIFT_N_OCTAVE_LAYERS,
            AppConfig.SIFT_CONTRAST_THRESHOLD,
            AppConfig.SIFT_EDGE_THRESHOLD,
            AppConfig.SIFT_SIGMA);
    private final DescriptorMatcher matcher = DescriptorMatcher.create(DescriptorMatcher.FLANNBASED);

    // --- PCA 与地图数据 (Native/堆外存储) ---
    private final Mat pcaEigenvectors = new Mat();
    private final Mat projectedMean = new Mat();
    private final Mat mapDescriptors = new Mat();
    private final int PCA_DIM = 64;
    private final MatOfKeyPoint sceneKeyPoints = new MatOfKeyPoint();
    private final Mat sceneDescriptors = new Mat();
    private final Mat reducedSceneDescriptors = new Mat();
    private final Mat repeatedMean = new Mat(); // 用于广播减法
    private final Mat emptyMask = new Mat();
    private final MatOfPoint2f srcMat = new MatOfPoint2f();
    private final MatOfPoint2f dstMat = new MatOfPoint2f();
    private final Mat ransacMask = new Mat();
    private final MatOfPoint2f srcCenter = new MatOfPoint2f();
    private final MatOfPoint2f dstCenter = new MatOfPoint2f();
    private FloatBuffer mapKeyPointsDirectBuffer; // 堆外坐标
    private int mapPointsCount = 0;
    // --- 运行期复用对象 (极致优化的核心) ---
    private ByteBuffer sceneRawPixelBuffer;
    private Mat sceneImg;
    // 用于零对象提取场景坐标的缓冲区
    private float[] sceneKeyPointsData = new float[AppConfig.SIFT_N_FEATURES * 7];
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
                log.info("🚀 SIFT-PCA 优化版载入成功 (PCA64)");
                initialized = true;
                return true;
            }
            return trainAndSave(mapPath, cacheFile.getAbsolutePath());
        }
    }

    private boolean trainAndSave(String mapPath, String cachePath) {
        MatOfByte mob = null;
        Mat mapColor = null;
        Mat mapGray = new Mat();
        MatOfKeyPoint kps = new MatOfKeyPoint();
        Mat rawDescriptors = new Mat();
        Mat fullEigenvectors = new Mat();
        Mat rawMean = new Mat();

        try (InputStream is = ResourceUtils.getResourceStream(mapPath)) {
            if (is == null) return false;
            mob = new MatOfByte(is.readAllBytes());
            mapColor = Imgcodecs.imdecode(mob, Imgcodecs.IMREAD_UNCHANGED);
            Imgproc.cvtColor(mapColor, mapGray, Imgproc.COLOR_BGR2GRAY);

            sift.detectAndCompute(mapGray, emptyMask, kps, rawDescriptors);
            if (rawDescriptors.type() != CvType.CV_32F) rawDescriptors.convertTo(rawDescriptors, CvType.CV_32F);

            // 1. PCA 计算
            Core.PCACompute(rawDescriptors, rawMean, fullEigenvectors);
            int actualDim = Math.min(PCA_DIM, fullEigenvectors.rows());
            fullEigenvectors.rowRange(0, actualDim).copyTo(pcaEigenvectors);
            Core.gemm(rawMean, pcaEigenvectors, 1, new Mat(), 0, projectedMean, Core.GEMM_2_T);

            // 2. 投影并保存地图描述子
            projectDescriptors(rawDescriptors, mapDescriptors);

            // 3. 坐标出堆
            KeyPoint[] kpsArray = kps.toArray();
            this.mapPointsCount = kpsArray.length;
            ByteBuffer bb = ByteBuffer.allocateDirect(mapPointsCount * 2 * 4).order(ByteOrder.nativeOrder());
            this.mapKeyPointsDirectBuffer = bb.asFloatBuffer();
            for (int i = 0; i < mapPointsCount; i++) {
                mapKeyPointsDirectBuffer.put(i * 2, (float) kpsArray[i].pt.x);
                mapKeyPointsDirectBuffer.put(i * 2 + 1, (float) kpsArray[i].pt.y);
            }

            saveToCache(cachePath);
            rebuildMatcher();
            initialized = true;
        } catch (Exception e) {
            log.error("PCA 训练失败", e);
            return false;
        } finally {
            rawDescriptors.release();
            fullEigenvectors.release();
            rawMean.release();
            if (mob != null) mob.release();
            if (mapColor != null) mapColor.release();
            mapGray.release();
            kps.release();
        }
        return true;
    }

    /**
     * 投影函数：使用复用矩阵 repeatedMean 避免分配
     */
    private void projectDescriptors(Mat src, Mat dst) {
        if (pcaEigenvectors.empty() || projectedMean.empty() || src.empty()) return;

        int rows = src.rows();
        // 第一步：矩阵乘法投影
        Core.gemm(src, pcaEigenvectors, 1.0, new Mat(), 0, dst, Core.GEMM_2_T);

        // 第二步：广播减法 (复用 repeatedMean)
        Core.repeat(projectedMean, rows, 1, repeatedMean);
        Core.subtract(dst, repeatedMean, dst);
    }

    @Override
    public double[][] match(byte[] grayData, int width, int height) {
        if (!initialized || grayData == null) return null;

        prepareSceneMat(width, height);
        sceneRawPixelBuffer.clear();
        sceneRawPixelBuffer.put(grayData);

        List<MatOfDMatch> knnMatches = new ArrayList<>();
        try {
            // 提取
            sift.detectAndCompute(sceneImg, emptyMask, sceneKeyPoints, sceneDescriptors);
            if (sceneDescriptors.empty()) return null;

            // PCA 降维 (直接将 sceneDescriptors 降维到 reducedSceneDescriptors)
            projectDescriptors(sceneDescriptors, reducedSceneDescriptors);

            // 匹配
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
            log.error("Match fail", e);
        } finally {
            knnMatches.clear();
        }
        return null;
    }

    private double[][] executeRansac(List<DMatch> goodMatches, int w, int h) {
        int count = goodMatches.size();
        srcMat.create(count, 1, CvType.CV_32FC2);
        dstMat.create(count, 1, CvType.CV_32FC2);

        // 零对象提取场景坐标
        int totalScenePoints = sceneKeyPoints.rows();
        if (sceneKeyPointsData.length < totalScenePoints * 7) {
            sceneKeyPointsData = new float[totalScenePoints * 7];
        }
        sceneKeyPoints.get(0, 0, sceneKeyPointsData);

        float[] srcData = new float[count * 2];
        float[] dstData = new float[count * 2];

        for (int i = 0; i < count; i++) {
            DMatch dm = goodMatches.get(i);
            int qOff = dm.queryIdx * 7;
            srcData[i * 2] = sceneKeyPointsData[qOff];
            srcData[i * 2 + 1] = sceneKeyPointsData[qOff + 1];
            dstData[i * 2] = mapKeyPointsDirectBuffer.get(dm.trainIdx * 2);
            dstData[i * 2 + 1] = mapKeyPointsDirectBuffer.get(dm.trainIdx * 2 + 1);
        }

        srcMat.put(0, 0, srcData);
        dstMat.put(0, 0, dstData);

        Mat H = Calib3d.findHomography(srcMat, dstMat, Calib3d.RANSAC,
                AppConfig.RANSAC_REPROJ_THRESHOLD, ransacMask, 2000, 0.995);

        try {
            if (H != null && !H.empty() && H.rows() == 3) {
                srcCenter.fromArray(new Point(w >> 1, h >> 1));
                Core.perspectiveTransform(srcCenter, dstCenter, H);
                Point p = dstCenter.toArray()[0];
                return new double[][]{{p.x, p.y}};
            }
        } finally {
            if (H != null) H.release();
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

    private void rebuildMatcher() {
        matcher.clear();
        matcher.add(List.of(mapDescriptors));
        matcher.train();
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
        repeatedMean.release();
        emptyMask.release();
        srcMat.release();
        dstMat.release();
        ransacMask.release();
        srcCenter.release();
        dstCenter.release();

        if (mapKeyPointsDirectBuffer != null) {
            mapKeyPointsDirectBuffer.clear();
            mapKeyPointsDirectBuffer = null;
        }
        if (sceneRawPixelBuffer != null) {
            sceneRawPixelBuffer.clear();
            sceneRawPixelBuffer = null;
        }

        initialized = false;
        System.gc();
    }

    // --- 持久化工具 ---
    private void saveToCache(String path) {
        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(path))) {
            writeMat(dos, pcaEigenvectors);
            writeMat(dos, projectedMean);
            writeMat(dos, mapDescriptors);
            dos.writeInt(mapPointsCount);
            for (int i = 0; i < mapPointsCount * 2; i++) {
                dos.writeFloat(mapKeyPointsDirectBuffer.get(i));
            }
        } catch (IOException e) {
            log.error("Cache save error", e);
        }
    }

    private boolean loadFromCache(String path) {
        File f = new File(path);
        if (!f.exists()) return false;
        try (DataInputStream dis = new DataInputStream(new FileInputStream(f))) {
            readMat(dis).copyTo(pcaEigenvectors);
            readMat(dis).copyTo(projectedMean);
            readMat(dis).copyTo(mapDescriptors);
            this.mapPointsCount = dis.readInt();
            ByteBuffer bb = ByteBuffer.allocateDirect(mapPointsCount * 2 * 4).order(ByteOrder.nativeOrder());
            this.mapKeyPointsDirectBuffer = bb.asFloatBuffer();
            for (int i = 0; i < mapPointsCount * 2; i++) {
                mapKeyPointsDirectBuffer.put(i, dis.readFloat());
            }
            rebuildMatcher();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void writeMat(DataOutputStream dos, Mat m) throws IOException {
        dos.writeInt(m.rows());
        dos.writeInt(m.cols());
        dos.writeInt(m.type());
        float[] data = new float[(int) (m.total() * m.channels())];
        m.get(0, 0, data);
        byte[] bytes = new byte[data.length * 4];
        ByteBuffer.wrap(bytes).asFloatBuffer().put(data);
        byte[] compressed = Zstd.compress(bytes);
        dos.writeInt(compressed.length);
        dos.writeInt(bytes.length);
        dos.write(compressed);
    }

    private Mat readMat(DataInputStream dis) throws IOException {
        int r = dis.readInt(), c = dis.readInt(), t = dis.readInt();
        int cLen = dis.readInt(), rLen = dis.readInt();
        byte[] cData = new byte[cLen];
        dis.readFully(cData);
        byte[] rData = Zstd.decompress(cData, rLen);
        float[] data = new float[rLen / 4];
        ByteBuffer.wrap(rData).asFloatBuffer().get(data);
        Mat m = new Mat(r, c, t);
        m.put(0, 0, data);
        return m;
    }
}