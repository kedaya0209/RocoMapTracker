package com.luoke.app.map.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * 地图配置数据传输对象
 * 封装地图的核心配置信息，包括缩放级别、中心点、边界范围、图层配置等
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MapConfig {
    /**
     * 地图的初始缩放级别
     */
    private int zoom;

    /**
     * 地图的最大缩放级别
     */
    private int maxZoom;

    /**
     * 地图的最小缩放级别
     */
    private int minZoom;

    /**
     * 是否显示图层控制组件
     */
    private boolean layerControl;

    /**
     * 地图的初始中心点坐标 [经度, 纬度]
     */
    private List<Double> center;

    /**
     * 地图的最大边界范围 [[经度, 纬度], [经度, 纬度]]
     */
    private List<List<Integer>> maxBounds;

    /**
     * 地图Logo的图片URL或路径
     */
    private String logo;

    /**
     * 地图标记点的阴影图片URL或路径
     */
    private String pointShadow;

    /**
     * 地图背景图片URL或路径
     */
    private String mapBG;

    /**
     * 数据文件的前缀路径
     */
    private String dataPrefix;

    /**
     * 地图数据文件名列表
     */
    private List<String> dataList;

    /**
     * 地图图层配置列表
     */
    private List<MapLayer> mapLayers;
}
