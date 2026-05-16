package com.luoke.app.macher;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * SIFT 变体枚举 — 将 variantOrdinal / cacheSuffix / displayName 统一管理。
 * 消除 SiftMatchHandler 和 SwitchMapMatcher 中重复的字符串常量。
 */
public enum SiftVariant {

    STANDARD(0, "SIFT", ".v2.feat"),
    PCA(1, "SIFT-PCA", ".pca64.feat"),
    ULTRA(2, "SIFT-ULTRA", ".sift.ultra.feat"),
    PCA_ULTRA(3, "SIFT-PCA-ULTRA", ".pca64.ultra.feat");

    private final int ordinal;
    private final String displayName;
    private final String cacheSuffix;

    SiftVariant(int ordinal, String displayName, String cacheSuffix) {
        this.ordinal = ordinal;
        this.displayName = displayName;
        this.cacheSuffix = cacheSuffix;
    }

    public int variantOrdinal() {
        return ordinal;
    }

    public String displayName() {
        return displayName;
    }

    public String cacheSuffix() {
        return cacheSuffix;
    }

    /** 根据显示名称查找变体 */
    public static SiftVariant fromDisplayName(String name) {
        for (SiftVariant v : values()) {
            if (v.displayName.equals(name)) return v;
        }
        return PCA_ULTRA;
    }

    /** 根据序号查找变体 */
    public static SiftVariant fromOrdinal(int ordinal) {
        for (SiftVariant v : values()) {
            if (v.ordinal == ordinal) return v;
        }
        return PCA_ULTRA;
    }

    /** 返回 UI 下拉菜单显示的变体名称列表 */
    public static Set<String> getDisplayNames() {
        LinkedHashSet<String> set = new LinkedHashSet<>(4);
        for (SiftVariant v : values()) {
            set.add(v.displayName);
        }
        return set;
    }

    /** 将 UI 配置名映射为 C++ 侧序号，兼容 SwitchMapMatcher 的字符串常量 */
    public static int variantOrdinal(String displayName) {
        return fromDisplayName(displayName).ordinal;
    }
}
