# roco-ui

用户界面层，JavaFX 桌面应用 + Native Image 构建配置。是整个项目的入口点和最终交付物。

## 职责

### 应用入口

- **Main** — `main()` 入口：初始化 JavaCPP OpenCV → launch JavaFX
- **ModernCanvasApp** — 主 Application：初始化流程 + UI 构建 + 截图守护

### UI 组件 (ui/component/)

- **InteractiveCanvas** — 交互画布：鼠标/键盘事件 + 图标渲染 + hover + 路线编辑
- **Sidebar** — 侧边栏 (进度条/统计/操作按钮)
- **FloatToolbox** — 浮动工具箱
- **TitleBar** — 标题栏 (跟随主题)
- **NotificationToast** — Toast 通知 (跟随主题)
- **LoadingOverlay** — 加载遮罩 (跟随主题)
- **ResourceCounterPanel** — 物资计数面板
- **RouteManagerStage** — 路线管理器
- **StatsOverlay** — 统计信息覆盖层
- **UiAnimator** — UI 动画

### 渲染 (ui/render/)

- **RenderLoop** — AnimationTimer 渲染循环：快照复用 + 分层绘制
- **PlayerRenderer** — 玩家图标渲染
- **PathRenderer** — 路线渲染

### 工具 (ui/util/)

- **DialogUtils** — 对话框工具 (跟随主题)
- **WindowManager** — 窗口拖拽/缩放
- **RestartUtils** — 重启工具

### 其他

- **MapRawCache** — mmap 地图缓存 (PNG→.raw→MappedByteBuffer)
- **UiResponseHook** — 监听 UI 相关 Hook 事件

## 依赖

| 依赖                                 | 版本     |
|------------------------------------|--------|
| JavaFX Controls/Graphics/Base/FXML | 25     |
| AtlantaFX Base                     | 2.1.0  |
| Logback Classic                    | 1.4.11 |

## 内部依赖

- `roco-engine` (传递依赖所有核心模块)

## 资源

- `dll/` — 运行时 DLL (awt, jvm, wgc_capture, jniframe 等)
- `META-INF/native-image/reachability-metadata.json` — GraalVM 反射/JNI 元数据
- `logback.xml` — 日志配置

## 构建配置

- **maven-assembly-plugin** — 构建 fat jar (jar-with-dependencies)
- **javafx-maven-plugin** — JavaFX 运行/打包
- **native-maven-plugin** — GraalVM Native Image 编译 (3 个 profile: native / native-instrument / native-pgo)
