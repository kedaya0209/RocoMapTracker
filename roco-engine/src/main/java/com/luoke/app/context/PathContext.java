package com.luoke.app.context;

import com.fasterxml.jackson.core.type.TypeReference;
import com.luoke.app.map.model.RoutePath;
import com.luoke.app.utils.JsonUtils;
import com.luoke.app.utils.ResourceUtils;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.List;

@Slf4j
public class PathContext {
    private static final PathContext INSTANCE = new PathContext();
    @Getter
    private final ObservableList<RoutePath> savedRoutes = FXCollections.observableArrayList();
    @Getter
    @Setter
    private Mode currentMode = Mode.VIEW;
    @Getter
    @Setter
    private RoutePath activeRoute;
    @Getter
    @Setter
    private double mouseLogicX;
    @Getter
    @Setter
    private double mouseLogicY;
    private PathContext() {
        loadFromLocal();
    }

    public static PathContext getInstance() {
        return INSTANCE;
    }

    public void startNewRoute() {
        activeRoute = new RoutePath("未命名路线_" + (savedRoutes.size() + 1));
        currentMode = Mode.DRAWING;
        // 如果新路线还没在列表里，先加进去
        if (!savedRoutes.contains(activeRoute)) {
            savedRoutes.add(activeRoute);
        }
    }

    public void enterEditMode(RoutePath route) {
        this.activeRoute = route;
        this.currentMode = Mode.EDITING;
    }

    public void viewMode(RoutePath route) {
        this.activeRoute = route;
        this.currentMode = Mode.VIEW;
    }

    public boolean saveToLocal() {
        try {
            File file = ResourceUtils.getExternalFile(ResourceConfigContext.getPaths());
            JsonUtils.getMapper().writerWithDefaultPrettyPrinter().writeValue(file, savedRoutes);
            this.currentMode = Mode.VIEW; // 保存后恢复视角模式
            log.info("路线已持久化");
            return true;
        } catch (Exception e) {
            log.error("保存失败", e);
            return false;
        }
    }

    public List<RoutePath> resolve(File file) {
        try {
            return JsonUtils.getMapper().readValue(file,
                    new TypeReference<List<RoutePath>>() {
                    });
        } catch (Exception e) {
            log.error("解析失败，e:", e);
            return null;
        }
    }

    public boolean exportPaths(RoutePath selected, File file) {
        try {
            JsonUtils.getMapper().writeValue(file, List.of(selected));
            return true;
        } catch (Exception e) {
            log.error("导出失败", e);
            return false;
        }
    }

    private void loadFromLocal() {
        try {
            File file = ResourceUtils.getExternalFile(ResourceConfigContext.getPaths());
            if (file.exists()) {
                List<RoutePath> loaded = JsonUtils.getMapper().readValue(file,
                        new TypeReference<List<RoutePath>>() {
                        });
                savedRoutes.addAll(loaded);
            }
        } catch (Exception e) {
            log.warn("初始加载路线失败");
        }
    }

    public enum Mode {VIEW, DRAWING, EDITING}
}