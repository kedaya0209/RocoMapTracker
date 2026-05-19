package com.luoke.app.context;

import com.luoke.app.config.StatsConfig;
import com.luoke.app.map.model.Point;
import com.luoke.app.map.model.ResourcePoint;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 资源点控件索引
 */
public class ResourcePointGridIndex {
    private final Map<Long, List<ResourcePoint>> grid = new HashMap<>();

    public void buildIndex(List<ResourcePoint> points) {
        grid.clear();
        if (points == null) return;
        for (ResourcePoint p : points) {
            Point pos = p.getScreenPosition();
            grid.computeIfAbsent(calculateKey(pos.getX(), pos.getY()), k -> new ArrayList<>()).add(p);
        }
    }

    public List<ResourcePoint> queryNear(double x, double y) {
        List<ResourcePoint> result = new ArrayList<>();
        long cellX = (long) (x / StatsConfig.GRID_CELL_SIZE);
        long cellY = (long) (y / StatsConfig.GRID_CELL_SIZE);

        for (long dx = -1; dx <= 1; dx++) {
            for (long dy = -1; dy <= 1; dy++) {
                List<ResourcePoint> cell = grid.get(combine(cellX + dx, cellY + dy));
                if (cell != null) result.addAll(cell);
            }
        }
        return result;
    }

    /**
     * 查询矩形范围内的所有点位（世界坐标）
     */
    public List<ResourcePoint> queryRect(double minX, double minY, double maxX, double maxY) {
        List<ResourcePoint> result = new ArrayList<>();
        long minCellX = (long) (minX / StatsConfig.GRID_CELL_SIZE);
        long minCellY = (long) (minY / StatsConfig.GRID_CELL_SIZE);
        long maxCellX = (long) (maxX / StatsConfig.GRID_CELL_SIZE);
        long maxCellY = (long) (maxY / StatsConfig.GRID_CELL_SIZE);

        for (long cx = minCellX; cx <= maxCellX; cx++) {
            for (long cy = minCellY; cy <= maxCellY; cy++) {
                List<ResourcePoint> cell = grid.get(combine(cx, cy));
                if (cell != null) result.addAll(cell);
            }
        }
        return result;
    }

    private long calculateKey(double x, double y) {
        return combine((long) (x / StatsConfig.GRID_CELL_SIZE), (long) (y / StatsConfig.GRID_CELL_SIZE));
    }

    private long combine(long cx, long cy) {
        return (cx << 32) | (cy & 0xFFFFFFFFL);
    }
}