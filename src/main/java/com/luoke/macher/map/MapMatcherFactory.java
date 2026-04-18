package com.luoke.macher.map;

import com.luoke.macher.map.sift.SiftMapMatcher;
import com.luoke.macher.map.sift.SiftMapMatcherOpenCL;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MapMatcherFactory {

    /**
     * 核心工厂方法：根据参数生产具体的实现类
     * * @param maxFeatures   特征点上限
     *
     * @param useGpu 是否启用 GPU (OpenCL)
     * @return 具体的 MapMatcher 实现实例
     */
    public static MapMatcher createMatcher(int maxFeatures, boolean useGpu) {
        if (useGpu) {
            log.info("工厂模式：正在生产 GPU (OpenCL) 匹配器实例...");
            return new SiftMapMatcherOpenCL(maxFeatures);
        } else {
            log.info("工厂模式：正在生产 CPU (优化版) 匹配器实例...");
            return new SiftMapMatcher(maxFeatures);
        }
    }
}