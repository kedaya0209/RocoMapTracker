package com.luoke.app.map.dto;

import lombok.Data;

@Data
public class MapLayer {
    private LayerOption layerOption;
    private String name;
    private int index;
}