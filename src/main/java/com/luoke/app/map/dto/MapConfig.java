package com.luoke.app.map.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MapConfig {
    private int zoom;
    private int maxZoom;
    private int minZoom;
    private boolean layerControl;
    private List<Double> center;
    private List<List<Integer>> maxBounds;
    private String logo;
    private String pointShadow;
    private String mapBG;
    private String dataPrefix;
    private List<String> dataList;
    private List<MapLayer> mapLayers;
}