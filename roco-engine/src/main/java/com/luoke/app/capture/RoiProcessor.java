package com.luoke.app.capture;

public interface RoiProcessor {
    /**
     * 声明关心的 ROI 索引 (-1 代表关心所有)
     */
    int targetRoiIndex();

    /**
     * 具体的处理逻辑
     */
    void onProcess(byte[] data, int width, int height);

    ROIData getRoi();
}