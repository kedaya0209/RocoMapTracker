package com.luoke.app.map.dto;

import lombok.Data;

@Data
public class LayerOption {
    private String tileUrl;

    public LayerOption() {
    }

    public LayerOption(String tileUrl) {
        this.tileUrl = tileUrl;
    }
}