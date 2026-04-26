# 洛克王国世界助手 - 项目介绍

## 📋 项目概述

**洛克王国世界助手**（RocoMapTracker）是一款专为《洛克王国》游戏开发的实时地图追踪和导航辅助工具。该程序通过Windows Graphics Capture (WGC)技术实时捕获游戏窗口，使用先进的计算机视觉算法实现小地图定位、玩家朝向识别、资源点标注等功能。

### 核心功能

- 🗺️ **实时地图追踪**：通过SIFT特征匹配算法实现小地图到大地图的精准定位
- 🧭 **玩家朝向识别**：基于颜色检测和轮廓分析识别玩家箭头朝向
- 📍 **资源点可视化**：自动标注各类资源点（矿物、植物、宝箱等）位置
- 🔄 **资源采集提示**：玩家靠近资源时自动置灰已采集资源
- 📷 **OCR识别**：实时识别游戏内的物品名称和数值信息
- 🎮 **交互式导航**：支持拖拽平移、滚轮缩放、跟随玩家等交互模式
- 🚀 **Native Image支持**：可编译为原生可执行文件，启动速度快、内存占用低

---

## 🏗️ 系统架构

### 整体架构图

```
┌─────────────────────────────────────────────────────────────┐
│                    MainApp (JavaFX 应用入口)               │
├─────────────────────────────────────────────────────────────┤
│  │                                                            │
│  ├─ 初始化阶段                                                │
│  │   ├─ 资源释放 (ResourceUtils)                             │
│  │   ├─ 地图资源下载 (MapResourceUpdater)                    │
│  │   └─ 配置加载 (AppConfig)                                │
│  │                                                            │
│  ├─ 启动阶段                                                  │
│  │   ├─ UI 初始化 (InteractiveCanvas)                         │
│  │   ├─ 渲染循环 (RenderLoop)                               │
│  │   ├─ 窗口监控 (WindowsMonitor)                            │
│  │   └─ 匹配器加载 (SiftMapMatcher)                         │
│  │                                                            │
│  └─ 运行阶段 (每帧处理)                                      │
│      ├─ 屏幕捕获 (WgcCapture)                                │
│      ├─ 小地图定位 (MapTracker)                              │
│      ├─ 地图匹配 (SiftMapMatcher)                            │
│      ├─ 玩家检测 (ArrowDetector)                             │
│      ├─ Hook 事件分发 (HookMulticaster)                      │
│      └─ 渲染更新 (RenderLoop)                                │
└─────────────────────────────────────────────────────────────┘
```

### 核心模块说明

#### 1. 屏幕捕获模块 (`capture`)

- **WindowsMonitor**：窗口监控器，负责查找和连接目标游戏窗口
- **WgcCapture**：基于Windows Graphics Capture API的高性能屏幕捕获
- **Frame**：帧数据封装，管理原生内存的字节数组

**技术特点**：
- 使用WGC API实现零拷贝的GPU直接访问
- 原生DLL封装，通过JNA调用Windows API
- 自定义帧率控制，避免资源浪费

#### 2. 地图匹配模块 (`macher`)

**SiftMapMatcher**：基于SIFT特征点的地图匹配器
- 使用OpenCV的SIFT算法提取特征点
- FLANN快速近邻搜索
- Lowe's Ratio Test筛选优质匹配
- RANSAC算法计算单应性矩阵

**MapTracker**：小地图定位器
- 霍夫圆变换检测小地图区域
- 动态ROI区域选择优化性能
- 缓存机制避免重复检测

**ArrowDetector**：玩家朝向检测器
- HSV颜色空间过滤检测橙色箭头
- 形态学处理（腐蚀+膨胀）连接断裂区域
- 凸包分析和最远点计算确定朝向

**性能优化**：
- 特征点缓存持久化（Zstd压缩）
- NDManager子管理器确保单帧资源释放
- ThreadLocal缓存避免GC抖动

#### 3. OCR识别模块 (`model`)

**OcrService**：OCR服务入口
- 集成PaddleOCR的检测和识别模型
- 端到端文本识别流程

**OnnxDetManager**：文本检测模型
- 基于ONNX Runtime推理
- 输出文本热力图用于定位文本行

**OnnxRecManager**：文本识别模型
- CTC解码算法处理序列输出
- 字符字典索引映射

**OcrAsyncManager**：异步任务管理器
- 虚拟线程池处理OCR请求
- 陈旧帧丢弃策略优化响应速度
- 任务去重避免重复计算

#### 4. 渲染模块 (`render`)

**RenderLoop**：JavaFX渲染循环
- 基于AnimationTimer的高频渲染
- 每帧30-60 FPS刷新

**PlayerRenderer**：玩家图标渲染
- 模拟箭头朝向显示

**CutterPlayerRenderer**：裁剪模式渲染
- 直接渲染小地图玩家箭头截图
- 零拷贝优化，无OpenCV操作

#### 5. 上下文管理模块 (`context`)

**MapContext**：地图状态管理
- 坐标系统转换
- 缩放和平移控制
- 边界限制算法

**CameraContext**：摄像机管理
- 跟随模式控制
- 视口更新算法

**ResourcePointContext**：资源点管理
- 空间索引加速查询
- 坐标预计算优化渲染

**OcrAsyncManager**：OCR异步管理
- 对象池复用OcrService实例
- 虚拟线程并发处理

#### 6. Hook事件系统 (`hook`)

**HookMulticaster**：事件广播器
- 观察者模式实现
- 线程安全的事件队列

**IHook**：Hook接口定义
- 支持多种事件类型

**RealOcrHook**：实时OCR Hook
- 增量OCR处理
- 帧稳定性算法

**ResourceGrayHook**：资源置灰 Hook
- 基于距离计算的置灰逻辑
- 空间索引优化查询

#### 7. 地图资源管理模块 (`map`)

**MapResourceUpdater**：统一资源更新入口
- 协调地图、图标、配置的下载

**MapDownloader**：地图瓦片下载器
- 广度优先策略下载瓦片
- 虚拟线程并发下载

**MapStitcher**：地图拼接器
- 瓦片坐标计算
- 大图生成和保存

**ImageLoader**：图片加载器
- 软引用缓存优化内存
- 透明背景处理

#### 8. 工具类模块 (`utils`)

**ImageUtil**：图像格式转换
- JavaFX Image与OpenCV Mat互转
- BGRA/BGR格式处理

**ResourceUtils**：资源文件管理
- Native Image兼容性处理
- 资源释放和读取

**CoordinateTransformer**：坐标转换
- 线性插值平滑坐标
- 地图传送检测

---

## 🔧 技术栈

### 核心技术

| 技术 | 版本 | 用途 |
|------|------|------|
| **Java** | 21+ | 开发语言，支持虚拟线程 |
| **JavaFX** | 25 | UI框架和渲染引擎 |
| **OpenCV** | 4.9.0 | 图像处理和计算机视觉 |
| **ONNX Runtime** | 1.25.0 | OCR模型推理引擎 |
| **DJL (Deep Java Library)** | 0.36.0 | 深度学习模型管理 |

### 依赖库

- **JavaCV**：OpenCV的Java封装，支持原生内存操作
- **JNA**：Java Native Access，调用Windows API
- **Zstd**：高性能压缩库，用于特征缓存
- **Jackson**：JSON序列化/反序列化
- **JSoup**：HTML解析，用于地图配置爬取
- **Lombok**：代码生成，简化POJO
- **SLF4J + Logback**：日志框架

### Native Image支持

- **GraalVM**：支持编译为原生可执行文件
- 原生打包优化：
  - 启动时间减少80%+
  - 内存占用降低50%+
  - 无JVM启动开销

---

## 🎯 核心算法

### 1. SIFT地图匹配

```
输入帧 → 转灰度 → 缩放 → SIFT特征提取 → KNN匹配
                                ↓
                         Lowe's Ratio Test 筛选
                                ↓
                         RANSAC 计算单应性矩阵
                                ↓
                         透视变换获取四个角点
                                ↓
                         坐标还原图大地图位置
```

**关键参数**：
- `RATIO_THRESHOLD`：0.6（匹配比例阈值）
- `MIN_MATCH_COUNT`：10（最小匹配点数）
- `RANSAC_REPROJ_THRESHOLD`：10.0（重投影误差阈值）

### 2. 玩家朝向检测

```
ROI区域 → HSV转换 → 颜色范围过滤 → 形态学处理
                                         ↓
                                  轮廓提取
                                         ↓
                                  评分筛选（面积+距离）
                                         ↓
                                  凸包计算 + 重心计算
                                         ↓
                                  最远点查找 → 计算角度
```

**颜色范围**：
- H：12-25（橙色）
- S：180-255（高饱和度）
- V：100-255（中高亮度）

### 3. OCR CTC解码

```
模型输出 → 每个时间步取最大概率 → 去重和blank过滤 → 索引映射字符
```

**关键逻辑**：
- 跳过blank字符（index=0）
- 连续相同字符只保留一个
- 置信度阈值0.35

---

## 💾 资源管理

### 内存管理策略

1. **Native资源自动释放**
   ```java
   // 使用try-with-resources确保Mat及时释放
   try (Mat mat = new Mat()) {
       // 处理图像
   } // 自动调用mat.close()
   ```

2. **NDManager子管理器**
   ```java
   try (NDManager sub = model.getNDManager().newSubManager()) {
       NDArray array = sub.create(...);
       // sub关闭时，array自动释放
   }
   ```

3. **ThreadLocal缓存**
   ```java
   // OCR的FloatBuffer缓存，只扩容不缩容
   private static final ThreadLocal<float[]> FLOAT_CACHE = new ThreadLocal<>();
   ```

4. **软引用缓存**
   ```java
   // ImageLoader使用软引用缓存图片
   private final Map<String, SoftReference<Image>> imageCache = ...;
   ```

### 资源生命周期

| 资源类型 | 生命周期 | 释放策略 |
|----------|----------|----------|
| OpenCV Mat | 帧级 | try-with-resources |
| NDArray | 帧级 | NDManager子管理器 |
| SIFT特征缓存 | 应用级 | destroy()释放 |
| JavaFX Image | GC管理 | 软引用辅助 |

---

## 🚀 性能优化

### 1. 启动优化

- **资源懒加载**：非必需资源延迟加载
- **异步初始化**：匹配器在后台线程加载
- **配置缓存**：特征点压缩缓存避免重复计算

### 2. 运行时优化

- **帧率控制**：固定30 FPS，避免资源浪费
- **陈旧帧丢弃**：OCR任务超时500ms自动放弃
- **空间索引**：资源点网格索引加速查询
- **坐标预计算**：渲染坐标提前计算

### 3. 内存优化

- **零拷贝**：BytePointer直接访问原生内存
- **对象池**：OcrService实例池复用
- **软引用缓存**：Image对象GC可控回收

### 4. 并发优化

- **虚拟线程**：高并发场景使用虚拟线程
- **无锁设计**：原子操作替代锁
- **事件驱动**：Hook系统异步处理事件

---

## 📝 配置说明

### 配置文件 (`app_config.properties`)

```properties
# 窗口设置
target.window.name=洛克王国：世界

# 地图缩放
map.zoom=7
map.min.zoom=4
map.max.zoom=8

# SIFT参数
sift.contrast.threshold=0.001
sift.edge.threshold=50.0

# 匹配参数
match.ratio.threshold=0.6
match.min.count=10

# RANSAC参数
ransac.reproj.threshold=10.0
ransac.max.iters=200
ransac.confidence=0.95

# 采集配置
gray.distance=12

# 帧率
target.capture.fps=30

# OCR核心数
ocr.core.size=2
```

---

## 🔨 构建和运行

### 开发环境运行

```bash
# 编译
mvn clean compile

# 运行
mvn javafx:run
```

### 打包JAR

```bash
# 打包带依赖的JAR
mvn clean package
```

### Native Image编译

```bash
# 编译原生可执行文件
mvn clean package -Pnative
```

**Native Image优势**：
- ✅ 启动时间：~2秒（vs JVM ~10秒）
- ✅ 内存占用：~200MB（vs JVM ~500MB）
- ✅ 无JVM依赖，单文件分发

---

## 🧪 测试

### 单元测试

```bash
# 运行所有测试
mvn test

# 运行特定测试类
mvn test -Dtest=TestProcessor
```

### 测试覆盖

- `TestProcessor`：小地图处理器测试
- `TestCapture`：屏幕捕获功能测试
- `TestMacher`：地图匹配算法测试

---

## 📚 项目结构

```
RocoMapTracker/
├── src/
│   ├── main/java/com/luoke/app/
│   │   ├── Main.java              # 应用入口
│   │   ├── MainApp.java           # JavaFX应用主类
│   │   ├── config/               # 配置管理
│   │   ├── capture/              # 屏幕捕获
│   │   ├── macher/               # 匹配算法
│   │   ├── model/                # OCR模型
│   │   ├── render/               # 渲染逻辑
│   │   ├── context/              # 上下文管理
│   │   ├── hook/                 # 事件系统
│   │   ├── map/                  # 地图资源
│   │   ├── processor/            # 图像处理
│   │   ├── component/            # UI组件
│   │   ├── utils/                # 工具类
│   │   └── event/                # 事件定义
│   └── test/java/                # 测试代码
├── resources/                    # 资源文件
├── maps/                        # 地图数据
├── pom.xml                      # Maven配置
└── app_config.properties         # 用户配置
```

---

## ⚠️ 注意事项

### Native资源管理

⚠️ **严格遵循**：
- 所有OpenCV Mat必须使用try-with-resources或手动close()
- NDArray必须在NDManager子管理器中创建
- 避免在循环中创建未释放的Native对象

### Native Image兼容性

⚠️ **限制**：
- 反射需要在reflect-config.json中配置
- 资源访问使用ResourceUtils而非ClassLoader
- 动态类加载有限制

---

## 📄 许可证

本项目仅供学习交流使用，请勿用于商业用途。


## 📮 联系方式

如有问题或建议，请通过以下方式联系：

- 提交Issue
- 发起Pull Request

---

**版本**：1.0.0  
**最后更新**：2026年4月26日  
**Java版本**：21+  
**支持平台**：Windows 10/11 (x64)
