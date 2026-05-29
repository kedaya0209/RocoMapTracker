# roco-engine

核心引擎层 — 截图采集、上下文管理、Hook 事件系统、匹配调度、Socket 通信。

依赖 roco-common + roco-map，是连接各层的枢纽。

## 职责

### 截图采集 (capture/)

- **CaptureService** — WGC 截图会话管理：BGRA 帧接收、黑帧检测、ROI 下发
- **CaptureHandler** — Socket 帧数据接收、反序列化、并行分发
- **MapMatcherProcessor** — ROI-0 管线：小地图 BGRA 帧 → C++ sift_match.exe 匹配
- **FullFrameControl** — 全帧截图控制接口
- **SaveImageProcessor** — 调试灰度帧保存

### 上下文管理 (context/)

- **MapContext** — 核心枢纽：地图、视口(scale/offset)、玩家世界坐标
- **MapCoordinateManager** — 三层坐标转换（L1 屏幕/L2 Canvas/L3 逻辑）
- **CameraContext** — 摄像机跟随模式
- **PathContext** — 路线管理（绘制/编辑/持久化）
- **ResourcePointContext** — 资源点位容器 + 网格索引
- **MaterialCollectionContext** — 物资采集统计

### Hook 事件系统 (hook/)

- **HookRegistry** — 枚举单例：发布/订阅/销毁入口
- **HookMulticast** — LinkedBlockingQueue + 虚拟线程异步分发
- **EventBus/AppEvents** — 泛型类型安全事件总线

### 匹配调度 (match/)

- **SiftMatchHandler** — SIFT 门面：请求/响应协调 + 消息路由
- **SwitchMapMatcher** — 4 种 SIFT 变体(STANDARD/PCA/ULTRA/PCA-ULTRA)热切换
- **PlayerStateTracker** — EMA 平滑 + 瞬移检测 + Lost 判定

### Socket 通信 (socket/)

- **SocketServer** — TCP 注册中心 + 消息路由器
- **ExternalBridgeHandler** — 外部桥接：接收 Python pcap 子进程游戏事件

### 进程管理 (process/)

- **NativeProcess** — 原生进程抽象（创建/读取 stdin/stderr/销毁）
- **JobObjectManager** — Windows Job Object 确保子进程随父进程退出

## 依赖

| 依赖 | 版本 |
|---|---|
| JavaFX Graphics | 25.0.3 |
| SLF4J | 2.0.18 |
| Lombok | 1.18.46 |

注：Java 侧零 OpenCV 依赖，全部视觉处理由 C++ 子进程完成。

## 内部依赖

- `roco-common`
- `roco-map`
