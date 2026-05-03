import torch
import torch.nn as nn
import torch.nn.functional as F

class SuperPoint(nn.Module):
    """模型结构必须与训练时完全一致：Conv2d + ReLU，无 BatchNorm，无 Sequential"""
    def __init__(self):
        super(SuperPoint, self).__init__()
        # 共享编码器（与训练时的 key 名称匹配：conv1a.weight, conv1a.bias, ...）
        self.conv1a = nn.Conv2d(1, 64, kernel_size=3, stride=1, padding=1)
        self.conv1b = nn.Conv2d(64, 64, kernel_size=3, stride=1, padding=1)
        self.conv2a = nn.Conv2d(64, 64, kernel_size=3, stride=1, padding=1)
        self.conv2b = nn.Conv2d(64, 64, kernel_size=3, stride=1, padding=1)
        self.conv3a = nn.Conv2d(64, 128, kernel_size=3, stride=1, padding=1)
        self.conv3b = nn.Conv2d(128, 128, kernel_size=3, stride=1, padding=1)
        self.conv4a = nn.Conv2d(128, 128, kernel_size=3, stride=1, padding=1)
        self.conv4b = nn.Conv2d(128, 128, kernel_size=3, stride=1, padding=1)
        # 检测头
        self.convPa = nn.Conv2d(128, 256, kernel_size=3, stride=1, padding=1)
        self.convPb = nn.Conv2d(256, 65, kernel_size=1, stride=1, padding=0)
        # 描述子头
        self.convDa = nn.Conv2d(128, 256, kernel_size=3, stride=1, padding=1)
        self.convDb = nn.Conv2d(256, 256, kernel_size=1, stride=1, padding=0)

    def forward(self, x):
        x = F.relu(self.conv1a(x))
        x = F.relu(self.conv1b(x))
        x = F.max_pool2d(x, kernel_size=2, stride=2)
        x = F.relu(self.conv2a(x))
        x = F.relu(self.conv2b(x))
        x = F.max_pool2d(x, kernel_size=2, stride=2)
        x = F.relu(self.conv3a(x))
        x = F.relu(self.conv3b(x))
        x = F.max_pool2d(x, kernel_size=2, stride=2)
        x = F.relu(self.conv4a(x))
        x = F.relu(self.conv4b(x))

        cPa = F.relu(self.convPa(x))
        semi = self.convPb(cPa)

        cDa = F.relu(self.convDa(x))
        desc = self.convDb(cDa)
        dn = torch.norm(desc, p=2, dim=1, keepdim=True)
        desc = desc.div(dn)

        return semi, desc

def run_export(pth_path):
    device = torch.device('cpu')
    model = SuperPoint()

    print(f"正在加载权重: {pth_path}")
    state_dict = torch.load(pth_path, map_location=device)
    if 'model' in state_dict:
        state_dict = state_dict['model']

    # strict=True: 有任何不匹配就报错，确保权重完整加载
    model.load_state_dict(state_dict, strict=True)
    print("✅ 所有权重加载成功")

    model.eval()

    # PyTorch 端自检
    test_input = torch.randn(1, 1, 256, 256)
    with torch.no_grad():
        s, d = model(test_input)
        print(f"PyTorch 测试: semi shape={s.shape}, desc shape={d.shape}")
        prob = torch.softmax(s, dim=1)
        max_det = prob[0, :64].max().item()
        print(f"  max detection prob={max_det:.6f} (应该 > 0.015)")

    fp32_path = "superpoint_fp32.onnx"
    int8_path = "superpoint_int8.onnx"

    print("导出 FP32 ONNX...")
    torch.onnx.export(
        model, test_input, fp32_path,
        input_names=['input'], output_names=['semi', 'desc'],
        dynamic_axes={'input': {2: 'height', 3: 'width'},
                      'semi': {2: 'height', 3: 'width'},
                      'desc': {2: 'height', 3: 'width'}},
        opset_version=13
    )

    # ONNX 端验证
    import onnxruntime as ort
    import numpy as np
    sess = ort.InferenceSession(fp32_path, providers=['CPUExecutionProvider'])

    # 棋盘格测试
    checker = np.zeros((1, 1, 256, 256), dtype=np.float32)
    for y in range(256):
        for x in range(256):
            checker[0, 0, y, x] = 1.0 if ((x // 32 + y // 32) % 2 == 0) else 0.0

    onnx_out = sess.run(None, {'input': checker})
    onnx_semi = onnx_out[0]
    semi_t = torch.from_numpy(onnx_semi)
    prob = torch.softmax(semi_t, dim=1)
    max_det = prob[0, :64].max().item()
    n_kps = (prob[0, :64].max(dim=0).values > 0.015).sum().item()

    print(f"ONNX 验证: semi shape={onnx_semi.shape}")
    print(f"  max detection prob={max_det:.6f} (必须 > 0.015)")
    print(f"  棋盘格检测关键点数: {n_kps} (必须 > 0)")

    if max_det < 0.015 or n_kps == 0:
        print("❌ ONNX 验证失败！")
        return
    else:
        print("✅ ONNX 验证通过")

    # INT8 量化
    from onnxruntime.quantization import quantize_dynamic, QuantType
    print("执行 INT8 量化...")
    quantize_dynamic(
        model_input=fp32_path,
        model_output=int8_path,
        weight_type=QuantType.QInt8
    )
    print(f"✅ 全部完成！\nFP32: {fp32_path}\nINT8: {int8_path}")

if __name__ == "__main__":
    import os
    pth = 'superpoint_v1.pth'
    if os.path.exists(pth):
        run_export(pth)
    else:
        print(f"❌ 找不到文件: {pth}")
