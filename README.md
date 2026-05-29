# RocoMapTracker

基于计算机视觉的游戏地图实时追踪工具。通过 C++ WGC (D3D11) 子进程捕获游戏画面，利用 C++ OpenCV SIFT 特征匹配 + HSV 箭头方向检测实时计算玩家在大地图上的位置和朝向，叠加显示资源点与路线。

## 技术栈

| 层 | 技术 | 版本 |
|---|---|---|
| 语言 | Java + C++ + Python | Java 25, C++17 |
| UI 框架 | JavaFX (AtlantaFX 主题) | JavaFX 25, AtlantaFX 2.1.0 |
| 视觉库 | C++ OpenCV (SIFT/FLANN/RANSAC + HSV) | OpenCV 4.10.0 |
| 截图引擎 | C++ WGC (D3D11) + Socket IO | 独立子进程 |
| 匹配引擎 | C++ sift_match.exe (SIFT/FLANN/RANSAC) | 独立子进程 |
| 事件桥接 | Python pcap 抓包 + Socket 通信 | PyInstaller 打包 |
| 更新补丁 | HDiffPatch 增量差分 | 独立工具 |
| 编译目标 | GraalVM Native Image | GraalVM 25.0.3 |

注：视觉处理全部在 C++ 子进程中完成，Java 侧零 OpenCV 依赖。

## 功能

- **实时定位** — C++ SIFT 特征匹配 (可选 PCA 降维 + 8-bit 量化) 将小地图 ROI 与完整地图匹配，获得玩家世界坐标
- **方向检测** — C++ HSV 颜色过滤 + 凸包最小内角顶点检测，在 sift_match.exe 子进程中直接完成
- **物资追踪** — Python pcap 桥接器 (rmt_bridge.py) 监听游戏网络包，通过 Socket 推送拾取事件到 Java 侧统计
- **资源点展示** — 地图上叠加显示资源点位，玩家靠近自动置灰
- **路线管理** — 绘制/编辑/保存路线路径，支持跟随模式
- **4 种匹配器** — STANDARD / PCA / ULTRA / PCA-ULTRA 运行时可热切换
- **增量更新** — 支持 HDiffPatch 增量补丁，减少更新下载体积
- **幽灵模式** — 窗口透明 + 置顶，方便覆盖在游戏窗口上对照

## 多模块结构

```
roco-map-tracker/
├── roco-common/     # 基础工具层 — 配置/路径/资源/JSON 工具
├── roco-map/        # 地图管理层 — 下载/拼接/瓦片/图标缓存/资源点模型
├── roco-engine/     # 核心引擎层 — 截图采集/匹配调度/Socket 通信/Hook 事件
├── roco-ui/         # 用户界面层 — JavaFX 界面/MapRenderer 渲染引擎/设置面板
├── cpp/             # C++ 子进程 — WGC 截图 + SIFT 匹配 + HSV 箭头检测
├── rust/            # Rust WGC 捕获库 (wgc_capture, 预研/未集成)
├── plugins/         # Python 打包子进程 — pcap 桥接器 (RocoMapTracker-pcap.exe)
└── python/          # Python HSV 调色工具
```

模块依赖关系：

```
roco-ui → roco-engine → roco-map → roco-common
```

## 环境要求

- **操作系统**: Windows 10/11 (x86-64)
- **JDK**: GraalVM JDK 25+ (JVM 模式) 或 GraalVM 25.0.3+ (Native Image)
- **构建工具**: Maven 3.9+
- **C++ 编译器**: Visual Studio MSVC (编译 capture.exe / sift_match.exe)
- **Python**: Python 3.12 + PyInstaller (打包 pcap 桥接器)

## 构建

```bash
# JVM 模式编译
mvn clean compile

# JVM 模式运行
mvn javafx:run -pl roco-ui

# 打包 fat jar
mvn clean package
# 产物: roco-ui/target/roco-ui-1.1.1-shaded.jar

# 编译 C++ capture.exe (Visual Studio Developer Command Prompt)
cd cpp && build_capture.bat
# 产物 → RocoMapTracker-capture.exe → roco-ui/src/main/resources/capture/

# 编译 C++ sift_match.exe (Visual Studio Developer Command Prompt)
cd cpp && build_sift.bat
# 产物 → RocoMapTracker-sift_match.exe + OpenCV DLLs → roco-ui/src/main/resources/sift/

# 打包 Python pcap 桥接器
pyinstaller --onefile --name RocoMapTracker-pcap.exe rmt_bridge.py
# 产物 → plugins/RocoMapTracker-pcap.exe
```

### Native Image 构建

Native Image 构建需要 Visual Studio MSVC 环境（`rc.exe` 资源编译器），推荐在 CI 流水线中执行。

```bash
# 基础构建
mvn -Pnative clean package -pl roco-ui -am

# PGO Step 1: 构建插桩二进制, 运行采集 profiling 数据
mvn -Pnative-instrument clean package -pl roco-ui -am
./roco-ui/target/RocoMapTracker-instrumented.exe   # 自动生成 default.iprof

# PGO Step 2: 用 iprof 数据构建优化版本
mvn -Pnative-pgo clean package -pl roco-ui -am
```

产物位于 `roco-ui/target/RocoMapTracker.exe`。

### Release 流水线

通过 GitHub Actions `workflow_dispatch` 触发，自动完成：

1. 编译 C++ 子进程 (capture.exe / sift_match.exe)
2. 打包 Python pcap 桥接器
3. 构建 Native Image (含 PGO 优化)
4. 生成 HDiffPatch 增量补丁
5. 发布 GitHub Release (附 SHA256 校验文件)

## 数据流

```
C++ capture.exe (WGC/D3D11) → Socket → CaptureService
  └─ ROI-0 (小地图全彩 BGRA) → MapMatcherProcessor
       └─ 发送 BGRA 帧 → C++ sift_match.exe
            ├─ 灰度转换 → MiniMapDetector (HoughCircles)
            ├─ SIFT 特征提取 + FLANN 匹配 + RANSAC → (x, y)
            └─ HSV 箭头检测 (64×64 裁剪 → inRange → 凸包 → 最小内角 → 角度)
       ├─ 结果 (x, y, angle) → PlayerStateTracker (EMA 平滑)
       └─ MapContext.updatePlayerState(x, y, angle)
            ├─ PlayerRenderer (角度旋转箭头图标)
            └─ CameraContext (自动跟随偏移)

Python rmt_bridge.py (pcap 抓包) → Socket → ExternalBridgeHandler
  ├─ MSG_ITEM_PICKUP → MaterialCollectionContext (物资统计)
  ├─ MSG_AREA_CHANGE → 区域变更
  └─ MSG_STOP/START_MATCHING → SIFT 匹配启停

MapRenderer (AnimationTimer)
  ├─ StatsOverlay.update() (每帧 33ms)
  ├─ viewportDirty 快照复用 (地图 + 图标 + 路线)
  └─ 每帧动态层 (玩家图标旋转 + hover)
```

## 坐标系

项目使用三层坐标系：

| 层 | 原点 | 用途 |
|---|---|---|
| L1 屏幕像素 | Canvas 左上角 | 鼠标事件 |
| L2 Canvas 逻辑像素 | 地图左上角 | 渲染/SIFT 结果 |
| L3 地图逻辑坐标 | 地图中心 | 游戏世界坐标 |

坐标转换由 `MapCoordinateManager` 统一管理，公式详见 CLAUDE.md。

## ROI 布局 (万分数)

| ROI | 用途 | 坐标 (x,y,w,h) | 实际覆盖 (1920×1080) |
|---|---|---|---|
| 0 | 小地图 (SIFT+箭头) | (8900,700,1000,1800) | 右上角 192×194 |

实际像素 = 万分数 × 窗口尺寸 / 10000

## 许可

本软件使用 GraalVM Native Image 技术构建，部分杀毒软件可能误报。如遇拦截，请添加信任或选择"允许运行"。
