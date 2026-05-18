package com.luoke.app.ui.component;

import com.luoke.app.config.AppConfig;
import javafx.scene.control.*;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.util.*;

/**
 * 设置配置管理器 — 负责配置字段的反射读写、控件注册、变更追踪和快照管理。
 * 不包含任何 UI 逻辑，仅管理数据与控制绑定。
 */
@Slf4j
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
     * 用于通知各组件重新读取 AppConfig 字段刷新 UI/行为。
     */
    @Setter
    private Runnable postApplyHook;

    // ================================================================
    // 控件注册
    // ================================================================

    /**
     * 将值适配为目标字段类型
     */
    private static Object adaptType(Object value, Class<?> targetType) {
        if (targetType == float.class && value instanceof Number n) return n.floatValue();
        if (targetType == double.class && value instanceof Number n) return n.doubleValue();
        if (targetType == int.class && value instanceof Number n) return n.intValue();
        if (targetType == long.class && value instanceof Number n) return n.longValue();
        if (targetType == boolean.class && value instanceof Boolean b) return b;
        return value;
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

    // ================================================================
    // 反射字段访问
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

    /**
     * 读取 AppConfig 静态字段值
     */
    public Object readField(String key) {
        try {
            Field field = AppConfig.class.getDeclaredField(key);
            field.setAccessible(true);
            return field.get(null);
        } catch (Exception e) {
            log.warn("读取配置字段失败: {}", key, e);
            return null;
        }
    }

    /**
     * 读取控件实时值（已注册则取控件实时值，否则回退到 AppConfig）。
     * 对可编辑 Spinner 优先取编辑器文本，确保键盘输入也能实时反映。
     * 供 PlayerPreview 实时预览使用。
     */
    public Object getCurrentValue(String key) {
        Control control = controlMap.get(key);
        if (control == null) return readField(key);

        SettingDef def = SettingDefinitions.findDef(key);
        if (def == null) return readField(key);

        // 可编辑 Spinner：从编辑器文本解析，反映未提交的输入
        if (control instanceof Spinner sp && sp.isEditable()) {
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
                // 解析失败（如输入中间态 "1."），回退到已提交值
                return sp.getValue();
            }
        }

        return convertValue(extractValue(control), def.type());
    }

    // ================================================================
    // 变更追踪
    // ================================================================

    /**
     * 写入 AppConfig 静态字段，自动处理类型适配（如 Double→float）
     */
    public void writeField(String key, Object value) {
        try {
            Field field = AppConfig.class.getDeclaredField(key);
            field.setAccessible(true);
            Class<?> fieldType = field.getType();
            Object adapted = adaptType(value, fieldType);
            field.set(null, adapted);
        } catch (Exception e) {
            log.warn("写入配置字段失败: {} = {}", key, value, e);
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
     * 应用所有修改：读取控件值 → 反射写入 AppConfig → 持久化 → 执行回调。
     *
     * @return 需要重启才能生效的字段标签列表
     */
    public List<String> applyChanges() {
        List<String> restartFields = new ArrayList<>();

        for (Map.Entry<String, Control> entry : controlMap.entrySet()) {
            String key = entry.getKey();
            SettingDef def = SettingDefinitions.findDef(key);
            if (def == null) continue;

            Control control = entry.getValue();
            Object rawValue = extractValue(control);
            Object typedValue = convertValue(rawValue, def.type());

            // 跳过值未变化的字段，避免弹出不必要的重启提示
            Object currentValue = readField(key);
            if (isUnchanged(currentValue, typedValue)) continue;

            writeField(key, typedValue);
            if (def.restartRequired()) {
                restartFields.add(def.label());
            }
        }

        // 持久化
        AppConfig.save();

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
     * 保存当前所有配置值的快照
     */
    public Map<String, Object> takeSnapshot() {
        Map<String, Object> snap = new HashMap<>();
        for (SettingCategory cat : SettingDefinitions.CATEGORIES) {
            for (SettingDef def : cat.fields()) {
                snap.put(def.key(), readField(def.key()));
            }
        }
        return snap;
    }

    /**
     * 从快照恢复所有配置值
     */
    public void restoreSnapshot(Map<String, Object> snap) {
        for (Map.Entry<String, Object> entry : snap.entrySet()) {
            writeField(entry.getKey(), entry.getValue());
        }
    }
}
