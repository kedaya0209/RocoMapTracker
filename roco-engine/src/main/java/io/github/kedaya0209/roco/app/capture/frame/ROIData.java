package io.github.kedaya0209.roco.app.capture.frame;

import net.jcip.annotations.NotThreadSafe;
import java.util.List;

/**
 * ROI 数据 — 纯 POJO, 描述截图区域 (万分比坐标 0-10000)
 */
@NotThreadSafe
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
        if (list == null || list.isEmpty()) return new ROIData[0];
        return list.toArray(new ROIData[0]);
    }
}
