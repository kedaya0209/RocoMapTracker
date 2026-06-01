package io.github.kedaya0209.roco.app.update.plugin;

import net.jcip.annotations.Immutable;

/**
 * 插件资产文件描述 - metadata.json 中 assets[] 的每一项. 
 */
@Immutable
public record PluginAsset(
        /** 在 Release 中的文件名 */
        String remoteName,

        /** 在插件目录中的相对路径 */
        String localPath,

        /** SHA-256 十六进制小写 */
        String sha256
) {}
