# roco-engine

核心引擎层，汇集截图采集、上下文管理、Hook 事件系统，是整个应用的业务逻辑中枢。

## 职责

### 截图采集 (capture/)

- **CaptureService** — 截图会话管理：WGC 回调分发 + 黑帧检测 + ROI 下发
- **WgcCaptureLib** — JNA 接口，加载 wgc_capture.dll
- **WindowFinder** — User32.EnumWindows 查找目标游戏窗口
- **ROI 处理器** — `MapMatcherProcessor` (小地图 SIFT 匹配)，`OcrProcessor` (物品栏 OCR)

### 上下文管理 (context/)

- **MapContext** — 核心枢纽：地图图像/视口(scale,offset)/玩家世界坐标
- **MapCoordinateManager** — 三层坐标转换
- **CameraContext** — 摄像机跟随模式
- **PathContext** — 路线管理 (绘制/编辑/持久化)
- **ResourcePointContext** — 资源点位容器 + 网格索引
- **MaterialCollectionContext** — 物资采集统计
- **OcrAsyncManager** — OCR 线程池管理
- **StatsContext** — 性能统计 (FPS/耗时)

### Hook 事件系统 (hook/)

- **HookRegistry** — 枚举单例：注册/发布/销毁入口
- **HookMulticast** — LinkedBlockingQueue + 虚拟线程分发
- **事件类型** — PLAYER_UPDATE / UI_NOTIFICATION / INIT_PROGRESS / CAPTURE_STATE / RESOURCE_POINT_CHANGED /
  MATERIAL_COLLECTION_UPDATED
- **ResourceGrayHook** — 玩家移动 → 邻近资源点置灰

### 玩家状态

- **PlayerStateTracker** — EMA 平滑 + 瞬移检测 + 地图丢失判定

## 依赖

| 依赖                 | 版本            |
|--------------------|---------------|
| JNA                | 5.13.0        |
| OpenCV (JavaCPP)   | 4.13.0-1.5.13 |
| OpenBLAS (JavaCPP) | 0.3.31-1.5.13 |

## 内部依赖

- `roco-common`
- `roco-model`
- `roco-map`
- `roco-macher`
