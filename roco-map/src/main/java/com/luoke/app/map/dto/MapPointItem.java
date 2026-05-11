package com.luoke.app.map.dto;

import lombok.Data;


@Data
public class MapPointItem {

    private Integer markType;

    private String title;

    private String id;

    private Point point;

    private String uid;

    private String layer;

    private Long time;

    private Integer version;
}