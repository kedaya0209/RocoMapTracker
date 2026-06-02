package io.github.kedaya0209.roco.app.match;

import net.jcip.annotations.ThreadSafe;
import io.github.kedaya0209.roco.app.map.model.CompositeMapMetadata;
import io.github.kedaya0209.roco.app.map.model.CompositeMapMetadata.SubImageInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 子图坐标映射器 — 将匹配结果坐标分解为所属子图信息。
 * <p>
 * 职责：
 * <ul>
 *   <li>根据总图高度和 SUB_IMAGE_HEIGHT 构建元数据</li>
 *   <li>将 (x, y) 坐标映射到对应的子图名称</li>
 *   <li>判断当前坐标是否在洞穴区域</li>
 * </ul>
 */
@ThreadSafe
public class SubImageMapper {

    /** 每个子图的标准高度 */
    public static final int SUB_IMAGE_HEIGHT = 8192;

    private final CompositeMapMetadata metadata;
    private final int totalHeight;

    public SubImageMapper(int mapWidth, int mapHeight) {
        this.totalHeight = mapHeight;
        int subCount = mapHeight / SUB_IMAGE_HEIGHT;
        if (mapHeight % SUB_IMAGE_HEIGHT != 0) subCount++;

        List<SubImageInfo> subs = new ArrayList<>(subCount);
        String[] defaultNames = {"mainland", "cave_1", "cave_2", "cave_3", "cave_4", "cave_5",
                "cave_6", "cave_7", "cave_8", "cave_9", "cave_10"};

        for (int i = 0; i < subCount; i++) {
            int offset = i * SUB_IMAGE_HEIGHT;
            int h = Math.min(SUB_IMAGE_HEIGHT, mapHeight - offset);
            String name = i < defaultNames.length ? defaultNames[i] : "sub_" + i;
            subs.add(new SubImageInfo(name, mapWidth, h, offset));
        }

        this.metadata = new CompositeMapMetadata(subs);
    }

    public SubImageMapper(CompositeMapMetadata metadata) {
        this.metadata = metadata;
        this.totalHeight = metadata.totalHeight();
    }

    /**
     * 根据 Y 坐标获取子图信息。
     */
    public SubImageInfo resolve(double y) {
        return metadata.findByY(y);
    }

    /**
     * 判断该 Y 坐标是否在洞穴区域。
     */
    public boolean isCaveY(double y) {
        return metadata.isCaveY(y);
    }

    /**
     * 获取子图在总图中的索引（0=大陆，1+=洞穴）。
     */
    public int subIndex(double y) {
        List<SubImageInfo> subs = metadata.subImages();
        for (int i = 0; i < subs.size(); i++) {
            var sub = subs.get(i);
            if (y >= sub.offsetY() && y < sub.offsetY() + sub.height()) {
                return i;
            }
        }
        return -1;
    }

    public CompositeMapMetadata metadata() {
        return metadata;
    }

    public int totalHeight() {
        return totalHeight;
    }
}
