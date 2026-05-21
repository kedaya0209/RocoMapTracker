# roco-model 模块

模型推理层 – ONNX 推理、CNN 箭头检测、OCR 全流程

AI 协作专用 – 仅依赖 roco-common，不涉及地图/匹配逻辑。


## 模块职责


- ONNX 模型管理：BaseOnnxManager 封装加载/重建/关闭。
- 箭头方向推理：ArrowPredictService 接收 64×64 灰度图，输出 sin/cos → 角度。
- OCR 全流程：OcrService 实现文本检测（det）+ 识别（rec）纯 Java 实现。
- 物资结果记录：ItemResult record(name, count)。


## 类清单 (7 个)


基础管理类：
BaseOnnxManager        ONNX 基类：loadModel/rebuild/close
ArrowOnnxManager       箭头 CNN 模型管理器（单线程 ONNX Runtime）
OnnxDetManager         文本检测 ONNX 模型管理器
OnnxRecManager         文本识别 ONNX 模型管理器 + CTC 解码

服务类：
ArrowPredictService     箭头方向推理：64×64 灰度 → sin/cos → 角度
OcrService              OCR 全流程：letterbox → det → rec

数据类：
ItemResult              record(name, count)


## 单例模式


| 类                    | 单例方式       | 持有全局状态                         |
|----------------------|--------------|-----------------------------------|
| ArrowPredictService  | DCL          | NDManager, ArrowOnnxManager       |
| OcrService           | 饿汉式        | OnnxDetManager, OnnxRecManager    |

注：BaseOnnxManager 子类均为单例，由各服务持有。


本模块工具类清单（优先使用）


以下工具类位于 roco-model，编辑本模块代码时应优先使用：

| 类名                  | 用途                                       |
|----------------------|-------------------------------------------|
| ArrowPredictService  | 箭头方向推理（sin/cos → 角度）               |
| OcrService           | OCR 全流程（det + rec）                    |
| BaseOnnxManager      | ONNX 模型加载/重建/关闭基类                 |
| ItemResult           | 物资识别结果 record                        |

**使用示例**：
- 箭头检测：`ArrowPredictService.getInstance().predictSin(cropMat)`
- OCR 识别：`OcrService.getInstance().recognizeAll(roiMat)`


## 特殊约束


内存管理（ArrowPredictService）
- 每 200 帧调用 resetNDManager() 释放 NDManager 累积内存。
- 使用 try (NDManager) 包裹单次推理。

OCR 稳定性
- OcrService.recognizeAll() 返回结果需经 OcrResultValidator 校验（位于 roco-engine）。
- 建议连续 2 次相同结果才采纳。

线程安全
- ArrowOnnxManager 注释为单线程，不应并发调用。
- OcrService 使用虚拟线程池执行，内部通过锁保护模型访问。

Native Image 兼容
- ONNX Runtime 需要反射配置，已在 reachability-metadata.json 中声明。

模型路径
- 箭头模型：`/model/arrow.onnx`
- OCR det 模型：`/model/ppocr_det_v3.onnx`
- OCR rec 模型：`/model/ppocr_rec_v3.onnx`
- 字典文件：`/model/ppocr_keys_v1.txt`


## 与其他模块的交互


- roco-engine：OcrAsyncManager 调用 OcrService，ArrowDetector 调用 ArrowPredictService。
- roco-macher：无直接依赖（匹配器仅使用 SIFT，不使用模型）。
- roco-ui：无直接依赖。

## 典型使用示例

// 箭头检测（单次）
ArrowPredictService service = ArrowPredictService.getInstance();
float sin = service.predictSin(cropMat);
float cos = service.predictCos(cropMat);
double angle = Math.toDegrees(Math.atan2(sin, cos));

// 箭头检测（带 NDManager 管理）
try (NDManager manager = NDManager.newBase()) {
NDArray input = manager.create(cropMat.getByteBuffer(), new Shape(1, 64, 64, 1));
NDArray output = model.predict(input);
// ...
}

// OCR 识别
OcrService ocr = OcrService.getInstance();
List<ItemResult> items = ocr.recognizeAll(roiMat);
for (ItemResult item : items) {
System.out.println(item.name() + " x" + item.count());
}