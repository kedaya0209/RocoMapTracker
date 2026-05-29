# roco-map 模块

地图管理层 – 地图下载/拼接/瓦片、资源点模型、远程 Wiki 数据抓取

AI 协作专用 – 依赖 roco-common + JavaFX。


## 模块职责


- 地图下载：MapDownloader BFS 下载瓦片，支持断点续传。
- 地图拼接：MapAssembler 16 图块并行加载 → 洪水填充 → 两张 8192 输出。
- 资源点导出：ResourceExporter 从游戏解包数据导出资源点和图标。
- 图标下载：IconDownloader 并发下载（虚拟线程 + 信号量）。
- 资源配置构建：ResourceConfigBuilder 生成 resource_config.json。
- 远程数据加载：LoadInfo + Jsoup 抓取 Wiki 配置/点位/分类。
- 图标缓存：ImageLoader 强引用缓存图标字节。
- 地图元数据解析：JsMapConfigParser 解析 JS 对象。


## 类清单 (29 个)


核心处理类：
MapAssembler          16 图块并行加载 → 洪水填充 → 两张 8192 输出
MapResourceUpdater    顶层更新协调器：下载→构建→移动
MapTileProcessor      图块元数据 JSON 解析
ResourceExporter      从游戏解包数据导出资源点和图标
MapDownloader         BFS 图块下载器 + 断点续传
MapStitcher           图块拼接为完整地图 PNG
IconDownloader        并发图标下载（虚拟线程 + 信号量）
ResourceConfigBuilder 分类+点位 → resource_config.json
DownloadProgressContext 下载进度 AtomicInteger 跟踪
JsMapConfigParser     JS 对象正则解析 → MapConfig DTO
MapFileMover          暂存→最终目录移动 + init 清单

loader 包 – 数据加载 (5 个)：
LoadInfo              远程配置/点位/分类加载协调
MapConfigLoader       Jsoup 抓取 mapData script
MapCategoryLoader     Jsoup 抓取 categoryData pre
MapPointLoader        Jsoup 抓取 mapPointData pre
ImageLoader           图标字节缓存 ConcurrentHashMap

DTO (8 个)：
DownloadResult, Tile, LayerOption, MapCategoryItem, MapConfig, MapLayer, MapPointItem, LatLng

Model (4 个)：
Point, ResourceConfig, ResourcePoint, RoutePath


## 单例模式


| 类                    | 单例方式       | 持有全局状态                         |
|----------------------|--------------|-----------------------------------|
| ImageLoader          | 饿汉式        | imageCache: ConcurrentHashMap     |
| MapConfigLoader      | 饿汉式        | 无状态，静态方法                    |
| MapCategoryLoader    | 饿汉式        | 无状态                            |
| MapPointLoader       | 饿汉式        | 无状态                            |
| DownloadProgressContext | 饿汉式     | progress: AtomicInteger           |

注：MapAssembler、MapDownloader 等均为普通类，由调用方实例化。


## 本模块工具类清单（优先使用）


以下工具类位于 roco-map，编辑本模块代码时应优先使用：

| 类名                  | 用途                                       |
|----------------------|-------------------------------------------|
| ImageLoader          | 图标字节缓存（强引用，线程安全）              |
| MapConfigLoader      | 地图配置 JS 解析（静态方法）                |
| MapCategoryLoader    | 分类数据加载（静态方法）                    |
| MapPointLoader       | 点位数据加载（静态方法）                    |
| DownloadProgressContext | 下载进度追踪（全局单例）                  |
| JsMapConfigParser    | JS 对象正则解析 → DTO                     |
| MapFileMover         | 暂存目录 → 最终目录移动 + init 清单生成     |

**使用示例**：
- 获取图标字节：`ImageLoader.getInstance().getImageBytes("icon/example.png")`
- 解析地图配置：`MapConfigLoader.loadConfig(configJson)`
- 更新进度：`DownloadProgressContext.getInstance().addProgress(delta)`


## 特殊约束


图标缓存
- ImageLoader.imageCache 使用强引用，不可清除（渲染循环每帧读取）。
- 若缓存被 GC 回收，会导致图标闪烁。
- 如需主动清除，使用 `ImageLoader.getInstance().clearCache()`。

地图组装
- MapAssembler 输出两张 8192×8192 地图：完整地图 + 洪水填充地图。
- 支持大图 (>9Mpx) 自动切换到瓦片模式（不再拼接完整图）。

资源路径
- 地图瓦片默认从 `/source/map/` 加载，外部更新后写入 ResourceUtils.getExternalFile()。
- 图标优先使用外部下载的 PNG，若缺失则 fallback 到 classpath 默认图标。

并发下载
- IconDownloader 使用虚拟线程 + Semaphore 限流（默认并发 10）。
- 下载失败自动重试 3 次。

模块依赖
- 依赖 JavaFX（ImageView 类型），但渲染由 roco-ui 负责。
- 不依赖 roco-engine。


## 与其他模块的交互


- roco-engine：ResourcePointContext 使用 MapAssembler 构建地图，使用 IconDownloader 下载图标。
- roco-ui：ImageLoader 被 IconCache 调用加载图标字节。
- roco-common：ResourceUtils 加载资源，FileUtil 操作文件。


## 典型使用示例


// 构建地图
MapAssembler assembler = new MapAssembler();
assembler.assemble(tileDir, outputPath1, outputPath2, progress -> {
System.out.println("Progress: " + progress);
});

// 下载图标
IconDownloader downloader = IconDownloader.getInstance();
downloader.downloadIcon(iconUrl, targetPath);

// 获取缓存的图标字节
byte[] bytes = ImageLoader.getInstance().getImageBytes(resourcePath);

// 解析 JS 地图配置
String jsContent = "...";
MapConfig config = JsMapConfigParser.parse(jsContent);

// 追踪下载进度
DownloadProgressContext ctx = DownloadProgressContext.getInstance();
ctx.reset();
ctx.addProgress(10);