package io.github.kedaya0209.roco.app.map.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import net.jcip.annotations.NotThreadSafe;

@Data
@NoArgsConstructor
@AllArgsConstructor
@NotThreadSafe
public class Point {
    private double x;
    private double y;

    public double distance(double px, double py) {
        return Math.sqrt((x - px) * (x - px) + (y - py) * (y - py));
    }
}