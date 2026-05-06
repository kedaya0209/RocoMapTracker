package com.luoke.app.macher.map;

import com.luoke.app.config.AppConfig;
import com.luoke.app.context.ResourceConfigContext;
import com.luoke.app.macher.map.sift.DescriptorTransform;
import com.luoke.app.macher.map.sift.SiftMapMatcher;
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

    private static volatile SwitchMapMatcher instance;

    private static final Set<String> set = new LinkedHashSet<>();

    private volatile MapMatcher mapMatcher;

    private SwitchMapMatcher() {
        set.add(SIFT);
        set.add(SIFT_PCA);
        set.add(SIFT_ULTRA);
        set.add(SIFT_PCA_ULTRA);
        this.mapMatcher = createMatcher(AppConfig.MAP_MATCHAER);
    }

    public Set<String> getMatchers() {
        return set;
    }

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

    private MapMatcher createMatcher(String type) {
        DescriptorTransform.Variant variant = switch (type) {
            case SIFT -> DescriptorTransform.Variant.STANDARD;
            case SIFT_PCA -> DescriptorTransform.Variant.PCA;
            case SIFT_ULTRA -> DescriptorTransform.Variant.ULTRA;
            default -> DescriptorTransform.Variant.PCA_ULTRA;
        };
        return SiftMapMatcher.create(variant);
    }

    public synchronized void switchMapMatcher(String type) {
        if (Objects.equals(type, AppConfig.MAP_MATCHAER)) {
            return;
        }

        log.info("Switching MapMatcher from {} to {}", AppConfig.MAP_MATCHAER, type);

        MapMatcher oldMapMatcher = this.mapMatcher;
        MapMatcher nextMatcher = createMatcher(type);

        if (nextMatcher.init(ResourceConfigContext.getSiftMap())) {
            AppConfig.MAP_MATCHAER = type;
            this.mapMatcher = nextMatcher;

            if (oldMapMatcher != null && oldMapMatcher != nextMatcher) {
                oldMapMatcher.destroy();
            }
        } else {
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
        return mapMatcher.match(imageBytes, width, height);
    }

    @Override
    public double[][] match(byte[] imageBytes, int width, int height, Double hintX, Double hintY) {
        return mapMatcher.match(imageBytes, width, height, hintX, hintY);
    }

    @Override
    public void destroy() {
        if (mapMatcher != null) {
            mapMatcher.destroy();
        }
    }
}
