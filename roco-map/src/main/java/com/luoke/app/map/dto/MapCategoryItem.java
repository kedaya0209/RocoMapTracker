package com.luoke.app.map.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import net.jcip.annotations.NotThreadSafe;


@Data
@NotThreadSafe
@JsonIgnoreProperties(ignoreUnknown = true)
public class MapCategoryItem {

    private String type;

    private Integer markType;

    private String length;

    private String markTypeName;

    private String defaultShow;

    private String clazz;

    private String collectible;

    private String geojson;

    private String icon;

    private String desc;
}