# 双缓存 SIFT 匹配方案

## 问题背景

玩家进入洞穴时，小地图从大陆场景过渡到洞穴场景，SIFT 匹配因特征差异过大而失败。
之前的复合图方案把 6 张图拼接训练，但洞穴与大陆的视觉差异导致匹配不稳定。

## 核心思路

C++ 侧持两套独立的 SIFT 特征缓存，每帧根据小地图亮度自动选择：

| 小地图亮度             | 使用缓存     | 原因                               |
| ---------------------- | ------------ | ---------------------------------- |
| **有像素 < 50**        | **洞穴唯**   | 含大陆暗色区域→排除大陆干扰定位洞穴 |
| **全部 ≥ 50**          | **完整图**   | 纯洞穴或纯大陆→完整图都能覆盖       |

判别规则：`hasDarkPixels(<50) ? caveOnly : full`

小地图亮度阈值 50 来自之前的预处理参数——暗色大陆区域像素值 < 50，洞穴区域 ≥ 50。

---

## 1. 缓存目录与参数自校验

```
cache/
  sift_<参数哈希>/              ← 目录名由参数内容决定
    full.sift                   完整图特征缓存
    cave.sift                   洞穴唯特征缓存
    cache_meta.json             参数快照
```

`cache_meta.json`：

```json
{
  "algoKind": 3,
  "siftVariant": "PCA_ULTRA",
  "nfeatures": 15000,
  "nOctaveLayers": 3,
  "contrastThreshold": 0.04,
  "edgeThreshold": 10.0,
  "sigma": 1.6,
  "matchRatioThreshold": 0.75,
  "matchMinCount": 4,
  "searchRadius": 200,
  "flannKDTreeCount": 1,
  "flannSearchChecks": 64,
  "ransacReprojThreshold": 5.0,
  "ransacMaxIters": 2000,
  "ransacConfidence": 0.995,
  "cacheVersion": 1
}
```

### 初始化流程

- 检查 `cache/<algo_dir>/cache_meta.json`，比对当前参数与快照。
- 一致且两个 .sift 文件存在 → 加载两份缓存。
- 不一致或无文件 → 重新训练两份，写入新快照。
- 参数哈希生成：`SHA256(参数拼接字符串).substring(0, 12)`。

---

## 2. MAP_DATA 协议扩展

### 当前协议

```
[4B]w [4B]h [4B]totalPixels [pixels(NB)]
```

### 新协议（多子图模式）

```
[4B]subImageCount       子图数量 (1=单图兼容, 2+)
[4B]width               所有子图宽度（相同）
[4B]totalHeight         所有子图高度之和
// subImageCount > 1 时，每个子图的高度：
[4B]subHeight_0 [4B]subHeight_1 ... [4B]subHeight_{N-1}
// 像素数据：
[4B]totalPixels
[pixels(NB)]
```

### Java 发送

完整图训练：

```
subImageCount = 6
width        = 8192
totalHeight  = 49152
subHeights   = [8192, 8192, 8192, 8192, 8192, 8192]
pixels       = 卡洛西亚大陆 + 下水管道口 + 二叠山丘一层 + 信仰者村落 + 拾荒者港口 + 月兔暗港
```

洞穴唯训练：

```
subImageCount = 5
width        = 8192
totalHeight  = 40960
subHeights   = [8192, 8192, 8192, 8192, 8192]
pixels       = 下水管道口 + 二叠山丘一层 + 信仰者村落 + 拾荒者港口 + 月兔暗港
```

### C++ 解析

```cpp
struct MapDataInfo {
    int subImageCount;
    int width;
    int totalHeight;
    std::vector<int> subHeights;
    std::vector<uchar> pixels;
};

// 从总像素中分离两份训练数据：
cv::Mat fullImage(totalHeight, width, CV_8UC1, pixels.data());

// 洞穴唯 = 跳过第一个子图（大陆图）
int caveOffset = subHeights[0] * width;
cv::Mat caveImage(totalHeight - subHeights[0], width,
                  CV_8UC1, pixels.data() + caveOffset);
```

### 协议兼容性

`subImageCount = 1` 时退化为旧协议（兼容单图模式），`subHeights[]` 不发送。

---

## 3. C++ 侧双缓存架构

### 数据流

```
每帧处理:
  1. 截取小地图 ROI (HoughCircles)
  2. 转灰度
  3. 遍历像素，统计 intensity < 50 的数量 darkCount
  4. hasDarkPixels = (darkCount > 0)
  5. 选活跃缓存:
     - hasDarkPixels && active == full  → 切到 cave
     - !hasDarkPixels && active == cave → 切到 full
     - 其他情况保持
     - 其他情况保持
  6. 用活跃缓存匹配
  7. 如果是洞穴唯匹配成功 → y += 8192（统一到世界坐标）
  8. 输出 MATCH_RESULT
```

### sift_matcher.h 新增

```cpp
class SiftMatcher : public MatcherBase {
public:
    bool load_or_train_two(const AlgoParams& params,
                           const cv::Mat& fullImage,
                           const cv::Mat& caveImage,
                           const std::string& cacheDir);
    void select_cache(bool hasDarkPixels);
    bool has_dark_pixels() const { return has_dark_pixels_; }

private:
    struct FeatureCache {
        std::vector<cv::KeyPoint> keypoints;
        cv::Mat descriptors;
        cv::flann::Index flannIndex;
        bool valid = false;
        std::string filePath;
        cv::Ptr<cv::Feature2D> detector;
        cv::Ptr<DescriptorTransform> transform;

        bool load(const std::string& path);
        bool save(const std::string& path) const;
        MatchResult match(const cv::Mat& queryDesc);
    };

    FeatureCache cache_full_;
    FeatureCache cache_cave_;
    int active_cache_ = -1;  // -1=none, 0=full, 1=cave
};
```

### match_common.cpp 亮度检测

在 `MiniMapProcessor::process()` 中，提取到小地图后增加：

```cpp
bool has_dark_pixels(const cv::Mat& gray_minimap, int threshold) {
    int dark_count = cv::countNonZero(gray_minimap < threshold);
    return dark_count > 0;
}
```

亮度检测在已提取出的小地图灰度图上进行，计算量约 O(200²)，可忽略。

---

## 4. MATCH_RESULT 协议

### 保持原格式（41 字节），不额外增加字段

```
[1]success [8]x [8]y [8]angle
[4]tMinimap [4]tExtract [4]tFlann [4]tArrow
```

Java 侧不需要知道用了哪个缓存，坐标已统一。

### 可选：调试时扩展为 42 字节

```
... [4]tArrow [1]usedCache
```

`usedCache`: 0=完整图, 1=洞穴唯。仅用于日志输出，不影响逻辑。

---

## 5. CONFIG_DATA 协议

扩展 cache path 为两个，第二个为洞穴唯缓存路径：

```
... [4]cachePathLen1 [N]cachePath1 [4]cachePathLen2 [N]cachePath2
```

Java 编码：

```java
public static byte[] encodeConfig(int variant, String cacheSuffix,
                                   String caveCacheSuffix, int algoKind) {
    String siftMapPath = ResourceConfigContext.getSiftMap();
    String cachePath = getCachePath(siftMapPath + cacheSuffix + "_full");
    String caveCachePath = getCachePath(siftMapPath + caveCacheSuffix + "_cave");

    // ByteBuffer 编码两个 path ...
}
```

---

## 6. Java 侧改动

### 改动清单

| 文件 | 改动 |
|------|------|
| `MapImageLoader.java` | `writeStreaming()` 支持写多子图协议头（subImageCount + 子图高度列表） |
| `SiftMatchProtocol.java` | `encodeConfig()` 带两个 cache path；`decodeMatchResult()` 支持 42 字节扩展 |
| `SiftMatchHandler.java` | 初始化时验证参数缓存，需重新训练时调用 `writeStreaming` 两次 |
| `CompositeMapMetadata.java` | 新建——元数据值对象（从 JSON 反序列化） |
| `SubImageMapper.java` | 新建——坐标分解，按 Y 查子图名称 |

### MapImageLoader 改动

```java
public void writeStreaming(OutputStream os, List<SubImage> images) {
    int subCount = images.size();
    int w = images.get(0).width();
    int totalH = images.stream().mapToInt(SubImage::height).sum();

    // 写新协议头
    writeInt(os, subCount);
    writeInt(os, w);
    writeInt(os, totalH);
    for (var img : images) {
        writeInt(os, img.height());
    }

    // 写像素数据
    writeInt(os, w * totalH);  // totalPixels (灰度下 = 总像素数)
    for (var img : images) {
        BufferedImage bi = loadGrayscale(img.sourcePath());
        writePixels(os, bi);
    }
}
```

两个调用：

```java
// 完整图训练
writeStreaming(os, allSubImages);     // 6 张

// 洞穴唯训练
writeStreaming(os, caveOnlySubImages); // 5 张（不含大陆）
```

### SiftMatchHandler 初始化流程

```
init():
  1. 计算参数哈希 → algoDir
  2. 检查 cache/algoDir/cache_meta.json
  3. 若缓存有效:
     encodeConfig(fullCachePath, caveCachePath) → 发送 CONFIG_DATA
     收到 REQUEST_MAP → 跳过 MAP_DATA（用缓存）
  4. 若缓存无效:
     loadImage() → 写 MAP_DATA (完整图, subImageCount=6)
     收到 INIT_COMPLETE → 继续
     loadCaveOnly() → 写 MAP_DATA (洞穴唯, subImageCount=5)
     收到 INIT_COMPLETE → 继续
     收到 READY
     saveCacheMeta()
```

### SubImageMapper 坐标分解

```java
public SubImageMatch resolve(double x, double y) {
    // 按 offsetY 查找子图
    for (var sub : subImages) {
        if (y >= sub.offsetY() && y < sub.offsetY() + sub.height()) {
            return new SubImageMatch(
                x,                           // worldX
                y - sub.offsetY(),           // worldY（子图内坐标）
                sub
            );
        }
    }
    return new SubImageMatch(x, y, null);
}
```

---

## 7. 流程图

```
┌─────────────────────────────────────────────────┐
│ 初始化                                            │
│  ┌─ cache valid? ──→ 加载 full.sift + cave.sift  │
│  └─ cache invalid ──→                             │
│       Java send MAP_DATA {6子图}                   │
│       C++ train(fullImage) → full.sift            │
│       Java send MAP_DATA {5子图}                   │
│       C++ train(caveImage) → cave.sift            │
│       write cache_meta.json                       │
│       READY                                       │
└─────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────┐
│ 匹配循环（每帧）                                     │
│  FRAME_DATA → C++                                 │
│    → extract minimap (HoughCircles)               │
│    → gray_minimap                                 │
│    → count < 50 pixels                            │
│    → dark > 0 ? select(cave) : select(full)       │
│    → match()                                      │
│    → if cave && success: y += 8192                │
│    → MATCH_RESULT                                 │
└─────────────────────────────────────────────────┘
```

---

## 8. 文件变更清单

| 操作 | 文件 | 位置 |
|------|------|------|
| 修改 | `match_common.h` | AlgoParams 加 caveCacheFilePath |
| 修改 | `sift_matcher.h` | FeatureCache、双缓存方法、亮度字段 |
| 修改 | `sift_matcher.cpp` | load_or_train_two、select_cache、双缓存训练/匹配/IO |
| 修改 | `match_common.cpp` | 亮度检测、双缓存切换、MAP_DATA 解析 |
| 修改 | `SiftMatchProtocol.java` | encodeConfig 双 path、decodeMatchResult 扩展 |
| 修改 | `SiftMatchHandler.java` | 双缓存初始化、训练协调 |
| 修改 | `MapImageLoader.java` | writeStreaming 多子图协议头 |
| 新建 | `CompositeMapMetadata.java` | 元数据值对象 |
| 新建 | `SubImageMapper.java` | 坐标分解 |

---

## 9. 边界情况

- **洞穴边缘过渡**：小地图一半大陆一半洞穴 → 有 <50 像素 → 用洞穴唯匹配，排除大陆暗色特征干扰，定位到洞穴内的正确坐标。
- **传送进洞穴**：SIFT 匹配失败（完整图找不到特征）→ 但当前帧小地图全是 ≥50 → 用完整图匹配（完整图也覆盖了洞穴区域，尽管之前实验效果不好，但 CaveImageFuser 处理后的洞穴图包含了暗化大陆特征，改善了匹配）。如果仍然失败 → watchDog 超时复位。
- **纯暗色场景**：如果洞穴内整张小地图都 <50 → 用洞穴唯匹配（因为 <50 的规则本身就是切到洞穴唯）。
