package com.luoke.app.context;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luoke.app.config.AppConfig;
import com.luoke.app.map.model.ResourceConfig;
import com.luoke.app.map.model.ResourcePoint;
import com.luoke.app.ui.ModernCanvasApp;
import com.luoke.app.ui.component.NotificationToast;
import com.luoke.app.utils.JsonUtils;
import com.luoke.app.utils.ResourceUtils;
import javafx.geometry.Point2D;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
public class ResourcePointContext {
    private static final ResourcePointContext INSTANCE = new ResourcePointContext();
    private final ObjectMapper objectMapper = JsonUtils.getMapper();

    private final List<ResourceConfig> rawResourceList = new ArrayList<>();
    private final List<ResourcePoint> pointList = new ArrayList<>();
    private final Map<String, List<ResourcePoint>> pointByType = new HashMap<>();
    private final ResourceGridIndex gridIndex = new ResourceGridIndex();

    public static ResourcePointContext getInstance() {
        return INSTANCE;
    }

    private ResourcePointContext() {
    }

    private static ResourceConfig getConfig(String markTypeName, ResourcePoint originPoint, Point2D rawPoint) {
        ResourceConfig originConfig = originPoint.getConfig();
        ResourceConfig config = new ResourceConfig();
        config.setMarkTypeName(markTypeName);
        config.setMarkType(originConfig.getMarkType());
        config.setType(originConfig.getType());
        config.setLayer(originConfig.getLayer());
        config.setZoom(originConfig.getZoom());
        config.setLat(rawPoint.getY());
        config.setLng(rawPoint.getX());
        config.setIcon(originConfig.getIcon());
        return config;
    }

    public void loadAndInit() {
        try {
            File extFile = ResourceUtils.getExternalFile(AppConfig.RESOURCE_POINT_CONFIG_PATH);
            if (extFile.exists()) {
                rawResourceList.clear();
                List<ResourceConfig> configs = objectMapper.readValue(extFile, new TypeReference<List<ResourceConfig>>() {
                });
                rawResourceList.addAll(configs);
            } else {
                InputStream inputStream = ResourceUtils.getResourceStream(AppConfig.RESOURCE_POINT_CONFIG_PATH);
                List<ResourceConfig> configs = objectMapper.readValue(inputStream, new TypeReference<List<ResourceConfig>>() {
                });
                rawResourceList.addAll(configs);
            }
            preprocessPoints();
            log.info("资源点位加载完成，总数：{}", pointList.size());
        } catch (Exception e) {
            throw new RuntimeException("资源点位配置加载失败", e);
        }
    }

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

        pointByType.putAll(pointList.stream().collect(Collectors.groupingBy(
                p -> p.getConfig().getType()
        )));
        gridIndex.buildIndex(pointList);
    }

    public void savePoint(final String markTypeName, double screenX, double screenY) {
        try {
            Point2D rawPoint = MapCoordinateManager.getInstance().fromScreen(screenX, screenY);
            ResourcePoint originPoint = pointList.stream().filter(point -> point.getConfig().getMarkTypeName().equalsIgnoreCase(markTypeName)).findFirst().get();
            ResourceConfig config = getConfig(markTypeName, originPoint, rawPoint);
            rawResourceList.add(config);
            File target = ResourceUtils.getExternalFile(AppConfig.RESOURCE_POINT_CONFIG_PATH);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(target, rawResourceList);
            preprocessPoints();
            ModernCanvasApp.notify("新增点位成功", NotificationToast.Type.SUCCESS);
        } catch (Exception e) {
            log.error("新增点位失败", e);
            ModernCanvasApp.notify("新增点位失败", NotificationToast.Type.ERROR);
        }
    }

    // ======================
    // ✅ 新增：删除点位
    // ======================
    public void deletePoint(ResourcePoint point) {
        try {
            rawResourceList.remove(point.getConfig());
            File target = ResourceUtils.getExternalFile(AppConfig.RESOURCE_POINT_CONFIG_PATH);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(target, rawResourceList);
            preprocessPoints();
            log.info("删除点位成功");
            ModernCanvasApp.notify("删除点位成功", NotificationToast.Type.SUCCESS);
        } catch (Exception e) {
            log.error("删除点位失败", e);
            ModernCanvasApp.notify("删除点位失败", NotificationToast.Type.ERROR);
        }
    }

    public List<ResourcePoint> getNearbyResources(double x, double y) {
        return gridIndex.queryNear(x, y);
    }

    public List<ResourcePoint> getAllPoints() {
        return pointList;
    }

}