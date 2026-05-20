# roco-model

模型推理层，封装 ONNX 模型加载与推理，提供 OCR 文字识别能力。

## 职责

- **ONNX 运行时管理** — `BaseOnnxManager` 模型加载/重建/子管理器
- **OCR 文字识别** — `OcrService` 全流程 (letterbox → det → rec)，`OnnxDetManager` 检测模型，`OnnxRecManager` 识别模型
- **结果模型** — `ItemResult` OCR 识别结果

## 依赖

| 依赖                  | 版本            |
|---------------------|---------------|
| DJL API             | 0.36.0        |
| ONNX Runtime Engine | 0.36.0        |
| JavaCPP             | 1.5.13        |
| OpenCV (JavaCPP)    | 4.13.0-1.5.13 |

## 内部依赖

- `roco-common`

## 资源

- `model/ch_PP-OCRv4_det_mobile.onnx` — OCR 检测模型
- `model/ch_PP-OCRv4_rec_mobile.onnx` — OCR 识别模型
- `model/ppocr_keys_v1.txt` — OCR 字符集
