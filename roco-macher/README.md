# roco-macher

匹配算法层，实现 SIFT 特征匹配、小地图检测和箭头方向检测的核心算法。

## 职责

- **SIFT 匹配器** — `SiftMapMatcher` 统一匹配器，4 种变体 (STANDARD/PCA/ULTRA/PCA-ULTRA)
- **描述符变换** — `DescriptorTransform` PCA 降维 + 8-bit 量化管道
- **策略切换** — `SwitchMapMatcher` 运行时热切换匹配器变体
- **小地图检测** — `MiniMapDetector` 霍夫圆检测，`CircleMaskApplier` 圆遮罩
- **箭头检测** — `ArrowDetector` CNN 方向检测入口，`PlayerAngle` 结果容器

## 依赖

| 依赖                 | 版本            |
|--------------------|---------------|
| JavaCPP            | 1.5.13        |
| OpenCV (JavaCPP)   | 4.13.0-1.5.13 |
| OpenBLAS (JavaCPP) | 0.3.31-1.5.13 |

## 内部依赖

- `roco-common`
- `roco-model`
