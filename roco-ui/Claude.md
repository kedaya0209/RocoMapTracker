# roco-ui 模块

用户界面层 – JavaFX 界面、渲染引擎、SvgManager、设置面板、Native Image 入口

AI 协作专用 – 依赖 roco-engine（以及传递依赖），是最终应用的展示层。


## 模块职责


- 应用入口：Main 初始化 OpenCV → 启动 JavaFX。
- 主界面：ModernCanvasApp 管理场景、启动截图守护、发布初始化进度。
- 渲染引擎：MapRenderer 驱动渲染循环，IconLayerManager 管理资源点图标，PlayerRenderer 渲染玩家，RouteRenderer 绘制路线。
- SVG 管理：SvgManager 门面，委托 SvgAnimator / SvgIconBuilder / SvgPathUtil 分别负责动画、构建、路径。
- 图标图集：IconCache 管理彩色+灰度纹理图集，GPU 槽位访问。
- 瓦片管理：TileManager 管理多分辨率地图瓦片 ImageView 生命周期。
- 设置面板：SettingsStage IntelliJ 风格设置窗口，支持 11 分类配置。
- UI 组件：侧边栏、标题栏、路线管理、悬浮工具栏、右键菜单、通知 Toast 等。
- 服务协调：ResourceInitService 资源初始化验证，CaptureServiceManager 截图服务看门狗，SiftClientManager SIFT 客户端管理。
- 窗口管理：WindowManager 支持 8 方向边缘拖拽缩放。


## 类清单 (54 个)


入口 (2 个)：
Main                   入口点：初始化 OpenCV → launch JavaFX
ModernCanvasApp        主 Application：初始化 + UI 构建 + 截图守护

ui.hook – Hook 实现 (1 个)：
UiResponseHook         监听 UI_NOTIFICATION / INIT_PROGRESS / CAPTURE_STATE

component – UI 组件 (16 个)：
InteractiveCanvas      交互画布：鼠标/键盘事件 → 委托 PathEditor/HoverManager
Sidebar                左侧分类侧边栏：算法/资源/主题/路线/WIKI 更新
SidebarCell            侧边栏分类单元格（从 Sidebar 内联类提取）
TitleBar               标题栏 + 状态轮播 + 幽灵模式（透明度+置顶）
RouteManagerStage      路线管理窗口：保存/编辑/导入/导出
FloatToolbox           浮动工具栏：自动跟随/物资计数开关
HoverManager           鼠标 hover 检测 + 提示框
PathEditor             路线绘制/编辑：节点增删拖拽 + Ctrl+Z 撤销
LoadingOverlay         全屏加载遮罩 + 进度条
NotificationToast      滑入/滑出 Toast 通知
ResourceCounterPanel   物资采集统计面板（订阅 Hook 事件）
StatsOverlay           实时性能覆盖层：匹配耗时/FPS
ContextMenuManager     右键菜单：添加标记/重置视口/资源点操作
UiAnimator             侧边栏滑入/滑出动画
WikiUpdateManager      WIKI 资源更新：下载按钮 + 进度条
AddPointDialog         添加地图标记对话框 + 自动补全

component/setting – 设置面板 (11 个)：
SettingsStage          单例 IntelliJ 风格设置窗口：分类列表 + 右侧配置面板
SettingDefinitions     11 分类全部可配置项定义注册中心
SettingConfigManager   设置数据层：控件注册/配置读写/变更追踪/快照管理
SettingFieldBuilder    配置控件工厂：CheckBox/Spinner/ComboBox 等
RoiPreview             ROI 截帧预览 + 全帧模式 + 弹出调整窗口
RoiZoomPopup           ROI 截帧弹出放大预览（从 RoiPreview 提取）
PlayerPreview          玩家实时动画预览：图标 + 拾取光晕 + 波纹
SettingCategory        record(name, icon, fields)
SettingCategoryCell    分类列表单元格：hover 高亮 + SVG 图标动画
SettingDef             record(key, label, type, getter/setter)
SettingType            枚举：BOOLEAN/INTEGER/LONG/DOUBLE/STRING/COMBO

render – 渲染引擎 (6 个)：
MapRenderer            渲染循环协调器：Timeline 驱动 + viewportDirty 快照
IconLayerManager       资源点图标 ImageView 节点管理 + 灰度切换
PlayerRenderer         玩家图标渲染：角度旋转 + 潮汐波纹 + 拾取光晕
RouteRenderer          Canvas 路线渲染：脏检测 + 平移补偿
HoverRenderer          Canvas hover 高亮：辉光 + 资源图标
RenderLayer            渲染层接口：getNode() / onFrame()

service.lifecycle – 进程/服务生命周期 (4 个)：
CaptureServiceManager  CaptureService 生命周期 + 看门狗重连
SiftClientManager      SIFT 客户端生命周期：启动/重启/停止
InfrastructureManager  JobObject + SocketServer 生命周期
PcapBridgeManager      pcap 桥接器生命周期

service.resource – 资源/缓存管理 (9 个)：
SvgManager             SVG 门面：加载/缓存/构建/画线动画（委托给子类）
SvgAnimator            SVG 画线动画实现（SvgManager 子类）
SvgIconBuilder         SVG 图标构建实现（SvgManager 子类）
SvgPathUtil            SVG 路径工具方法（SvgManager 子类）
ResourceInitService    资源初始化验证 + 首运行对话框 + 地图元数据加载
ResourceInitUiDelegate 资源初始化 UI 回调接口
TileManager            瓦片 ImageView 生命周期：级别选择/视口约束/回收
IconCache              图标纹理图集：彩色+灰度双图集，GPU 槽位访问
TileGeneratorService   多分辨率瓦片金字塔生成（5 级）

service.ui – UI 级服务 (4 个)：
MainUiComposer         静态 UI 组装器：场景图构建
ThemeManager           7 种 AtlanFX 主题切换
WindowManager          窗口拖拽/缩放：8 方向边缘矩形
VersionManager         版本切换管理

service – 服务 (1 个)：
VersionMode            版本模式枚举

util (4 个)：
DialogUtils            模态覆盖对话框工具
FxRippleUtil           Material Design 涟漪效果
RestartUtils           Native Image 兼容的重启工具
Win32TraySymbols       Win32 托盘图标和常量（TrayManager 静态符号）


## 单例模式


| 类                    | 单例方式       | 持有全局状态                         |
|----------------------|--------------|-----------------------------------|
| ModernCanvasApp      | 普通类        | 由 JavaFX 启动，可通过 getInstance 获取 |
| IconCache            | 懒汉式        | iconCache: ConcurrentHashMap      |
| SvgManager           | 饿汉式        | svgCache, lineAnimationMap        |
| TileManager          | 饿汉式        | tileImageViews, tileLevels        |
| SettingsStage        | DCL          | 设置面板单例窗口                    |
| SettingConfigManager | 饿汉式        | settingsMap, dirtyFlags           |
| CaptureServiceManager| 饿汉式        | captureService 引用               |
| SiftClientManager    | 饿汉式        | siftMatchHandler 引用             |
| InfrastructureManager| 饿汉式        | jobObject, socketServer           |
| ThemeManager         | 饿汉式        | currentTheme                      |

注：MapRenderer、PlayerRenderer 等为 DCL 单例，由渲染循环持有。


## 本模块工具类清单（优先使用）


以下工具类位于 roco-ui，编辑本模块代码时应优先使用：

| 类名                     | 用途                                       |
|-------------------------|-------------------------------------------|
| SvgManager              | SVG 门面：加载/缓存/构建/画线动画（委托子类）|
| IconCache               | 图标纹理图集（彩色+灰度）                   |
| TileManager             | 地图瓦片管理（多分辨率）                    |
| MapRenderer             | 渲染循环协调（强制刷新 viewport）           |
| DialogUtils             | 模态对话框（确认/警告/错误）                |
| FxRippleUtil            | Material Design 涟漪动画                   |
| RestartUtils            | Native Image 安全重启                      |
| ThemeManager            | 主题切换                                   |
| WindowManager           | 窗口拖拽/缩放                               |
| CaptureServiceManager   | 截图服务控制（启动/停止/重启）              |
| SiftClientManager       | SIFT 匹配服务控制                          |
| ResourceInitService     | 资源初始化（首次运行复制外部文件）          |

**使用示例**：
- SVG 加载：`SvgManager.getInstance().loadSvg("/icon/example.svg")`
- 图标图集：`IconCache.getInstance().getIconView(resourceId, gray)`
- 显示对话框：`DialogUtils.showConfirmDialog(title, message)`
- 重启应用：`RestartUtils.restartApplication()`
- 切换主题：`ThemeManager.getInstance().setTheme("Dark")`


## 特殊约束


UI 线程
- 所有 JavaFX Node 操作必须在 `Platform.runLater()` 中执行。
- Hook 回调中更新 UI 必须使用 `Platform.runLater()`。

图标缓存
- IconCache 使用纹理图集（彩色+灰度），GPU 槽位有限，不可无限添加图标。
- 图标资源通过 ResourceInitService 从外部或 classpath 加载。

SVG 解析
- SvgManager 使用 XML DOM 解析，不支持 SVG 所有特性（仅支持基础形状和路径）。
- 画线动画通过 PathTransition 实现，需预先计算路径长度。

瓦片管理
- TileManager 最多管理 5 级瓦片（0~4），每级瓦片 256×256。
- 当前视口外的瓦片会被回收释放内存。

渲染循环
- MapRenderer 使用 AnimationTimer 驱动，每帧检查 viewportDirty。
- 地图+图标+路线快照（mapSnapshot）仅在 viewport 变化时重建。
- 玩家、hover、性能覆盖层每帧独立绘制。

窗口管理
- WindowManager 支持 8 方向边缘拖拽，边框宽度 8px。
- 幽灵模式（透明+置顶）通过 Stage 透明度和 alwaysOnTop 实现。

Native Image 兼容
- SVG 解析需反射 XML 类，已在 reachability-metadata.json 中声明。
- 图标图集中使用的 JavaFX Image 构造需反射配置。


## 与其他模块的交互


- roco-engine：监听 Hook 事件更新 UI；调用 CaptureServiceManager 控制截图；
  调用 SiftClientManager 控制匹配；调用 MapContext、ResourcePointContext 获取数据。
- roco-map：使用 ImageLoader 获取图标字节，但不直接依赖（通过 IconCache 间接调用）。
- roco-common：读取 UiConfig、RenderConfig、ViewConfig 等配置。


## 典型使用示例


// 显示通知
HookRegistry.getInstance().publish(HookEventType.UI_NOTIFICATION, "匹配成功");

// 更新加载进度
HookRegistry.getInstance().publish(HookEventType.INIT_PROGRESS, 50);

// 强制刷新地图渲染
MapRenderer.getInstance().markDirty();

// 获取玩家渲染器
PlayerRenderer.getInstance().setAngle(45.0);

// 启动截图服务
CaptureServiceManager.getInstance().startCapture();

// 切换 SIFT 变体
SiftClientManager.getInstance().switchVariant(SiftVariant.PCA_ULTRA);

// 显示设置面板
SettingsStage.getInstance().show();

// 添加地图标记
AddPointDialog.showAndWait(point -> {
ResourcePointContext.getInstance().addPoint(point);
});

// 退出应用
Platform.exit();