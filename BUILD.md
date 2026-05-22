# RocoMapTracker 构建指南

## 环境要求

| 组件            | 版本                             |
|---------------|--------------------------------|
| GraalVM       | 25.0.2+10.1 (企业版)              |
| Maven         | 3.8+                           |
| Visual Studio | 18 (Community) + Windows 11 SDK |
| JDK           | 25                             |

## 构建管线总览

```
1、build-jar.bat ──→ 2、run-agent.bat ──→ 3、copy-config.bat ──→ 4、build-native.bat
                                                                       │
                                          ┌────────────────────────────┘
                                          │
                                          └──→ 5、build-native-instrument.bat
                                                   │
                                                   └──→ 6、run-pgo.bat
                                                            │
                                                            └──→ 7、build-native-pgo.bat
```

## 第一阶段：常规构建（Step 1-4）

### Step 1 — 打包 JAR

```bat
1、build-jar.bat
```

执行 `mvn clean package -DskipTests`，生成 `target/RocoMapTracker-1.0.0-jar-with-dependencies.jar`。

### Step 2 — 采集反射配置

```bat
2、run-agent.bat
```

用 `native-image-agent` 启动 JAR，**手动操作一遍完整功能**（地图匹配、OCR、资源加载等），每 10 秒自动存盘。

> 首次运行或依赖变更后必须执行。日常迭代可跳过。

### Step 3 — 复制配置

```bat
3、copy-config.bat
```

将 `target/native-agent/` 下的反射配置复制到 `src/main/resources/META-INF/native-image/`。

### Step 4 — 构建 Native EXE

```bat
4、build-native.bat
```

执行 `mvn clean native:compile -Pnative`，编译为 Native Image。输出 `target/RocoMapTracker.exe`。

**构建参数说明：**

| 参数                            | 说明             |
|-------------------------------|----------------|
| `--gc=Z`                      | ZGC 低延迟并发 GC   |
| `-R:MaxHeapSize=1G`           | 堆上限 1GB        |
| `-R:MinHeapSize=256M`         | 启动预分配 256MB    |
| `-R:MaxDirectMemorySize=512M` | 堆外内存上限         |
| `-H:+UseStringDeduplication`  | String 去重（企业版） |
| `-H:+RemoveUnusedSymbols`     | 移除未使用符号        |
| `-O3 -march=x86-64-v3`        | 最高优化 + AVX2    |

---

## 第二阶段：PGO 优化构建（Step 5-7）

PGO（Profile-Guided Optimization）通过采集运行时数据指导编译器优化，匹配速度提升 **15-30%**。

### Step 5 — 构建插桩版

```bat
5、build-native-instrument.bat
```

执行 `mvn clean native:compile -Pnative-instrument`，生成 `target/RocoMapTracker-instrumented.exe`。

> 插桩版比常规版慢，仅用于采集数据。

### Step 6 — 采集 PGO 数据

```bat
6、run-pgo.bat
```

启动插桩版 EXE，**务必覆盖完整业务场景**：

- 地图匹配（不同区域跑一遍）
- OCR 文字识别
- 箭头方向检测
- 资源点加载

运行 **10-15 分钟**后**正常关闭**程序（点窗口 × 或 Alt+F4，不要杀进程），自动生成 `default.iprof`。

### Step 7 — 构建最终优化版

```bat
7、build-native-pgo.bat
```

读取 `default.iprof` 执行 `mvn clean native:compile -Pnative-pgo`，输出最终优化版 `target/RocoMapTracker.exe`。

---

## 快速参考

| 场景     | 命令              |
|--------|-----------------|
| 首次构建   | `1 → 2 → 3 → 4` |
| 日常迭代   | `4`             |
| PGO 优化 | `5 → 6 → 7`     |
| 依赖变更   | `1 → 2 → 3 → 4` |

## 内存配置

| 参数            | 值         | 说明                      |
|---------------|-----------|-------------------------|
| Java Heap     | 256M - 1G | ZGC 自动管理                |
| Direct Memory | 512M      | DirectByteBuffer 上限     |
| Total Process | ~1.2G     | 含 OpenCV/ONNX Native 内存 |

## 故障排查

| 问题                         | 解决                                     |
|----------------------------|----------------------------------------|
| `Unknown GC: Z`            | 检查 GRAALVM_HOME 指向企业版 25.0.2           |
| `default.iprof not found`  | 确保插桩版正常关闭（不能杀进程）                       |
| Native 内存持续增长              | 设置环境变量 `ORT_DISABLE_ARENA_ALLOCATOR=1` |
| `link: fatal error LNK...` | 检查 VS 18 和 Windows SDK 安装              |
