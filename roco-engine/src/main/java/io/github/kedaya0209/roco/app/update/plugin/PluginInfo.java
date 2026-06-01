package io.github.kedaya0209.roco.app.update.plugin;

import net.jcip.annotations.Immutable;
import java.io.File;
import java.util.List;

/**
 * 插件元数据 - 由 metadata.json 解析而来. 
 */
@Immutable
public record PluginInfo(
        /** 插件标识(目录名) */
        String id,

        /** metadata.json 中的 name */
        String name,

        /** 显示名称 */
        String title,

        /** 本地版本号 */
        String version,

        /** 插件描述 */
        String description,

        /** 图标文件(相对于插件目录), 为空时用首字母占位 */
        String icon,

        /** 入口可执行文件(相对于插件目录) */
        String entry,

        /** 远程源配置 */
        PluginSource source,

        /** 资产文件列表 */
        List<PluginAsset> assets,

        /** 当前状态 */
        PluginStatus status,

        /** 插件目录绝对路径 */
        File pluginDir
) {}
