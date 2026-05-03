package com.luoke.app.context;

import com.luoke.app.map.model.ResourcePoint;
import javafx.geometry.Point2D;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ResourceGridIndex {
    private static final int CELL_SIZE = 120;
    // 使用 Long 键值对：高32位存储 X，低32位存储 Y
    private final Map<Long, List<ResourcePoint>> grid = new HashMap<>();

    public void buildIndex(List<ResourcePoint> points) {
        grid.clear();
        if (points == null) return;
        for (ResourcePoint p : points) {
            Point2D pos = p.getScreenPosition();
            grid.computeIfAbsent(calculateKey(pos.getX(), pos.getY()), k -> new ArrayList<>()).add(p);
        }
    }

    public List<ResourcePoint> queryNear(double x, double y) {
        List<ResourcePoint> result = new ArrayList<>();
        long cellX = (long) (x / CELL_SIZE);
        long cellY = (long) (y / CELL_SIZE);

        for (long dx = -1; dx <= 1; dx++) {
            for (long dy = -1; dy <= 1; dy++) {
                List<ResourcePoint> cell = grid.get(combine(cellX + dx, cellY + dy));
                if (cell != null) result.addAll(cell);
            }
        }
        return result;
    }

    private long calculateKey(double x, double y) {
        return combine((long) (x / CELL_SIZE), (long) (y / CELL_SIZE));
    }

    private long combine(long cx, long cy) {
        return (cx << 32) | (cy & 0xFFFFFFFFL);
    }
}