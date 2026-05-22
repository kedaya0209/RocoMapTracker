# RocoMapTracker 项目

版本: 1.1.0 | Java 25 + GraalVM Native Image | OpenCV 4.13.0

## 快速命令
mvn clean compile
mvn javafx:run -pl roco-ui
mvn -Pnative clean package -pl roco-ui -am

## 模块结构
roco-ui → roco-engine → roco-macher → roco-model → roco-common
↗ roco-map ↗

详见各模块 CLAUDE.md

## 坐标系转换
L1 屏幕像素 ↔ L2 Canvas 逻辑像素：CanvasX = (ScreenX - offsetX) / scale
L2 ↔ L3 地图逻辑坐标：CanvasX = mapWidth/2 + x * 2^(imageZoom - jsonZoom)

## ROI 布局（万分数）
0 小地图: (8900,700,1000,1800)
1 物品栏: (8750,2870,1100,1700)

## 跨语言通信
C++ 子进程 (capture.exe / sift_match.exe) ↔ Java (Socket, BGRA 帧)

## Native Image 已知约束
- DLL 运行时释放到临时目录
- virtual thread + blocking I/O → RIP=0 crash
- 规避：使用 Executors.newSingleThreadExecutor()
- System.gc() 在 Serial GC 下有效