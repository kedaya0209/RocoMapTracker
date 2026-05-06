package com.luoke.app.map.model;

import lombok.Data;

@Data
public class ResourceConfig {
    /**
     * 资源类型
     */
    private String type;
    /**
     *
     */
    private Integer markType;
    /**
     * 资源名称
     */
    private String markTypeName;
    /**
     * 图片每次
     */
    private String icon;
    /**
     * 经纬度
     */
    private Double lat;
    private Double lng;
    /**
     * 层数
     */
    private String layer;
}
