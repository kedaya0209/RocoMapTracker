package com.luoke.app.context;

import com.luoke.app.map.model.ResourcePoint;
import javafx.geometry.Point2D;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ResourceGridIndex {
    private static final int CELL_SIZE = 120; // 与拾取半径匹配
    private final Map<String, List<ResourcePoint>> grid = new HashMap<>();

    // 构建索引
    public void buildIndex(List<ResourcePoint> points) {
        grid.clear();
        for (ResourcePoint p : points) {
            Point2D pos = p.getScreenPosition();
            String key = getCellKey(pos.getX(), pos.getY());
            grid.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
        }
    }

    // 附近查询（只查 3x3 网格，性能 O(1)）
    public List<ResourcePoint> queryNear(double x, double y) {
        List<ResourcePoint> result = new ArrayList<>();

        int cellX = (int) (x / CELL_SIZE);
        int cellY = (int) (y / CELL_SIZE);

        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                String key = (cellX + dx) + "_" + (cellY + dy);
                List<ResourcePoint> cell = grid.get(key);
                if (cell != null) {
                    result.addAll(cell);
                }
            }
        }
        return result;
    }

    private String getCellKey(double x, double y) {
        int cx = (int) (x / CELL_SIZE);
        int cy = (int) (y / CELL_SIZE);
        return cx + "_" + cy;
    }
}