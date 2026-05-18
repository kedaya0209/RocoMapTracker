package com.luoke.app.capture;

import java.util.List;

/**
 * ROI 数据 — 纯 POJO, 描述截图区域 (万分比坐标 0-10000)
 */
public class ROIData {
    public int x;
    public int y;
    public int w;
    public int h;

    public ROIData() {
    }

    public ROIData(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
    }

    /**
     * List → 数组
     */
    public static ROIData[] createContiguousArray(List<ROIData> list) {
        if (list == null || list.isEmpty()) return null;
        return list.toArray(new ROIData[0]);
    }
}
