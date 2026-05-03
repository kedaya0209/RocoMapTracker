package com.luoke.app.map.model;

import javafx.geometry.Point2D;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Point {
    private double x;
    private double y;

    // 方便转换回 JavaFX 的 Point2D
    public Point2D toPoint2D() {
        return new Point2D(x, y);
    }
}