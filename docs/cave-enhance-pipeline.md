# 洞穴图增强管线

## 目的

洞穴源图偏暗、对比度低，SIFT 训练特征点少（~966）。处理管线对洞穴 PNG 做两步预处理后再训练：

1. CaveImageFuser — 洞穴区域外扩 200px，填充暗色大陆背景
2. CaveImageEnhancer — CLAHE 对比度增强

增强后 C++ 训练侧不再额外做 CLAHE，匹配侧保留 CLAHE（截图仍需增强）。

## 文件

| 文件 | 路径 |
|------|------|
| CaveImageFuser.java | `roco-map/.../map/CaveImageFuser.java` |
| CaveImageEnhancer.java | `roco-map/.../map/CaveImageEnhancer.java` |
| MultiMap_metadata.json | `roco-map/src/main/resources/source/maps/MultiMap_metadata.json` |
| sift_matcher.cpp | `cpp/sift_matcher.cpp` |

## CaveImageFuser 流程

1. 加载洞穴 PNG + 大陆图（卡洛西亚大陆.png，8192x8192）
2. 检测洞穴遮罩：
   - 有 alpha 透明像素 → alpha>0 为洞穴
   - 无 alpha 透明（全不透明黑底图）→ 亮度 > BRIGHTNESS_THRESHOLD(20) 为洞穴
3. BFS 从洞穴边界向外膨胀 extendPx 像素（默认 200）
4. 合成：膨胀区域铺暗化大陆底图，原洞穴图 alpha 混合盖在上面
5. 写回 ARGB PNG

### 参数

- `extendPx`：延伸像素数（默认 200）
- `darkFactor`：暗化系数（默认 0.7）。公式：亮度乘 `(1 - darkFactor)`，0.5=50%亮度，0.7=30%亮度

运行：`mvn compile exec:java -pl roco-map -Dexec.mainClass="io.github.kedaya0209.roco.app.map.CaveImageFuser" -Dexec.args="200 0.7"`

## CaveImageEnhancer 流程

1. 读取 MultiMap_metadata.json 获取洞穴子图列表
2. 加载 PNG → 提取 alpha + 原始 RGB + 灰度
3. 对灰度图应用 CLAHE（8×8 tile grid, clip limit=3, 双线性插值）
4. 颜色保持：对每个像素，CLAHE 亮度 / 原始亮度 作为缩放比，乘到原始 RGB 上
5. 非洞穴像素（alpha=0）RGB 置 0
6. 写回彩色 ARGB PNG（保留原始 alpha）

运行：`mvn compile exec:java -pl roco-map -Dexec.mainClass="io.github.kedaya0209.roco.app.map.CaveImageEnhancer"`

## C++ 端

- `train()` / `train_cave()`：直接使用传入灰度图，不做 CLAHE（源图已增强）
- `match()`：保留 CLAHE（截图仍需增强才能匹配增强后的地图）

## 当前状态（2025-06-02）

- CaveImageFuser: darkFactor 默认 0.7，公式为 `亮度乘 (1-darkFactor)`
- CaveImageEnhancer: 输出彩色 ARGB，保存 alpha 通道
- C++: 训练侧无 CLAHE，匹配侧保留
- 仅处理了"信仰者村落"单子图做测试

## 待办

- [ ] 验证特征点数量提升（期望从 ~966 到 5000+）
- [ ] 验证匹配稳定性
- [ ] 批量处理所有洞穴图
- [ ] 恢复 MultiMap_metadata.json 完整 6 子图
