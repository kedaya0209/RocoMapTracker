import cv2
import numpy as np
import os
import math
from tqdm import tqdm
from multiprocessing import Pool, cpu_count

# --- 配置 ---
NUM_SAMPLES = 50000
SAMPLE_SIZE = 64
ARROW_SIZE = 42
OUTPUT_DIR = "dataset"

arrow_template_path = "arrow_template.png"
map_bg_path = "map_background.png"

# ---- 单张处理函数 ----
def process_one(i, angle, arrow_src, map_center):
    """
    注意：参数增加了 angle，确保由主函数分配
    """
    c_h, c_w = map_center.shape
    # Y 轴中心区域 (50% 高度)
    y_min = c_h // 4
    y_max = c_h * 3 // 4 - SAMPLE_SIZE
    y = np.random.randint(y_min, y_max + 1)
    # X 轴全宽度
    x_min = 0
    x_max = c_w - SAMPLE_SIZE
    x = np.random.randint(x_min, x_max + 1)

    # 裁剪背景
    bg = map_center[y:y+SAMPLE_SIZE, x:x+SAMPLE_SIZE].copy()

    # 使用传入的角度，并转化为弧度
    rad = math.radians(angle)

    # 缩放箭头模板
    temp_arrow = cv2.resize(arrow_src, (ARROW_SIZE, ARROW_SIZE), interpolation=cv2.INTER_CUBIC)

    # 旋转箭头 (OpenCV 旋转是逆时针，angle 为正值表示逆时针转)
    M = cv2.getRotationMatrix2D((ARROW_SIZE//2, ARROW_SIZE//2), -angle, 1.0)
    rotated = cv2.warpAffine(temp_arrow, M, (ARROW_SIZE, ARROW_SIZE),
                             flags=cv2.INTER_CUBIC,
                             borderMode=cv2.BORDER_CONSTANT,
                             borderValue=(0,0,0,0))

    # 分离通道
    b, g, r, alpha = cv2.split(rotated)

    # 灰度主体 (计算更高效的整数位移灰度化)
    arrow_body_gray = ((r.astype(np.uint16) * 77 + g.astype(np.uint16) * 150 + b.astype(np.uint16) * 29) >> 8).astype(np.uint8)

    # 描边逻辑
    kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (3,3))
    alpha_dilated = cv2.dilate(alpha, kernel, iterations=1)
    outline_mask = cv2.subtract(alpha_dilated, alpha)
    outline_f = outline_mask.astype(np.float32) / 255.0
    body_f = alpha.astype(np.float32) / 255.0

    # 随机偏移 ±2 (让模型学会不只认中心)
    start = (SAMPLE_SIZE - ARROW_SIZE) // 2
    off_y, off_x = np.random.randint(-2, 3, 2)
    sy, sx = start + off_y, start + off_x
    roi = bg[sy:sy+ARROW_SIZE, sx:sx+ARROW_SIZE].astype(np.float32)

    # 混合渲染
    res_with_outline = roi * (1 - outline_f) + 240.0 * outline_f
    final_roi = res_with_outline * (1 - body_f) + arrow_body_gray * body_f
    bg[sy:sy+ARROW_SIZE, sx:sx+ARROW_SIZE] = np.clip(final_roi, 0, 255).astype(np.uint8)

    # 保存图片
    img_name = f"arrow_{i:05d}.png"
    cv2.imwrite(os.path.join(OUTPUT_DIR, "images", img_name), bg, [cv2.IMWRITE_PNG_COMPRESSION, 0])

    # label: 注意这里必须对应旋转后的 sin/cos
    # 在 0 度时箭头朝上，y = -cos, x = sin
    s_val = math.sin(rad)
    c_val = math.cos(rad)
    return f"{img_name},{s_val},{c_val}\n"

# ---- 主生成函数 ----
def generate():
    if not os.path.exists(os.path.join(OUTPUT_DIR, "images")):
        os.makedirs(os.path.join(OUTPUT_DIR, "images"))

    arrow_src = cv2.imread(arrow_template_path, cv2.IMREAD_UNCHANGED)
    map_bg = cv2.imread(map_bg_path, cv2.IMREAD_GRAYSCALE)

    if arrow_src is None or map_bg is None:
        print("错误：资源文件加载失败！")
        return

    print(f"🚀 开始生成均匀分布数据集 ({NUM_SAMPLES} 张图)...")

    # --- 核心修改：生成均匀角度队列 ---
    # 将 360 度平均分成 NUM_SAMPLES 份
    # 为了增加鲁棒性，每份可以加一个极小的随机偏移 (噪声)，但整体绝对均匀
    step = 360.0 / NUM_SAMPLES
    angles = [(i * step) % 360.0 for i in range(NUM_SAMPLES)]

    # 打乱 angles 顺序（避免文件名和角度成正比，让训练读取更随机）
    np.random.shuffle(angles)

    # 多进程参数准备
    # args 包含索引 i 和 预分配的角度 angle
    args = [(i, angles[i], arrow_src, map_bg) for i in range(NUM_SAMPLES)]

    labels = []
    with Pool(processes=cpu_count()) as pool:
        # 使用 starmap 进行并行计算
        for result in tqdm(pool.starmap(process_one, args), total=NUM_SAMPLES):
            labels.append(result)

    # 写 labels
    with open(os.path.join(OUTPUT_DIR, "labels.txt"), "w") as f:
        f.writelines(labels)

    print("✅ 角度均匀分布的数据集生成完毕！")

if __name__ == "__main__":
    generate()