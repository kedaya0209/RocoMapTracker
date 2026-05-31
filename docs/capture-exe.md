# capture.exe — WGC 窗口截图子进程

## 概述

`capture.exe` 是一个独立的 Windows Graphics Capture (WGC) 截图子进程，通过 TCP Socket 与 Java 主进程通信。它替代了传统的 JNI/FFM 内联截图方案，消除了跨语言调用的内存开销。

### 功能

- 使用 WGC API 对指定窗口进行高帧率截图（最高 60 FPS）
- 按 ROI（感兴趣区域）列表提取局部像素，避免全帧 GPU→CPU 回读瓶颈
- 支持全帧模式，用于设置面板预览
- 检测窗口关闭、最小化/恢复事件
- 窗口尺寸变化时自动重建 Frame Pool

---

## 启动参数

```
capture.exe <hwnd_decimal> <port> [max_fps]
```

| 参数 | 类型 | 说明 |
|------|------|------|
| `hwnd_decimal` | 整数 | 目标窗口句柄（十进制） |
| `port` | 整数 | Java SocketServer 的 TCP 端口 |
| `max_fps` | 整数 | 目标帧率（可选，默认 30，范围 1~60） |

### 示例

```bat
capture.exe 123456 9001 30
```

---

## 架构

进程启动后创建 3 个线程：

| 线程 | 职责 |
|------|------|
| `capture_loop` | WGC 截图循环，使用 D3D11 逐 ROI 小纹理提取像素 |
| `send_loop` | 接收 Java 的 `PROCESSING_DONE` 信号，发送 `FRAME_DATA` |
| `monitor_loop` | 每秒检查窗口状态（是否销毁、是否最小化） |

### 数据流

```
Java SocketServer  <--TCP-->  capture.exe
                                  │
                          ┌───────┴───────┐
                          │  send_loop    │  ← 等待 PROCESSING_DONE
                          └───────┬───────┘
                                  │
                          ┌───────┴───────┐
                          │  capture_loop │  ← WGC TryGetNextFrame
                          │  (D3D11)      │    提取 ROI 像素
                          └───────┬───────┘
                                  │
                          ┌───────┴───────┐
                          │  monitor_loop │  ← 窗口状态检测
                          └───────────────┘
```

### 关键类

- **CaptureManager** — WGC 截图管理器：D3D11 设备、Frame Pool、Per-ROI Staging 纹理
- **FrameCache** — 线程安全的帧缓冲区（mutex + condition_variable）
- **ROI** — 万分数坐标区域（x, y, w, h, 0~10000）

---

## 通信协议

### 传输层

- TCP，地址 `127.0.0.1`
- 二进制，**Big-Endian**（网络字节序）
- `TCP_NODELAY` 启用（低延迟）
- Socket 超时：5 秒
- 最大重连次数：30 次（每次间隔 1 秒）

### 帧格式

所有消息使用统一的帧格式：

```
[4B] msgType   (int32, Big-Endian)
[4B] bodyLen   (int32, Big-Endian, 0 表示无消息体)
[NB] body      (消息体，长度由 bodyLen 指定)
```

### 握手流程

```
capture.exe                         Java
    │                                 │
    │──── HELLO (1) ────────────────>│  标识自身，声明 provides/subscribes
    │<─── (注册成功) ─────────────────│  SocketServer 路由注册
    │                                 │
    │──── REQUEST_ROI (100) ────────>│  请求 ROI 列表
    │<─── RETURN_ROI (101) ──────────│  返回 ROI 万分数坐标
    │                                 │
    │══════ 初始化 WGC 截图 ══════════│
    │                                 │
    │──── CAPTURE_READY (102) ──────>│  通知就绪
    │                                 │
    │══════ 开始截图循环 ═════════════│
```

### 消息类型

| 类型 | 方向 | 值 | 说明 |
|------|------|----|------|
| HELLO | C++→Java | 1 | 握手（定义于 socket_common.h） |
| REQUEST_ROI | C++→Java | 100 | 请求 ROI 列表 |
| RETURN_ROI | Java→C++ | 101 | 返回 ROI 列表 |
| CAPTURE_READY | C++→Java | 102 | 截图已就绪 |
| FRAME_DATA | C++→Java | 103 | 帧数据 |
| PROCESSING_DONE | Java→C++ | 104 | Java 处理完毕，请求下一帧 |
| WINDOW_CLOSED | C++→Java | 105 | 目标窗口已销毁 |
| STOP_REQUEST | Java→C++ | 106 | 停止截图 |
| WINDOW_STATE | C++→Java | 107 | 窗口状态变化 |
| SWITCH_MODE | Java→C++ | 108 | 切换 ROI/全帧模式 |

### 消息体格式详解

#### HELLO (1) — C++ → Java

```
[2B] clientIdLen
[NB] clientId           (UTF-8, 值为 "capture")
[2B] providesCount      (= 4)
[N*4B] provides[]       = { FRAME_DATA(103), CAPTURE_READY(102), WINDOW_CLOSED(105), WINDOW_STATE(107) }
[2B] subscribesCount    (= 4)
[N*4B] subscribes[]     = { RETURN_ROI(101), PROCESSING_DONE(104), STOP_REQUEST(106), SWITCH_MODE(108) }
```

#### RETURN_ROI (101) — Java → C++

```
[2B] count              (uint16, Big-Endian)
重复 count 次:
  [2B] x               (int16, 万分数 0~10000)
  [2B] y               (int16, 万分数 0~10000)
  [2B] w               (int16, 万分数 0~10000)
  [2B] h               (int16, 万分数 0~10000)
```

ROI 坐标使用万分数（per-mil），范围 0~10000。实际像素 = `万分数 * 窗口宽(高) / 10000`。

#### FRAME_DATA (103) — C++ → Java

```
[2B] roi_count          (uint16, Big-Endian)
重复 roi_count 次:
  [1B] index            (uint8, ROI 序号)
  [2B] w                (uint16, Big-Endian, 像素)
  [2B] h                (uint16, Big-Endian, 像素)
  [2B] stride           (uint16, Big-Endian, 行字节数 = w * 4)
  [4B] data_len         (uint32, Big-Endian, 像素数据长度)
  [data_len] bgra_data  (BGRA 32bpp 像素数据)
```

- 每帧只包含有变化的 ROI 区域
- 全帧模式下会在最后一个 index（index = roi_count）附加完整窗口内容区

#### WINDOW_STATE (107) — C++ → Java

```
[1B] state              (0=最小化, 1=恢复)
```

#### SWITCH_MODE (108) — Java → C++

```
[1B] mode               (0=ROI模式, 1=全帧模式)
```

全帧模式下，`FRAME_DATA` 在正常 ROI 后附加 index=`roi_count` 的额外条目，包含完整窗口内容区（去除标题栏）的 BGRA 像素。

---

## 构建

使用 `build_capture.bat`：

```bat
cd cpp
build_capture.bat
```

### 依赖

- Windows SDK（WGC: `windows.graphics.capture.h`）
- D3D11 (`d3d11.lib`, `dxgi.lib`)
- WinRT (`windowsapp.lib`, `runtimeobject.lib`)
- Winsock2 (`ws2_32.lib`)
- User32 (`user32.lib`)

### MSVC 编译命令

```bat
rc /nologo /fo resource.res resource.rc
cl /std:c++17 /O2 /EHsc /arch:AVX2 /Fe:capture.exe ^
    capture_main.cpp resource.res ^
    d3d11.lib dxgi.lib windowsapp.lib runtimeobject.lib ^
    user32.lib ws2_32.lib winmm.lib
```

---

## 输出目录

编译产物输出到 `roco-ui/src/main/resources/capture/RocoMapTracker-capture.exe`（由构建脚本复制）。
