package com.luoke.app.model;

import java.util.Objects;

/**
 * 结构化 OCR 结果
 */
public record ItemResult(String name, int count) {
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ItemResult that = (ItemResult) o;
        return count == that.count && Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, count);
    }
}