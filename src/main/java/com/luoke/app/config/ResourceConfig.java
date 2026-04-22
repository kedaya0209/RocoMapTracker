package com.luoke.app.config;

import lombok.Data;

@Data
public class ResourceConfig {
    /**
     * 类型 （可以对应菜单按钮） 取自 MapCategoryItem
     */
    private String type;
    /**
     * 资源类型 取自 MapCategoryItem
     */
    private Integer markType;
    /**
     * 资源名称 取自 MapCategoryItem
     */
    private String markTypeName;
    /**
     * 图标 取自 MapCategoryItem
     */
    private String icon;
    /**
     * 经度 取自 Point
     */
    private Double lat;
    /**
     * 纬度 取自 Point
     */
    private Double lng;
    /**
     * 图层 取自 Point
     */
    private String layer;
    /**
     * 默认值4
     */
    private Integer zoom;
}
