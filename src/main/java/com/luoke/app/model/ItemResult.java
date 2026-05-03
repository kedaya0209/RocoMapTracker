package com.luoke.app.model;

import java.util.Objects;

public record ItemResult(String name, int count) {

    @Override
    public boolean equals(Object o) {
        // 快速路径：对象引用相同
        if (this == o) return true;

        // 类型检查：必须是ItemResult类
        if (o == null || getClass() != o.getClass()) return false;

        // 类型转换后进行字段比较
        ItemResult that = (ItemResult) o;

        // 先比较count（基本类型比较更快），再比较name（可能为null）
        return count == that.count && Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        // Objects.hash内部使用Arrays.hashCode，保证组合哈希的一致性
        return Objects.hash(name, count);
    }
}
