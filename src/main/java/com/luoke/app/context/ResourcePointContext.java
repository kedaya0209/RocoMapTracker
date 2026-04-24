package com.luoke.app.context;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luoke.app.config.AppConfig;
import com.luoke.app.map.model.ResourceConfig;
import com.luoke.app.map.model.ResourcePoint;
import com.luoke.app.utils.JsonUtils;
import com.luoke.app.utils.ResourceUtils;
import javafx.geometry.Point2D;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 管理资源点位信息（数据 + 预计算坐标 + GEO空间索引）
 */
@Slf4j
public class ResourcePointContext {
    private static final ResourcePointContext INSTANCE = new ResourcePointContext();
    private final ObjectMapper objectMapper = JsonUtils.getMapper();

    // 原始资源配置
    private final List<ResourceConfig> rawResourceList = new ArrayList<>();

    // 预处理后的点位
    private final List<ResourcePoint> pointList = new ArrayList<>();

    // 按类型分组
    private final Map<String, List<ResourcePoint>> pointByType = new HashMap<>();

    // ====================== GEO 空间网格索引（高性能 nearby） ======================
    private final ResourceGridIndex gridIndex = new ResourceGridIndex();

    public static ResourcePointContext getInstance() {
        return INSTANCE;
    }

    private ResourcePointContext() {
    }

    // =========================
    // 程序启动时调用一次
    // =========================
    public void loadAndInit() {
        try {
            InputStream inputStream = ResourceUtils.getResourceStream(AppConfig.RESOURCE_POINT_CONFIG_PATH);
            List<ResourceConfig> configs = objectMapper.readValue(inputStream, new TypeReference<List<ResourceConfig>>() {
            });

            rawResourceList.clear();
            rawResourceList.addAll(configs);

            preprocessPoints();

            log.info("资源点位加载完成，总数：{}", pointList.size());

        } catch (Exception e) {
            throw new RuntimeException("资源点位配置加载失败", e);
        }
    }

    // =========================
    // 预处理 + 构建 GEO 索引
    // =========================
    private void preprocessPoints() {
        pointList.clear();
        pointByType.clear();
        MapCoordinateManager coordManager = MapCoordinateManager.getInstance();

        for (ResourceConfig config : rawResourceList) {
            double lat = config.getLat() != null ? config.getLat() : 0.0;
            double lng = config.getLng() != null ? config.getLng() : 0.0;

            Point2D screenPos = coordManager.toScreen(lng, lat);
            ResourcePoint point = new ResourcePoint(config, screenPos);
            pointList.add(point);
        }

        // 按类型分组
        pointByType.putAll(
                pointList.stream().collect(Collectors.groupingBy(
                        p -> p.getConfig().getType()
                ))
        );

        // ====================== 构建 GEO 空间索引 ======================
        gridIndex.buildIndex(pointList);
    }

    // =========================
    // 【高性能】获取玩家附近的资源点
    // =========================
    public List<ResourcePoint> getNearbyResources(double x, double y) {
        return gridIndex.queryNear(x, y);
    }

    // =========================
    // 基础接口
    // =========================
    public List<ResourcePoint> getAllPoints() {
        return Collections.unmodifiableList(pointList);
    }

    public List<ResourcePoint> getPointsByType(String type) {
        return pointByType.getOrDefault(type, Collections.emptyList());
    }

}