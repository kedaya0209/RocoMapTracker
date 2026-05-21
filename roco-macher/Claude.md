# roco-macher 模块

匹配算法层 – SIFT 匹配器（4 变体）、小地图检测、数据集工具

AI 协作专用 – 依赖 roco-common + roco-model，不依赖 roco-engine。


## 模块职责


- SIFT 特征匹配：SiftMapMatcher 实现 4 种描述符变体 + 重叠分块训练。
- 描述符变换：DescriptorTransform 支持 PCA、量化、Zstd 缓存。
- 小地图圆检测：MiniMapDetector 基于 HoughCircles + 圆校验。
- 圆遮罩：CircleMaskApplier 纯 Java 零化圆外像素。
- 数据集生成：DatasetGeneratorServer HTTP 服务，用于收集训练数据。
- PCA 重校准：PCARecalibrator 提供 PCA 角度验证和可视化。
- 训练/验证同步：MoveValidationDataSet 同步训练/验证目录。


## 类清单 (8 个)


核心匹配类：
SiftMapMatcher         核心 SIFT 匹配器：4 变体 + 重叠分块训练
DescriptorTransform    描述符变换管道：PCA + 量化 + Zstd 缓存
MiniMapDetector        HoughCircles 小地图圆检测 + 校验
CircleMaskApplier      纯 Java 圆遮罩：零化圆外像素

数据集与工具：
DatasetGeneratorServer HTTP 数据集生成服务 (arrow 训练数据)
MoveValidationDataSet  训练/验证目录同步工具
PCARecalibrator        PCA 角度重校准 + 调试可视化

数据类：
PlayerAngle            record(found, angle)


## 单例模式


| 类                    | 单例方式       | 持有全局状态                         |
|----------------------|--------------|-----------------------------------|
| SiftMapMatcher       | 普通类        | 无全局单例，由 SwitchMapMatcher 持有 |
| DescriptorTransform  | 普通类        | 由 SiftMapMatcher 实例持有          |
| MiniMapDetector      | 普通类        | 无状态，建议复用实例                 |
| CircleMaskApplier    | 静态方法      | 无状态，直接调用静态方法              |
| DatasetGeneratorServer | 普通类     | HTTP 服务，需手动启动                |

注：本模块无全局单例，所有类由 roco-engine 中的 SwitchMapMatcher 管理生命周期。


## 本模块工具类清单（优先使用）


以下工具类位于 roco-macher，编辑本模块代码时应优先使用：

| 类名                  | 用途                                       |
|----------------------|-------------------------------------------|
| SiftMapMatcher       | SIFT 匹配主入口：train/match/destroy      |
| DescriptorTransform  | 描述符 PCA + 量化变换                      |
| MiniMapDetector      | HoughCircles 小地图检测                    |
| CircleMaskApplier    | 纯 Java 圆遮罩（零化圆外像素）              |
| PlayerAngle          | 匹配结果 record(found, angle)             |

**使用示例**：
- 小地图检测：`MiniMapDetector.detect(grayData, w, h)`
- 圆遮罩：`CircleMaskApplier.apply(grayData, w, h, cx, cy, radius)`
- SIFT 匹配：`SiftMapMatcher matcher = new SiftMapMatcher(params); matcher.train(mapPixels, w, h);`


## 特殊约束


SIFT 变体
- 4 种变体：STANDARD（128-dim float）、PCA（64-dim float）、
  ULTRA（8-bit 量化）、PCA_ULTRA（64-dim + 8-bit 量化，默认）。
- 变体通过 SiftConfig.variant 配置，会影响匹配精度和内存占用。

重叠分块训练
- 当地图像素数 > 9,000,000 (约 3000×3000) 时自动启用分块。
- 分块参数：TILE_SIZE=2000，TILE_OVERLAP=200，DEDUP_DISTANCE=4.0f。
- 去重：基于空间网格，重复特征点只保留一个。

FLANN 索引
- 使用 KDTreeIndex，单树模式（KDTreeIndexParams(1)）。
- 搜索参数：checks=24，不排序（SearchParams(24, 0, true)）。
- 直接使用 unsigned char 或 float 数据，避免 FlannBasedMatcher 的 clone 开销。

PCA 缓存
- DescriptorTransform 支持 Zstd 压缩缓存（.feat / .pca64.ultra.feat）。
- 缓存文件通过 cacheFilePath 配置，保存训练好的变换矩阵和持久化描述符。

线程安全
- SiftMapMatcher 不是线程安全的，同一时刻只能由一个线程调用 match()。
- roco-engine 中的 SiftMatchHandler 通过 synchronized 保护匹配过程。


## 与其他模块的交互


- roco-engine：MapMatcherProcessor 调用 MiniMapDetector 和 CircleMaskApplier；
  SiftMatchHandler 调用 SiftMapMatcher 进行匹配。
- roco-model：无直接依赖（匹配器不使用 ONNX 模型）。
- roco-common：SiftConfig 提供参数，FileUtil 处理缓存文件。


## 典型使用示例


// 创建 SIFT 匹配器
AlgoParams params = ...; // 从 SiftConfig 构建
SiftMapMatcher matcher = new SiftMapMatcher(params);

// 训练（从灰度图）
matcher.train(grayPixels, mapWidth, mapHeight);

// 匹配单帧
SiftMapMatcher.MatchResult result = matcher.match(frameGray, w, h, hintX, hintY);
if (result.success) {
System.out.println("Matched at: " + result.x + ", " + result.y);
}

// 小地图检测
var detection = MiniMapDetector.detect(grayData, w, h);
if (detection.success) {
CircleMaskApplier.apply(grayData, w, h, detection.center_x, detection.center_y, detection.radius);
}

// 保存/加载缓存
DescriptorTransform transform = new DescriptorTransform(variant);
transform.save_cache(file);
boolean loaded = transform.load_cache(file);