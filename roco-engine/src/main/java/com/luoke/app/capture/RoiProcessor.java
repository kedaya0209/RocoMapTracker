package com.luoke.app.capture;

public interface RoiProcessor {

    /**
     * 需要的图片类型 (默认灰度)
     */
    default ImageType requiredImageType() {
        return ImageType.GRAY;
    }

    /**
     * 声明关心的 ROI 索引 (-1 代表关心所有)
     */
    int targetRoiIndex();

    /**
     * 具体的处理逻辑
     * data 格式由 requiredImageType() 决定: GRAY → w*h bytes, BGRA → w*h*4 bytes
     */
    void onProcess(byte[] data, int width, int height);

    enum ImageType {GRAY, BGRA}

    ROIData getRoi();
}