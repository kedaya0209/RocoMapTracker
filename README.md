# RocoMapTracker

基于计算机视觉的游戏地图实时追踪工具。通过 Windows Graphics Capture (WGC) 截取游戏画面，利用 SIFT 特征匹配 + HSV 箭头方向检测 + OCR 文字识别，实时计算玩家在大地图上的位置和朝向，并叠加显示资源点与路线。

## 技术栈

| 层     | 技术                             | 版本                         |
|-------|--------------------------------|----------------------------|
| 语言    | Java + C++ + C                 | Java 25, C++17             |
| UI 框架 | JavaFX (AtlantaFX 主题)          | JavaFX 25, AtlantaFX 2.1.0 |
| 视觉库   | JavaCPP OpenCV (nopointergc) / C++ OpenCV | 4.13.0-1.5.13 / 4.10.0 |
| 推理引擎  | DJL + ONNX Runtime             | DJL 0.36.0                 |
| 截图引擎  | C++ WGC (D3D11) + Socket IO    | 独立子进程                      |
| 匹配引擎  | C++ sift_match.exe (SIFT/FLANN/RANSAC + HSV 箭头) | 独立子进程           |
| 编译目标  | GraalVM Native Image           | GraalVM 25.0.2             |

## 功能

- **实时定位** — SIFT 特征匹配 (可选 PCA 降维 + 8-bit 量化) 将小地图 ROI 与完整地图匹配，获得玩家世界坐标
- **方向检测** — C++ HSV 颜色过滤 + 凸包最小内角顶点检测，在 sift_match.exe 子进程中直接完成
- **物资计数** — OCR 识别物品栏文字，经稳定性过滤后统计物资数量
- **资源点展示** — 地图上叠加显示资源点位，玩家靠近自动置灰
- **路线管理** — 绘制/编辑/保存路线路径，支持跟随模式
- **4 种匹配器** — STANDARD / PCA / ULTRA / PCA-ULTRA 运行时可热切换
- **增量更新** — 支持 HDiffPatch 增量补丁，减少更新下载体积

## 多模块结构

```
roco-map-tracker/
├── roco-common/     # 基础工具层 — 配置/资源/JSON
├── roco-model/      # 模型推理层 — ONNX 推理/OCR
├── roco-map/        # 地图管理层 — 下载/拼接/图标缓存/资源点
├── roco-macher/     # 测试模块 — SIFT 匹配器数据集测试
├── roco-engine/     # 核心引擎层 — 截图/上下文/匹配调度/Hook 事件
├── roco-ui/         # 用户界面层 — JavaFX 界面/MapRenderer 渲染引擎
├── cpp/             # C++ 子进程 — WGC 截图 + SIFT 匹配 + HSV 箭头检测 (Socket IO)
└── c/               # JNI 局部引用帧管理 (jniframe.dll)
```

模块依赖关系：

```
roco-ui → roco-engine → roco-macher → roco-model → roco-common
                    ↘ roco-map ──────────┘
```

> `roco-macher` 为测试模块，不打包进最终产物。

## 环境要求

- **操作系统**: Windows 10/11 (x86-64)
- **JDK**: GraalVM JDK 25+ (JVM 模式) 或 GraalVM 25.0.2+ (Native Image)
- **构建工具**: Maven 3.9+
- **C++ 编译器**: Visual Studio MSVC (编译 capture.exe / sift_match.exe)
- **C 编译器**: MSVC cl.exe (编译 jniframe.dll)

## 构建

```bash
# JVM 模式编译
mvn clean compile

# JVM 模式运行
mvn javafx:run -pl roco-ui

# 打包 fat jar
mvn clean package
# 产物: roco-ui/target/roco-ui-1.1.1.jar (shade plugin)

# 编译 C++ 子进程 (Visual Studio Developer Command Prompt)
cd cpp && build_capture.bat              # 产物 → capture.exe
cd cpp && build_sift.bat                 # 产物 → sift_match.exe + opencv_world4100.dll

# 编译 C JNI 库 (jniframe.dll)
cl /LD /Fe:jniframe.dll c/jniframe.c /I"%JAVA_HOME%\include" /I"%JAVA_HOME%\include\win32"
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

1. 编译 C++ 子进程（capture.exe / sift_match.exe）
2. 构建 Native Image（含 PGO 优化）
3. 生成 HDiffPatch 增量补丁
4. 发布 GitHub Release（附 SHA256 校验文件）

## 数据流

```
C++ capture.exe → Socket → CaptureService
  ├─ ROI-0 (小地图全彩 BGRA) → MapMatcherProcessor
  │    ├─ 发送 BGRA 帧 → C++ sift_match.exe
  │    │    ├─ 灰度转换 → MiniMapDetector (HoughCircles)
  │    │    ├─ SIFT 特征提取 + FLANN 匹配 + RANSAC → (x, y)
  │    │    └─ HSV 箭头检测 (64×64 裁剪 → inRange → 凸包 → 最小内角 → 角度)
  │    ├─ 结果 (x, y, angle) → PlayerStateTracker (EMA 平滑)
  │    └─ MapContext.updatePlayerState(x, y, angle)
  │         ├─ PlayerRenderer (角度旋转箭头图标)
  │         └─ CameraContext (自动跟随偏移)
  │
  └─ ROI-1 (物品栏) → OcrProcessor
       ├─ OCR 识别 (det + rec)
       └─ MaterialCollectionContext.addMaterial()

MapRenderer (AnimationTimer)
  ├─ StatsOverlay.update() (每帧 33ms)
  ├─ viewportDirty 快照复用 (地图 + 图标 + 路线)
  └─ 每帧动态层 (玩家图标旋转 + hover)
```

## 坐标系

项目使用三层坐标系：

| 层              | 原点         | 用途         |
|----------------|------------|------------|
| L1 屏幕像素        | Canvas 左上角 | 鼠标事件       |
| L2 Canvas 逻辑像素 | 地图左上角      | 渲染/SIFT 结果 |
| L3 地图逻辑坐标      | 地图中心       | 游戏世界坐标     |

坐标转换由 `MapCoordinateManager` 统一管理，公式详见 CLAUDE.md。

## 许可

本软件使用 GraalVM Native Image 技术构建，部分杀毒软件可能误报。如遇拦截，请添加信任或选择"允许运行"。
