# UI 层架构改进计划

> 基于 2026-06-03 架构审计。Phase B (状态层重构) 已完成，此文档规划后续改进。

---

## 0. 当前状态摘要

Phase B 已完成：
- PlayerState 合并到 ViewportState（B0）
- ViewportState 单一状态源（B1）
- Config 同步回调（B2）
- HookRegistry → AppEvents 全量迁移（B3+B4）
- 旧事件系统 7 个文件已删除（IHook, AbstractGenericHook, HookEventType, HookRegistry, HookMulticast, HookEventTask, HookContainer）
- 文档同步（B5）

### 现有评分（修正后）

| 维度 | 评分 |
|------|------|
| 可维护性 | 7/10 |
| 可扩展性 | 8/10 |
| 解耦程度 | 7/10 |
| 状态管理 | 6/10 |
| 重复代码 | 5/10 |
| 复杂度 | 7/10 |
| **总分** | **6.7/10** |

### 已排除的重构项

- MapRenderer/PlayerRenderer：渲染器和帧循环编排器职责内聚，不拆分。

---

## 1. 核心问题清单

### P0 级（架构 Bug）

| # | 问题 | 位置 | 现状 |
|---|------|------|------|
| S1 | **DialogUtils 上帝类** | `DialogUtils.java` 877 行 | 8 种弹窗全部内联，`buildBaseDialog` 4 层级联重载，每类弹窗 60-120 行重复代码（遮罩层、样式、动画、图标） |
| S2 | **ModernCanvasApp 多职责** | `ModernCanvasApp.java` 527 行 | 同时承担：Application 生命周期 + 场景骨架 + 资源初始化编排 + 更新 UI 委托 + 插件 UI 委托 + 版本切换回调 + sniffer 安装逻辑 |

### P1 级（状态流）

| # | 问题 | 位置 | 现状 |
|---|------|------|------|
| S3 | **MapContext 视口双状态源** | MapContext(engine) + ViewportState(ui) | MapRenderer 每帧从 MapContext 读 offsetX/Y/scale 同步到 ViewportState。Phase B 已建立 ViewportState，但 MapContext 仍持有 5 个视口字段（scale, offsetX/Y, viewWidth/Height），engine 和 UI 都依赖它 |
| S4 | **AppState ↔ Config 双向同步** | AppState.setter → Config field + ConfigPersistence → AppState.reloadFromConfig() | 虽然用 volatile Runnable + Platform.runLater 解决了临时竞态，但不对称：AppState 写 Config，Config 加载回调写 AppState。新增状态时容易忘记注册回调 |

### P2 级（代码组织）

| # | 问题 | 位置 | 现状 |
|---|------|------|------|
| S5 | **Sidebar 业务逻辑不应在 UI 类中** | `Sidebar.java` 413 行 | switchAlgorithm/switchResource/switchTheme/handleNavOption 内联在 UI 类中 |
| S6 | **markDirty() 空方法残留** | `MapRenderer.java` | 方法体已空（line 145-146），但 10+ 处调用仍在传递空 Runnable，混淆阅读 |
| S7 | **StatusCarouselEvent 多余** | engine 层，独立 event record | 结构与 StatusEvent 完全一致（文本 + 类型），仅显示位置不同（TitleBar 轮播 vs Toast 弹窗），可合并 |

### P3 级（长期）

| # | 问题 | 位置 | 现状 |
|---|------|------|------|
| S8 | **Config 手动 getter/setter 样板代码** | `SettingDefinitions.java` ~600 行 | 每个配置项需手写 `()->Config.FIELD` + `v->Config.FIELD = v` lambda，57 个配置项 = 114 个 lambda |
| S9 | **设置面板分类渲染 if-else 发散** | `SettingsStage.refreshCategory()` | 4 条渲染路径（普通/玩家/ROI+OCR/插件管理）全部内联在单个 if-else 链中，新增分类需修改此方法 |
| S10 | **RouteListEvent 可用 ObservableList 替代** | `RouteListEvent.java` + RouteManagerStage | 一个事件仅包装 `List<RoutePath>`，PathContext 路线列表可直接用 ObservableList 驱动 UI |
| S11 | **匹配开关三路同步重复发 StatusCarouselEvent** | SidebarCell + TitleBar + SettingsStage | 三处触发入口各发一次轮播事件，TitleBar 收到 2-3 次重复 |

---

## 2. 目标架构

```
┌────────────────────────────────────────────────────────────┐
│  UI 组件层                                                   │
│  ┌──────────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐  │
│  │ 响应式控件     │ │ 交互组件  │ │ 渲染器    │ │ 其他     │  │
│  │ bind/addList.│ │ get()    │ │ onFrame  │ │ 对话框   │  │
│  └──────┬───────┘ └────┬─────┘ └────┬─────┘ └────┬─────┘  │
│         │              │            │            │         │
│         ▼              ▼            ▼            ▼         │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  UI State 层 (JavaFX Property)                        │  │
│  │  ViewportState + AppState                             │  │
│  │  所有 UI 组件只从这里读，永不 import engine Context       │  │
│  └────────────┬─────────────────────────────────────────┘  │
│               │ Platform.runLater()                        │
│  ┌────────────┴─────────────────────────────────────────┐  │
│  │  StateBridge (单点 EventBus 订阅)                      │  │
│  └────────────┬─────────────────────────────────────────┘  │
└───────────────┼────────────────────────────────────────────┘
                │ AppEvents / EventBus
┌───────────────┼────────────────────────────────────────────┐
│  Engine 层    │                                            │
│               ▼                                            │
│  MapContext  CameraContext  PlayerStateTracker  ...        │
│  └ 业务逻辑: zoom / ensureBounds / setFollowMode             │
│  └ 视口字段 (scale/offsetX/Y) → 待迁移到 ViewportState      │
└────────────────────────────────────────────────────────────┘
```

### 核心规则

1. UI 组件永不 `import` engine Context 类（MapContext、CameraContext...）
2. UI 只读 UI State 层的 JavaFX Property
3. UI 写操作通过 engine API（如 `CameraContext.setFollowMode()`）
4. StateBridge 是唯一 EventBus → UI 的桥接点
5. Config 类只负责持久化，运行时状态放 AppState

---

## 3. 分阶段实施计划

### Phase 1 — 快速清理（预计 1 周）

#### 1.1 DialogUtils → 独立 Dialog 类

**文件**：`DialogUtils.java` (877 行 → 删除)

**改动**：创建 `component/dialog/` 包，每个弹窗类型独立类：

| 新文件 | 从 DialogUtils 提取的方法 | 行数估计 |
|--------|--------------------------|---------|
| `AbstractDialog.java` | 公共基类：遮罩层、样式、透明度动画、`fadeOutAndRemove` | ~80 |
| `ConfirmDialog.java` | `showSimpleDialog`, `showConfirmDialog` (2 个) | ~80 |
| `UpdateDialog.java` | `showUpdateDialog`, `showUpdateReadyDialog` | ~90 |
| `DownloadProgressDialog.java` | `showDownloadProgressDialog` + `ProgressControl` 内部类 | ~80 |
| `PluginUpdateDialog.java` | `showPluginUpdatesDialog` | ~120 |
| `FirstRunDialog.java` | `showFirstRunDialog` | ~100 |
| `AboutDialog.java` | `showAboutDialog` | ~90 |
| `ModalConfirmDialog.java` | `showModalConfirmDialog` | ~120 |

**调用方修改**：ModernCanvasApp, Sidebar, RouteManagerStage, ResourceInitService, SettingsStage, WikiUpdateManager

**风险**：低 — 纯提取，不改变行为。注意 `DialogUtils.showDownloadProgressDialog` 返回 `ProgressControl` 接口，需保持返回类型一致。

**收益**：
- 消除 877 行 God Class
- 每类弹窗可独立修改、测试
- 新增弹窗类型不需要改已有文件

#### 1.2 StatusCarouselEvent 合并到 StatusEvent

**文件**：
- `StatusCarouselEvent.java` (engine) → 删除
- `StatusEvent.java` (engine) → 增加 `displayMode` 字段
- `TitleBar.java`, `SidebarCell.java` → 订阅逻辑调整

**改动**：

```java
// StatusEvent.java 新增枚举
public enum DisplayMode { TOAST, CAROUSEL, BOTH }
// 新增字段
private final DisplayMode displayMode;  // 默认 BOTH
```

```java
// TitleBar: 之前 subscribe StatusCarouselEvent → 改为 subscribe StatusEvent
AppEvents.subscribe(StatusEvent.class, event -> {
    if (event.displayMode() == StatusEvent.DisplayMode.TOAST) return;
    Platform.runLater(() -> updateStatus(event));
});

// UiResponseHook: 之前 subscribe StatusEvent → 过滤 TOAST/BOTH
AppEvents.subscribe(StatusEvent.class, event -> {
    if (event.displayMode() == StatusEvent.DisplayMode.CAROUSEL) return;
    Platform.runLater(() -> ToastManager.show(...));
});
```

**风险**：低 — 纯重构

**收益**：-1 文件，~30 行，消除事件类型冗余

#### 1.3 删除 markDirty() 空方法 + 调用点

**文件**：`MapRenderer.java` + 10 处调用点

**改动**：
- 删除 `markDirty()` 空方法（line 145-146）
- 删除所有调用点，或替换为 `@Deprecated` 空方法（兼容外部调用）

**收益**：消除混淆，~20 行

---

### Phase 2 — 职责分离（预计 2 周）

#### 2.1 ModernCanvasApp 拆分

**文件**：`ModernCanvasApp.java` (527 行)

**改动**：将内联委托提取为独立类

```java
// 新文件: AppUpdateUiHandler.java — 从 ModernCanvasApp 提取
public class AppUpdateUiHandler implements UpdateUiDelegate {
    private final StackPane rootStack;
    private final Sidebar sidebar;
    // ProgressControl 生命周期
    private volatile DialogUtils.ProgressControl downloadProgress;
    private volatile boolean backgroundMode;
    // showNotification, showUpdateAvailable, showDownloadProgress, etc.
}

// 新文件: PluginUpdateUiHandler.java — 提取
public class PluginUpdateUiHandler implements PluginUpdateUiDelegate {
    private final StackPane rootStack;
    // showPluginUpdatesAvailable, showDownloadProgress, etc.
}

// 新文件: SnifferInstallService.java — 提取
// 版本切换时 sniffer 插件的下载/安装逻辑
public class SnifferInstallService {
    public void installIfNeeded(StackPane rootStack, int port, PcapBridgeManager pcapBridgeManager);
}
```

**风险**：中 — 注意 `rootStack`、`sidebar`、`floatToolbox` 等引用的生命周期。ModernCanvasApp 通过 lambda 捕获这些引用，提取后需要构造器注入。

**收益**：
- ModernCanvasApp 527 行 → ~250 行
- UI 回调逻辑可独立测试
- 新增回调不需改 ModernCanvasApp

#### 2.2 Sidebar 业务逻辑提取

**文件**：`Sidebar.java` (413 行)

**改动**：

```java
// 新文件: SidebarActionHandler.java
public class SidebarActionHandler {
    public void switchAlgorithm(String name, SidebarItem header) { ... }
    public void switchResource(String name, SidebarItem header) { ... }
    public void switchTheme(String name, SidebarItem header) { ... }
    public void handleNavOption(String value, SidebarItem header) { ... }
}
```

Sidebar 仅保留 UI 布局和列表管理。

**风险**：低 — 纯提取

**收益**：Sidebar ~260 行，业务逻辑可测试

#### 2.3 MapContext 视口剥离

> 这是 Phase B 的遗留工作。Phase B 建立了 ViewportState 并让渲染器从其读取，但 MapContext 仍持有 scale/offsetX/Y/viewWidth/viewHeight，且 engine 侧代码（MapMatcherProcessor、CameraContext、MapCoordinateManager 等）仍直接读写 MapContext 的视口字段。

**目标**：MapContext 只保留地图数据和玩家坐标，视口状态完全由 ViewportState 持有。

**前置条件**：确认所有读 MapContext 当前视口的组件都已改为读 ViewportState。

**需要修改的组件**：

| 文件 | 当前 | 改为 |
|------|------|------|
| `MapContext.java` (engine) | `setOffsetX/Y`, `ensureBounds()`, `zoom()`, `setScale()`, `setViewWidth/Height()` 等 | 方法签名和字段删除 |
| `InteractiveCanvas.java` | `mapManager.setOffsetX/Y()`, `mapManager.zoom()` | 修改或保留为 engine 委托 |
| `CameraContext.java` | `applyFollowViewport()`, `updateViewport()` → `MapContext.setOffsetX/Y/Scale()` | 需要桥接 |
| `MapRenderer.java` | `mm.getOffsetX/Y/Scale()` | 已改 → ViewportState |
| `MapCoordinateManager.java` | 地图坐标转换 | 只读地图数据，不涉及视口 |

**风险**：高 — 涉及 engine 层大量方法签名变更，需要逐步迁移。分两步：
1. ViewportState 成为视口 source of truth，MapContext 视口方法委托到 ViewportState
2. 清理无调用者的 MapContext 方法

**收益**：根治渲染层从哪里读状态的歧义。不再需要 MapRenderer 每帧手动同步到 ViewportState。

---

### Phase 3 — 长期演进（预计 1 月）

#### 3.1 Config Observable 化

**当前痛点**：
- SettingDefinitions.java 中 57 个配置项各需手写 `getter/setter` lambda（114 个 lambda，~600 行）
- AppState 和 Config 的双向同步是手动的
- 新增配置项需要改 3 个文件（Config 类 + SettingDefinitions + AppState 如果需要监听）

**方案**：`ConfigProperty<T>`

```java
// 统一的 Config Property，自动持久化 + 可观测
public class ConfigProperty<T> {
    private final SimpleObjectProperty<T> value;
    private final String key;
    private final T defaultValue;

    // 写入时自动标记 dirty
    public void set(T v) { value.set(v); ConfigPersistence.markDirty(); }
    public T get() { return value.get(); }
    public ObservableValue<T> asObservable() { return value; }
}

// Config 类改为:
public class SiftConfig {
    public static final ConfigProperty<Integer> SIFT_N_FEATURES
            = new ConfigProperty<>("SIFT_N_FEATURES", 2000);
}
```

**风险**：极高 — Config 类被 engine + UI 大量引用，当前是 static volatile 字段。需要考虑：
- 向后兼容：现有 `SiftConfig.SIFT_N_FEATURES` 直接读的需要保持可用
- 序列化兼容：ConfigPersistence 的 JSON 结构不能变
- 性能：ConfigProperty 的 Observable 机制不能比 volatile 慢太多

**建议**：仅做调研和设计，不实际执行，除非有充足的时间预算。

#### 3.2 设置面板分类渲染策略化

**文件**：`SettingsStage.java` (604 行)

**改动**：

```java
// 策略接口
public interface CategoryRenderer {
    Node render(SettingConfigManager configManager, SettingCategory category);
}

// 具体策略
public class NormalCategoryRenderer implements CategoryRenderer { ... }
public class PlayerCategoryRenderer implements CategoryRenderer {
    // PlayerPreview 生命周期
}
public class RoiCategoryRenderer implements CategoryRenderer {
    // RoiPreview + ROI 坐标参数面板
}
public class PluginCategoryRenderer implements CategoryRenderer { ... }
```

`SettingsStage.refreshCategory()` 的 180 行 if-else 变为一行：
```java
CategoryRenderer renderer = rendererRegistry.get(category.name());
rightPanel.getChildren().setAll(renderer.render(configManager, category));
```

**风险**：中 — 注意 RoiPreview/PlayerPreview 生命周期在策略类中的一致性管理

**收益**：SettingsStage 减少 120 行，新增分类只需新增策略类

#### 3.3 RouteListEvent 信号化

**文件**：`PathContext.java` (engine), `RouteListEvent.java`, `RouteManagerStage.java`

**改动**：
- RouteListEvent 去掉数据负载，改为信号型（`INSTANCE` 单例）
- PathContext.notifyChanged() 只发信号，不做防御性拷贝
- RouteManagerStage 收到信号后直接从 `PathContext.getInstance().getSavedRoutes()` 读取

**收益**：RouteListEvent 简化为信号，减少防御性拷贝开销，RouteManagerStage 直接读源

---

## 4. 文件变更汇总

| Phase | 文件 | 操作 | 代码量变化 | 风险 |
|-------|------|------|-----------|------|
| P1.1 | `DialogUtils.java` | 删除 | -877 | 低 |
| P1.1 | `component/dialog/*.java` | 新建 8 个 | +~760 | 低 |
| P1.1 | 调用方 6 个文件 | 修改 import | ~0 | 低 |
| P1.2 | `StatusCarouselEvent.java` | 删除 | -1 文件 | 低 |
| P1.2 | `StatusEvent.java` | 修改 | +~5 | 低 |
| P1.2 | `TitleBar.java`, `UiResponseHook.java`, 发布者 | 修改 | ~0 | 低 |
| P1.3 | `MapRenderer.java` | 删除空方法 | -10 | 极低 |
| P1.3 | 10 处调用点 | 清理 | -10 | 极低 |
| **P1 合计** | | | **~-132 + 760 (净 +~628)** | |
| P2.1 | `ModernCanvasApp.java` | 提取 | -277 | 中 |
| P2.1 | `AppUpdateUiHandler.java` | 新建 | +~200 | 中 |
| P2.1 | `PluginUpdateUiHandler.java` | 新建 | +~60 | 中 |
| P2.1 | `SnifferInstallService.java` | 新建 | +~120 | 中 |
| P2.2 | `Sidebar.java` | 提取 | -150 | 低 |
| P2.2 | `SidebarActionHandler.java` | 新建 | +~150 | 低 |
| P2.3 | `MapContext.java` (engine) | 视口剥离 | -~80 | 高 |
| P2.3 | 调用方 ~5 个文件 | 调整 | ~0 | 高 |
| **P2 合计** | | | **~-277 + 530 (净 +~253)** | |
| P3.1 | Config 类全部改造 | 调研阶段 | -~600 (SettingDefinitions) | 极高 |
| P3.2 | `SettingsStage.java` | 策略化 | -120 | 中 |
| P3.2 | `CategoryRenderer` 接口 + 4 个实现 | 新建 | +~200 | 中 |
| P3.3 | `RouteListEvent.java` | 删除 | -1 文件 | 低 |

---

## 5. 验证策略

### 每次提交后

```bash
mvn clean compile -q          # 编译零错误
mvn test                      # 测试通过
```

### Phase 1 验证

| 交付物 | 验证方式 |
|--------|---------|
| Dialog 拆分 | 每种弹窗在应用中触发一次：确认/更新/进度/插件更新/首次运行/关于 |
| StatusEvent 合并 | TitleBar 轮播文本正常显示，Toast 通知正常弹出 |
| markDirty 清理 | 路线编辑/悬停/拖拽后地图正常刷新 |

### Phase 2 验证

| 交付物 | 验证方式 |
|--------|---------|
| ModernCanvasApp 拆分 | 启动应用，更新检查/下载/安装流程正常，版本切换正常 |
| Sidebar 拆分 | 算法切换/主题切换/资源模式切换正常 |
| MapContext 视口剥离 | 拖拽/缩放/跟随/导航全链路正常 |

### Phase 3 验证

| 交付物 | 验证方式 |
|--------|---------|
| 设置面板策略化 | 11 个分类渲染正常，ROI 预览/玩家预览生命周期管理正确 |
| RouteListEvent 替换 | 路线增删改后列表自动刷新 |

---

## 6. 不做的事项

| 事项 | 原因 |
|-----|------|
| **MapRenderer 拆分 onFrameInternal** | 渲染编排器集中调度是职责内聚，拆分增加无意义的间接层 |
| **PlayerRenderer 拆分波纹/光环** | 共享同一 playerGroup 变换链，分离后坐标同步更复杂 |
| **HoverRenderer/RouteRenderer** | 使用 Canvas 屏幕坐标绘制，定位合适，不拆分 |
| **StatsOverlay 状态化** | 每帧读 engine StatsContext 是性能最优的方式（只在活跃时渲染文字） |
| **TileManager 职责调整** | 瓦片生命周期管理内聚，无跨领域耦合 |
| **NavigationController 状态化** | 纯算法类，无 UI 依赖，无 JavaFX Property 的必要 |
