package com.luoke.app.capture;

import com.sun.jna.Structure;

import java.util.List;

@Structure.FieldOrder({"x", "y", "w", "h"})
public class ROIData extends Structure {
    public int x;
    public int y;
    public int w;
    public int h;

    public ROIData() {
        super();
    }

    public ROIData(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
    }

    public static ROIData[] createContiguousArray(List<ROIData> list) {
        if (list == null || list.isEmpty()) return null;

        // 申请连续内存
        ROIData[] array = (ROIData[]) list.get(0).toArray(list.size());

        // 从索引 1 开始拷贝数据（索引 0 已经是第一个对象了）
        for (int i = 1; i < list.size(); i++) {
            ROIData source = list.get(i);
            array[i].x = source.x;
            array[i].y = source.y;
            array[i].w = source.w;
            array[i].h = source.h;
        }
        return array;
    }

    public static class ByReference extends ROIData implements Structure.ByReference {
        public ByReference(int x, int y, int w, int h) {
            super(x, y, w, h);
        }
    }
}