package com.luoke.app.macher.map.sift;

import org.opencv.core.Core;
import org.opencv.core.Mat;

/**
 * SIFT 匹配器共享工具 — 消除 PCA 投影的重复代码。
 */
final class SiftUtils {

    private SiftUtils() {
    }

    /**
     * PCA 投影: dst = (src * eigenvectors^T) - mean
     * 复用于所有 PCA 变体 (SiftUltra, SiftPCA, SiftPCAUltra)。
     */
    static void projectDescriptors(Mat src, Mat eigenvectors, Mat projectedMean,
                                   Mat repeatedMean, Mat emptyMat, Mat dst) {
        Core.gemm(src, eigenvectors, 1.0, emptyMat, 0, dst, Core.GEMM_2_T);
        Core.repeat(projectedMean, dst.rows(), 1, repeatedMean);
        Core.subtract(dst, repeatedMean, dst);
    }
}
