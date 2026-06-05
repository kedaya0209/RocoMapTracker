# UI 状态层重构方案

> 目标：解决 UI 层状态管理混乱、跨组件联动成本高的问题。
> 核心思路：Engine 负责状态来源与业务逻辑，UI State 层负责可观测性，EventBus 负责同步。

---

## 1. 现状问题

### 1.1 状态分散在三层

```
┌─────────────────────────────────────────────────┐
│  UI 组件层                                        │
│  FloatToolbox  TitleBar  Sidebar  InteractiveCanvas ... │
│     ↕ getInstance()       ↕ HookRegistry        │
├─────────────────────────────────────────────────┤
│  Engine Context 层                                │
│  MapContext (18字段)  CameraContext (4字段)        │
│  PathContext  StatsContext  ResourcePointContext  │
├─────────────────────────────────────────────────┤
│  Config 层 (被当运行时状态用)                       │
│  NavigConfig  SiftConfig  ViewConfig  ...        │
└─────────────────────────────────────────────────┘
```

问题：

- **Engine Context 是 God Object**：`MapContext` 持 18 个 `volatile` 字段 + Lombok `@Getter @Setter`，UI 和 engine 代码都直接读写
- **两个事件总线并行**：旧 `HookRegistry`（虚拟线程 dispatch）+ 新 `AppEvents/EventBus`，同一个状态变更走两条路径
- **Config 类被当运行时状态**：`NavigConfig.NAVIGATION_ENABLED`、`SiftConfig.SIFT_MATCHING_ENABLED`、`ViewConfig.MATERIAL_COLLECTION` 等本应用来持久化的字段，在运行时被频繁读写
- **无可观测状态**：UI 要么每帧轮询 `volatile` 字段，要么通过 `HookRegistry` 手动 `Platform.runLater`
- **UI → Engine 强耦合**：UI 组件直接 `import` engine 模块的 `MapContext`、`CameraContext` 等

### 1.2 两套事件路径

```
CameraContext.setFollowMode()
  ├─ HookRegistry.INSTANCE.publish(FOLLOW_MODE_CHANGED, ...)   ← 旧
  │     └─ virtual thread dispatch → AbstractGenericHook.onEvent()
  │           └─ Platform.runLater() → icon.setFill(...)       ← 手动绕回 FX 线程
  │
  └─ AppEvents.publish(FollowModeEvent.class, ...)              ← 新 (Phase A)
        └─ EventBus → StateBridge
              └─ Platform.runLater() → ViewportState.setFollowMode()
                    └─ JavaFX Property 自动通知                  ← bind() 自动响应
```

问题：`Sidebar` 和 `SettingDefinitions` 在调 `CameraContext.setNavMode()` 之后，还要**手动补发两套事件**，因为 `setNavMode()` 只发了 `AppEvents` 没发 `HookRegistry`。调用方在补实现方的漏洞。

### 1.3 数据流不可追踪

要理解点击"跟随"按钮的完整链路，需要看：

1. `FloatToolbox` — 按钮点击 → `CameraContext.setFollowMode()`
2. `CameraContext.setFollowMode()` — CAS 更新 → `HookRegistry.publish` + `AppEvents.publish`
3. `Sidebar.handleNavOption()` — 手动 `NavigConfig.NAVIGATION_ENABLED = x` + `CameraContext.setNavMode()` + `HookRegistry.publish` + `AppEvents.publish`
4. `SettingDefinitions` — 同 Sidebar，四连发
5. `TitleBar.toggleNavMode()` — 只有 `CameraContext.setNavMode()`，没发 HookRegistry
6. `InteractiveCanvas.onMouseDragged()` — 直接 `cameraManager.setFollowMode(false)`

同一种操作，三种不同的写入风格。

---

## 2. 目标架构

```
┌─────────────────────────────────────────────────────────────┐
│  UI 组件层                                                    │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  交互组件 (InteractiveCanvas / PathEditor)            │   │
│  │  读: ViewportState / PlayerState                     │   │
│  │  写: CameraContext.setXxx() / MapContext.setXxx()    │   │
│  ├──────────────────────────────────────────────────────┤   │
│  │  响应式控件 (FloatToolbox / TitleBar / Sidebar)       │   │
│  │  绑定: ViewportState.followModeProperty().bind()     │   │
│  │  写: CameraContext.setFollowMode()                   │   │
│  ├──────────────────────────────────────────────────────┤   │
│  │  渲染器 (MapRenderer / 子渲染器)                      │   │
│  │  读: ViewportState.getXxx() / PlayerState.getXxx()   │   │
│  │  无写                                                   │   │
│  └──────────────────────────────────────────────────────┘   │
│                            ↕                                 │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  UI State 层 (JavaFX Property, 可观测)                │   │
│  │  PlayerState  ViewportState  AppState                │   │
│  │  └ 所有 UI 组件从此读，永不 import engine context       │   │
│  └──────────────────────────────────────────────────────┘   │
│                            ↕                                 │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  StateBridge (单点订阅 EventBus → Platform.runLater) │   │
│  └──────────────────────────────────────────────────────┘   │
└──────────────────────────┬──────────────────────────────────┘
                           │ AppEvents / EventBus
┌──────────────────────────┴──────────────────────────────────┐
│  Engine 层 (source of truth)                                 │
│  MapContext  CameraContext  PlayerStateTracker  ...          │
│  └ 业务逻辑: zoom / ensureBounds / setFollowMode ...          │
└─────────────────────────────────────────────────────────────┘
```

### 核心规则

1. **UI 组件永不 `import` engine Context 类**（MapContext、CameraContext、StatsContext...）
2. **UI 只读 UI State 层的 JavaFX Property**
3. **UI 写操作通过 engine API**（如 `CameraContext.setFollowMode()`），engine 负责 publish EventBus
4. **StateBridge 是唯一 EventBus 订阅者**，将事件同步到 UI State
5. **UI 控件通过 `bind()` / `Bindings.when()` 自动响应**，无需手动 addListener + Platform.runLater
6. **渲染器每帧 pull**（帧循环本质决定），但数据来源从 engine volatile 改为 UI State

---

## 3. 组件逐项分析

### 3.1 需改进的组件

#### A 组：写路径需统一 (3 个文件)

| # | 文件 | 现状问题 | 改动 |
|---|------|---------|------|
| A1 | `Sidebar.java` (line 200-204) | 手动写 `NavigConfig` + 调 `CameraContext.setNavMode()` + 手动 publish 两套事件 | 删掉 line 203-204，只保留 `CameraContext.getInstance().setNavMode(enabled)` |
| A2 | `SettingDefinitions.java` (line 284-288) | 同样四连发 | 删掉 line 286-287，只保留 `CameraContext.getInstance().setNavMode(enabled)` |
| A3 | `CameraContext.setNavMode()` (engine) | 只发 `AppEvents` 没发 `HookRegistry` → 导致 A1/A2 必须手动补发 | 内部加一行 `HookRegistry.INSTANCE.publish(NAV_MODE_CHANGED, ...)` |

#### B 组：渲染器读路径迁移 (6 个文件)

核心改动：`MapContext.getInstance()` → `ViewportState.getInstance()`，`CameraContext.getInstance()` → `ViewportState.getInstance()`

| # | 文件 | 当前读 engine 的字段 | 改为 |
|---|------|-------------------|------|
| B1 | `MapRenderer.java` | `MapContext.offsetX/Y/scale` + `CameraContext.navAngle/navMode` | `ViewportState` 全部替代。首帧 autoFitViewport 仍用 `MapContext`（写逻辑） |
| B2 | `PlayerRenderer.java` | `CameraContext.navAngle/navMode` (其余已是 PlayerState) | `ViewportState` |
| B3 | `RouteRenderer.java` | `MapContext.offsetX/Y/scale` + `CameraContext.navAngle/navMode` | `ViewportState` |
| B4 | `HoverRenderer.java` | `MapContext.offsetX/Y/scale` + `CameraContext.navAngle/navMode` | `ViewportState` |
| B5 | `IconLayerManager.java` | `CameraContext.navAngle/navMode` + `MapContext.playerX/Y` | `ViewportState` + `PlayerState` |
| B6 | `PathEditor.java` | `MapContext.offsetX/Y/scale` (交互时读，非每帧) | `ViewportState` |

#### C 组：交互组件读路径迁移 (3 个文件)

| # | 文件 | 当前读 engine 的字段 | 改为 |
|---|------|-------------------|------|
| C1 | `InteractiveCanvas.java` | `MapContext.scale/ox/oy` + `CameraContext.followMode/navMode/navAngle/validPlayerPos` | 读 → `ViewportState` + `PlayerState`；写 → 保持 `MapContext/CameraContext` API |
| C2 | `TitleBar.java` | `CameraContext.navMode` (line 209 EventBus 订阅同步) + line 295 `cam.setNavMode()` 写 | 读 → `ViewportState` 绑定；写 → 保持 `CameraContext` API |
| C3 | `NavigationController.java` | `CameraContext.navAngle` | 读 → `ViewportState`；写 → 保持 `CameraContext.setNavAngle()` |

#### D 组：响应式绑定替代 HookRegistry (4 个文件)

| # | 文件 | 当前 HookRegistry 订阅 | 改为 |
|---|------|----------------------|------|
| D1 | `TitleBar.java` | `NAV_MODE_CHANGED` → `setNavModeFromExternal()` (line 478, 490) | `ViewportState.navModeProperty().addListener()` |
| D2 | `SidebarCell.java` | `STATUS_CAROUSEL` → 同步匹配开关 (line 172) | 待定：可改为 `AppState.matchingEnabledProperty().bind()` |
| D3 | `SettingsStage.java` | `STATUS_CAROUSEL` → 同步开关 (line 93, 265) | 同上 |
| D4 | `FloatToolbox.java` | ✅ 已改为 `ViewportState.followModeProperty().bind()` | 已完成 |

#### E 组：Config 运行时状态迁移 (3 个字段)

| # | 字段 | 当前 | 改为 |
|---|------|------|------|
| E1 | `NavigConfig.NAVIGATION_ENABLED` | Sidebar 手动读写，TitleBar 写 | 存入 `AppState.navModeProperty`，ConfigPersistence 仅持久化 |
| E2 | `ViewConfig.MATERIAL_COLLECTION` | FloatToolbox 切换时写 | 存入 `AppState.materialCollectionProperty` |
| E3 | `SiftConfig.SIFT_MATCHING_ENABLED` | SidebarCell/TitleBar 读+写 | 存入 `AppState.matchingEnabledProperty` |

#### F 组：ViewportState 补全同步 (2 个文件)

| # | 文件 | 现状 | 改动 |
|---|------|------|------|
| F1 | `InteractiveCanvas.bindViewport()` (line 67) | Canvas resize → 只写 `MapContext.viewWidth/Height`，ViewportState 不知情 | 双写 `MapContext` + `ViewportState` |
| F2 | `StateBridge.java` | 已订阅 PlayerState/FollowMode/NavMode | 新增 NavAngle 同步 |

---

### 3.2 无需改进的组件

这些组件**不使用 engine Context**，只读 Config 常量或纯本地状态，无需迁移：

| 文件 | 原因 |
|------|------|
| `NotificationToast.java` | 只读 RenderConfig/UiConfig 常量，无 getInstance 调用 |
| `ToastManager.java` | 同上 |
| `StatsOverlay.java` | 只读 StatsConfig + StatsContext（纯 engine 统计，UI 无需镜像） |
| `CheckUpdateManager.java` | 只读 UpdateManager 状态 + SVG 图标，无 Context 依赖 |
| `VersionSelectorPanel.java` | 只读 VersionManager + SnifferConfig，纯本地 UI + 文件 I/O |
| `WikiUpdateManager.java` | 只读 DownloadProgressContext，纯进度回调 |
| `AddPointDialog.java` | 读/写 ResourcePointContext（数据操作，非状态） |
| `ContextMenuManager.java` | 读/写 ResourcePointContext（同上） |
| `HoverManager.java` | 读 PathContext + ResourcePointContext（数据查询） |
| `RouteManagerStage.java` | 读/写 PathContext（数据操作） + HookRegistry 发布 UI_NOTIFICATION（通知型） |
| `ResourceCounterPanel.java` | HookRegistry 订阅 MATERIAL_COLLECTION（事件通知） |
| `LoadingOverlay.java` | HookRegistry 订阅 INIT_PROGRESS（一次性事件） |
| `SidebarComponent.java` | 纯接口，无状态访问 |
| `UiAnimator.java` | 纯动画，无状态 |
| `DialogUtils.java` | 纯工具类 |
| `CoordinateUtil.java` | 纯数学工具类 |
| `TrayManager.java` | 系统托盘，无 Context 依赖 |
| `WindowManager.java` | 窗口管理，无 Context 依赖 |
| `ThemeManager.java` | 主题切换，无 Context 依赖 |
| `VersionManager.java` | 版本模式，无 Context 依赖 |
| `MainUiComposer.java` | 一次性的场景图构建，调用 `MapContext.getInstance().getMapWidth/Height()` （只读一次地图尺寸，初始化后不再变动，可以保留） |

### 3.3 保持 HookRegistry 不变的事件类组件

这些组件订阅的是一次性**事件通知**，不是持续状态，不适合用 Property。保持现状：

| 文件 | 事件类型 | 原因 |
|------|---------|------|
| `RouteManagerStage.java` | `ROUTE_LIST_CHANGED` | 一次性刷新列表的通知 |
| `ResourceCounterPanel.java` | `MATERIAL_COLLECTION_UPDATED` | 采集数据到达的通知 |
| `MapRenderer.java` | `RESOURCE_POINT_CHANGED` | 资源点变更通知 → markDirty |
| `LoadingOverlay.java` | `INIT_PROGRESS` | 初始化进度事件 |
| `UiResponseHook.java` | `UI_NOTIFICATION`、`INIT_PROGRESS`、`CAPTURE_STATE` | 通用 UI 事件桥接 |

---

## 4. 改造清单汇总

### 4.1 需修改的文件 (18 个)

| 优先级 | # | 文件 | 改动量估计 | 说明 |
|--------|---|------|----------|------|
| P0 | A1 | `Sidebar.java` | 删 2 行 | 消除重复 publish |
| P0 | A2 | `SettingDefinitions.java` | 删 2 行 | 消除重复 publish |
| P0 | A3 | `CameraContext.java` (engine) | 加 2 行 | setNavMode 补齐 HookRegistry 发布 |
| P1 | B1 | `MapRenderer.java` | ~5 行 | import + 字段替换 |
| P1 | B2 | `PlayerRenderer.java` | ~3 行 | 同上 |
| P1 | B3 | `RouteRenderer.java` | ~5 行 | 同上 |
| P1 | B4 | `HoverRenderer.java` | ~5 行 | 同上 |
| P1 | B5 | `IconLayerManager.java` | ~5 行 | 同上 |
| P1 | B6 | `PathEditor.java` | ~3 行 | 同上 |
| P2 | C1 | `InteractiveCanvas.java` | ~10 行 | import + 方法内替换 |
| P2 | C2 | `TitleBar.java` | ~5 行 | 绑定替换 Hook 回调 |
| P2 | C3 | `NavigationController.java` | ~3 行 | 字段替换 |
| P3 | D1 | `TitleBar.java` (NAV_MODE) | ~5 行 | addListener 替代 Hook |
| P3 | D2 | `SidebarCell.java` (STATUS_CAROUSEL) | 待定 | 需增加 AppState |
| P3 | D3 | `SettingsStage.java` (STATUS_CAROUSEL) | 待定 | 同上 |
| P4 | E1-E3 | `AppState.java` (新建) | ~50 行 | 新文件 |
| P5 | F1 | `InteractiveCanvas.java` (bindViewport) | ~5 行 | 双写 |
| P5 | F2 | `StateBridge.java` | ~10 行 | 补全 navAngle 同步 |
| - | - | `ViewportState.java` | ~30 行 | 新增 navAngle/navMode 字段 |

### 4.2 无需修改的文件 (23 个)

NotificationToast, ToastManager, StatsOverlay, CheckUpdateManager,
VersionSelectorPanel, WikiUpdateManager, AddPointDialog,
ContextMenuManager, HoverManager, RouteManagerStage,
ResourceCounterPanel, LoadingOverlay, SidebarComponent,
UiAnimator, DialogUtils, CoordinateUtil, TrayManager,
WindowManager, ThemeManager, VersionManager, MainUiComposer,
ModernCanvasApp, FloatToolbox (已改完)

---

## 5. 分阶段实施计划

### 阶段 1：统一写路径 (P0)

**目标**：消除 Sidebar/SettingDefinitions 的手动重复 publish。

**改动**：
1. `CameraContext.setNavMode()` 内部加 `HookRegistry.INSTANCE.publish(NAV_MODE_CHANGED, new NavModeEvent(navMode))`
2. `Sidebar.handleNavOption()` 删掉 `HookRegistry.publish` 和 `AppEvents.publish`
3. `SettingDefinitions` line 285-287 删掉重复 publish

**验证**：启动应用，侧边栏和导航开关切换正常。

---

### 阶段 2：渲染器读路径迁移 (P1)

**目标**：6 个渲染器/交互类从 `MapContext/CameraContext` 改为 `ViewportState/PlayerState`。

**前置条件**：
- `ViewportState` 新增 `navMode`、`navAngle`、`playerX`、`playerY` 镜像字段
- `CameraContext.setNavMode()` 发布 `NavModeEvent`
- `CameraContext.setNavAngle()` 发布 `NavAngleEvent`（新建 event record）
- `StateBridge` 订阅 `NavAngleEvent` 同步到 `ViewportState`

**改动模式**（每个渲染器相同）：

```java
// 改前
MapContext mm = MapContext.getInstance();
double ox = mm.getOffsetX();
double oy = mm.getOffsetY();
double scale = mm.getScale();

// 改后
ViewportState vp = ViewportState.getInstance();
double ox = vp.getOffsetX();
double oy = vp.getOffsetY();
double scale = vp.getScale();
```

**验证**：启动应用，地图平移/缩放/导航旋转/玩家/路线/hover 渲染正常。

---

### 阶段 3：交互组件读路径迁移 (P2)

**目标**：InteractiveCanvas、TitleBar、NavigationController 从 engine 读取改为 UI State。

**关键点**：InteractiveCanvas 中 `toLogic()` / `toLogicX()` / `toLogicY()` 的坐标转换需要 `offsetX/Y/scale`，改为从 `ViewportState` 读。但 `zoom()` / `ensureBounds()` / `setOffsetX/Y` 等写操作仍走 `MapContext` API。

**验证**：拖拽地图、缩放、跟随切换、导航旋转均正常。

---

### 阶段 4：HookRegistry 旧路径退役 (P3)

**目标**：将状态型 HookRegistry 订阅改为 JavaFX Property 绑定。

**验证**：navMode 图标、匹配开关同步正常。

---

### 阶段 5：Config 运行时状态迁移 (P4)

**目标**：新建 `AppState` 类收纳不属于 Config 的运行时状态。

**改动**：
- 新建 `AppState.java`（`SimpleBooleanProperty navMode/hasAngle/followMode/materialCollection/matchingEnabled`）
- 各组件改为 `AppState.getInstance().matchingEnabledProperty().bind()`
- 持久化时读 ConfigPersistence，运行时读 AppState

---

## 6. 改进后的优点

### 改一个 followMode 按钮，从 8 文件降到 1-2 文件

**改前**：要改 FloatToolbox 的 follow 按钮行为，需要看：
1. `FloatToolbox` — 按钮 UI
2. `CameraContext` — 状态存储 + Hook 发布
3. `HookRegistry` — 事件路由
4. `AbstractGenericHook` — 回调适配
5. `StateBridge` — EventBus 同步
6. `ViewportState` — Property 更新
7. `InteractiveCanvas` — 拖拽时关 follow
8. `TitleBar` — nav 模式自动开 follow
9. `SettingDefinitions` — 重置 follow 模式

**改后**：
1. `FloatToolbox` — 按钮 UI（`bind()` 绑定）
2. `InteractiveCanvas` — 拖拽时关 follow（读 `ViewportState.isFollowMode()`）

中间 7 层全部由 EventBus + StateBridge 自动处理，不需要手动触碰。

### 改一个 navMode 切换，从 5 文件降到 1 文件

**改前**：`TitleBar.toggleNavMode()` 切换 navMode，5 个文件联动：
- `CameraContext.setNavMode()` → EventBus + HookRegistry
- `MapRenderer` + `PlayerRenderer` + `RouteRenderer` + `HoverRenderer` 各读 `CameraContext.isNavMode()`

**改后**：
- `CameraContext.setNavMode()` → EventBus → ViewportState → 4 个渲染器下帧自动读到新值
- 渲染器不需要改任何代码——它们已改为读 `ViewportState`

### 核心收益

1. **跨组件联动自动化**：一次 `CameraContext.setFollowMode()` → 所有绑定的 UI 组件 + 所有渲染器自动响应
2. **删除大量样板代码**：`Platform.runLater`、`AbstractGenericHook`、`HookRegistry.register` 全部消失
3. **可测试性**：UI State 层是纯 JavaFX Property，可在测试中直接设置值观察组件响应
4. **模块边界清晰**：UI 不再 import engine 的 Context 类，编译层面保证解耦
5. **修改范围可控**：新加功能只需改 1-2 个文件，不需要排查「谁还读了这个状态」

---

## 附录：数据流对比

### 改前：followMode 切换

```
FloatToolbox click
  → CameraContext.setFollowMode()
    → HookRegistry.publish(FOLLOW_MODE_CHANGED)
      → (virtual thread) AbstractGenericHook.onEvent()
        → Platform.runLater() → icon.setFill(...)                 ← FloatToolbox 图标
    → AppEvents.publish(FollowModeEvent)
      → StateBridge
        → Platform.runLater() → ViewportState.setFollowMode()
  → InteractiveCanvas.onMouseDragged()
    → CameraContext.getInstance().isFollowMode()                  ← 直接读 volatile
  → TitleBar (无监听)
  → Sidebar (无监听)
  → SettingDefinitions (无监听)
```

### 改后：followMode 切换

```
FloatToolbox click
  → CameraContext.setFollowMode()
    → AppEvents.publish(FollowModeEvent)
      → StateBridge → Platform.runLater() → ViewportState.setFollowMode(true)
        → icon.fillProperty() 自动更新                              ← FloatToolbox 图标
        → InteractiveCanvas.onMouseDragged()
          → ViewportState.getInstance().isFollowMode()            ← 读 Property 值
        → TitleBar 绑定了 followModeProperty() → 自动响应
        → Sidebar 绑定了 followModeProperty() → 自动响应
        → SettingDefinitions → 同上

  | CameraContext 不再发 HookRegistry | 
  | FloatToolbox 的 icon 用 bind() 而不是 addListener + Platform.runLater |
  | 所有 UI 组件从同一 Property 读，不需要各自 import CameraContext |
```

---

## 附录：状态依赖拓扑

```
Engine 写入口                    EventBus                    UI State                    UI 消费者
─────────────────────────────────────────────────────────────────────────────────────────
CameraContext.setFollowMode()  → FollowModeEvent  → ViewportState.followMode  → bind() (FloatToolbox 图标)
                                                              ↓              → get() (InteractiveCanvas drag)
                                                              ↓              → get() (渲染器 nav 跟随)
                                                              
CameraContext.setNavMode()     → NavModeEvent      → ViewportState.navMode    → bind() (TitleBar 图标)
                                                              ↓              → get() (交互/渲染器)
CameraContext.setNavAngle()    → NavAngleEvent     → ViewportState.navAngle   → get() (渲染器/坐标转换)

PlayerStateTracker            → PlayerStateEvent  → PlayerState              → get() (渲染器/IconLayer)

MapContext (via onFrame sync) → (帧同步)          → ViewportState 其余字段     → get() (所有渲染器)
```

注意：`MapContext` 的 `offsetX/Y/scale` 等字段由 `MapRenderer.onFrame()` 末同步（或后续改为 MapContext 发布 `ViewportChangedEvent`）。这些字段的变化源是 `InteractiveCanvas` 的拖拽/缩放，或者 `CameraContext.applyFollowViewport()`。
