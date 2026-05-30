package io.github.kedaya0209.roco.app.update.plugin;

/**
 * 插件状态枚举. 
 */
public enum PluginStatus {

    /** 所有文件校验通过 */
    NORMAL,

    /** 用户已禁用 */
    DISABLED,

    /** 文件校验失败或损坏 */
    DAMAGED,

    /** 远程有更新版本 */
    HAS_UPDATE,

    /** metadata.json 缺失或无法解析 */
    UNKNOWN
}
