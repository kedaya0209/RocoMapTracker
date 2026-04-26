package com.luoke.app;

import com.luoke.app.ui.ModernCanvasApp;
import javafx.application.Application;

/**
 * 应用程序入口类
 * <p>
 * 作为应用程序的启动入口点，将命令行参数转发给JavaFX应用程序主类MainApp。
 * 这是为了支持从命令行直接启动程序而设计的一个薄包装层。
 * </p>
 * <p>
 * Native打包优化说明：
 * 在使用GraalVM进行Native Image编译时，此类作为JavaFX应用程序的启动点，
 * 需要在reflect-config.json中配置反射访问以支持JavaFX的反射调用机制。
 * </p>
 *
 * @author 可达鸭
 * @version 1.0
 */
public class Main {
    /**
     * 应用程序主入口方法
     * <p>
     * 该方法接收命令行参数并传递给JavaFX应用程序的主启动方法。
     * JavaFX框架会自动处理应用程序的初始化和启动过程。
     * </p>
     * <p>
     * 性能考虑：
     * 此方法仅做参数转发，没有任何额外开销，确保启动时的性能。
     * </p>
     *
     * @param args 命令行参数数组，可以包含配置文件路径或其他运行时参数
     */
    public static void main(String[] args) {
        Application.launch(ModernCanvasApp.class, args);
    }
}
