package io.github.kedaya0209.roco.app.update.plugin;

import net.jcip.annotations.Immutable;
import java.util.List;

/**
 * 远程更新版本信息 - 由 GitHub API 解析而来. 
 */
@Immutable
public record PluginUpdateInfo(
        /** 插件标识 */
        String pluginId,

        /** 远程版本号(去除 v 前缀) */
        String version,

        /** GitHub Release tag */
        String tagName,

        /** 插件 zip 包下载 URL */
        String zipDownloadUrl,

        /** Release 说明 */
        String releaseNotes,

        /** 远程资产文件列表(已包含 checksum) */
        List<PluginAsset> remoteAssets
) {}
