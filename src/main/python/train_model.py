import torch
import torch.nn as nn
import torch.optim as optim
from torch.utils.data import Dataset, DataLoader
import cv2
import os
import pandas as pd
import numpy as np
import time

# ONNX 相关
import onnx
from onnxruntime.quantization import quantize_static, CalibrationDataReader, QuantType, QuantFormat

# --- 配置参数 ---
DATA_DIR = "dataset"
BATCH_SIZE = 1024
NUM_WORKERS = 12
EPOCHS = 20
DEVICE = torch.device("cuda" if torch.cuda.is_available() else "cpu")
FP32_PATH = "arrow_fp32.onnx"

# --- 1. 模型架构定义 (V9) ---
class SEBlock(nn.Module):
    def __init__(self, channels, reduction=16):
        super().__init__()
        self.avg_pool = nn.AdaptiveAvgPool2d(1)
        self.fc = nn.Sequential(
            nn.Linear(channels, channels // reduction, bias=False),
            nn.ReLU(inplace=True),
            nn.Linear(channels // reduction, channels, bias=False),
            nn.Sigmoid()
        )
    def forward(self, x):
        b, c, _, _ = x.size()
        y = self.avg_pool(x).view(b, c)
        y = self.fc(y).view(b, c, 1, 1)
        return x * y.expand_as(x)

class ResSEBlock(nn.Module):
    def __init__(self, channels):
        super().__init__()
        self.conv = nn.Sequential(
            nn.Conv2d(channels, channels, 3, padding=1),
            nn.BatchNorm2d(channels),
            nn.ReLU(inplace=True),
            nn.Conv2d(channels, channels, 3, padding=1),
            nn.BatchNorm2d(channels)
        )
        self.se = SEBlock(channels)
        self.relu = nn.ReLU(inplace=True)
    def forward(self, x):
        identity = x
        out = self.conv(x)
        out = self.se(out)
        out += identity
        return self.relu(out)

class ArrowNetV9(nn.Module):
    def __init__(self):
        super().__init__()
        self.features = nn.Sequential(
            nn.Conv2d(1, 32, 3, padding=1), nn.BatchNorm2d(32), nn.ReLU(inplace=True),
            nn.MaxPool2d(2),
            ResSEBlock(32),
            nn.Conv2d(32, 64, 3, padding=1), nn.BatchNorm2d(64), nn.ReLU(inplace=True),
            nn.MaxPool2d(2),
            ResSEBlock(64),
            nn.Conv2d(64, 128, 3, padding=1), nn.BatchNorm2d(128), nn.ReLU(inplace=True),
            nn.MaxPool2d(2),
            ResSEBlock(128)
        )
        self.fc = nn.Sequential(
            nn.Flatten(),
            nn.Linear(128 * 8 * 8, 512),
            nn.ReLU(inplace=True),
            nn.Dropout(0.4),
            nn.Linear(512, 2)
        )
    def forward(self, x):
        return self.fc(self.features(x))

class AngleSimLoss(nn.Module):
    def forward(self, pred, target):
        pred_n = pred / (torch.norm(pred, dim=1, keepdim=True) + 1e-8)
        target_n = target / (torch.norm(target, dim=1, keepdim=True) + 1e-8)
        cos_sim = torch.sum(pred_n * target_n, dim=1)
        mse_loss = nn.functional.mse_loss(pred, target)
        return torch.mean(1 - cos_sim) + 0.1 * mse_loss

# --- 2. 数据处理 ---
class ArrowDataset(Dataset):
    def __init__(self, data_list, data_dir, is_train=True):
        self.data_dir = data_dir
        self.data_list = data_list
        self.is_train = is_train

    def __len__(self):
        return len(self.data_list)

    def __getitem__(self, idx):
        row = self.data_list[idx]
        img_name, s_val, c_val = row[0], float(row[1]), float(row[2])
        img_path = os.path.join(self.data_dir, "images", img_name)
        img = cv2.imread(img_path, cv2.IMREAD_GRAYSCALE)
        if img is None: img = np.zeros((64, 64), dtype=np.uint8)

        if self.is_train:
            rot_deg = np.random.uniform(-180, 180)
            M = cv2.getRotationMatrix2D((32, 32), rot_deg, 1.0)
            img = cv2.warpAffine(img, M, (64, 64))
            rad = np.radians(-rot_deg)
            new_s = s_val * np.cos(rad) + c_val * np.sin(rad)
            new_c = -s_val * np.sin(rad) + c_val * np.cos(rad)
            s_val, c_val = new_s, new_c
            tx, ty = np.random.randint(-3, 4, 2)
            M_s = np.float32([[1, 0, tx], [0, 1, ty]])
            img = cv2.warpAffine(img, M_s, (64, 64))
            if np.random.random() > 0.5:
                img = cv2.GaussianBlur(img, (3, 3), 0)

        img_tensor = torch.from_numpy(img).float().unsqueeze(0) / 255.0
        return img_tensor, torch.tensor([s_val, c_val], dtype=torch.float32)

# --- 3. 量化校准器 ---
class ArrowCalibReader(CalibrationDataReader):
    def __init__(self, data_list, data_dir):
        self.data_list = data_list[:200]
        self.data_dir = data_dir
        self.enum_data = iter(self.prepare())

    def prepare(self):
        for row in self.data_list:
            img_path = os.path.join(self.data_dir, "images", row[0])
            img = cv2.imread(img_path, cv2.IMREAD_GRAYSCALE)
            if img is None: continue
            img = cv2.resize(img, (64, 64)).astype(np.float32) / 255.0
            img = np.expand_dims(img, axis=(0, 1))
            yield {'input': img}

    def get_next(self):
        return next(self.enum_data, None)

# --- 4. 训练与自动化量化 ---
def main():
    labels_path = os.path.join(DATA_DIR, "labels.txt")
    if not os.path.exists(labels_path):
        print(f"❌ 找不到 labels 文件: {labels_path}")
        return

    all_data = pd.read_csv(labels_path, header=None).values
    np.random.shuffle(all_data)
    split = int(len(all_data) * 0.9)
    train_data, val_data = all_data[:split], all_data[split:]

    train_loader = DataLoader(ArrowDataset(train_data, DATA_DIR, True),
                              batch_size=BATCH_SIZE, shuffle=True, num_workers=NUM_WORKERS, pin_memory=True)
    val_loader = DataLoader(ArrowDataset(val_data, DATA_DIR, False),
                            batch_size=BATCH_SIZE, shuffle=False, num_workers=NUM_WORKERS, pin_memory=True)

    model = ArrowNetV9().to(DEVICE)
    criterion = AngleSimLoss()
    optimizer = optim.AdamW(model.parameters(), lr=1e-3, weight_decay=5e-4)
    scheduler = optim.lr_scheduler.CosineAnnealingWarmRestarts(optimizer, T_0=10)

    print(f"🚀 V9 训练启动 | 设备: {DEVICE}")
    try:
        for epoch in range(EPOCHS):
            model.train()
            running_loss = 0
            for imgs, targets in train_loader:
                imgs, targets = imgs.to(DEVICE), targets.to(DEVICE)
                optimizer.zero_grad()
                loss = criterion(model(imgs), targets)
                loss.backward()
                optimizer.step()
                running_loss += loss.item()

            model.eval()
            v_loss = 0
            with torch.no_grad():
                for v_imgs, v_targets in val_loader:
                    v_loss += criterion(model(v_imgs.to(DEVICE)), v_targets.to(DEVICE)).item()

            scheduler.step()
            print(f"Epoch [{epoch+1:02d}/{EPOCHS}] | Train Loss: {running_loss/len(train_loader):.6f} | Val Loss: {v_loss/len(val_loader):.6f}")

    except KeyboardInterrupt:
        print("\n🛑 中断，准备导出...")

    # --- 导出 FP32 ---
    print("📦 导出 FP32 ONNX...")
    model.cpu().eval()
    dummy = torch.randn(1, 1, 64, 64)
    torch.onnx.export(model, dummy, FP32_PATH, input_names=['input'], output_names=['output'],
                      dynamic_axes={'input': {0: 'batch_size'}, 'output': {0: 'batch_size'}}, opset_version=12)


    print(f"✅ 流程完成！\n原始模型: {FP32_PATH}")

if __name__ == "__main__":
    main()