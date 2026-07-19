package io.github.kedaya0209.roco.app.ui.state;

import net.jcip.annotations.NotThreadSafe;
import io.github.kedaya0209.roco.app.config.SiftConfig;
import io.github.kedaya0209.roco.app.config.ViewConfig;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * 应用级运行时状态 — JavaFX Property，可观测。
 * <p>
 * 收纳不应放在 Config 类（设计为持久化层）的运行时字段：
 * <ul>
 *   <li>{@link #materialCollection} — 物资采集面板显示</li>
 *   <li>{@link #matchingEnabled} — SIFT 匹配开关</li>
 *   <li>{@link #ghostMode} — 幽灵模式（纯运行时，不持久化）</li>
 *   <li>{@link #resourceFilters} — 资源筛选状态（动态，默认全部可见）</li>
 * </ul>
 * Property 变更的副作用（Config 写入、EventBus 通知）由 Command 的 handler 统一处理，
 * 不再在 setter 中直接写 Config 字段。
 */
@NotThreadSafe
public class AppState {

    private static final AppState INSTANCE = new AppState();

    /** 物资采集面板显示状态 */
    private final SimpleBooleanProperty materialCollection =
            new SimpleBooleanProperty(ViewConfig.MATERIAL_COLLECTION);
    /** SIFT 匹配启用状态 */
    private final SimpleBooleanProperty matchingEnabled =
            new SimpleBooleanProperty(SiftConfig.SIFT_MATCHING_ENABLED);
    /** 幽灵模式（纯运行时，不持久化） */
    private final SimpleBooleanProperty ghostMode = new SimpleBooleanProperty(false);
    /** 资源筛选状态：markTypeName → BooleanProperty（默认 true=可见） */
    private final Map<String, SimpleBooleanProperty> resourceFilters = new ConcurrentHashMap<>();

    public static AppState getInstance() {
        return INSTANCE;
    }

    // ==== materialCollection ====

    public BooleanProperty materialCollectionProperty() {
        return materialCollection;
    }

    public boolean isMaterialCollection() {
        return materialCollection.get();
    }

    public void setMaterialCollection(boolean v) {
        materialCollection.set(v);
    }

    // ==== matchingEnabled ====

    public BooleanProperty matchingEnabledProperty() {
        return matchingEnabled;
    }

    public boolean isMatchingEnabled() {
        return matchingEnabled.get();
    }

    public void setMatchingEnabled(boolean v) {
        matchingEnabled.set(v);
    }

    // ==== ghostMode ====

    public BooleanProperty ghostModeProperty() {
        return ghostMode;
    }

    public boolean isGhostMode() {
        return ghostMode.get();
    }

    public void setGhostMode(boolean v) {
        ghostMode.set(v);
    }

    // ==== resourceFilters ====

    /**
     * 获取指定资源名称的筛选 Property，不存在时自动创建（默认 true=可见）。
     */
    public BooleanProperty getResourceFilter(String name) {
        return resourceFilters.computeIfAbsent(name, _ -> new SimpleBooleanProperty(true));
    }

    /**
     * 切换指定资源名称的可见性。
     */
    public void toggleResourceFilter(String name) {
        SimpleBooleanProperty prop = resourceFilters.get(name);
        if (prop != null) {
            prop.set(!prop.get());
        }
    }

    /**
     * 从 Config 静态字段重新加载所有值，在 ConfigPersistence.load() 后调用。
     * ghostMode 为纯运行时状态，不从 Config 恢复。
     */
    public void reloadFromConfig() {
        materialCollection.set(ViewConfig.MATERIAL_COLLECTION);
        matchingEnabled.set(SiftConfig.SIFT_MATCHING_ENABLED);
    }
}
