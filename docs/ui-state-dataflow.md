# UI 层数据流图

> 基于状态层重构后的架构。所有 UI 状态通过 JavaFX Property 可观测，
> 引擎通过 EventBus 写入，帧循环同步高频变化字段。

---

## 1. 整体架构分层

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  UI 展示层 (JavaFX Node / Canvas)                                           │
│                                                                             │
│  ┌─────────────────┐ ┌────────────┐ ┌──────────┐ ┌───────────┐             │
│  │ 响应式控件        │ │ 交互组件    │ │ 渲染器    │ │ 其他       │           │
│  │ FloatToolbox    │ │ Interactive │ │ MapRender│ │ Settings  │           │
│  │ TitleBar        │ │ Canvas     │ │ Player…  │ │ Sidebar   │           │
│  │ SidebarCell     │ │ PathEditor │ │ Route…   │ │ Tray      │           │
│  └────────┬────────┘ └─────┬──────┘ └────┬─────┘ └─────┬─────┘           │
│           │                │             │              │                 │
│      bind() / addListener()               getXxx() onFrame()              │
│           │                │             │              │                 │
│           ▼                ▼             ▼              ▼                 │
│  ┌────────────────────────────────────────────────────────────────────┐   │
│  │  UI State 层 (JavaFX Property)                                     │   │
│  │                                                                    │   │
│  │  ┌──────────────────────────────┐  ┌──────────┐                  │   │
│  │  │ ViewportState                │  │ AppState │                  │   │
│  │  │──────────────────────────────│  │─────────│                  │   │
│  │  │ scale / offsetX/Y            │  │ material │                  │   │
│  │  │ viewWidth / viewHeight       │  │ Collection│                 │   │
│  │  │ followMode / followScale     │  │ matching │                  │   │
│  │  │ navMode / navAngle           │  │ Enabled  │                  │   │
│  │  │ playerX / playerY            │  └──────────┘                  │   │
│  │  │ playerAngle / hasAngle       │                                │   │
│  │  │ playerInitialized            │                                │   │
│  │  └──────────────────────────────┘                                │   │
│  └────────────────────────────────────────────────────────────────────┘   │
│                           ↕ Platform.runLater()                           │
│  ┌────────────────────────────────────────────────────────────────────┐   │
│  │  StateBridge (单点订阅)                                             │   │
│  └────────────────────────┬───────────────────────────────────────────┘   │
└───────────────────────────┼───────────────────────────────────────────────┘
                            │ AppEvents / EventBus
┌───────────────────────────┼───────────────────────────────────────────────┐
│  Engine 层                │                                              │
│                           ▼                                              │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐               │
│  │ MapContext    │  │CameraContext │  │PlayerStateTracker│               │
│  │ offsetX/Y     │  │ followMode   │  │ playerX/Y/angle │               │
│  │ scale         │  │ navMode      │  └──────────────────┘               │
│  │ viewWidth/H   │  │ navAngle     │                                     │
│  │ playerX/Y     │  │ followScale  │  ┌──────────────┐                  │
│  └──────────────┘  └──────────────┘  │Config 类     │                  │
│                                       │(纯持久化)    │                  │
│  ┌──────────────┐  ┌──────────────┐  │ NavigConfig  │                  │
│  │ PathContext   │  │ResourcePoint │  │ SiftConfig   │                  │
│  │ (路线数据)     │  │Context       │  │ ViewConfig   │                  │
│  └──────────────┘  │ (资源点数据)   │  └──────────────┘                 │
│                    └──────────────┘                                      │
└───────────────────────────────────────────────────────────────────────────┘
```

---

## 2. 状态属性一览

### 2.1 `ViewportState` — 视口 + 相机 + 玩家状态

| 属性 | 类型 | 初始值 | 数据来源 | 同步方式 |
|------|------|--------|---------|---------|
| scale | Double | 1.0 | MapContext | 首帧 syncFromMapContext() |
| offsetX | Double | 0 | MapContext | MapRenderer 每帧读 MapContext |
| offsetY | Double | 0 | MapContext | 同上 |
| viewWidth | Double | 0 | Canvas resize | InteractiveCanvas 双写 |
| viewHeight | Double | 0 | Canvas resize | 同上 |
| followMode | Boolean | DEFAULT | CameraContext.setFollowMode() | EventBus → StateBridge |
| followScale | Double | DEFAULT | CameraContext | EventBus → StateBridge |
| navMode | Boolean | false | CameraContext.setNavMode() | EventBus → StateBridge |
| navAngle | Double | 0 | CameraContext.setNavAngle() | MapRenderer 每帧帧同步 |
| playerX | ReadOnlyDouble | -1 | PlayerStateTracker → EventBus | StateBridge 订阅 |
| playerY | ReadOnlyDouble | -1 | 同上 | 同上 |
| playerAngle | ReadOnlyDouble | 0 | 同上 | 同上 |
| hasAngle | ReadOnlyBoolean | false | 同上 | 同上 |
| playerInitialized | ReadOnlyBoolean | false | 同上 | 同上 |

### 2.2 `AppState` — 应用运行时状态

| 属性 | 类型 | 初始值 | 数据来源 | 同步方式 |
|------|------|--------|---------|---------|
| materialCollection | Boolean | ViewConfig加载值 | FloatToolbox/设置面板 | setter 自动回写 ViewConfig |
| matchingEnabled | Boolean | SiftConfig加载值 | 3 个入口(见下文) | setter 自动回写 SiftConfig |

---

## 3. 数据流详图

### 3.1 玩家位置流

```
PlayerStateTracker (engine 帧循环)
  │
  ├─ MapContext.updatePlayerState()     ← 旧路径(保留)
  │
  └─ AppEvents.publish(PlayerStateEvent)
       │
       [EventBus 异步]
       │
       StateBridge 订阅
       │
       Platform.runLater()
       │
       ViewportState.updatePlayerPosition(x, y, angle)
       │
       ├─ playerXProperty.set(x)
       ├─ playerYProperty.set(y)
       ├─ playerAngleProperty.set(angle)
       └─ playerInitializedProperty.set(true)
            │
            ├─ [渲染器每帧读取]
            │   ├─ PlayerRenderer.onFrame() → getPlayerX/Y/Angle()
            │   ├─ IconLayerManager.onFrame() → getPlayerX/Y()
            │   ├─ MapRenderer → getPlayerAngle() (传给 NavigationController)
            │   └─ InteractiveCanvas.onScroll() → getPlayerX/Y()
            │
            └─ [无 bind() 消费者 — 渲染器用 pull 模式]
```

**改前 vs 改后**：

| | 改前 | 改后 |
|---|---|---|
| 渲染器读 | MapContext.getInstance().getPlayerX() (volatile) | ViewportState.getInstance().getPlayerX() (JavaFX Property) |
| 同步方式 | 直接读 volatile | EventBus + Platform.runLater |
| 可观测性 | 无 | Property 可 bind/addListener |

### 3.2 导航模式流

```
3 个触发入口（统一写 CameraContext.setNavMode()）：
  │
  ├─ TitleBar.toggleNavMode()        ← 按钮点击
  ├─ Sidebar.handleNavOption()       ← 侧边栏开关
  └─ SettingDefinitions (设置面板)    ← 设置面板保存

                            │
                  CameraContext.setNavMode(enabled)
                            │
         ┌──────────────────┼──────────────────┐
         │                  │                  │
         ▼                  ▼                  ▼
  NavigConfig.        AppEvents.publish    [已删除]
  NAVIGATION          (NavModeEvent)       HookRegistry 路径
  _ENABLED = enabled
         │                  │
         │          [EventBus]
         │                  │
         │          StateBridge
         │                  │
         │          Platform.runLater()
         │                  │
         │                  ▼
         │          ViewportState.setNavMode(enabled)
         │                  │
         │         navModeProperty.set(enabled)
         │                  │
         │     ┌────────────┼─────────────────┐
         │     │            │                  │
         │     │            │                  │
         ▼     ▼            ▼                  ▼
     TitleBar        所有渲染器              Sidebar
     syncNavUi()     onFrame():             header 文字
     ├─ 图标变色      ├─ PlayerRenderer     (初始化时读一次)
     ├─ Slider 显示   ├─ RouteRenderer
     ├─ Stage 透明度  ├─ HoverRenderer
     └─ navMode 字段  ├─ IconLayerManager
                      └─ MapRenderer
                         (worldRotate)
```

**改前 vs 改后**：

| | 改前 | 改后 |
|---|---|---|
| Sidebar 切换 | 手动 NavigConfig + HookRegistry + AppEvents 三连发 | 只调 CameraContext.setNavMode() |
| 渲染器读 | CameraContext.getInstance().isNavMode() | ViewportState.getInstance().isNavMode() |
| TitleBar 响应 | HookRegistry NAV_MODE_CHANGED 回调 | ViewportState.navModeProperty().addListener() |

### 3.3 跟随模式流

```
触发入口：
  │
  ├─ FloatToolbox 按钮点击 → CameraContext.setFollowMode()
  ├─ InteractiveCanvas.onMouseDragged → cameraManager.setFollowMode(false)
  ├─ TitleBar.toggleNavMode() (AUTO_FOLLOW_MODE 时自动开)
  └─ 引擎内 CameraContext 逻辑

                            │
                  CameraContext.setFollowMode(enabled)
                            │
                  AppEvents.publish(FollowModeEvent)
                            │
                        [EventBus]
                            │
                      StateBridge 订阅
                            │
                      Platform.runLater()
                            │
                  ViewportState.setFollowMode(enabled)
                            │
                  followModeProperty.set(enabled)
                            │
              ┌─────────────┼──────────────┐
              │             │              │
              ▼             ▼              ▼
      FloatToolbox      InteractiveCanvas  渲染器读
      图标颜色          拖拽时判断是否关    get()
      Bindings.when():  跟随后不触发拖动
      followMode → Blue
      !followMode → White
```

**关键点**：FloatToolbox 图标用 `Bindings.when()` 纯声明式绑定，无需任何手动 `Platform.runLater`。

### 3.4 导航角度 (NavAngle) 流

> navAngle 每帧变化（~60fps），不经过 EventBus 避免 publish 风暴，通过帧循环同步。

```
NavigationController.update(playerAngle, navMode)
  │
  │  EMA 平滑 + 防抖 + 匀速旋转动画
  │
  ├─ CameraContext.setNavAngle(computedAngle)
  │     │
  │     └─ 引擎内存储，不发布 EventBus
  │
  └─ (返回后，MapRenderer.onFrameInternal 中)
                            │
                  vp.setNavAngle(cam.getNavAngle())
                            │
                  navAngleProperty.set(angle)
                            │
                  ┌─────────┼──────────┐
                  │         │          │
                  ▼         ▼          ▼
          MapRenderer   PlayerRenderer   RouteRenderer
          worldRotate   group rotate     canvas 坐标转换
          .setAngle()   .setAngle()
```

### 3.5 视口位置流 (offsetX/Y/scale)

> offset/scale 每帧随拖拽/缩放变化，不经过 EventBus。MapRenderer 读 MapContext 直接使用。

```
InteractiveCanvas (用户交互)
  │
  ├─ onMouseDragged → mapManager.setOffsetX/Y()   ← 写入 MapContext
  ├─ onScroll       → mapManager.zoom() / setScale()  ← 写入 MapContext
  └─ bindViewport() → mapManager.setViewWidth/H() + ViewportState.setViewWidth/H()
                            │
              ┌─────────────┴─────────────┐
              │                           │
              ▼                           ▼
      MapContext (引擎源)           ViewportState (首帧 sync 后只读)
      offsetX, offsetY, scale      viewWidth, viewHeight (双写)
              │
              │  MapRenderer 每帧读取
              ▼
      GPU 变换: worldTranslate.setX(ox) ...
```

### 3.6 匹配开关流

```
3 个触发入口（统一写 AppState.setMatchingEnabled()）:
  │
  ├─ TitleBar 按钮点击
  │   appState.setMatchingEnabled(nowOn)
  │
  ├─ SidebarCell ToggleSwitch 切换
  │   AppState.getInstance().setMatchingEnabled(newVal)
  │
  └─ SettingsStage (设置面板 CheckBox 保存)
      AppState.getInstance().setMatchingEnabled(v)

                            │
                  AppState.setMatchingEnabled(v)
                            │
              ┌─────────────┼──────────────┐
              │             │              │
              ▼             │              ▼
  1. matchingEnabled        │      2. SiftConfig.
     Property.set(v)        │         SIFT_MATCHING_
              │             │         ENABLED = v
              │             │              │
              ▼             │              ▼
  响应式更新：              │      Engine 读取
  ├─ TitleBar 图标          │      MapMatcherProcessor
  │  addListener →          │      .executeMatching()
  │  setSvgFill()           │      读 isMatchingEnabled()
  ├─ SidebarCell toggle     │
  │  listener → setSelected │
  └─ SettingsStage          │
     listener → cb.setSelected

              │
              ▼
  3. AppEvents.publish(StatusCarouselEvent.class, ...)
              │
              ▼
     TitleBar (AppEvents.subscribe)
     Platform.runLater → updateStatus() (轮播文本动画)
```

---

## 4. 组件状态依赖矩阵

| 组件 | 读取 | 写入(Engine) | 绑定方式 |
|------|------|-------------|---------|
| **FloatToolbox** | ViewportState.followMode | CameraContext.setFollowMode() | `Bindings.when()` |
| **TitleBar** | ViewportState.navMode (listener) | CameraContext.setNavMode() | `addListener()` |
| | AppState.matchingEnabled (listener) | AppState.setMatchingEnabled() | `addListener()` |
| | local navMode 字段 (toggle 时) | CameraContext.setNavAngle() | — |
| **Sidebar** (header) | ViewportState.navMode (init 一次) | CameraContext.setNavMode() | — |
| | AppState.matchingEnabled (init 一次) | — | — |
| **SidebarCell** | AppState.matchingEnabled | AppState.setMatchingEnabled() | toggle listener |
| **InteractiveCanvas** | ViewportState.followMode | CameraContext.setFollowMode() | 方法内 get() |
| | ViewportState.navMode | — | 方法内 get() |
| | ViewportState.navAngle | — | 方法内 get() |
| | ViewportState.playerInitialized | — | 方法内 get() |
| | ViewportState.playerX/Y | — | 方法内 get() |
| | (toLogic: MapContext offset/scale) | MapContext.offsetX/Y/zoom/ensureBounds | 方法内 get() |
| **PathEditor** | ViewportState.scale/offsetX/Y | — | 方法内 get() |
| **MapRenderer** | MapContext.offsetX/Y/scale | — | 方法内 get() |
| | ViewportState.navMode/navAngle | CameraContext.updateViewport() | 方法内 get() |
| | ViewportState.playerAngle/initialized | — | 方法内 get() |
| **PlayerRenderer** | ViewportState.playerX/Y/Angle | — | 方法内 get() |
| | ViewportState.navMode/navAngle | — | 方法内 get() |
| **RouteRenderer** | ViewportState.offsetX/Y/scale/navMode/navAngle | — | 方法内 get() |
| **HoverRenderer** | ViewportState.offsetX/Y/scale/navMode/navAngle | — | 方法内 get() |
| **IconLayerManager** | ViewportState.navMode/navAngle | — | 方法内 get() |
| | ViewportState.playerX/Y/initialized | — | 方法内 get() |
| **NavigationController** | — | CameraContext.setNavAngle() | — |
| **SettingDefinitions** | ViewportState.navMode (getter) | Config fields + CameraContext | 设置面板回调 |
| | AppState.matchingEnabled (getter) | AppState.setMatchingEnabled() | 设置面板回调 |
| | AppState.materialCollection | AppState.setMaterialCollection() | 设置面板回调 |

---

## 5. 事件路径对比

### 改前: navMode 从切换 -> 渲染

```
TitleBar.toggleNavMode()
  → NavigConfig.NAVIGATION_ENABLED = x          ← Config 写入
  → CameraContext.setNavMode(x)                 ← Engine 写入
      → HookRegistry.publish(NAV_MODE_CHANGED)   ← 旧事件
      → AbstractGenericHook.onEvent()
        → Platform.runLater()
  → Sidebar (手动补发 AppEvents)                 ← 手动同步
  → SettingDefinitions (手动补发两套)              ← 手动同步
  → 渲染器: CameraContext.getInstance().isNavMode()  ← 直接读 engine volatile
```

### 改后: navMode 从切换 -> 渲染

```
TitleBar.toggleNavMode()
  → CameraContext.setNavMode(x)                 ← 唯一入口
      → AppEvents.publish(NavModeEvent)          ← 事件总线
        → StateBridge
          → Platform.runLater()
            → ViewportState.setNavMode(x)
              → navModeProperty.set(x)
                ├─ [Listener] TitleBar.syncNavUi()   ← 响应式
                └─ [下一次帧]
                   所有渲染器.onFrame() → get()
```

### 改前: matching 从切换 -> UI 更新

```
SidebarCell toggle
  → SiftConfig.SIFT_MATCHING_ENABLED = x
  → HookRegistry.publish(STATUS_CAROUSEL)
    → TitleBar.onEvent → Platform.runLater → setSvgFill
    → SettingsStage.onEvent → Platform.runLater → cb.setSelected
```

### 改后: matching 从切换 -> UI 更新

```
SidebarCell toggle
  → AppState.setMatchingEnabled(x)
    ├─ matchingEnabledProperty.set(x)
    │   ├─ [Listener] TitleBar 图标变色 ← 即刻响应，无需 EventBus
    │   ├─ [Listener] SidebarCell toggle.setSelected
    │   └─ [Listener] SettingsStage cb.setSelected
    └─ SiftConfig.SIFT_MATCHING_ENABLED = x
  → AppEvents.publish(StatusCarouselEvent)
    └─ [subscriber] TitleBar → Platform.runLater → updateStatus (轮播文字)
```

---

## 6. 生命周期状态图

### ViewportState.player*

```
[未初始化] ──→ PlayerStateTracker 首次匹配成功 ──→ [已初始化]
  │                                                    │
  │  playerInitialized=false                            │ playerInitialized=true
  │  playerX/Y=-1                                      │ playerX/Y=最新坐标
  │                                                    │
  └── resetPlayer() ── (应用重置时) ──────────←───────────┘
```

### NavMode

```
[关闭] ←──→ [开启]
  │              │
  │              ├─ 跟随模式自动开启 (AUTO_FOLLOW_MODE)
  │              ├─ Slider 显示
  │              ├─ Stage 透明度 = NAV_WINDOW_OPACITY
  │              └─ NavigationController 开始工作
  │
  ├─ 关闭时: Stage 透明度 = 1.0
  ├─ GhostMode 共存: 优先 GhostMode 透明度
  └─ navAngle 归零
```

### 匹配开关 Matching Enabled

```
[开启] ←──→ [暂停]
  │              │
  ├─ SiftConfig 同步     │
  ├─ TitleBar 图标高亮   │ TitleBar 图标灰色
  ├─ 引擎执行 SIFT 匹配  │ 引擎跳过匹配
  └─ 轮播: "匹配中"      └─ 轮播: "匹配已暂停"
```

---

## 7. 同步方式速查

| 状态 | 变化频率 | 同步机制 | 延迟 |
|------|---------|---------|------|
| playerX/Y | ~60fps | EventBus + Platform.runLater | 1-2 帧 |
| navAngle | ~60fps | 帧循环直接 sync | 0 帧(同帧) |
| offsetX/Y | ~60fps (拖拽时) | MapRenderer 直接读 MapContext | 0 帧 |
| scale | 低频 (缩放时) | 同上 | 0 帧 |
| navMode | 低频 | EventBus + Platform.runLater | 1-2 帧 |
| followMode | 低频 | EventBus + Platform.runLater | 1-2 帧 |
| matchingEnabled | 极低频 | 同步 setMatchingEnabled | 0 帧(同线程) |
| materialCollection | 极低频 | 同步 setMaterialCollection | 0 帧(同线程) |
