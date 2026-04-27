# Python 模块

箭头方向检测机器学习模块，包含数据集生成和模型训练功能。

## 概述

本模块用于训练一个轻量级 CNN 模型，用于检测游戏地图中箭头的方向。模型输入 64x64 灰度图像，输出箭头方向的单位向量表示（sin, cos）。

## 目录结构

```
src/main/python/
├── gen_dataset.py    # 数据集生成脚本
├── train_model.py   # 模型训练与量化脚本
└── Readme.md         # 本文档
```

## 1. 数据集生成 (gen_dataset.py)

### 功能说明

生成用于箭头方向检测的训练数据集，通过在地图背景上合成随机角度的箭头图像。

### 核心特性

- **多进程并行生成**：利用 CPU 多核加速数据生成
- **均匀角度分布**：确保 0-360° 范围内角度均匀采样
- **随机位置采样**：箭头在图像中心区域随机偏移
- **高质量渲染**：使用双线性插值和抗锯齿处理

### 配置参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| NUM_SAMPLES | 50000 | 生成样本总数 |
| SAMPLE_SIZE | 64 | 输出图像尺寸（64x64） |
| ARROW_SIZE | 42 | 箭头模板尺寸（42x42） |
| OUTPUT_DIR | "dataset" | 输出目录 |

### 数据集格式

```
dataset/
├── images/
│   ├── arrow_00000.png
│   ├── arrow_00001.png
│   └── ...
└── labels.txt
```

**标签格式**：
```
arrow_00000.png,0.0000,1.0000
arrow_00001.png,0.7071,0.7071
...
```

每行格式：`文件名,sin(angle),cos(angle)`

### 运行命令

```bash
# 确保 arrow_template.png 和 map_background.png 在当前目录
python gen_dataset.py
```

### 实现细节

#### 角度均匀分布

```python
step = 360.0 / NUM_SAMPLES
angles = [(i * step) % 360.0 for i in range(NUM_SAMPLES)]
np.random.shuffle(angles)  # 打乱顺序，避免训练偏差
```

#### 高效灰度转换

使用整数移位实现工业级灰度算法：
```python
gray = (R * 77 + G * 150 + B * 29) >> 8
```

#### 多进程并行

```python
with Pool(processes=cpu_count()) as pool:
    for result in tqdm(pool.starmap(process_one, args), total=NUM_SAMPLES):
        labels.append(result)
```

## 2. 模型训练 (train_model.py)

### 功能说明

训练 ArrowNetV9 卷积神经网络，并导出 ONNX 模型（FP32）。

### 模型架构 (ArrowNetV9)

```
输入 (1, 64, 64)
    │
    ▼
Conv2d(1→32) → BN → ReLU → MaxPool
    │
    ▼
ResSEBlock(32)
    │
    ▼
Conv2d(32→64) → BN → ReLU → MaxPool
    │
    ▼
ResSEBlock(64)
    │
    ▼
Conv2d(64→128) → BN → ReLU → MaxPool
    │
    ▼
ResSEBlock(128)
    │
    ▼
Flatten → FC(8192→512) → ReLU → Dropout(0.4) → FC(512→2)
    │
    ▼
输出 (sin, cos)
```

### 核心组件

#### SEBlock (Squeeze-and-Excitation)

通道注意力机制，自适应调整特征通道权重。

#### ResSEBlock

残差连接 + SE 注意力模块，提升特征提取能力。

#### AngleSimLoss

混合损失函数：
```python
# 余弦相似度损失（主要）
cos_loss = 1 - cos_similarity(pred, target)

# MSE 损失（辅助）
mse_loss = mse(pred, target)

total_loss = cos_loss + 0.1 * mse_loss
```

### 训练配置

| 参数 | 默认值 | 说明 |
|------|--------|------|
| BATCH_SIZE | 1024 | 批次大小 |
| NUM_WORKERS | 12 | 数据加载线程数 |
| EPOCHS | 20 | 训练轮数 |
| DEVICE | cuda/cpu | 训练设备 |
| 学习率 | 1e-3 | 初始学习率 |
| 权重衰减 | 5e-4 | L2 正则化 |

### 数据增强

训练时应用以下增强：
- **随机旋转**：-180° ~ 180°
- **随机平移**：±3 像素
- **高斯模糊**：50% 概率

### 模型导出

#### FP32 ONNX

```python
arrow_fp32.onnx
```

- 动态 batch size 支持
- OPSET 版本：12
- 输入：`[batch, 1, 64, 64]`
- 输出：`[batch, 2]` (sin, cos)


### 运行命令

```bash
# 确保已生成数据集
python train_model.py
```

### 训练流程

1. 加载数据集并划分训练/验证集（9:1）
2. 初始化模型、优化器、学习率调度器
3. 训练 EPOCHS 轮
4. 导出 FP32 ONNX 模型

### ONNX 模型使用示例

```python
import onnxruntime as ort
import cv2
import numpy as np

# 加载模型
session = ort.InferenceSession("arrow_v9_int8_static.onnx")

# 预处理图像
img = cv2.imread("arrow.png", cv2.IMREAD_GRAYSCALE)
img = cv2.resize(img, (64, 64))
img = img.astype(np.float32) / 255.0
input_tensor = np.expand_dims(np.expand_dims(img, 0), 0)  # [1, 1, 64, 64]

# 推理
outputs = session.run(None, {"input": input_tensor})
sin_val, cos_val = outputs[0][0]

# 计算角度
angle = math.degrees(math.atan2(sin_val, cos_val))
print(f"箭头方向: {angle:.1f}°")
```

## 依赖项

### Python 版本

Python 3.8+

### 必需库

```bash
pip install torch torchvision
pip install onnx onnxruntime
pip install opencv-python
pip install numpy pandas tqdm
```

### ONNX 量化额外依赖

```bash
pip install onnxruntime-tools
```

## 性能指标

### 数据集生成

- **速度**：约 5000-10000 样本/分钟（取决于 CPU 核心数）
- **内存**：需要约 2-3GB RAM
- **输出大小**：约 200MB（PNG 图片）+ 1MB（标签文件）

### 模型训练

- **训练时间**：约 10-20 分钟（单卡 GPU）
- **显存占用**：约 2-3GB
- **最终模型大小**：
  - FP32: ~600KB
  - INT8: ~200KB

### 推理性能

- **FP32 ONNX**: ~1ms/image (CPU)

## 注意事项

1. **资源文件**：运行 `gen_dataset.py` 前需准备：
   - `arrow_template.png`：箭头 PNG 模板（带 alpha 通道）
   - `map_background.png`：地图背景灰度图

2. **数据集路径**：训练脚本默认从 `dataset/` 目录读取数据

3. **GPU 可选**：训练自动检测 GPU，无 GPU 时使用 CPU（速度较慢）

4. **量化校准**：静态量化使用验证集前 200 个样本进行校准

5. **中断恢复**：支持 Ctrl+C 中断，会直接导出当前模型

## 扩展方向

- 增加 ResNet/VGG 等更深的架构
- 实现混合精度训练（FP16）
- 添加 TensorRT 导出支持
- 实现模型剪枝优化
- 支持在线学习和增量训练
