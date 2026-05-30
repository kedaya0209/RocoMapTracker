package io.github.kedaya0209.roco.app.context;

import net.jcip.annotations.ThreadSafe;
import com.fasterxml.jackson.core.type.TypeReference;
import io.github.kedaya0209.roco.app.map.model.RoutePath;
import io.github.kedaya0209.roco.app.utils.JsonUtils;
import io.github.kedaya0209.roco.app.utils.ResourceUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * 路线持久化服务 — 将路线数据读写到外部 JSON 文件。
 * 无状态设计，与 PathContext 的状态管理分离。
 * 遵循单一职责原则：仅负责文件 I/O，不管理路线状态。
 */
@ThreadSafe
@Slf4j
public class RoutePersistenceService {

    private static final TypeReference<List<RoutePath>> ROUTE_LIST_TYPE =
            new TypeReference<List<RoutePath>>() {
            };

    /**
     * 将路线列表保存到默认路径
     */
    public boolean save(List<RoutePath> routes) {
        try {
            File file = ResourceUtils.getExternalFile(ResourceConfigContext.getPaths());
            JsonUtils.getMapper().writerWithDefaultPrettyPrinter().writeValue(file, routes);
            log.info("路线已持久化 ({} 条)", routes.size());
            return true;
        } catch (IOException e) {
            log.error("保存路线失败", e);
            return false;
        }
    }

    /**
     * 从指定文件加载路线列表
     */
    public List<RoutePath> load(File file) {
        try {
            return JsonUtils.getMapper().readValue(file, ROUTE_LIST_TYPE);
        } catch (IOException e) {
            log.error("解析路线文件失败", e);
            return Collections.emptyList();
        }
    }

    /**
     * 导出单条路线到指定文件
     */
    public boolean export(RoutePath route, File file) {
        try {
            JsonUtils.getMapper().writeValue(file, List.of(route));
            return true;
        } catch (IOException e) {
            log.error("导出路线失败", e);
            return false;
        }
    }

    /**
     * 从默认路径加载已保存的路线
     */
    public List<RoutePath> loadDefault() {
        try {
            File file = ResourceUtils.getExternalFile(ResourceConfigContext.getPaths());
            if (file.exists()) {
                List<RoutePath> loaded = JsonUtils.getMapper().readValue(file, ROUTE_LIST_TYPE);
                log.info("已加载 {} 条路线", loaded.size());
                return loaded;
            }
        } catch (IOException e) {
            log.warn("加载默认路线失败", e);
        }
        return List.of();
    }
}
