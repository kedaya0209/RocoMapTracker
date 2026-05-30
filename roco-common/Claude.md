roco-common 模块

# 基础工具层 – 配置中心、资源/JSON 工具、文件操作、路径常量

AI 协作专用 – 本模块不依赖任何内部模块，无 JavaFX，无模型类。


## 模块职责


- 配置中心：15 个 Config 类，管理应用所有可配置参数。
- 配置持久化：Properties 文件读写，支持 UTF-8 BOM。
- 资源管理：ResourceUtils 实现外部文件优先 → classpath 回退。
- 文件路径解析：FilePathUtil 提供应用根目录定位和文件路径拼接。
- 资源释放：ResourceExtractor 从 JAR 解压内嵌资源到外部文件系统。
- 哈希校验：HashUtil 提供 MD5 / SHA-256 文件校验。
- 环境检测：EnvironmentUtil 判断是否运行在 GraalVM Native Image 环境。
- JSON 工具：Jackson ObjectMapper 单例。
- 路径常量：PathConfig 定义 exe、地图、模型等不可变路径。
- 资源套件切换：ResourceConfigContext 支持 INTERNAL / EXTERNAL 资源模式。


## 类清单 (25 个)


### 配置类 (15 个)：
CaptureConfig         截图配置：窗口名、FPS、黑帧检测阈值
ConfigHelper          Properties 类型安全读取工具（含默认值）
ConfigPersistence     配置持久化：UTF-8 BOM 的 app_config.properties
DownloadConfig        下载设置：URL、超时、重试、并发数
MiniMapConfig         小地图检测参数：resize、HoughCircles
NavigConfig           导航模式配置：角度、旋转
PathConfig            不可变路径常量：exe、地图、模型等
PcapConfig            pcap 桥接器配置：端口、重启策略
PlayerConfig          玩家追踪：EMA 平滑、瞬移检测、Lost 容差
RenderConfig          渲染/动画：图标尺寸、缩放、玩家标记、Toast
SiftConfig            SIFT 匹配参数：特征检测、FLANN、RANSAC
SocketConfig          Socket/子进程：超时、重启间隔
StatsConfig           统计覆盖层：FPS、耗时显示开关
UiConfig              UI/交互：主题、字体、缩放、hover 半径
UpdateConfig          自动更新配置：检查间隔、下载源
ViewConfig            地图视图：缩放、跟随模式、置灰距离

### 工具类 (7 个)：
FilePathUtil          路径解析：应用根目录、相对路径、外部文件映射
HashUtil              哈希校验：MD5 / SHA-256
ResourceExtractor     资源释放：从 JAR 解压内嵌资源到外部文件系统
EnvironmentUtil       GraalVM Native Image 环境检测
JsonUtils             Jackson ObjectMapper 单例
ResourceUtils         资源加载：外部文件优先 → classpath 回退
ResourceConfigContext 资源套件枚举：INTERNAL / EXTERNAL 切换

### 值对象 (1 个)：
RoiRect              不可变 ROI 万分数坐标 (x, y, w, h)，@ThreadSafe

注意：旧 FileUtil 已拆分为 FilePathUtil + HashUtil + ResourceExtractor + EnvironmentUtil 四个专用工具类。
FileUtil 保留为 @Deprecated(forRemoval=true) 委托层，新代码禁止使用。


## 单例模式


| 类                    | 单例方式       | 持有全局状态                         |
|----------------------|--------------|-----------------------------------|
| JsonUtils            | 饿汉式        | ObjectMapper 实例                  |
| ResourceConfigContext| 静态枚举切换   | currentProfile (INTERNAL/EXTERNAL) |

注：配置类均为普通 POJO，不实现单例，由调用方自行实例化。


## 特殊约束


配置持久化
- ConfigPersistence 读写 Properties 文件时强制使用 UTF-8 编码，保留 BOM。
- 配置变更后需调用 save() 方法才会持久化到磁盘。

资源路径解析
- ResourceUtils.getResourceStream()：先检查外部目录（ResourceConfigContext 决定外部根路径），
  若不存在则回退到 classpath。
- 外部目录可通过启动参数 -Dapp.resource.external.path 覆盖。
- FilePathUtil.getExternalFile() 将 classpath 路径映射为外部物理文件路径。

Native Image 兼容
- EnvironmentUtil.isNative() 判断是否运行在 Native Image 环境。
- PathConfig 中的路径常量使用 String 不可变，避免反射问题。

模块依赖
- roco-common 不依赖任何内部模块（纯净工具层）。
- 允许依赖 Java 标准库和第三方库（Jackson、Logback 等）。

## 与其他模块的交互

- roco-map：通过 ResourceUtils 访问地图瓦片和图标资源，通过 PathConfig 获取存储路径。
- roco-engine：通过各 Config 类获取运行时参数，通过 ResourceConfigContext 切换资源模式，
  通过 FilePathUtil 定位子进程 exe 路径。
- roco-ui：通过 UiConfig、RenderConfig 等控制 UI 行为，通过 PathConfig 定位子进程 exe，
  通过 ResourceExtractor 在启动时释放内嵌资源。

## 典型使用示例

// 读取配置
CaptureConfig cfg = new CaptureConfig();
cfg.loadFromProperties(props);

// 持久化配置
ConfigPersistence.getInstance().saveConfig(cfg);

// 加载资源文件（外部优先）
InputStream is = ResourceUtils.getResourceStream("icon/example.svg");

// 获取外部文件路径
File external = ResourceUtils.getExternalFile("maps/map.png");

// 路径解析（相对于应用根目录）
File file = FilePathUtil.getRelativeFile("data", "config.json");
String exePath = FilePathUtil.getExternalPath("/dll/sift/sift_match.exe", true);

// 哈希校验
String sha256 = HashUtil.computeFileSHA256(new File("update.zip"));
String md5 = HashUtil.computeFileMD5(new File("file.bin"));

// 资源释放
ResourceExtractor.extractAll();

// 环境检测
boolean isNative = EnvironmentUtil.isNative();

// 获取子进程 exe 路径
String exePath = FilePathUtil.getExternalPath(PcapConfig.PCAP_EXE, true);

## 本模块工具类清单（优先使用）

| 类名 | 用途 |
|------|------|
| FilePathUtil | 应用根目录定位、相对路径解析、外部文件路径映射 |
| HashUtil | 文件哈希校验（MD5、SHA-256） |
| ResourceExtractor | JAR 内嵌资源释放到外部文件系统 |
| EnvironmentUtil | GraalVM Native Image 环境检测 |
| ResourceUtils | 外部文件优先 → classpath 回退的资源加载 |
| JsonUtils | Jackson ObjectMapper 单例 |
| ConfigHelper | Properties 类型安全读取 |
| ConfigPersistence | 配置持久化（UTF-8 BOM） |
| PathConfig | 不可变路径常量