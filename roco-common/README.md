# roco-common

基础工具层，为所有模块提供公共基础设施。不依赖任何内部模块。

## 职责

- **配置管理** — `AppConfig` 静态配置中心，`ResourceConfigContext` 资源套件切换
- **资源工具** — `ResourceUtils` 内嵌/外部/下载资源路径管理，`FileUtil` 资源释放
- **JSON 序列化** — `JsonUtils` Jackson ObjectMapper 单例
- **JNI 帧管理** — `JNIFrameNative` push/pop 本地引用帧 (调用 jniframe.dll)
- **图像处理** — `BrightnessExtractor` 亮度提取

## 依赖

| 依赖               | 版本            |
|------------------|---------------|
| Jackson          | 2.15.2        |
| Zstd JNI         | 1.5.6-2       |
| SLF4J            | 2.0.9         |
| JavaCPP          | 1.5.13        |
| OpenCV (JavaCPP) | 4.13.0-1.5.13 |

## 内部依赖

无（最底层模块）
