package io.github.kedaya0209.roco.app.ui.component.setting;

import net.jcip.annotations.ThreadSafe;
import io.github.kedaya0209.roco.app.config.ConfigPersistence;
import javafx.scene.control.*;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * 设置配置管理器 — 负责配置字段的读写、控件注册、变更追踪和快照管理。
 * 不包含任何 UI 逻辑，仅管理数据与控制绑定。
 * <p>
 * 读写通过 {@link SettingDef#getter()}/{@link SettingDef#setter()} 回调，
 * 不依赖反射，Native Image 兼容。
 */
@Slf4j
@ThreadSafe
public class SettingConfigManager {

    private final Map<String, Control> controlMap = new LinkedHashMap<>();
    @Getter
    private boolean modified = false;
    /**
     * -- SETTER --
     * 设置修改状态变更回调（用于更新标题栏和按钮状态）
     */
    @Setter
    private Runnable onModifiedChanged;

    /**
     * -- SETTER --
     * 应用后钩子 — 全部配置写入并持久化后执行，
     */
    @Setter
    private Runnable postApplyHook;

    // ================================================================
    // 控件注册
    // ================================================================

    /**
     * 注册控件到管理器
     */
    public void registerControl(String key, Control control) {
        controlMap.put(key, control);
    }

    /**
     * 清空所有注册的控件（切换分类时调用）
     */
    public void clearControls() {
        controlMap.clear();
    }

    // ================================================================
    // 字段访问（通过 SettingDef getter/setter，无反射）
    // ================================================================

    /**
     * 通过 SettingDef getter 读取字段当前值
     */
    public Object readField(String key) {
        SettingDef def = SettingDefinitions.findDef(key);
        if (def == null || def.getter() == null) {
            log.warn("未找到配置字段的 getter: {}", key);
            return null;
        }
        return def.getter().get();
    }

    /**
     * 读取控件实时值（已注册则取控件实时值，否则回退到 getter）。
     * 对可编辑 Spinner 优先取编辑器文本，确保键盘输入也能实时反映。
     * 供 PlayerPreview 实时预览使用。
     */
    public Object getCurrentValue(String key) {
        Control control = controlMap.get(key);
        if (control == null) return readField(key);

        SettingDef def = SettingDefinitions.findDef(key);
        if (def == null) return readField(key);

        // 可编辑 Spinner：从编辑器文本解析，反映未提交的输入
        if (control instanceof Spinner<?> sp && sp.isEditable()) {
            try {
                String text = sp.getEditor().getText();
                if (text == null || text.isBlank()) return sp.getValue();
                return switch (def.type()) {
                    case INTEGER -> Integer.valueOf(text);
                    case LONG -> Long.valueOf(text);
                    case DOUBLE -> Double.valueOf(text);
                    default -> extractValue(control);
                };
            } catch (NumberFormatException e) {
                return sp.getValue();
            }
        }

        return convertValue(extractValue(control), def.type());
    }

    // ================================================================
    // 变更追踪
    // ================================================================

    /**
     * 通过 SettingDef setter 写入字段值
     */
    public void writeField(String key, Object value) {
        SettingDef def = SettingDefinitions.findDef(key);
        if (def == null || def.setter() == null) {
            log.warn("未找到配置字段的 setter: {}", key);
            return;
        }
        def.setter().accept(value);
    }

    /**
     * 将控件值从配置 getter 同步（用于 RoiPreview 拖拽后刷新 Spinner）
     */
    public void syncRoiControls(String prefix) {
        syncControl(prefix + "X");
        syncControl(prefix + "Y");
        syncControl(prefix + "W");
        syncControl(prefix + "H");
    }

    @SuppressWarnings("unchecked")
    private void syncControl(String key) {
        Control control = controlMap.get(key);
        if (control == null) return;
        SettingDef def = SettingDefinitions.findDef(key);
        if (def == null || def.getter() == null) return;
        Object value = def.getter().get();
        if (control instanceof Spinner<?> sp) {
            ((SpinnerValueFactory<Object>) sp.getValueFactory()).setValue(value);
        }
    }

    /**
     * 标记为已修改
     */
    public void markModified() {
        if (!modified) {
            modified = true;
            if (onModifiedChanged != null) {
                onModifiedChanged.run();
            }
        }
    }

    // ================================================================
    // 应用 / 类型转换
    // ================================================================

    /**
     * 清除修改标记
     */
    public void clearModified() {
        modified = false;
        if (onModifiedChanged != null) {
            onModifiedChanged.run();
        }
    }

    /**
     * 应用所有修改：读取控件值 → 通过 setter 写入 → 持久化 → 执行回调。
     *
     * @return 需要重启才能生效的字段标签列表
     */
    public List<String> applyChanges() {
        List<String> restartFields = new ArrayList<>();

        for (Map.Entry<String, Control> entry : controlMap.entrySet()) {
            String key = entry.getKey();
            SettingDef def = SettingDefinitions.findDef(key);
            if (def == null || def.setter() == null) continue;

            Control control = entry.getValue();
            Object rawValue = extractValue(control);
            Object typedValue = convertValue(rawValue, def.type());

            // 跳过值未变化的字段，避免弹出不必要的重启提示
            Object currentValue = readField(key);
            if (isUnchanged(currentValue, typedValue)) continue;

            def.setter().accept(typedValue);
            if (def.restartRequired()) {
                restartFields.add(def.label());
            }
        }

        // 持久化
        ConfigPersistence.save();

        // 执行回调
        for (Map.Entry<String, Control> entry : controlMap.entrySet()) {
            SettingDef def = SettingDefinitions.findDef(entry.getKey());
            if (def != null && def.onApply() != null) {
                def.onApply().run();
            }
        }

        // 更新快照
        clearModified();

        // 全局配置变更通知
        if (postApplyHook != null) {
            postApplyHook.run();
        }

        return restartFields;
    }

    /**
     * 比较当前值与控件值是否无实质变化（含浮点精度容差）
     */
    private static boolean isUnchanged(Object current, Object typed) {
        if (current == typed) return true;
        if (current == null || typed == null) return false;
        if (current instanceof Number n1 && typed instanceof Number n2) {
            return Math.abs(n1.doubleValue() - n2.doubleValue()) < 1e-9;
        }
        return current.equals(typed);
    }

    /**
     * 从控件中提取原始值
     */
    @SuppressWarnings({"rawtypes"})
    public Object extractValue(Control control) {
        if (control instanceof CheckBox cb) return cb.isSelected();
        if (control instanceof Spinner sp) return sp.getValue();
        if (control instanceof TextField tf) return tf.getText();
        if (control instanceof ComboBox cb) return cb.getValue();
        return null;
    }

    /**
     * 根据目标类型转换值
     */
    public Object convertValue(Object value, SettingType type) {
        if (type == SettingType.INTEGER) {
            if (value instanceof Number n) return n.intValue();
            try {
                return Integer.parseInt(value.toString());
            } catch (Exception e) {
                return 0;
            }
        }
        if (type == SettingType.LONG) {
            if (value instanceof Number n) return n.longValue();
            try {
                return Long.parseLong(value.toString());
            } catch (Exception e) {
                return 0L;
            }
        }
        if (type == SettingType.DOUBLE) {
            if (value instanceof Number n) return n.doubleValue();
            try {
                return Double.parseDouble(value.toString());
            } catch (Exception e) {
                return 0.0;
            }
        }
        if (type == SettingType.BOOLEAN) {
            return value instanceof Boolean b && b;
        }
        return value != null ? value.toString() : "";
    }

    // ================================================================
    // 快照管理
    // ================================================================

    /**
     * 保存当前所有配置值的快照（通过 getter，无反射）
     */
    public Map<String, Object> takeSnapshot() {
        Map<String, Object> snap = new HashMap<>();
        for (SettingCategory cat : SettingDefinitions.CATEGORIES) {
            for (SettingDef def : cat.fields()) {
                if (def.getter() != null) {
                    snap.put(def.key(), def.getter().get());
                }
            }
        }
        return snap;
    }

    /**
     * 从快照恢复所有配置值（通过 setter，无反射）
     */
    public void restoreSnapshot(Map<String, Object> snap) {
        for (SettingCategory cat : SettingDefinitions.CATEGORIES) {
            for (SettingDef def : cat.fields()) {
                if (def.setter() != null && snap.containsKey(def.key())) {
                    def.setter().accept(snap.get(def.key()));
                }
            }
        }
    }
}
