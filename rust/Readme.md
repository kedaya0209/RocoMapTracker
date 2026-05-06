# WGC Capture

Windows Graphics Capture (WGC) Rust FFI 库，用于高性能窗口内容捕获和 ROI 区域提取。

## 功能特性

- **高性能窗口捕获**：基于 Windows Graphics Capture API 实现，支持窗口级实时捕获
- **ROI 区域提取**：支持自定义多个感兴趣区域 (ROI)，按比例坐标提取指定区域
- **灰度转换**：内置优化的 BGRA 到灰度转换算法，使用整数移位优化性能
- **零拷贝传输**：通过指针直接传输数据，避免额外的内存复制开销
- **多线程架构**：生产者-消费者模式，捕获与处理并行执行
- **GPU 加速**：使用 D3D11 Staging Texture 进行高效的 GPU 到 CPU 内存传输
- **动态尺寸适配**：自动检测窗口尺寸变化并重置 Staging Texture

## 架构设计

```
┌─────────────┐    生产者    ┌─────────────┐    消费者    ┌─────────────┐
│  WGC API    │ ──────────→ │ Frame Slot  │ ──────────→ │  Callback   │
│ (D3D11)     │             │ (Condvar)   │             │ (JNI/JNA)   │
└─────────────┘             └─────────────┘             └─────────────┘
      ↑                                                          ↓
      │                    ┌─────────────┐                      │
      └────────────────────│  ROI List  │──────────────────────┘
                            │ (RwLock)   │
                            └─────────────┘
```

### 核心组件

1. **CaptureInstance**：单个捕获实例，包含：
    - D3D11 设备和上下文
    - 运行状态控制
    - ROI 区域列表
    - 帧数据槽（生产者-消费者同步）
    - 捕获会话

2. **FrameData**：帧数据结构，包含原始 BGRA 数据和元信息

3. **GpuContext**：D3D11 设备上下文封装，支持跨线程共享

## API 接口

### `create(hwnd_i64: i64, cb: JniCallback) -> i32`

创建新的窗口捕获实例。

**参数：**

- `hwnd_i64`: 窗口句柄（64 位整数）
- `cb`: 回调函数，用于接收处理后的灰度数据

**返回：**

- 成功返回实例 ID（非负整数）
- 失败返回 -1

### `set_rois(id: i32, ptr: *const ROI, len: usize)`

设置指定捕获实例的 ROI 区域列表。

**参数：**

- `id`: 实例 ID
- `ptr`: ROI 数组指针
- `len`: ROI 数组长度

**ROI 坐标说明：**
ROI 使用 **万分之一比例坐标** (0-10000)：

- `x, y`: 左上角位置（相对于窗口）
- `w, h`: 宽度和高度

例如，窗口中心的 ROI：

```rust
ROI { x: 2500, y: 2500, w: 5000, h: 5000 }  // 50% 大小，居中
```

### `stop(id: i32)`

停止并销毁指定捕获实例。

**参数：**

- `id`: 实例 ID

## 回调函数格式

```rust
extern "C" fn callback(
    id: i32,         // 实例 ID
    index: i32,      // ROI 索引
    data: *const u8, // 灰度数据指针
    len: usize,      // 数据长度
    w: i32,          // 区域宽度
    h: i32,          // 区域高度
    code: i32        // 状态码（0 = 正常，-1 = 已停止）
)
```

## 性能优化

### 1. 灰度度转换优化

使用工业级标准灰度算法的快速实现：

```rust
gray = (R * 77 + G * 150 + B * 29) >> 8
```

相比浮点运算，整数移位性能提升约 **10 倍**。

### 2. 内存复用机制

- **Frame Slot 缓冲复用**：重用已分配的缓冲区，避免频繁 `malloc/free`
- **局部缓冲复用**：worker 线程本地 gray_buffer 持久化，减少锁竞争
- **Staging Texture 复用**：尺寸不变时复用 GPU 资源

### 3. 线程同步优化

- 使用 `Condvar` 实现高效的生产者-消费者同步
- 尽早释放锁，减少临界区时间
- D3D11 Context 使用 `Mutex` 保护，确保线程安全

### 4. GPU-CPU 传输优化

- 使用 `D3D11_USAGE_STAGING` 和 `D3D11_CPU_ACCESS_READ` 标志
- 通过 `CopyResource` 实现异步传输
- 使用 `Map/Unmap` 进行 CPU 可读映射

## 构建与依赖

### 依赖项

```toml
[dependencies]
lazy_static = "1.4"
windows = { version = "0.58", features = [
    "Win32_Graphics_Direct3D11",
    "Win32_Graphics_Direct3D",
    "Win32_Graphics_Dxgi",
    "Win32_Graphics_Dxgi_Common",
    "Win32_Foundation",
    "Win32_System_WinRT_Direct3D11",
    "Win32_System_WinRT_Graphics_Capture",
    "Win32_UI_WindowsAndMessaging",
    "Graphics_Capture",
    "Graphics_DirectX_Direct3D11",
    "Graphics_DirectX",
] }
```

### 构建

```bash
cd src/main/rust
cargo build --release
```

生成的动态库：

- Windows: `target\release\wgc_capture.dll`

## 使用示例

### Java/JNA 调用

```java
// 定义 ROI 结构
public static class ROI extends Structure {
    public int x, y, w, h;
    // ... 实现 ByReference ...
}

// 定义回调接口
public interface Callback extends Callback {
    void invoke(int id, int index, Pointer data, int len, int w, int h, int code);
}

// 使用
NativeLibrary lib = NativeLibrary.getInstance("wgc_capture");
Function create = lib.getFunction("create");
Function setRois = lib.getFunction("set_rois");
Function stop = lib.getFunction("stop");

// 创建捕获实例
int id = create.invoke(Int.class32, hwnd, callback);

// 设置 ROI
ROI[] rois = new ROI[]{
        new ROI(1000, 1000, 8000, 8000)  // 80% 大小，偏移 10%
};
setRois.

invoke(id, rois, rois.length);

// 停止捕获
stop.

invoke(id);
```

## 注意事项

1. **线程安全**：D3D11 DeviceContext 非线程安全，已通过 Mutex 保护
2. **坐标系统**：ROI 使用比例坐标 (0-10000)，非像素坐标
3. **内存管理**：回调中的数据指针仅在回调期间有效，需立即复制
4. **窗口关闭**：监听窗口关闭事件，自动停止捕获并通知回调
5. **边框显示**：默认关闭捕获边框 (`SetIsBorderRequired(false)`)

## 技术栈

- **Rust 2021 Edition**
- **Windows Graphics Capture API**
- **Direct3D 11**
- **Windows-RS (0.58)**
- **lazy_static (1.4)**

## 许可证

根据项目整体许可证。
