package io.github.kedaya0209.roco.app.map.model;

import com.fasterxml.jackson.databind.JsonNode;
import net.jcip.annotations.Immutable;
import io.github.kedaya0209.roco.app.utils.JsonUtils;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 复合地图元数据 — 描述 SIFT 匹配地图由哪些子图拼接而成。
 * <p>每个子图有名称、高度和在总图中的 Y 偏移量。
 * 支持从 MultiMap_metadata.json 反序列化。
 */
@Immutable
public final class CompositeMapMetadata {

    private final List<SubImageInfo> subImages;
    private final SiftParams matchingSift;

    public CompositeMapMetadata(List<SubImageInfo> subImages, SiftParams matchingSift) {
        this.subImages = List.copyOf(subImages);
        this.matchingSift = matchingSift;
    }

    /**
     * 从 JSON 输入流加载 MultiMap 元数据。
     */
    public static CompositeMapMetadata load(InputStream jsonStream) throws IOException {
        JsonNode root = JsonUtils.getMapper().readTree(jsonStream);
        int cw = root.get("compositeWidth").asInt();
        int ch = root.get("compositeHeight").asInt();

        // 全局匹配参数（可空）
        SiftParams matchingSift = null;
        if (root.has("matchingSift")) {
            matchingSift = parseSiftParams(root.get("matchingSift"));
        }

        List<SubImageInfo> subs = new ArrayList<>();
        for (JsonNode node : root.get("subImages")) {
            SiftParams siftOverride = null;
            if (node.has("sift")) {
                siftOverride = parseSiftParams(node.get("sift"));
            }
            subs.add(new SubImageInfo(
                    node.get("index").asInt(),
                    node.get("name").asText(),
                    node.get("isCave").asBoolean(),
                    node.get("offsetY").asInt(),
                    node.get("width").asInt(),
                    node.get("height").asInt(),
                    node.get("sourcePath").asText(),
                    node.get("tileDir").asText(),
                    siftOverride
            ));
        }
        return new CompositeMapMetadata(subs, matchingSift);
    }

    private static SiftParams parseSiftParams(JsonNode n) {
        Double ct = n.has("contrastThreshold") ? n.get("contrastThreshold").asDouble() : null;
        Double et = n.has("edgeThreshold") ? n.get("edgeThreshold").asDouble() : null;
        Integer nf = n.has("nfeatures") ? n.get("nfeatures").asInt() : null;
        Integer nol = n.has("nOctaveLayers") ? n.get("nOctaveLayers").asInt() : null;
        Double sg = n.has("sigma") ? n.get("sigma").asDouble() : null;
        return new SiftParams(ct, et, nf, nol, sg);
    }

    public List<SubImageInfo> subImages() {
        return subImages;
    }

    public SiftParams matchingSift() {
        return matchingSift;
    }

    public int totalHeight() {
        return subImages.stream().mapToInt(SubImageInfo::height).sum();
    }

    public int width() {
        return subImages.isEmpty() ? 0 : subImages.get(0).width();
    }

    /**
     * 查找包含指定 Y 坐标的子图。
     *
     * @return 匹配的子图，越界时返回 null
     */
    public SubImageInfo findByY(double y) {
        for (var sub : subImages) {
            if (y >= sub.offsetY() && y < sub.offsetY() + sub.height()) {
                return sub;
            }
        }
        return null;
    }

    /**
     * 判断指定 Y 坐标是否在洞穴区域（非大陆）。
     */
    public boolean isCaveY(double y) {
        if (subImages.isEmpty()) return false;
        // 第一个子图为大陆，其余为洞穴
        return y >= subImages.get(0).height();
    }

    /**
     * SIFT 参数覆盖（所有字段可空，null = 使用默认值）。
     */
    @Immutable
    public record SiftParams(
            Double contrastThreshold,
            Double edgeThreshold,
            Integer nfeatures,
            Integer nOctaveLayers,
            Double sigma
    ) {
        public boolean hasAny() {
            return contrastThreshold != null || edgeThreshold != null
                    || nfeatures != null || nOctaveLayers != null || sigma != null;
        }
    }

    /**
     * 单个子图信息。
     */
    @Immutable
    public record SubImageInfo(
            int index,
            String name,
            boolean isCave,
            int offsetY,
            int width,
            int height,
            String sourcePath,
            String tileDir,
            SiftParams siftOverride
    ) {
        /** @deprecated 兼容旧代码，使用 index/name/isCave/sourcePath/tileDir 代替 */
        @Deprecated
        public SubImageInfo(String name, int width, int height, int offsetY) {
            this(0, name, false, offsetY, width, height, null, null, null);
        }
    }
}
