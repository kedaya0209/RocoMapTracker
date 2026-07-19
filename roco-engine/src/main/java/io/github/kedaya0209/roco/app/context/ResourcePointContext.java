package io.github.kedaya0209.roco.app.context;

import net.jcip.annotations.ThreadSafe;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kedaya0209.roco.app.config.PathConfig;
import io.github.kedaya0209.roco.app.hook.AppEvents;
import io.github.kedaya0209.roco.app.hook.event.NotificationType;
import io.github.kedaya0209.roco.app.hook.event.ResourcePointChangedEvent;
import io.github.kedaya0209.roco.app.hook.event.StatusEvent;
import io.github.kedaya0209.roco.app.map.model.Point;
import io.github.kedaya0209.roco.app.map.model.ResourceConfig;
import io.github.kedaya0209.roco.app.map.model.ResourcePoint;
import io.github.kedaya0209.roco.app.utils.JsonUtils;
import io.github.kedaya0209.roco.app.utils.ResourceUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

@ThreadSafe
@Slf4j
public class ResourcePointContext {
    private static final ResourcePointContext INSTANCE = new ResourcePointContext();
    private final ObjectMapper objectMapper = JsonUtils.getMapper();

    private final List<ResourceConfig> rawResourceList = new ArrayList<>();
    private final List<ResourcePoint> pointList = new ArrayList<>();
    private final Map<String, ResourceConfig> typeTemplates = new HashMap<>(); // 模板缓存
    private final ResourcePointGridIndex gridIndex = new ResourcePointGridIndex();
    private final Set<String> collectSet = new LinkedHashSet<>();

    private ResourcePointContext() {
    }

    public static ResourcePointContext getInstance() {
        return INSTANCE;
    }

    public void loadAndInit() {
        try (InputStream inputStream = ResourceUtils.getResourceStream(ResourceConfigContext.getPointResource())) {
            List<ResourceConfig> configs = objectMapper.readValue(inputStream, new TypeReference<>() {});

            rawResourceList.clear();
            rawResourceList.addAll(configs);
            preprocessPoints();
            //加载所有可收集资源
            collectSet.addAll(ResourceUtils.readResourceLines(PathConfig.RESOURCE_COLLECT_SET));
            log.info("资源点位加载完成，总数：{}", pointList.size());
        } catch (IOException e) {
            log.error("可收集资源加载失败", e);
        }
    }

    /**
     * 预处理点位：计算屏幕坐标、构建网格索引、刷新模板缓存
     */
    private void preprocessPoints() {
        pointList.clear();
        typeTemplates.clear();
        MapCoordinateManager coordManager = MapCoordinateManager.getInstance();

        for (ResourceConfig config : rawResourceList) {
            Point screenPos = coordManager.toScreen(config.getLng(), config.getLat());
            ResourcePoint point = new ResourcePoint(config, screenPos);
            pointList.add(point);
            // 模板缓存：以每类点位的第一个配置作为新增点位时的参考（图标、层级等）
            typeTemplates.putIfAbsent(config.getMarkTypeName(), config);
        }
        gridIndex.buildIndex(pointList);
        AppEvents.publish(ResourcePointChangedEvent.class, ResourcePointChangedEvent.INSTANCE);
    }

    /**
     * 新增点位
     */
    public void savePoint(final String markTypeName, double canvasX, double canvasY) {
        try {
            ResourceConfig template = typeTemplates.get(markTypeName);
            if (template == null) throw new RuntimeException("找不到原始模板点位: " + markTypeName);

            // 转换坐标
            Point rawPoint = MapCoordinateManager.getInstance().fromScreen(canvasX, canvasY);

            ResourceConfig nc = new ResourceConfig();
            nc.setMarkTypeName(markTypeName);
            nc.setMarkType(template.getMarkType());
            nc.setType(template.getType());
            nc.setLayer(template.getLayer());
            nc.setLng(rawPoint.getX());
            nc.setLat(rawPoint.getY());
            nc.setIcon(template.getIcon());

            rawResourceList.add(nc);
            saveToFile();
            preprocessPoints(); // 刷新内存

            AppEvents.publish(StatusEvent.class,
                    new StatusEvent("新增点位成功", NotificationType.SUCCESS));
        } catch (IOException e) {
            log.error("新增点位失败", e);
            AppEvents.publish(StatusEvent.class,
                    new StatusEvent("新增点位失败", NotificationType.ERROR));
        }
    }

    /**
     * 删除点位（补回逻辑）
     */
    public void deletePoint(ResourcePoint point) {
        try {
            // 从原始数据列表中移除（匹配 config 对象）
            boolean removed = rawResourceList.remove(point.getConfig());

            if (removed) {
                saveToFile();
                preprocessPoints(); // 刷新内存及网格索引
                log.info("删除点位成功: {}", point.getConfig().getMarkTypeName());
                AppEvents.publish(StatusEvent.class,
                        new StatusEvent("点位移除成功", NotificationType.SUCCESS));
            }
        } catch (IOException e) {
            log.error("删除点位失败", e);
            AppEvents.publish(StatusEvent.class,
                    new StatusEvent("点位移除失败", NotificationType.ERROR));
        }
    }

    private void saveToFile() throws IOException {
        File target = ResourceUtils.getExternalFile(PathConfig.RESOURCE_POINT_CONFIG_PATH);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(target, rawResourceList);
    }

    public boolean isCollect(String... names) {
        for (String name : names) {
            if (!collectSet.contains(name)) return false;
        }
        return true;
    }

    public List<ResourcePoint> getAllPoints() {
        return pointList;
    }

    public List<ResourcePoint> getNearbyResources(double x, double y) {
        return gridIndex.queryNear(x, y);
    }

    public List<ResourcePoint> getPointsInRect(double minX, double minY, double maxX, double maxY) {
        return gridIndex.queryRect(minX, minY, maxX, maxY);
    }

    /**
     * 获取所有不重复的资源类别（如 宝箱、材料、眠枭之星），按名称排序。
     */
    public List<String> getResourceTypes() {
        return rawResourceList.stream()
                .map(ResourceConfig::getType)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * 获取指定类别下所有不重复的资源名称（markTypeName），按名称排序。
     */
    public List<String> getResourceNamesByType(String type) {
        return rawResourceList.stream()
                .filter(c -> type.equals(c.getType()))
                .map(ResourceConfig::getMarkTypeName)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * 获取指定资源名称对应的图标文件名（取第一个匹配的 config）。
     */
    public String getIconForName(String markTypeName) {
        return rawResourceList.stream()
                .filter(c -> markTypeName.equals(c.getMarkTypeName()))
                .map(ResourceConfig::getIcon)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }
}