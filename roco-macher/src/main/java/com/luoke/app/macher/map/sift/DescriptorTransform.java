package com.luoke.app.macher.map.sift;

import com.github.luben.zstd.Zstd;
import net.jcip.annotations.NotThreadSafe;
import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.javacpp.FloatPointer;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.opencv_core.Mat;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.function.BiConsumer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * 描述符变换管道 — 组合 PCA 降维和 8-bit 量化两个可选步骤，覆盖四种匹配器变体。
 *
 * <pre>
 *   STANDARD:  raw SIFT 128维 CV_32F → 直接存储
 *   PCA:       raw → PCA投影 64维 CV_32F → 存储
 *   ULTRA:     raw → 8-bit 量化 CV_8U → 存储
 *   PCA_ULTRA: raw → PCA投影 64维 → 8-bit 量化 CV_8U → 存储
 * </pre>
 */
@NotThreadSafe
public class DescriptorTransform {

    // PCA 状态
    final Mat pcaEigenvectors = new Mat();  // CV_32F, (dim × 128)
    final Mat projectedMean = new Mat();    // CV_32F, (1 × dim)
    // 最终持久化描述符 Mat (CV_32F 或 CV_8U，取决于变体)
    final Mat persistentMat = new Mat();
    private final Variant variant;
    // PCA 投影用临时 Mat
    private final Mat emptyMat = new Mat();
    // 量化参数
    float qMin;
    float qScale;

    DescriptorTransform(Variant variant) {
        this.variant = variant;
    }

    private static void writeMat(DataOutputStream dos, Mat m) throws IOException {
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

    private static Mat readMat(DataInputStream dis) throws IOException {
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

    // ================== Train: 处理地图描述符 ==================

    String cacheSuffix() {
        return variant.cacheSuffix;
    }

    // ================== Match: 处理场景描述符 ==================

    /**
     * 对原始 SIFT 描述符执行 PCA + 量化变换，结果存入 persistentMat。
     * 必须在 PointerScope 内调用（临时 Mat 由 scope 回收）。
     */
    void train(Mat rawDescriptors) {
        Mat result = rawDescriptors;

        if (variant.usePca) {
            // PCACompute + 投影
            Mat rawMean = new Mat();
            Mat fullEigenvectors = new Mat();
            opencv_core.PCACompute(rawDescriptors, rawMean, fullEigenvectors);
            int dim = Math.min(64, fullEigenvectors.rows());
            fullEigenvectors.rowRange(0, dim).copyTo(pcaEigenvectors);
            opencv_core.gemm(rawMean, pcaEigenvectors, 1.0, emptyMat, 0.0, projectedMean, opencv_core.CV_HAL_GEMM_2_T);
            result = pcaProject(result);
        }

        if (variant.useQuantize) {
            DoublePointer minVal = new DoublePointer(1);
            DoublePointer maxVal = new DoublePointer(1);
            opencv_core.minMaxLoc(result, minVal, maxVal, null, null, emptyMat);
            qMin = (float) minVal.get();
            qScale = 255.0f / ((float) maxVal.get() - qMin + 1e-6f);

            Mat quantized = new Mat();
            result.convertTo(quantized, opencv_core.CV_8U, qScale, -qMin * qScale);
            result = quantized;
        }

        result.copyTo(persistentMat);
    }

    // ================== PCA 投影 ==================

    /**
     * 对每帧场景描述符执行 PCA + 量化变换，返回 CV_32F 供 FLANN 匹配。
     * 必须在 PointerScope 内调用。
     */
    Mat process(Mat sceneDescriptors) {
        Mat result = sceneDescriptors;

        if (variant.usePca) {
            result = pcaProject(result);
        }

        if (variant.useQuantize) {
            Mat quantized = new Mat();
            result.convertTo(quantized, opencv_core.CV_8U, qScale, -qMin * qScale);
            Mat asFloat = new Mat();
            quantized.convertTo(asFloat, opencv_core.CV_32F);
            result = asFloat;
        }

        return result; // 始终返回 CV_32F
    }

    // ================== 缓存序列化 ==================

    private Mat pcaProject(Mat src) {
        Mat dst = new Mat();
        opencv_core.gemm(src, pcaEigenvectors, 1.0, emptyMat, 0.0, dst, opencv_core.CV_HAL_GEMM_2_T);
        Mat repeatedMean = new Mat();
        opencv_core.repeat(projectedMean, dst.rows(), 1, repeatedMean);
        opencv_core.subtract(dst, repeatedMean, dst);
        return dst;
    }

    void saveToCache(DataOutputStream dos, ByteBuffer mapKeyPointsDirectBuffer, int mapPointsCount) throws IOException {
        if (variant.usePca) {
            writeMat(dos, pcaEigenvectors);
            writeMat(dos, projectedMean);
        }
        writeMat(dos, persistentMat);
        if (variant.useQuantize) {
            dos.writeFloat(qMin);
            dos.writeFloat(qScale);
        }
        dos.writeInt(mapPointsCount);
        FloatBuffer fb = mapKeyPointsDirectBuffer.asFloatBuffer();
        for (int i = 0; i < mapPointsCount * 2; i++) {
            dos.writeFloat(fb.get(i));
        }
    }

    void loadFromCache(DataInputStream dis, BiConsumer<ByteBuffer, Integer> keyPointsSetter) throws IOException {
        if (variant.usePca) {
            readMat(dis).copyTo(pcaEigenvectors);
            readMat(dis).copyTo(projectedMean);
        }
        readMat(dis).copyTo(persistentMat);
        if (variant.useQuantize) {
            qMin = dis.readFloat();
            qScale = dis.readFloat();
        }
        int mapPointsCount = dis.readInt();
        ByteBuffer buf = ByteBuffer.allocateDirect(mapPointsCount * 2 * 4).order(ByteOrder.nativeOrder());
        FloatBuffer fb = buf.asFloatBuffer();
        for (int i = 0; i < mapPointsCount * 2; i++) {
            fb.put(i, dis.readFloat());
        }
        keyPointsSetter.accept(buf, mapPointsCount);
    }

    // ================== Mat 序列化工具 ==================

    void destroy() {
        persistentMat.close();
        if (variant.usePca) {
            pcaEigenvectors.close();
            projectedMean.close();
        }
        emptyMat.close();
    }

    public enum Variant {
        STANDARD(false, false, ".v2.feat"),
        PCA(true, false, ".pca64.feat"),
        ULTRA(false, true, ".sift.ultra.feat"),
        PCA_ULTRA(true, true, ".pca64.ultra.feat");

        final boolean usePca;
        final boolean useQuantize;
        final String cacheSuffix;

        Variant(boolean usePca, boolean useQuantize, String cacheSuffix) {
            this.usePca = usePca;
            this.useQuantize = useQuantize;
            this.cacheSuffix = cacheSuffix;
        }
    }
}
