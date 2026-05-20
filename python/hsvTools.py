import cv2
import numpy as np

def nothing(x):
    pass

# 1. 读取静态图片（确保当前目录下有 player.png）
frame = cv2.imread("C:/Users/tangh/Desktop/dataset/result/-3_097d0500.png")

if frame is None:
    print("错误：无法读取图片，请检查 'player.png' 路径是否正确！")
    exit()

# 2. 创建调试窗口和滑块
cv2.namedWindow("Trackbars")
cv2.createTrackbar("L-H", "Trackbars", 0, 180, nothing)
cv2.createTrackbar("L-S", "Trackbars", 0, 255, nothing)
cv2.createTrackbar("L-V", "Trackbars", 0, 255, nothing)
cv2.createTrackbar("U-H", "Trackbars", 180, 180, nothing)
cv2.createTrackbar("U-S", "Trackbars", 255, 255, nothing)
cv2.createTrackbar("U-V", "Trackbars", 255, 255, nothing)

# 3. 提前转好 HSV，避免在循环里重复转换，提升流畅度
hsv = cv2.cvtColor(frame, cv2.COLOR_BGR2HSV)

while True:
    # 获取滑块当前值
    l_h = cv2.getTrackbarPos("L-H", "Trackbars")
    l_s = cv2.getTrackbarPos("L-S", "Trackbars")
    l_v = cv2.getTrackbarPos("L-V", "Trackbars")
    u_h = cv2.getTrackbarPos("U-H", "Trackbars")
    u_s = cv2.getTrackbarPos("U-S", "Trackbars")
    u_v = cv2.getTrackbarPos("U-V", "Trackbars")

    lower_range = np.array([l_h, l_s, l_v])
    upper_range = np.array([u_h, u_s, u_v])

    # 4. 阈值化处理
    mask = cv2.inRange(hsv, lower_range, upper_range)
    res = cv2.bitwise_and(frame, frame, mask=mask)

    # 5. 显示结果
    cv2.imshow("Original Frame", frame)
    cv2.imshow("Mask", mask)
    cv2.imshow("Filtered Result", res)

    # 按下 'q' 键退出循环
    if cv2.waitKey(1) & 0xFF == ord('q'):
        break

# 静态图不需要 cap.release()，直接销毁窗口即可
cv2.destroyAllWindows()