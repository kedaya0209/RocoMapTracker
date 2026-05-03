package com.luoke.app.macher.map;

import com.luoke.app.config.AppConfig;
import com.luoke.app.context.ResourceContext;
import com.luoke.app.macher.map.sift.SiftMapMatcher;
import com.luoke.app.macher.map.sift.SiftPCAMapMatcher;
import com.luoke.app.macher.map.sift.SiftPCAUltraMapMatcher;
import com.luoke.app.macher.map.sift.SiftUltraMapMatcher;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

@Slf4j
public class SwitchMapMatcher implements MapMatcher {

    public static final String SIFT = "SIFT";
    public static final String SIFT_PCA = "SIFT-PCA";
    public static final String SIFT_PCA_ULTRA = "SIFT-PCA-ULTRA";
    public static final String SIFT_ULTRA = "SIFT-ULTRA";

    // 单例实例
    private static volatile SwitchMapMatcher instance;

    private static final Set<String> set = new LinkedHashSet<>();

    // 使用 volatile 保证在 switchMapMatcher 时，其他线程能立即看到引用的变化
    private volatile MapMatcher mapMatcher;

    // 私有构造函数
    private SwitchMapMatcher() {
        set.add(SIFT);
        set.add(SIFT_PCA);
        set.add(SIFT_ULTRA);
        set.add(SIFT_PCA_ULTRA);

        // 初始化默认匹配器
        this.mapMatcher = createMatcher(AppConfig.MAP_MATCHAER);
    }

    public Set<String> getMatchers() {
        return set;
    }

    // 获取单例的入口
    public static SwitchMapMatcher getInstance() {
        if (instance == null) {
            synchronized (SwitchMapMatcher.class) {
                if (instance == null) {
                    instance = new SwitchMapMatcher();
                }
            }
        }
        return instance;
    }

    /**
     * 内部工厂方法：根据类型创建匹配器实例
     */
    private MapMatcher createMatcher(String type) {
        return switch (type) {
            case SIFT -> SiftMapMatcher.getInstance();
            case SIFT_PCA -> SiftPCAMapMatcher.getInstance();
            case SIFT_ULTRA -> SiftUltraMapMatcher.getInstance();
            case SIFT_PCA_ULTRA -> SiftPCAUltraMapMatcher.getInstance();
            default -> SiftUltraMapMatcher.getInstance();
        };
    }

    public synchronized void switchMapMatcher(String type) {
        if (Objects.equals(type, AppConfig.MAP_MATCHAER)) {
            return;
        }

        log.info("Switching MapMatcher from {} to {}", AppConfig.MAP_MATCHAER, type);

        MapMatcher oldMapMatcher = this.mapMatcher;
        MapMatcher nextMatcher = createMatcher(type);

        // 尝试初始化新匹配器
        if (nextMatcher.init(ResourceContext.getSiftMap())) {
            AppConfig.MAP_MATCHAER = type;
            this.mapMatcher = nextMatcher; // 引用替换

            // 只有切换成功才销毁旧的，防止初始化失败导致无匹配器可用
            if (oldMapMatcher != null && oldMapMatcher != nextMatcher) {
                oldMapMatcher.destroy();
            }
        } else {
            // 初始化失败的处理逻辑
            if (nextMatcher != oldMapMatcher) {
                nextMatcher.destroy();
            }
            log.error("Failed to initialize new MapMatcher: {}", type);
        }
    }

    @Override
    public boolean init(String largeMapPath) {
        return mapMatcher.init(largeMapPath);
    }

    @Override
    public double[][] match(byte[] imageBytes, int width, int height) {
        // 由于 mapMatcher 是 volatile，这里可以保证获取到的是当前最新的匹配器
        return mapMatcher.match(imageBytes, width, height);
    }

    @Override
    public void destroy() {
        if (mapMatcher != null) {
            mapMatcher.destroy();
        }
    }
}