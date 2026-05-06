# roco-map

地图管理层，负责地图资源的下载、拼接、加载，以及资源点位和路线的数据模型。

## 职责

- **地图下载与拼接** — `MapDownloader` 瓦片下载，`MapStitcher` 拼接合成，`MapAssembler` 组装
- **图标缓存** — `ImageLoader` ConcurrentHashMap 强引用缓存 + 32px 离屏渲染
- **资源配置** — `ResourceConfigBuilder` 构建，`MapCategoryLoader`/`MapConfigLoader`/`MapPointLoader` 加载
- **数据模型** — `ResourcePoint` 资源点 (含 JavaFX 渲染)，`RoutePath` 路线，`ResourceConfig` 配置
- **DTO 层** — `MapConfig`, `MapLayer`, `MapPointItem`, `LayerOption` 等
- **配置解析** — `JsMapConfigParser` JS 配置解析

## 依赖

| 依赖                   | 版本     |
|----------------------|--------|
| Jsoup                | 1.21.2 |
| Jackson              | 2.15.2 |
| JavaFX Base/Graphics | 25     |

## 内部依赖

- `roco-common`

## 资源

- `source/map/WorldMap_Show.png` — 显示用地图
- `source/map/WorldMap_SIFT.png` — SIFT 匹配用地图
- `source/icon/*.png` — 资源点图标 (28个) + 玩家图标
- `source/point/` — 资源点配置 JSON + 采集集合
