# RocoMapTracker-match.exe — SIFT 图像匹配子进程

## 概述

`RocoMapTracker-match.exe` 是一个独立的图像匹配子进程（以下简称 match.exe），通过 TCP Socket 与 Java 主进程通信。它负责对游戏小地图进行 SIFT 特征匹配，定位玩家在参考地图上的位置。

### 功能

- 接收 Java 端发送的 SIFT 配置参数（算法参数、缓存路径等）
- 接收参考地图灰度图，训练 SIFT 特征并构建 FLANN 索引
- 支持 4 种 SIFT 变体（STANDARD / PCA / ULTRA / PCA_ULTRA）
- 对每帧截图执行：小地图检测 → ROI 裁剪 → SIFT 匹配 → 箭头朝向检测
- 返回匹配坐标和分阶段耗时统计
- 支持匹配缓存加速（避免重复训练）

---

## 启动参数

```
RocoMapTracker-match.exe <port>
```

| 参数 | 类型 | 说明 |
|------|------|------|
| `port` | 整数 | Java SocketServer 的 TCP 端口 |

### 示例

```bat
RocoMapTracker-match.exe 9002
```

---

## 架构

### 初始化流程

```
match.exe                               Java
    │                                      │
    │──── HELLO (1) ──────────────────────>│  标识自身
    │                                      │
    │──── REQUEST_CONFIG (208) ───────────>│  请求配置
    │<─── CONFIG_DATA (209) ───────────────│  接收 SIFT 参数
    │                                      │
    │──── 创建 Matcher ────────────────────│  根据 AlgoKind 实例化
    │                                      │
    │──── 尝试加载缓存 ────────────────────│  磁盘 cache 文件
    │  ├─ 成功 → 直接就绪                  │
    │  └─ 失败 →                            │
    │      ──── REQUEST_MAP (200) ────────>│  请求参考地图
    │     <─── MAP_DATA (201) ─────────────│  接收灰度图
    │      ──── train() ──────────────────│  SIFT 检测 + FLANN 建索引
    │      ──── save_cache() ─────────────│  保存缓存到磁盘
    │                                      │
    │──── INIT_COMPLETE (202) ────────────>│  通知就绪（含特征点数）
```

### 匹配循环（每帧）

```
match.exe                               Java
    │                                      │
    │──── READY (204) ────────────────────>│  就绪，等待帧数据
    │<─── FRAME_DATA (205) ────────────────│  接收截图帧（BGRA）
    │                                      │
    │══════ 处理流程 ═══════════════════════│
    │  1. BGRA → GRAY                     │
    │  2. MiniMapProcessor.detect()       │  HoughCircles 检测小地图圆
    │  3. 裁剪 ROI（1.5x 半径）            │
    │  4. SiftMatcher.match()             │  SIFT 检测 + FLANN 匹配
    │     ├─ 场景图 SIFT detectAndCompute │
    │     ├─ 描述子变换（PCA/量化）        │
    │     ├─ FLANN knnSearch（k=2）        │
    │     ├─ 比率测试（阈值 0.6）           │
    │     └─ RANSAC 单应性矩阵             │
    │  5. detect_arrow_angle_hsv()        │  灰度阈值+连通域+PCA 检测箭头朝向
    │                                      │
    │──── MATCH_RESULT (206) ─────────────>│  返回匹配结果
```

### 关键类

- **MatcherBase** — 抽象匹配器接口
- **SiftMatcher** — SIFT 匹配器实现
- **DescriptorTransform** — 描述子 PCA 降维 + 8-bit 量化
- **MiniMapProcessor** — HoughCircles 小地图圆检测
- **AlgoParams** — 算法参数结构体

---

## SIFT 变体

| 变体 | ordinal | 描述子类型 | 描述子维度 | 说明 |
|------|---------|-----------|-----------|------|
| STANDARD | 0 | CV_32F | 128 | 原始 128 维 float 描述子 |
| PCA | 1 | CV_32F | 64 | PCA 降维至 64 维 |
| ULTRA | 2 | CV_8U | 128 | 8-bit 量化（速度优化） |
| PCA_ULTRA | 3 | CV_8U | 64 | PCA + 量化（默认） |

FLANN 索引根据描述子类型选择：
- `CV_8U` → `KDTreeIndex<L2<unsigned char>>`
- `CV_32F` → `KDTreeIndex<L2<float>>`

### 重叠分块训练

当地图像素超过阈值（900 万像素，约 3000×3000）时，启用重叠分块训练：

- 瓦片尺寸：2000×2000 像素
- 瓦片重叠：200 像素
- 步长：1800 像素
- 去重：空间网格（4px 单元）+ 距离过滤

---

## 通信协议

### 传输层

- TCP，地址 `127.0.0.1`
- 二进制，**Big-Endian**（网络字节序）
- `TCP_NODELAY` 启用
- 最大重连次数：30 次（每次间隔 1 秒）

### 帧格式

```
[4B] msgType   (int32, Big-Endian)
[4B] bodyLen   (int32, Big-Endian, 0 表示无消息体)
[NB] body      (消息体)
```

### 消息类型

| 类型 | 方向 | 值 | 说明 |
|------|------|----|------|
| HELLO | C++→Java | 1 | 握手 |
| REQUEST_MAP | C++→Java | 200 | 请求参考地图数据 |
| MAP_DATA | Java→C++ | 201 | 返回参考地图灰度图 |
| INIT_COMPLETE | C++→Java | 202 | 初始化完成（含特征数） |
| INIT_FAILED | C++→Java | 203 | 初始化失败 |
| READY | C++→Java | 204 | 就绪，等待帧数据 |
| FRAME_DATA | Java→C++ | 205 | 匹配帧数据 |
| MATCH_RESULT | C++→Java | 206 | 匹配结果 |
| SHUTDOWN | Java→C++ | 207 | 关闭命令 |
| REQUEST_CONFIG | C++→Java | 208 | 请求配置参数 |
| CONFIG_DATA | Java→C++ | 209 | 返回配置参数 |

### 消息体格式详解

#### HELLO (1) — C++ → Java

```
[2B] clientIdLen
[NB] clientId           (UTF-8, 值为 "match")
[2B] providesCount      (= 6)
[N*4B] provides[]       = { REQUEST_MAP(200), REQUEST_CONFIG(208), INIT_COMPLETE(202),
                            INIT_FAILED(203), READY(204), MATCH_RESULT(206) }
[2B] subscribesCount    (= 4)
[N*4B] subscribes[]     = { MAP_DATA(201), FRAME_DATA(205), SHUTDOWN(207), CONFIG_DATA(209) }
```

#### CONFIG_DATA (209) — Java → C++

固定 84 字节头部 + 变长缓存路径：

```
偏移  长度    字段               说明
────  ────   ────────────────  ──────────────────────────
 0     4B    algoKind          AlgoKind (0=SIFT)
 4     4B    siftVariant       SIFT 变体 (0~3)
 8     4B    nfeatures         最大特征点数 (0=无限制)
12     4B    nOctaveLayers     SIFT 每层组数
16     8B    contrastThreshold  SIFT 对比度阈值
24     8B    edgeThreshold      SIFT 边缘阈值
32     8B    sigma              SIFT sigma
40     8B    matchRatioThreshold 比率测试阈值
48     4B    matchMinCount     最小匹配点数
52     4B    searchRadius      搜索半径 (像素)
56     4B    flannKDTreeCount  FLANN KD 树数量
60     4B    flannSearchChecks FLANN 搜索检查次数
64     8B    ransacReprojThreshold RANSAC 重投影阈值
72     4B    ransacMaxIters    RANSAC 最大迭代次数
76     8B    ransacConfidence  RANSAC 置信度
84     4B    cachePathLen      缓存文件路径长度 (字节)
88     NB    cacheFilePath     缓存文件路径 (UTF-8)
```

所有数值均为 Big-Endian。
示例：`[0,0,0,0, 0,0,0,3, 0,0,0,0, 0,0,0,3, ...]`

#### MAP_DATA (201) — Java → C++

```
[4B] map_w             (int32, 地图宽度, 像素)
[4B] map_h             (int32, 地图高度, 像素)
[4B] pixels_len        (uint32, 像素数据长度 = map_w * map_h)
[NB] gray_pixels       (灰度像素数据, 每像素 1 字节)
```

#### FRAME_DATA (205) — Java → C++

```
[4B] width             (int32, 帧宽度, 像素)
[4B] height            (int32, 帧高度, 像素)
[8B] hintX             (double, 提示 X 坐标, NaN = 无提示)
[8B] hintY             (double, 提示 Y 坐标, NaN = 无提示)
[4B] pixels_len        (uint32, 像素数据长度)
[NB] bgra_pixels       (BGRA 32bpp 像素数据)
```

#### INIT_COMPLETE (202) — C++ → Java

```
[4B] feature_count     (int32, 特征点数)
```

#### INIT_FAILED (203) — C++ → Java

```
[4B] error_code        (int32, 错误码)
[4B] msg_len           (int32, 错误消息长度)
[NB] error_message     (UTF-8 错误消息)
```

#### MATCH_RESULT (206) — C++ → Java

```
偏移  长度    字段               类型      说明
────  ────   ────────────────  ───────  ────────────────────
 0     1B    success           uint8     1=匹配成功, 0=失败
 1     8B    x                 double    匹配到的地图 X 坐标
 9     8B    y                 double    匹配到的地图 Y 坐标
17     8B    angle             double    箭头朝向角度 (度)
25     4B    t_minimap_ms      float     小地图检测耗时 (ms)
29     4B    t_extract_ms      float     SIFT 特征提取耗时 (ms)
33     4B    t_matching_ms     float     FLANN 匹配 + RANSAC 耗时 (ms)
37     4B    t_arrow_ms        float     箭头朝向检测耗时 (ms)
```

总计 41 字节。所有数值为 Big-Endian。

---

## 匹配流程详解

### 小地图检测 (MiniMapProcessor)

1. 将灰度帧缩放到 120px 宽度
2. `medianBlur`(5) 去噪
3. `HoughCircles` 检测圆
4. 黑边验证：在圆周上采样 120 个点，检查亮度 < 150 的比例
5. 圆心偏移验证：限制圆心偏离帧中心不超过 `min_side * 0.2`

### 箭头朝向检测 (detect_arrow_angle_hsv)

1. 以小地图圆心为中心裁剪 64×64 区域
2. BGRA→GRAY 灰度化
3. **对比度拉伸**：min-max 归一化，拉满 0–255 动态范围
4. **阈值二值化**：固定阈值 192，暗像素（箭头）置黑 0，亮背景置白 255
5. **中心连通域提取**：从图像中心开始 BFS 洪水填充，仅沿黑色像素扩散，只保留与中心连通的黑色区域（剔除边缘离散噪点）
6. **PCA 主成分分析**：收集所有黑色像素坐标，计算协方差矩阵的第一特征向量方向
7. **方向消歧**：利用黑色像素质心偏离图像中心的方向，确定箭尖指向（箭头从中心向外延伸，质心偏移方向即箭尖方向）

> 因游戏更新后箭头颜色变化，新方案用灰度阈值代替旧的 HSV inRange 方式。

### 匹配后处理

匹配成功后：
1. 通过 `findHomography` + RANSAC 计算单应性矩阵
2. 将场景帧中心 `(w/2, h/2)` 投射到地图坐标
3. 输出投射后的 `(x, y)` 位置

---

## 匹配缓存

缓存文件格式（SIFT 特有）：

```
[4B] magic             = 0x53494654 ("SIFT")
[4B] version           = 1
[4B] variant           = SiftVariant ordinal

[--- DescriptorTransform 数据 ---]
  (PCA 模式下)
  [compressed] pca_eigenvectors      (cv::Mat)
  [compressed] projected_mean        (cv::Mat)
  [compressed] persistent_mat        (变换后的描述子矩阵)
  (ULTRA模式下附加:)
  [4B] q_min                         (float)
  [4B] q_scale                       (float)

[4B] keypoint_count
重复 keypoint_count 次:
  [4B] x               (float, 特征点地图 X)
  [4B] y               (float, 特征点地图 Y)
```

Mat 序列化格式（`write_mat_compressed`）：

```
[4B] rows             (int32)
[4B] cols             (int32)
[4B] type             (int32, cv::Mat type)
[4B] compressed_len   (int32, 0 表示未压缩)
[4B] raw_len          (int32)
[NB] data             (zlib 压缩数据, 若 compressed_len == raw_len 则为未压缩)
```

缓存储存在 Java 侧配置的文件路径（`cache/` 目录下），匹配子进程在初始化时首先尝试加载缓存，命中则跳过地图训练步骤。

---

## 构建

使用 `build_match.bat`：

```bat
cd cpp
build_match.bat
```

### 依赖

- **OpenCV 4.10.0**（自源码构建）：core, imgproc, features2d, flann, calib3d
- **zlib 1.3.1**（自源码构建）
- Winsock2

### MSVC 编译命令

```bat
rc /nologo /fo resource.res resource.rc
cl /std:c++17 /O2 /EHsc /arch:AVX2 ^
    /I"D:\a\opencv-install\include" /I"D:\a\zlib\include" ^
    /Fe:RocoMapTracker-match.exe ^
    match_main.cpp sift_matcher.cpp match_common.cpp resource.res ^
    /link /OPT:REF ^
    /LIBPATH:"D:\a\opencv-install\x64\vc17\lib" /LIBPATH:"D:\a\zlib\lib" ^
    ws2_32.lib ^
    opencv_core4100.lib opencv_imgproc4100.lib ^
    opencv_features2d4100.lib opencv_flann4100.lib opencv_calib3d4100.lib ^
    zlib.lib
```

需要配套分发 OpenCV DLL：

```
opencv_core4100.dll
opencv_imgproc4100.dll
opencv_features2d4100.dll
opencv_flann4100.dll
opencv_calib3d4100.dll
```

---

## 性能指标

- 每帧匹配延迟：通常 10~50ms（取决于特征点数量）
- 内存占用：约 100~150MB（SIFT 128D float 描述子）
- 缓存文件：每变体约数十 MB（地图特征 + FLANN 索引）
