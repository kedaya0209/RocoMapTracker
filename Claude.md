# RocoMapTracker 项目根目录

AI 协作专用 – 全局架构索引与跨模块约束
项目版本: 1.1.1 | Java 25 + GraalVM Native Image | C++ OpenCV 4.10.0


# Language & Communication Rules (MANDATORY)



- 你必须始终使用简体中文交流。所有思考、分析、代码注释和响应都必须使用中文。
- 技术术语仅在代码标识符（变量名、类名等）中可以保留英文。
- 违反此规则是不可接受的。


## Java Code Style Rules (MANDATORY)

- 禁止使用全限定类名。例如：com.luoke.app.map.loader.ImageLoader.getInstance().clearCache(); [错误]
- 所有使用的类（除 java.lang 外）必须在文件顶部使用 import 语句导入。
- 如果你生成了一个全限定名，必须同步添加 import 并将调用处改为简短类名。
- 例外：两个包有同名类时，可以保留其中一个的全限定名。

### 代码设计原则 (SOLID 与 OOP 实践)

- **单一职责原则**：一个类只应有一个引起它变化的原因。避免“上帝类”，每个类聚焦一个明确的职责。
- **开闭原则**：对扩展开放，对修改关闭。优先使用接口抽象、策略模式、装饰器模式等，而不是修改已有稳定代码。
- **里氏替换原则**：子类必须能够替换父类且不破坏程序正确性。避免子类覆盖父类方法时改变原有语义。
- **接口隔离原则**：接口应小而专注，避免“胖接口”。多个专用接口优于一个通用接口。
- **依赖倒置原则**：依赖抽象（接口/抽象类）而非具体实现。高层模块不应依赖低层模块，二者都应依赖抽象。

### 工具类使用规则（通用）

- **优先使用项目已有工具类**：在实现通用功能（文件、资源、JSON、配置等）时，必须先检查是否存在已有工具类。
- **查找顺序**：
    1. 当前模块内的专用工具类（见各模块 CLAUDE.md 中的“本模块工具类清单”）
    2. 全局工具类：roco-common 中的 FilePathUtil、HashUtil、ResourceExtractor、EnvironmentUtil、ResourceUtils、JsonUtils、ConfigHelper、ConfigPersistence
    3. 最后才考虑自行实现或引入第三方库
- **禁止行为**：不得重复实现项目已提供的工具方法。
- **例外**：现有工具类确实无法满足需求时，应扩展工具类而非绕过。

### 其他编码规范

- **使用不可变类**：优先使用 record、final 字段、不可变集合，减少并发隐患。
- **避免返回 null**：优先返回 Optional、空集合或空对象（如 Collections.emptyList()）。
- **异常处理**：捕获特定异常，避免 catch(Exception)；使用 try-with-resources 管理资源。
- **命名约定**：类名 UpperCamelCase，方法/变量 lowerCamelCase，常量 UPPER_SNAKE_CASE。
- **测试覆盖**：关键逻辑应有单元测试，使用 JUnit 5 和 AssertJ 等断言库。

#### 线程安全约定 (MANDATORY)

- **所有类必须标注并发安全性**：使用 `@ThreadSafe` 或 `@NotThreadSafe`（来自 `net.jcip:jcip-annotations`，provided 依赖，所有模块已继承）。
- **@ThreadSafe 适用场景**：
    - 无状态工具类（仅静态方法，无实例字段）
    - 使用 `ConcurrentHashMap`、`CopyOnWriteArrayList`、`Atomic*`、`synchronized`、`volatile` 保障共享状态的类
    - 单例且所有字段不可变或线程安全的类
    - 不可变 record
- **@NotThreadSafe 适用场景**：
    - 有可变实例字段且无显式同步的类
    - JavaFX Node 子类或操作 JavaFX Node 的类（UI 组件/渲染器必须标注）
    - DTO/POJO 类（即使使用 @Data）
- **共享可变状态**：必须使用锁（`synchronized`、`ReentrantLock`）或原子类（`AtomicInteger`、`AtomicReference`）保护。
- **发布规则**：标注放在 class/enum/record 声明之前，import 语句之后。注解为类级别，描述所有 public 方法的线程安全语义。

#### SLF4J 日志约定 (MANDATORY)

- **必须使用 Lombok @Slf4j**：在类级别添加 `@Slf4j` 注解，通过 `log` 字段访问 Logger，禁止手动声明 `private static final Logger`。
- **日志级别选用**：
    - `ERROR`：可恢复的异常、操作失败（如 IO 异常、初始化失败）
    - `WARN`：非预期但可自动恢复的情况（如重试、降级、解析跳过）
    - `INFO`：模块生命周期事件（启动、停止、切换、配置变更）
    - `DEBUG`：详细诊断信息（仅开发阶段需要）
- **禁止使用 System.out/System.err**：所有控制台输出必须通过 SLF4J。
- **参数化日志**：使用 SLF4J 占位符 `{}`，禁止字符串拼接（`log.info("val: " + val)` [错误]）。
    - 正确：`log.info("val: {}", val);`
- **异常日志**：异常对象作为最后一个参数传入，不要调用 `e.getMessage()`。
    - 正确：`log.error("操作失败", e);`
    - 错误：`log.error("操作失败: " + e.getMessage());`

#### Lombok 使用约定 (MANDATORY)

- **允许使用的 Lombok 注解**：
    - `@Slf4j` — 日志（必需）
    - `@Getter` / `@Setter` — 简单字段访问器
    - `@Data` — `@Getter + @Setter + @ToString + @EqualsAndHashCode + @RequiredArgsConstructor` 的组合
    - `@AllArgsConstructor` / `@NoArgsConstructor` — 构造器
    - `@Builder` — 构建器模式
- **禁止使用的 Lombok 注解**：
    - `@UtilityClass` — 隐藏构造器逻辑，降低可读性
    - `@Accessors(chain = true)` — 链式 setter 与 JavaBean 规范不兼容
    - `@SneakyThrows` — 隐藏受检异常，违反异常处理原则
    - `val` / `var` — 降低类型可读性，统一使用显式类型或 `var`（仅限局部变量类型可自明时）
- **使用 @Data 前确认**：类是否适合生成 `equals()`/`hashCode()`。例如持有 mutable 集合、byte[] 或 UI 组件的类应手动排除。
    - 使用 `@Data(exclude = {"fieldName"})` 排除不应参与等值比较的字段。
    - 不可变类应转为 `record` 而非使用 `@Data`。

此规则优先级高于其他所有风格建议，必须严格遵守。


## 快速参考 – 构建/运行/测试命令



# 日常开发 (JVM)
mvn clean compile
mvn javafx:run -pl roco-ui

# 打包
mvn clean package

# C++ 子进程编译 (Visual Studio)
cd cpp && build_capture.bat   # RocoMapTracker-capture.exe
cd cpp && build_sift.bat      # RocoMapTracker-sift_match.exe

# Native Image 构建
mvn -Pnative clean package -pl roco-ui -am
mvn -Pnative-instrument clean package -pl roco-ui -am   # PGO 插桩
mvn -Pnative-pgo clean package -pl roco-ui -am          # PGO 优化


# 多模块结构



模块依赖树：

roco-ui (最终应用 JavaFX + Native Image)
└─ roco-engine (核心引擎: 截图/上下文/Hook/匹配调度)
├─ roco-map (地图管理: 下载/拼接/资源点)
│    └─ roco-common (基础工具)
└─ roco-common

模块职责表（使用空格分隔，请视为等宽字体）：

模块            职责
roco-common    配置中心、资源/JSON 工具
roco-map       地图下载/拼接、资源点模型
roco-engine    截图采集、上下文、Hook 事件、匹配调度
roco-ui        JavaFX 界面、渲染引擎、设置面板
cpp/           C++ WGC 截图 + SIFT 匹配子进程 (Socket)
plugins/       Python pcap 桥接器子进程


# 跨语言边界逻辑



C++ 子进程                     Java 侧                                         协议
RocoMapTracker-capture.exe     CaptureHandler + CaptureProcessManager          Socket, BGRA 帧, ROI 万分数
RocoMapTracker-sift_match.exe  SiftMatchHandler + SiftProcessManager          请求-响应, 特征匹配

- ROI 坐标使用万分数 (0~10000)：自适应分辨率。
Python 子进程                     Java 侧                                    协议
RocoMapTracker-pcap.exe          ExternalBridgeHandler + PcapProcessManager   Socket, 游戏事件

- 子进程生命周期：NativeProcess 管理，JobObjectManager 保证父进程退出时子进程销毁。
- 崩溃自动重连：CaptureService / SiftClientManager / PcapBridgeManager 监控。


# 坐标系数学



层级   名称              原点          单位
L1     屏幕像素          Canvas 左上角  物理像素
L2     Canvas 逻辑像素    地图左上角      1:1 地图像素
L3     地图逻辑坐标       地图中心        缩放后逻辑单位

转换公式（详见 MapCoordinateManager, InteractiveCanvas）：
- L1 ↔ L2： CanvasX = (ScreenX - offsetX) / scale
- L2 ↔ L3： CanvasX = mapWidth/2 + x * 2^(imageZoom - jsonZoom)

缩放交互：MapContext.zoom(factor, mx, my) 保持鼠标点对齐。
跟随模式：offsetX = viewWidth/2 - playerX * followScale


# 全局架构约束

所有 UI 操作必须 Platform.runLater()
- HookMulticast 在虚拟线程中发布事件，禁止直接操作 Node。

Hook 事件单向流：数据层 → UI 层
- 禁止在 Hook 回调中修改核心状态（如 MapContext）。

## 捕获与匹配
- CaptureService 黑帧检测阈值：连续 30 帧全黑 → 强停 + 重连。
- 地图匹配连续失败 5 次才标记 Lost。

## GraalVM Native Image 约束
- DLL 必须在运行时释放到临时目录（不能从 JAR 内加载）。
- reachability-metadata.json 必须完整声明反射/JNI 访问。
- System.gc() 在 Serial GC 下有效（同步全量回收）。
- roco-common 需依赖 graal-sdk (provided)。

## 资源路径系统
- 内嵌资源通过 classpath 访问，外部资源通过 ResourceUtils.getExternalFile()。
- SIFT 缓存：.feat / .pca64.ultra.feat (Zstd 压缩)。

## Native Image 虚拟线程约束
- Substrate VM 25.0.2 在 Windows 上的虚拟线程 Continuation 实现存在缺陷（空函数指针 → RIP=0 → DEP 违规）。
- 涉及阻塞 Socket I/O 的虚拟线程在载体线程卸载/挂载时可能触发此 bug。
- 规避方案：使用 `Executors.newSingleThreadExecutor()` 等平台线程池替代虚拟线程，仅在不需要阻塞 I/O 的场景使用虚拟线程。
- 相关文档：`docs/Native-Image-虚拟线程崩溃排查清单.md`。
- 之前修复的触发点：`MapMatcherProcessor.executeMatching()` 原使用 `Thread.startVirtualThread()`，涉及 `SocketSession.send()` 阻塞 I/O，已替换为专用单线程池。


#ROI 布局 (万分数)
ROI   用途                坐标 (x,y,w,h)           实际覆盖 (1920×1080)
0     小地图 (SIFT+箭头)   (8900,700,1000,1800)    右上角 192×194

实际像素 = 万分数 × 窗口尺寸 / 10000


# 各模块详细说明

请查看对应子目录下的 CLAUDE.md：

- roco-common/CLAUDE.md
- roco-map/CLAUDE.md
- roco-engine/CLAUDE.md
- roco-ui/CLAUDE.md
- cpp/CLAUDE.md