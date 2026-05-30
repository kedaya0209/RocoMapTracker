package io.github.kedaya0209.roco.app.update.plugin;

import net.jcip.annotations.Immutable;

/**
 * 插件远程源配置 - metadata.json 中 source 字段. 
 */
@Immutable
public record PluginSource(
        /** 源类型, 如 "github-release" */
        String type,

        /** GitHub 仓库 "owner/repo" */
        String repo
) {

    /** GitHub API 地址 */
    public String apiUrl() {
        return "https://api.github.com/repos/" + repo + "/releases/latest";
    }
}
