package com.luoke.app.map.entity;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class Tile {
    private int x;
    private int y;
    private byte[] data;
}