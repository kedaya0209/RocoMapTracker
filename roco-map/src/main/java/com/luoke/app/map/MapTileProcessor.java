package com.luoke.app.map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.jcip.annotations.NotThreadSafe;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@NotThreadSafe
public class MapTileProcessor {

    private static final ObjectMapper mapper = new ObjectMapper();

    public static void main(String[] args) {
        // 假设你的 16 个 json 文件放在这个目录下
        File folder = new File("C:\\Users\\tangh\\Desktop\\map\\metadata");
        File[] files = folder.listFiles((dir, name) -> name.endsWith(".json"));

        if (files == null) return;

        List<TileInfo> allTiles = new ArrayList<>();

        for (File file : files) {
            try {
                TileInfo info = parseTileMetadata(file);
                if (info != null) {
                    allTiles.add(info);
                }
            } catch (IOException e) {
                System.err.println("解析失败: " + file.getName());
            }
        }

        // 打印结果
        allTiles.forEach(System.out::println);

        // 如果需要计算整个大世界的中心，可以对所有 centerX/Y 求平均值
        calculateWorldCenter(allTiles);
    }

    private static TileInfo parseTileMetadata(File file) throws IOException {
        JsonNode root = mapper.readTree(file);

        // 遍历数组查找 Type 为 BoxComponent 的项
        for (JsonNode node : root) {
            if ("BoxComponent".equals(node.get("Type").asText())) {
                JsonNode props = node.get("Properties");
                if (props != null && props.has("RelativeLocation")) {
                    TileInfo info = new TileInfo();
                    info.fileName = file.getName();

                    // 提取中心坐标
                    JsonNode loc = props.get("RelativeLocation");
                    info.centerX = loc.get("X").asDouble();
                    info.centerY = loc.get("Y").asDouble();
                    info.centerZ = loc.get("Z").asDouble();

                    // 提取尺寸 (Scale 表示该 Box 的拉伸，即实际占用的空间大小)
                    JsonNode scale = props.get("RelativeScale3D");
                    info.sizeX = scale.get("X").asDouble();
                    info.sizeY = scale.get("Y").asDouble();

                    return info;
                }
            }
        }
        return null;
    }

    private static void calculateWorldCenter(List<TileInfo> tiles) {
        if (tiles.isEmpty()) return;
        double sumX = 0, sumY = 0;
        for (TileInfo t : tiles) {
            sumX += t.centerX;
            sumY += t.centerY;
        }
        System.out.printf("\n>>> 16块瓦片聚合后的世界中心点: (%.2f, %.2f)\n",
                sumX / tiles.size(), sumY / tiles.size());
    }

    // 定义瓦片信息实体
    public static class TileInfo {
        public String fileName;
        public double centerX, centerY, centerZ;
        public double sizeX, sizeY;

        @Override
        public String toString() {
            return String.format("瓦片: %s | 中心点: (%.2f, %.2f, %.2f) | 尺寸: %.2f x %.2f",
                    fileName, centerX, centerY, centerZ, sizeX, sizeY);
        }
    }
}