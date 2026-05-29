# roco-ui

用户界面层 — JavaFX 桌面应用 + Native Image 构建配置。是整个项目的入口点和最终交付物。

## 职责

### 应用入口

- **Main** — `main()` 入口：初始化环境 → launch JavaFX
- **ModernCanvasApp** — 主 Application：初始化流程 + UI 构建 + 截图守护启动

### UI 组件

- **InteractiveCanvas** — 交互画布：鼠标/键盘事件编排（委托 PathEditor/HoverManager/ContextMenuManager）
- **TitleBar** — 标题栏 + 幽灵模式 + 状态轮播
- **Sidebar/SidebarCell** — 分类侧边栏
- **FloatToolbox** — 浮动工具栏（跟随/计数开关）
- **SettingsStage** — IntelliJ 风格设置面板（11 分类）
- **RouteManagerStage** — 路线管理窗口
- **NotificationToast** — Toast 通知
- **ResourceCounterPanel** — 物资采集统计面板
- **StatsOverlay** — 实时性能覆盖层
- **LoadingOverlay** — 全屏加载遮罩

### 渲染引擎

- **MapRenderer** — AnimationTimer 渲染循环：viewportDirty 快照复用 + 分层绘制
- **PlayerRenderer** — 玩家图标渲染（角度旋转 + 潮汐波纹）
- **IconLayerManager** — 资源点图标 ImageView 管理 + 灰度切换
- **RouteRenderer** — Canvas 路线渲染
- **HoverRenderer** — Canvas hover 高亮
- **TileManager** — 多分辨率地图瓦片管理

### SVG 管理

- **SvgManager** — 门面（委托 SvgAnimator / SvgIconBuilder / SvgPathUtil）
- **SvgAnimator** — 画线动画
- **SvgIconBuilder** — 图标构建

### 服务

- **CaptureServiceManager** — 截图服务看门狗
- **SiftClientManager** — SIFT 客户端管理
- **PcapBridgeManager** — pcap 桥接器生命周期管理
- **InfrastructureManager** — JobObject + SocketServer 管理
- **ResourceInitService** — 资源初始化验证
- **IconCache** — 纹理图集缓存

### 窗口管理

- **WindowManager** — 8 方向边缘拖拽缩放
- **ThemeManager** — 7 种 AtlantaFX 主题切换

## 依赖

| 依赖 | 版本 |
|---|---|
| JavaFX Controls/Graphics/Base | 25.0.3 |
| AtlantaFX Base | 2.1.0 |
| Logback Classic | 1.5.32 |

## 内部依赖

- `roco-engine`（传递依赖所有核心模块）

## 构建配置

- **maven-shade-plugin** — 构建 fat jar
- **javafx-maven-plugin** — JavaFX 运行/打包
- **native-maven-plugin** — GraalVM Native Image 编译（3 个 profile: native / native-instrument / native-pgo）

## GraalVM Native Image 元数据维护规则

当新增静态资源（DLL、SVG、配置文件等）或需要反射/JNI 访问的 Java 类时，必须同步修改 `META-INF/native-image/reachability-metadata.json`。
