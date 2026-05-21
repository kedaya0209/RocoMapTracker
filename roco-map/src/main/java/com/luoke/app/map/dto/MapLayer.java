package com.luoke.app.map.dto;

import lombok.Data;
import net.jcip.annotations.NotThreadSafe;

/**
 * 地图图层配置数据传输对象
 * 封装单个地图图层的配置信息
 */
@Data
@NotThreadSafe
public class MapLayer {
    /**
     * 图层的选项配置，包含瓦片服务URL等
     */
    private LayerOption layerOption;

    /**
     * 图层的显示名称
     */
    private String name;

    /**
     * 图层的显示索引（层级），控制渲染顺序
     * index值越小越靠底层，值越大越靠顶层
     */
    private int index;
}
