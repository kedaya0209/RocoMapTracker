package com.luoke.app.map.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Point {
    private double x;
    private double y;

    public double distance(double px, double py) {
        return Math.sqrt((x - px) * (x - px) + (y - py) * (y - py));
    }
}