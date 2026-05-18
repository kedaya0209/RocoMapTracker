package com.luoke.app.map.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class RoutePath {
    private String name;
    private List<Point> nodes = new ArrayList<>();
    //资源文件标记
    private String tag;
    private boolean visible = true;

    public RoutePath() {
    } // 序列化需要

    public RoutePath(String name) {
        this.name = name;
    }

    public void remove(int index) {
        nodes.remove(index);
    }

    public void addNode(Point point) {
        nodes.add(point);
    }

    public void addNode(int index, Point point) {
        nodes.add(index, point);
    }

    public void setNode(int draggedNodeIndex, Point point) {
        nodes.set(draggedNodeIndex, point);
    }
}