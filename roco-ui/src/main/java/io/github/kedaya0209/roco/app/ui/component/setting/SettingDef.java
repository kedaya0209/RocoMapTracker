package io.github.kedaya0209.roco.app.ui.component.setting;

import net.jcip.annotations.ThreadSafe;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 设置字段定义 — 描述一个配置项元数据。
 * <p>
 * @param key             字段键名
 * @param label           显示标签
 * @param type            字段类型
 * @param optionsSupplier COMBO 类型的选项提供者
 * @param onApply         应用时的回调
 * @param restartRequired 修改后是否需要重启生效
 * @param getter          读取当前值（替代反射）
 * @param setter          写入新值（替代反射）
 */
@ThreadSafe
public record SettingDef(String key, String label, SettingType type,
                         Supplier<String[]> optionsSupplier, Runnable onApply, boolean restartRequired,
                         Supplier<Object> getter, Consumer<Object> setter) {
    public SettingDef {
        optionsSupplier = optionsSupplier != null ? optionsSupplier : () -> new String[0];
    }
}
