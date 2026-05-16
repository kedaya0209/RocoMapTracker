package com.luoke.app.context;

import com.luoke.app.map.model.RoutePath;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Slf4j
public class PathContext {
    private static final PathContext INSTANCE = new PathContext();
    private final RoutePersistenceService persistence = new RoutePersistenceService();

    @Getter
    private final ArrayList<RoutePath> savedRoutes = new ArrayList<>();
    private final CopyOnWriteArrayList<Consumer<List<RoutePath>>> changeListeners = new CopyOnWriteArrayList<>();

    @Getter @Setter
    private Mode currentMode = Mode.VIEW;
    @Getter @Setter
    private RoutePath activeRoute;
    @Getter @Setter
    private double mouseLogicX;
    @Getter @Setter
    private double mouseLogicY;

    private PathContext() {
        savedRoutes.addAll(persistence.loadDefault());
    }

    public static PathContext getInstance() {
        return INSTANCE;
    }

    /** 注册列表变化回调 (用于 UI 层绑定) */
    public void onChange(Consumer<List<RoutePath>> listener) {
        changeListeners.add(listener);
    }

    private void notifyChanged() {
        for (Consumer<List<RoutePath>> r : changeListeners) {
            r.accept(savedRoutes);
        }
    }

    public void startNewRoute() {
        activeRoute = new RoutePath("未命名路线_" + (savedRoutes.size() + 1));
        currentMode = Mode.DRAWING;
        if (!savedRoutes.contains(activeRoute)) {
            savedRoutes.add(activeRoute);
            notifyChanged();
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

    public void removeRoute(RoutePath route) {
        savedRoutes.remove(route);
        notifyChanged();
    }

    public void addRoutes(List<RoutePath> routes) {
        savedRoutes.addAll(routes);
        notifyChanged();
    }

    /** 持久化路线列表到本地文件，委托给 RoutePersistenceService */
    public boolean saveToLocal() {
        boolean ok = persistence.save(savedRoutes);
        if (ok) this.currentMode = Mode.VIEW;
        return ok;
    }

    /** 从文件解析路线列表，委托给 RoutePersistenceService */
    public List<RoutePath> resolve(File file) {
        return persistence.load(file);
    }

    /** 导出单条路线到文件，委托给 RoutePersistenceService */
    public boolean exportPaths(RoutePath selected, File file) {
        return persistence.export(selected, file);
    }

    public enum Mode {VIEW, DRAWING, EDITING}
}