package com.luoke.app.ui.component;

import java.util.function.Supplier;

/**
 * 设置字段定义 — 描述一个配置项元数据。
 *
 * @param key             AppConfig 字段名
 * @param label           显示标签
 * @param type            字段类型
 * @param optionsSupplier COMBO 类型的选项提供者
 * @param onApply         应用时的回调
 * @param restartRequired 修改后是否需要重启生效
 */
public record SettingDef(String key, String label, SettingType type,
                         Supplier<String[]> optionsSupplier, Runnable onApply, boolean restartRequired) {
    public SettingDef {
        optionsSupplier = optionsSupplier != null ? optionsSupplier : () -> new String[0];
    }
}
