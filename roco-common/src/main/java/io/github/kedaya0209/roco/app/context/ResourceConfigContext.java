package io.github.kedaya0209.roco.app.context;

import io.github.kedaya0209.roco.app.config.PathConfig;
import io.github.kedaya0209.roco.app.config.DownloadConfig;
import io.github.kedaya0209.roco.app.utils.ResourceUtils;
import lombok.Getter;
import net.jcip.annotations.ThreadSafe;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 资源上下文：支持多套资源路径切换（MultiMap 模式）。
 */
@ThreadSafe
public class ResourceConfigContext {

    /**
     * 获取当前资源 profile。
     * 实时判断 DownloadConfig.INTERNAL_RESOURCE，而非类加载时快照。
     */
    public static ResourceProfile getCurrentProfile() {
        return DownloadConfig.INTERNAL_RESOURCE
                ? ResourceProfile.INTERNAL
                : ResourceProfile.WIKI;
    }

    /**
     * SIFT 缓存文件命名基址。
     * 按资源来源（INTERNAL/EXTERNAL）隔离，切换资源模式时重新生成缓存。
     * C++ 侧根据 metadata 逐子图创建缓存，此处仅提供文件名前缀。
     */
    public static String getSiftMap() {
        return PathConfig.MULTI_MAP_BASE + "." + getCurrentProfile().name();
    }

    public static String getMultiMapMetadata() {
        return PathConfig.MULTI_MAP_METADATA;
    }

    /**
     * 检查 MultiMap 资源是否可用。
     */
    public static boolean isMultiMapActive() {
        try {
            ResourceUtils.getResourceStream(PathConfig.MULTI_MAP_METADATA).close();
            return true;
        } catch (Exception e) {
            return false;
        }
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
    @ThreadSafe
    public enum ResourceProfile {
        INTERNAL(
                "内置资源",
                PathConfig.PLAYER_ICON_PATH,
                PathConfig.INTERNAL_RESOURCE_POINT_CONFIG_PATH,
                PathConfig.INTERNAL_PATHS
        ),
        WIKI(
                "WIKI资源",
                PathConfig.PLAYER_ICON_PATH,
                PathConfig.RESOURCE_POINT_CONFIG_PATH,
                PathConfig.PATHS
        );

        final String tag;
        final String playerIcon;
        final String pointConfig;
        final String paths;

        ResourceProfile(String tag, String icon, String point, String paths) {
            this.tag = tag;
            this.playerIcon = icon;
            this.pointConfig = point;
            this.paths = paths;
        }
    }
}
