# Native Image 崩溃排查清单：虚拟线程 Continuation 导致的 DEP 违规

## 概述

GraalVM Native Image 构建的 `RocoMapTracker.exe` 在 SIFT 匹配运行约 214 秒后崩溃，进程退出。IDEA (HotSpot JVM) 模式下运行正常。

---

## 清单

### 1. 崩溃现象

- [x] 应用启动正常，SIFT 初始化完成（201247 features loaded）
- [x] 运行约 3.5 分钟后崩溃（VM uptime: 214s）
- [x] 无 Java 异常堆栈输出
- [x] 崩溃日志：`C:\Users\tangh\Desktop\新建文件夹\err.txt`

### 2. 日志分析步骤

- [x] 确认崩溃信号：`data execution prevention violation at address 0x0000000000000000`
- [x] 确认 RIP（指令指针）：`0x0000000000000000` — 尝试执行空地址
- [x] 确认崩溃线程：`ForkJoinPool-3-worker-3` — 虚拟线程载体线程
- [x] 确认线程状态：`STATUS_IN_JAVA (PREVENT_VM_FROM_REACHING_SAFEPOINT)`
- [x] 检查 Java 帧锚点：`JavaFrameAnchors.lastAnchor = 0` — 栈上无应用代码帧
- [x] 检查寄存器：RDX 指向 `MapMatcherProcessor` 实例
- [x] 检查 GC 历史：64 次 GC 均正常，崩溃前最后一次 GC 在 212.987s
- [x] 检查线程列表：所有其他线程均为 `STATUS_IN_SAFEPOINT (ALLOW_SAFEPOINT)`

### 3. 根因排查

- [x] 是否 HotSpot JVM 特有？ → 否，IDEA 运行正常
- [x] 是否 Native Image 特有？ → 是
- [x] 是否内存溢出？ → 否，堆使用正常（Eden: 42.75M, Old: 157.83M / Max: 512M）
- [x] 是否栈溢出？ → 否，RIP=0 而非栈地址
- [x] 是否 JNI 调用错误？ → 否，调用路径无 JavaCPP/JNI，纯 Socket 通信
- [x] 触发代码路径：`MapMatcherProcessor.onProcess()` → `Thread.startVirtualThread()` → `executeMatching()` → `SiftMatchHandler.sendFrameAndWait()` → `SocketSession.send()` → Socket I/O 阻塞 → 虚拟线程卸载

### 4. 修复方案

- [x] 将 `Thread.startVirtualThread()` 替换为 `Executors.newSingleThreadExecutor()`
- [x] 匹配池容量为 1（由 `matching` AtomicBoolean 门控保证，最多一个并发操作）
- [x] 线程设为 daemon，不影响 JVM 退出
- [x] 在 `close()` 中 shutdown 线程池

### 5. 验证

- [x] `mvn compile -q` 编译通过
- [x] IDEA 运行正常
- [ ] Native Image 重新构建并运行验证（需用户执行）

---

## 根因分析

### 崩溃路径

```
MapMatcherProcessor.onProcess()
  └─ Thread.startVirtualThread(() -> executeMatching(...))    ← 虚拟线程启动
       └─ executeMatching()
            └─ matchClient.sendFrameAndWait()
                 ├─ encodeFrameData()                          ← 纯 Java
                 └─ s.send(MSG_FRAME_DATA, data)               ← SocketSession.send()
                      └─ DataOutputStream.write() / SocketOutputStream.write()
                           ↓ Socket I/O 阻塞
                      虚拟线程卸载 (Continuation.yield)
                           ↓
                      载体线程 ForkJoinPool-3-worker-3
                      执行 Continuation 内部操作
                           ↓
                      RIP=0 → DEP 违规 !!
```

### 直接原因

崩溃寄存器状态：
- **RIP** = `0x0000000000000000` — 执行了空函数指针
- **RDX** = `MapMatcherProcessor` 实例 — 该对象是触发路径的入口
- **RAX** = `0x0000000000000000` — 函数指针值为空
- **状态** = `PREVENT_VM_FROM_REACHING_SAFEPOINT` — 线程在 VM 内部关键区阻塞 GC

崩溃位于 **Substrate VM 运行时内部**（非应用代码），发生在虚拟线程载体线程（ForkJoinPool worker）上。栈上无任何应用代码帧（`JavaFrameAnchors.lastAnchor = 0`），说明崩溃点在 Continuation 挂载/卸载的底层实现中。

### 根本原因

**GraalVM Substrate VM (25.0.2) 在 Windows 平台上的虚拟线程 Continuation 实现存在缺陷。**

具体分析：

1. **工作流差异**：IDEA 使用 HotSpot JVM 的虚拟线程实现（Project Loom），该实现在 JDK 21~24 经过充分测试，Continuation yield/unmount 机制成熟稳定。

2. **Substrate VM 独立实现**：Native Image 使用自己独立的 Continuation 实现，需要处理线程栈帧序列化、寄存器保存恢复、载体线程调度等底层操作，与 HotSpot 代码路径完全不同。

3. **Socket I/O 阻塞触发**：匹配线程通过 `SocketSession.send()` 向 C++ sift_match.exe 发送帧数据。Socket I/O 在等待数据发送时会阻塞，触发虚拟线程的 Continuation.yield 操作——将虚拟线程从载体线程卸载，让载体线程可以运行其他虚拟线程。

4. **空函数指针**：在卸载/挂载的 VM 内部代码中，某个函数指针表（vtable/itable 或调度回调）为 null，导致调用 `call *%rax` 时 RAX=0，CPU 跳转到地址 0 执行代码，触发操作系统的 DEP（数据执行保护）违规。

### 为什么之前被误判为栈溢出

第一次崩溃日志的 RIP 在栈地址附近，导致误判为虚拟线程载体的栈溢出。但实际上：
- 堆使用正常（Old Gen 占用不到一半）
- GC 日志正常，无 OOM 征兆
- 新崩溃日志明确显示 RIP=0 而非栈地址

### 修复原理

`MapMatcherProcessor` 的 `matching` AtomicBoolean 门控保证**最多只有一个匹配操作并发执行**，不需要虚拟线程的轻量级多路复用能力。改用容量为 1 的平台线程池后：

- 匹配任务在专用平台线程上执行，不走 Substrate VM 的 Continuation 机制
- Socket I/O 阻塞是平台线程级别的阻塞，不涉及虚拟线程的挂载/卸载
- 完全避开 Substrate VM 虚拟线程 Continuation 实现的 bug

---

## 涉及文件

| 文件 | 改动 |
|------|------|
| `roco-engine/.../capture/processor/MapMatcherProcessor.java` | `Thread.startVirtualThread()` → `executor.submit()` |
| `.` | 新增 executor 字段 + close 中 shutdown |

## 备注

项目中共有约 20 处使用了虚拟线程（`Thread.ofVirtual()` / `Thread.startVirtualThread()` / `Executors.newVirtualThreadPerTaskExecutor()`）。如果后续在其他运行路径上遇到类似 Native Image 崩溃，可按相同模式逐一替换为平台线程。当前的修复仅针对 SIFT 匹配路径——这是崩溃触发的实际路径，改动最小、风险最低。
