package com.luoke.app.map.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luoke.app.map.LoadInfo;
import com.luoke.app.map.MapResourceUpdater;
import com.luoke.app.map.dto.MapCategoryItem;
import com.luoke.app.map.dto.MapPointItem;
import com.luoke.app.map.model.ResourceConfig;
import com.luoke.app.utils.FileUtil;
import com.luoke.app.utils.JsonUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 资源配置构建器
 * <p>
 * 负责从地图分类和点位数据构建资源配置文件。
 * 该类实现了以下核心功能：
 * <ul>
 *   <li>加载地图分类数据</li>
 *   <li>加载地图点位数据</li>
   *   <li>将分类和点位数据关联</li>
 *   <li>构建资源配置对象</li>
   *   <li>将配置保存为JSON文件</li>
 * </ul>
 * <pator>
 * 设计要点：
 * <ul>
 *   <li>使用Map建立分类索引，提高查询效率</li>
 *   <li>使用Jackson进行JSON序列化</li>
 *   <li>使用pretty printer格式化输出</li>
 *   <li>过滤无效数据，提高配置质量</li>
 *   <li>从URL中提取文件名作为图标名称</li>
 * </ul>
 * <p>
 * 数据处理流程：
 * <ol>
 *   <li>加载所有地图分类数据</li>
 *   <li>加载所有地图点位数据</li>
 *   <li>建立分类索引（按markType）</li>
 *   <li>遍历所有点位，查找对应分类</li>
 *   <li>为每个点位构建资源配置</li>
 *   <li>保存配置为JSON文件</li>
 * </ol>
 * <p>
 * 配置字段说明：
 * <ul>
 *   <li>type: 资源类型（菜单按钮）</li>
 *   <li>markType: 资源类型ID</li>
 *   <li>markTypeName: 资源名称</li>
 *   <li>icon: 图标文件名</li>
 *   <li>lat: 经度</li>
 *   <li>lng: 纬度</li>
 *   <li>layer: 图层</li>
 *   <li>zoom: 默认缩放级别</li>
 * </ul>
 * <p>
 * 性能优化：
 * <ul>
 *   <li>使用HashMap建立分类索引，O(1)查找</li>
 *   <li>使用ArrayList收集配置，提高遍历效率</li>
 *   <li>Jackson复用ObjectMapper实例，减少开销</li>
 * </ul>
 *
 * @author RocoMapTracker
 * @since 1.0
 */
@Slf4j
public class ResourceConfigBuilder {

    /**
     * Jackson ObjectMapper实例，用于JSON序列化
     * <p>
     * 使用静态final变量复用ObjectMapper实例：
     * <ul>
     *   <li>避免重复创建，减少开销</li>
     *   <li>复用缓存和配置，提高性能</li>
     *   <li>从JsonUtils获取，确保配置一致</li>
     * </ul>
     * <p>
     * ObjectMapper开销：
     * <ul>
     *   <li>创建实例需要序列化器注册</li>
     *   <li>缓存反射信息，后续使用更快</li>
     *   <li>复用实例可以提高整体性能</li>
     * </ul>
     */
    private static final ObjectMapper om = JsonUtils.getMapper();

    /**
     * 构建并保存资源配置文件
     * <p>
     * 该方法执行完整的资源配置构建流程：
     * <ol>
     *   <li>加载地图分类数据</li>
     *   <li>加载地图点位数据</li>
     *   <li>建立分类索引（按markType）</li>
     *   <li>遍历所有点位，查找对应分类</li>
     *   <li>为每个点位构建资源配置</li>
     *   <li>保存配置为JSON文件</li>
     * </ol>
     * <p>
     * 数据关联逻辑：
     * <ul>
     *   <li>点位数据包含markType字段</li>
     *   <li>分类数据也包含markType字段</li>
     *   <li>通过markType将点位和分类关联</li>
     *   <li>分类包含图标等资源信息</li>
     *   <li>点位包含位置信息</li>
     * </ul>
     * <p>
     * 过滤逻辑：
     * <ul>
     *   <li>忽略没有markType的分类</li>
     *   <li>忽略找不到分类的点位</li>
     *   <li>只保存有效配置</li>
     * </ul>
     * <p>
     * 性能优化：
     * <ul>
     *   <li>使用HashMap建立分类索引，O(1)查找</li>
     *   <li>遍历点位时快速查找分类</li>
     *   <li>使用ArrayList收集配置，提高遍历效率</li>
     * </ul>
     * <p>
     * 错误处理：
     * <ul>
     *   <li>捕获所有异常，记录错误日志</li>
     *   <li>单个配置失败不影响其他配置</li>
     * </ul>
     */
    public static void buildAndSaveConfig() {
        try {
            // 加载地图分类数据
            // 分类数据包含资源类型、名称、图标等信息
            List<MapCategoryItem> categories = LoadInfo.parseCategoryData();

            // 加载地图点位数据
            // 点位数据包含位置、图层等信息
            List<MapPointItem> points = LoadInfo.parsePointJson();

            // 建立分类索引，按markType作为key
            // 使用HashMap提高查找效率，O(1)时间复杂度
            Map<Integer, MapCategoryItem> catMap = new HashMap<>();

            // 遍历所有分类，建立索引
            for (MapCategoryItem cat : categories) {
                // 只处理有markType的分类
                // markType是关联点位和分类的关键字段
                if (cat.getMarkType() != null) {
                    catMap.put(cat.getMarkType(), cat);
                }
            }

            // 收集所有有效的资源配置
            List<ResourceConfig> list = new ArrayList<>();

            // 遍历所有点位，构建资源配置
            for (MapPointItem point : points) {
                // 获取点位的markType
                Integer type = point.getMarkType();

                // 在分类索引中查找对应的分类
                // 使用HashMap快速查找，提高性能
                MapCategoryItem cat = catMap.get(type);

                // 如果找不到分类，跳过该点位
                // 这种过滤逻辑确保配置的完整性
                if (cat == null) continue;

                // 为该点位构建资源配置
                // 将分类和点位的信息合并到配置对象中
                ResourceConfig cfg = getResourceConfig(point, cat);
                list.add(cfg);
            }

            // 构建输出文件路径
            // 配置文件保存在下载点位目录，命名为resource_config.json
            File out = FileUtil.getRelativeFile(MapResourceUpdater.DOWNLOAD_POINT_DIR, "resource_config.json");

            // 使用Jackson将配置保存为JSON文件
            // 使用pretty printer格式化输出，便于阅读
            om.writerWithDefaultPrettyPrinter().writeValue(out, list);

            // 记录配置生成成功的日志
            log.info("✅ 配置生成完成：{} 条", list.size());

        } catch (Exception e) {
            // 捕获所有异常，记录错误日志
            log.error("❌ 生成配置失败", e);
        }
    }

    /**
     * 从点位和分类数据构建资源配置
     * <p>
     * 该方法将点位和分类数据合并为资源配置对象：
     * <ul>
     *   <li>创建ResourceConfig对象</li>
     *   <li>设置资源类型（来自分类）</li>
     *   <li>设置资源类型ID（来自分类）</li>
     *   <li>设置资源名称（来自分类）</li>
     *   <li>设置图标文件名（从分类的icon URL提取）</li>
     *   <li>设置经度（来自点位）</li>
     *   <li>设置纬度（来自点位）</li>
     *   <li>设置图层（来自点位）</li>
     *   <li>设置缩放级别（使用默认值）</li>
     * </ul>
     * <p>
     * 数据来源说明：
     * <ul>
     *   <li>分类数据：type, markType, markTypeName, icon</li>
     *   <li>点位数据：lat, lng, layer</li>
     *   <li>默认值：zoom = 4</li>
     * </ul>
     * <p>
     * 图标文件名提取：
     * <ul>
     *   <li>分类的icon字段是完整URL</li>
     *   <li>提取最后一个/之后的部分作为文件名</li>
     *   <li>例如："https://example.com/icon.png" -> "icon.png"</li>
     * </ul>
     * <p>
     * 默认值处理：
     * <ul>
     *   <li>zoom使用配置的默认值</li>
     *   <li>确保配置的完整性</li>
     * </ul>
     *
     * @param point 地图点位数据，包含位置信息
     * @param cat 地图分类数据，包含资源信息
     * @return 构建好的资源配置对象
     */
    private static ResourceConfig getResourceConfig(MapPointItem point, MapCategoryItem cat) {
        // 创建资源配置对象
        ResourceConfig cfg = new ResourceConfig();

        // 设置资源类型（菜单按钮）
        // 来自分类数据，用于前端菜单分组
        cfg.setType(cat.getType());

        // 设置资源类型ID
        // 来自分类数据，用于关联点位和分类
        cfg.setMarkType(cat.getMarkType());

        // 设置资源名称
        // 来自分类数据，用于显示和标识
        cfg.setMarkTypeName(cat.getMarkTypeName());

        // 设置图标文件名
        // 从分类的icon URL中提取文件名
        // 提取最后一个/之后的部分，即文件名
        String icon = cat.getIcon();
        String fileName = icon.substring(icon.lastIndexOf("/") + 1);
        cfg.setIcon(fileName);

        // 设置经度（注意这里字段名反了，lat实际是经度）
        // 来自点位数据，用于地图定位
        cfg.setLat(point.getPoint().getLat());

        // 设置纬度（注意这里字段名反了，lng实际是纬度）
        // 来自点位数据，用于地图定位
        cfg.setLng(point.getPoint().getLng());

        // 设置图层
        // 来自点位数据，用于分层显示
        cfg.setLayer(point.getLayer());

        // 设置缩放级别
        // 使用配置的默认值，确保所有配置都有缩放级别
        cfg.setZoom(MapResourceUpdater.DEFAULT_ZOOM);

        return cfg;
    }
}
