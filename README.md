# RocoMapTracker

基于计算机视觉的游戏地图实时追踪工具。通过 Windows Graphics Capture (WGC) 截取游戏画面，利用 SIFT 特征匹配 + CNN 方向检测 + OCR 文字识别，实时计算玩家在大地图上的位置和朝向，并叠加显示资源点与路线。

## 技术栈

| 层 | 技术 | 版本 |
|---|---|---|
| 语言 | Java + Rust + C + Python | Java 25, Rust 1.x |
| UI 框架 | JavaFX (AtlantaFX 主题) | JavaFX 25, AtlantaFX 2.1.0 |
| 视觉库 | JavaCPP OpenCV (nopointergc) | 4.13.0-1.5.13 |
| 推理引擎 | DJL + ONNX Runtime | DJL 0.36.0 |
| 截图引擎 | Rust WGC (D3D11) + JNA | JNA 5.13.0 |
| 编译目标 | GraalVM Native Image | GraalVM 25.0.2 |
| 序列化 | Zstd + Jackson | Zstd 1.5.6-2 |

## 功能

- **实时定位** — SIFT 特征匹配 (可选 PCA 降维 + 8-bit 量化) 将小地图 ROI 与完整地图匹配，获得玩家世界坐标
- **方向检测** — CNN 回归模型 (64×64 灰度图 → sin/cos → 角度) 检测玩家箭头方向
- **物资计数** — OCR 识别物品栏文字，经稳定性过滤后统计物资数量
- **资源点展示** — 地图上叠加显示资源点位，玩家靠近自动置灰
- **路线管理** — 绘制/编辑/保存路线路径，支持跟随模式
- **4 种匹配器** — STANDARD / PCA / ULTRA / PCA-ULTRA 运行时可热切换
- **Native Image 编译** — 经 PGO 优化的独立 exe，256MB 堆 + 512MB 堆外内存上限

## 多模块结构

```
roco-map-tracker/
├── roco-common/     # 基础工具层 — 配置/资源/JSON/JNI帧管理
├── roco-model/      # 模型推理层 — ONNX 推理/CNN 箭头/OCR
├── roco-map/        # 地图管理层 — 下载/拼接/图标缓存/资源点
├── roco-macher/     # 匹配算法层 — SIFT 匹配/小地图检测/箭头
├── roco-engine/     # 核心引擎层 — 截图/上下文/Hook 事件
├── roco-ui/         # 用户界面层 — JavaFX 界面/Native Image
├── rust/            # WGC 截图引擎 (独立 Cargo 构建)
├── c/               # JNI 局部引用帧管理 (jniframe.dll)
├── python/          # CNN 模型训练脚本
├── dll/             # 运行时 DLL
└── resources/       # 外部资源 (运行时释放)
```

模块依赖关系：

```
roco-ui → roco-engine → roco-macher → roco-model → roco-common
                    ↘ roco-map ──────────┘
```

## 环境要求

- **操作系统**: Windows 10/11 (x86-64)
- **JDK**: GraalVM JDK 25+ (JVM 模式) 或 GraalVM 25.0.2+ (Native Image)
- **构建工具**: Maven 3.9+
- **Rust 工具链**: MSVC toolchain (编译 wgc_capture.dll)
- **C 编译器**: MSVC cl.exe (编译 jniframe.dll)

## 环境变量

```bash
export JAVA_HOME="D:\Documents\environment\java\graalvm-win\graalvm-jdk-25.0.2+10.1"
```

## 构建

```bash
# JVM 模式编译
mvn clean compile

# JVM 模式运行
mvn javafx:run -pl roco-ui

# 打包 fat jar
mvn clean package
# 产物: roco-ui/target/roco-ui-1.1.0-jar-with-dependencies.jar

# 编译 Rust 原生库
cd rust && cargo build --release
# 产物: rust/target/release/wgc_capture.dll → 复制到 dll/ 目录

# 编译 C JNI 库
cl /LD /Fe:jniframe.dll c/jniframe.c /I"%JAVA_HOME%\include" /I"%JAVA_HOME%\include\win32"
```

### Native Image 构建

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

## 运行时

以下 DLL 必须与 exe/jar 同目录：

| 文件                   | 用途                                   |
|----------------------|--------------------------------------|
| `opencv_java490.dll` | JavaCPP OpenCV native (Maven 依赖自动提供) |
| `wgc_capture.dll`    | Rust WGC 截图引擎                        |
| `jniframe.dll`       | JNI 局部引用帧管理                          |

首次运行时会自动将内置资源释放到 `resources/` 目录，并生成 SIFT 特征缓存文件。

## 数据流

```
Rust WGC → JniCallback → CaptureService
  ├─ ROI-0 (小地图) → MapMatcherProcessor
  │    ├─ 霍夫圆检测 → 圆遮罩
  │    ├─ CNN 方向推理 → ArrowPredictService
  │    ├─ SIFT 匹配 → 玩家坐标
  │    └─ MapContext.updatePlayerState()
  │
  └─ ROI-1 (物品栏) → OcrProcessor
       ├─ OCR 识别 (det + rec)
       └─ MaterialCollectionContext.addMaterial()
```

## 坐标系

项目使用三层坐标系：

| 层 | 原点 | 用途 |
|---|---|---|
| L1 屏幕像素 | Canvas 左上角 | 鼠标事件 |
| L2 Canvas 逻辑像素 | 地图左上角 | 渲染/SIFT 结果 |
| L3 地图逻辑坐标 | 地图中心 | 游戏世界坐标 |

坐标转换由 `MapCoordinateManager` 统一管理，公式详见 CLAUDE.md。

## 许可

