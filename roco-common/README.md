# roco-common

基础工具层 — 配置中心、资源/JSON 工具、文件操作、路径常量。

不依赖任何内部模块，无 JavaFX。

## 职责

- **配置管理** — 15 个 Config 类管理应用所有可配置参数，ConfigPersistence 支持 UTF-8 BOM 持久化
- **资源加载** — ResourceUtils 外部文件优先 → classpath 回退
- **文件路径** — FilePathUtil 应用根目录定位、路径拼接、exe 路径映射
- **哈希校验** — HashUtil MD5/SHA-256 文件校验
- **JSON 工具** — JsonUtils Jackson ObjectMapper 单例
- **资源释放** — ResourceExtractor JAR 内嵌资源解压
- **环境检测** — EnvironmentUtil GraalVM Native Image 环境判断
- **资源套件切换** — ResourceConfigContext INTERNAL/EXTERNAL 模式

## 依赖

| 依赖 | 版本 |
|---|---|
| Jackson | 2.21.3 |
| SLF4J | 2.0.18 |
| Lombok | 1.18.46 |
| JUnit | 6.0.3 |

## 内部依赖

无（最底层模块）
