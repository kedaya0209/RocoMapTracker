## 关于我
- **项目角色**：RocoMapTracker 维护者
- **技术背景**：熟悉 Java、C++、OpenCV、GraalVM
- **当前痛点**：Claude 总是忘记 Native Image 虚拟线程 Bug、忘记检查编码规范

## 项目上下文
- **语言**：Java 25 + C++ 17 + Python + GraalVM Native Image
- **构建**：Maven 多模块
- **关键依赖**：JavaFX 25、Jackson、Jsoup、AtlantaFX
- **视觉处理**：全部在 C++ 子进程中完成（OpenCV SIFT/FLANN/RANSAC + HSV），Java 侧零 OpenCV 依赖
- **特殊约束**：
    - C++ 子进程通过 Socket 通信（万分数坐标）
    - Native Image 下虚拟线程阻塞 I/O 会崩溃（必须用平台线程）

## 我的偏好
- **回答格式**：列表优于段落，需要时给出对比表格
- **代码要求**：
    - 必须包含 import，禁止全限定类名
    - 必须加 @ThreadSafe 或 @NotThreadSafe
    - 必须用 @Slf4j，禁止 System.out
    - 必须用 lombok, 禁止手写setter/getter
- **问题排查**：提供根因分析 + 解决方案，而非仅表面解释

## 我的雷区
- 长篇废话开场白
- 忽略现有工具类（必须先查 roco-common）
- 生成违反 Lombok 约定的代码（val/var/@SneakyThrows 等）