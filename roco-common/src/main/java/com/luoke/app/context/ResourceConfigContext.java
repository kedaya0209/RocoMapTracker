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

    @Getter
    private static ResourceProfile currentProfile = AppConfig.INTERNAL_RESOURCE
            ? ResourceProfile.INTERNAL
            : ResourceProfile.EXTERNAL;

    /**
     * 切换资源套件（如：用于运行时切换皮肤或地图包）
     */
    public static void switchProfile(ResourceProfile profile) {
        currentProfile = profile;
    }

    public static String getSiftMap() {
        return currentProfile.siftMap;
    }

    public static String getShowMap() {
        return currentProfile.showMap;
    }

    public static String getTilesDir() {
        return currentProfile.showMap.substring(0, currentProfile.showMap.lastIndexOf('.')) + "_tiles";
    }

    public static String getPlayerIcon() {
        return currentProfile.playerIcon;
    }

    public static String getPointResource() {
        return currentProfile.pointConfig;
    }

    public static String getPaths() {
        return currentProfile.paths;
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