package com.luoke.app.context;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luoke.app.config.AppConfig;
import com.luoke.app.map.model.ResourceConfig;
import com.luoke.app.utils.JsonUtils;
import com.luoke.app.utils.ResourceUtils;
import javafx.geometry.Point2D;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 管理资源点位信息（数据 + 预计算坐标）
 */
@Slf4j
public class ResourcePointContext {
    private static final ResourcePointContext INSTANCE = new ResourcePointContext();
    private final ObjectMapper objectMapper = JsonUtils.getMapper();

    // 原始资源配置列表
    private final List<ResourceConfig> rawResourceList = new ArrayList<>();

    // 【缓存：预处理好的点位 + 坐标】
    private final List<ResourcePoint> pointList = new ArrayList<>();

    // 按类型分组（方便查询）
    private final Map<String, List<ResourcePoint>> pointByType = new HashMap<>();

    private ResourcePointContext() {
    }

    public static ResourcePointContext getInstance() {
        return INSTANCE;
    }

    // =========================
    // 程序启动时调用一次
    // =========================
    public void loadAndInit() {
        try {
            // 1. 读取配置文件
            InputStream inputStream = ResourceUtils.getResourceStream(AppConfig.RESOURCE_POINT_CONFIG_PATH);
            List<ResourceConfig> configs = objectMapper.readValue(inputStream, new TypeReference<List<ResourceConfig>>() {
            });

            rawResourceList.clear();
            rawResourceList.addAll(configs);

            // 2. 预处理 → 计算坐标
            preprocessPoints();

        } catch (Exception e) {
            throw new RuntimeException("资源点位配置加载失败", e);
        }
    }

    // =========================
    // 预处理：计算地图坐标
    // =========================
    private void preprocessPoints() {
        pointList.clear();
        pointByType.clear();
        MapCoordinateManager coordManager = MapCoordinateManager.getInstance();

        for (ResourceConfig config : rawResourceList) {
            double lat = config.getLat() != null ? config.getLat() : 0.0;
            double lng = config.getLng() != null ? config.getLng() : 0.0;

            // 【关键：预计算转换后的坐标】
            Point2D screenPos = coordManager.toScreen(lng, lat);

            // 包装成带坐标的点位对象
            ResourcePoint point = new ResourcePoint(config, screenPos);
            pointList.add(point);
        }

        // 按 type 分组
        pointByType.putAll(
                pointList.stream().collect(Collectors.groupingBy(
                        p -> p.getConfig().getType()
                ))
        );
    }

    // =========================
    // 对外接口
    // =========================
    public List<ResourcePoint> getAllPoints() {
        return Collections.unmodifiableList(pointList);
    }

    public List<ResourcePoint> getPointsByType(String type) {
        return pointByType.getOrDefault(type, Collections.emptyList());
    }

    // =========================
    // 【点位包装类：原始数据 + 计算好的坐标】
    // =========================
    @Getter
    @ToString
    public static class ResourcePoint {
        private final ResourceConfig config;      // 原始数据
        private final Point2D screenPosition;     // 已计算好的屏幕坐标

        public ResourcePoint(ResourceConfig config, Point2D screenPosition) {
            this.config = config;
            this.screenPosition = screenPosition;
        }
    }
}