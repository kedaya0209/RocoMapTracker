package io.github.kedaya0209.roco.app.context;

import net.jcip.annotations.ThreadSafe;
import io.github.kedaya0209.roco.app.hook.AppEvents;
import io.github.kedaya0209.roco.app.hook.event.RouteListEvent;
import io.github.kedaya0209.roco.app.map.model.RoutePath;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

@ThreadSafe
@Slf4j
public class PathContext {
    private static final PathContext INSTANCE = new PathContext();
    private final RoutePersistenceService persistence = new RoutePersistenceService();

    @Getter
    private final ArrayList<RoutePath> savedRoutes = new ArrayList<>();

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
        savedRoutes.addAll(persistence.loadDefault());
    }

    public static PathContext getInstance() {
        return INSTANCE;
    }

    private void notifyChanged() {
        AppEvents.publish(RouteListEvent.class, RouteListEvent.INSTANCE);
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

    /**
     * 持久化路线列表到本地文件，委托给 RoutePersistenceService
     */
    public boolean saveToLocal() {
        boolean ok = persistence.save(savedRoutes);
        if (ok) this.currentMode = Mode.VIEW;
        return ok;
    }

    /**
     * 从文件解析路线列表，委托给 RoutePersistenceService
     */
    public List<RoutePath> resolve(File file) {
        return persistence.load(file);
    }

    /**
     * 导出单条路线到文件，委托给 RoutePersistenceService
     */
    public boolean exportPaths(RoutePath selected, File file) {
        return persistence.export(selected, file);
    }

    @ThreadSafe
    public enum Mode {VIEW, DRAWING, EDITING}
}