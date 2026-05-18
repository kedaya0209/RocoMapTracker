package com.luoke.app.context;

import com.luoke.app.config.AppConfig;
import lombok.Getter;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 资源上下文：支持多套资源路径切换
 */
public class ResourceConfigContext {

    /**
     * 获取当前资源 profile。
     * 实时判断 AppConfig.INTERNAL_RESOURCE，而非类加载时快照。
     */
    public static ResourceProfile getCurrentProfile() {
        return AppConfig.INTERNAL_RESOURCE
                ? ResourceProfile.INTERNAL
                : ResourceProfile.EXTERNAL;
    }

    public static String getSiftMap() {
        return getCurrentProfile().siftMap;
    }

    public static String getShowMap() {
        return getCurrentProfile().showMap;
    }

    public static String getTilesDir() {
        ResourceProfile p = getCurrentProfile();
        return p.showMap.substring(0, p.showMap.lastIndexOf('.')) + "_tiles";
    }

    public static String getPlayerIcon() {
        return getCurrentProfile().playerIcon;
    }

    public static String getPointResource() {
        return getCurrentProfile().pointConfig;
    }

    public static String getPaths() {
        return getCurrentProfile().paths;
    }

    public static Set<String> getTags() {
        return Arrays.stream(ResourceProfile.values()).map(ResourceProfile::getTag).collect(Collectors.toSet());
    }

    /**
     * 定义不同的资源套件
     */
    @Getter
    public enum ResourceProfile {
        INTERNAL(
                "内置资源",
                AppConfig.SIFT_MAP,
                AppConfig.SHOW_MAP,
                AppConfig.PLAYER_ICON_PATH,
                AppConfig.INTERNAL_RESOURCE_POINT_CONFIG_PATH,
                AppConfig.INTERNAL_PATHS
        ),
        EXTERNAL(
                "WIKI资源",
                AppConfig.MAP_RESOURCE_PATH,
                AppConfig.MAP_RESOURCE_PATH,
                AppConfig.PLAYER_ICON_PATH,
                AppConfig.RESOURCE_POINT_CONFIG_PATH,
                AppConfig.PATHS
        );

        final String tag;
        final String siftMap;
        final String showMap;
        final String playerIcon;
        final String pointConfig;
        final String paths;

        ResourceProfile(String tag, String sift, String show, String icon, String point, String paths) {
            this.tag = tag;
            this.siftMap = sift;
            this.showMap = show;
            this.playerIcon = icon;
            this.pointConfig = point;
            this.paths = paths;
        }
    }
}