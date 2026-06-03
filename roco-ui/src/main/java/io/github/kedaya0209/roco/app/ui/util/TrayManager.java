package io.github.kedaya0209.roco.app.ui.util;

import io.github.kedaya0209.roco.app.config.CaptureConfig;
import io.github.kedaya0209.roco.app.config.SiftConfig;
import io.github.kedaya0209.roco.app.ui.service.resource.SvgManager;
import io.github.kedaya0209.roco.app.utils.FilePathUtil;
import io.github.kedaya0209.roco.app.utils.ResourceUtils;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Label;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import lombok.extern.slf4j.Slf4j;
import net.jcip.annotations.NotThreadSafe;

import java.io.File;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;

/**
 * 系统托盘管理器 — FFM 直接调用 Win32 Shell_NotifyIconW 实现。
 * <p>
 * 使用 Java 22+ FFM（Foreign Function & Memory API）直接操作原生系统托盘，
 * 替代 AWT SystemTray/TrayIcon，避免拉入 java.desktop 模块。
 * 支持 HiDPI 精确尺寸图标，解决 AWT 下图标模糊的问题。
 * </p>
 */
@NotThreadSafe
@Slf4j
public class TrayManager {


    // ============================================================
    // 实例字段
    // ============================================================

    private final Stage primaryStage;
    private boolean initialized;
    private volatile boolean trayAvailable = true;
    private Stage menuStage;
    private Stage ownerStage;

    // FFM 状态
    private Arena arena;
    private long messageHwnd;          // 消息窗口句柄
    private MemorySegment nidData;     // NOTIFYICONDATAW
    private Thread pumpThread;
    private volatile boolean pumpRunning;
    private int callbackMsg;           // uCallbackMessage 编号
    private int taskbarCreatedMsg;     // "TaskbarCreated" 注册消息
    private long currentHIcon;
    private long pumpThreadId;

    // ============================================================
    // 构造
    // ============================================================

    public TrayManager(Stage primaryStage) {
        this.primaryStage = primaryStage;
        Platform.setImplicitExit(false);

        primaryStage.setOnCloseRequest(e -> {
            if (trayAvailable) {
                e.consume();
                minimizeToTray();
            } else {
                Platform.exit();
            }
        });

        ownerStage = new Stage();
        ownerStage.initStyle(StageStyle.UTILITY);
        ownerStage.setWidth(1);
        ownerStage.setHeight(1);
        ownerStage.setOpacity(0);
        ownerStage.setX(-10000);
        ownerStage.setY(-10000);
        ownerStage.setScene(new Scene(new Pane()));
        ownerStage.show();
    }

    // ============================================================
    // 初始化
    // ============================================================

    public void init() {
        if (initialized) return;
        initialized = true;
        if (!trayAvailable) return;

        Win32TraySymbols.ensureSymbols();
        if (!Win32TraySymbols.symbolsLoaded) {
            trayAvailable = false;
            return;
        }

        try {
            arena = Arena.ofShared();

            // 1. 在泵线程中创建消息窗口
            startMessagePump();

            // 2. 创建托盘图标
            recreateIcon();

            log.info("系统托盘已创建（FFM Win32 Shell_NotifyIconW）");
        } catch (Throwable e) {
            log.error("创建系统托盘失败: {}", e.getMessage());
            trayAvailable = false;
        }
    }

    private void recreateIcon() {
        if (nidData != null && messageHwnd != 0) {
            // 先删除旧图标
            if (currentHIcon != 0) {
                try {
                    int _nim = (int) Win32TraySymbols.ShellNotifyIconW.invokeExact(Win32TraySymbols.NIM_DELETE, nidData);
                    int _di = (int) Win32TraySymbols.DestroyIcon.invokeExact(currentHIcon);
                } catch (Throwable ignored) {
                }
                currentHIcon = 0;
            }
        }

        // 获取图标物理像素尺寸（考虑 DPI）
        int iconSize = getTrayIconSize();

        // 渲染 HICON
        currentHIcon = renderHICON(iconSize);
        if (currentHIcon == 0) {
            log.warn("托盘图标渲染失败");
            trayAvailable = false;
            return;
        }

        // 注册 "TaskbarCreated" 消息（资源管理器重启通知）
        if (taskbarCreatedMsg == 0) {
            taskbarCreatedMsg = registerWindowMessage("TaskbarCreated");
        }

        // 构造 NOTIFYICONDATAW
        nidData = arena.allocate(Win32TraySymbols.NID_SIZE);
        nidData.fill((byte) 0);
        nidData.set(ValueLayout.JAVA_INT, Win32TraySymbols.NID_CBSIZE, Win32TraySymbols.NID_CBSIZE_VAL);
        nidData.set(ValueLayout.JAVA_LONG, Win32TraySymbols.NID_HWND, messageHwnd);
        nidData.set(ValueLayout.JAVA_INT, Win32TraySymbols.NID_UID, 0);
        nidData.set(ValueLayout.JAVA_INT, Win32TraySymbols.NID_UFLAGS, Win32TraySymbols.NIF_MESSAGE | Win32TraySymbols.NIF_ICON | Win32TraySymbols.NIF_TIP | Win32TraySymbols.NIF_SHOWTIP);
        nidData.set(ValueLayout.JAVA_INT, Win32TraySymbols.NID_UCALLBACKMSG, callbackMsg);
        nidData.set(ValueLayout.JAVA_LONG, Win32TraySymbols.NID_HICON, currentHIcon);

        // 设置标题提示
        String tip = CaptureConfig.APP_MAIN_TITLE;
        byte[] tipBytes = tip.getBytes(StandardCharsets.UTF_16LE);
        nidData.asSlice(Win32TraySymbols.NID_SZTIP, Math.min(tipBytes.length, 256))
                .copyFrom(MemorySegment.ofArray(tipBytes));

        // 添加图标
        try {
            int result = (int) Win32TraySymbols.ShellNotifyIconW.invokeExact(Win32TraySymbols.NIM_ADD, nidData);
            if (result == 0) {
                log.warn("Shell_NotifyIconW(Win32TraySymbols.NIM_ADD) 失败");
                trayAvailable = false;
                return;
            }

            // 设置 Win32TraySymbols.NOTIFYICON_VERSION_4 以获得正确的消息通知
            MemorySegment verData = arena.allocate(Win32TraySymbols.NID_SIZE);
            verData.fill((byte) 0);
            verData.set(ValueLayout.JAVA_INT, Win32TraySymbols.NID_CBSIZE, Win32TraySymbols.NID_CBSIZE_VAL);
            verData.set(ValueLayout.JAVA_LONG, Win32TraySymbols.NID_HWND, messageHwnd);
            verData.set(ValueLayout.JAVA_INT, Win32TraySymbols.NID_UID, 0);
            verData.set(ValueLayout.JAVA_INT, Win32TraySymbols.NID_UFLAGS, Win32TraySymbols.NIF_MESSAGE);
            verData.set(ValueLayout.JAVA_INT, Win32TraySymbols.NID_UCALLBACKMSG, callbackMsg);
            verData.set(ValueLayout.JAVA_INT, Win32TraySymbols.NID_UVERSION, Win32TraySymbols.NOTIFYICON_VERSION_4);
            int _nsv = (int) Win32TraySymbols.ShellNotifyIconW.invokeExact(Win32TraySymbols.NIM_SETVERSION, verData);
        } catch (Throwable e) {
            log.error("创建托盘图标异常", e);
            trayAvailable = false;
        }
    }

    // ============================================================
    // 消息泵
    // ============================================================

    private void startMessagePump() throws Exception {
        pumpRunning = true;
        CompletableFuture<Void> windowReady = new CompletableFuture<>();

        pumpThread = Thread.ofPlatform().daemon(true).name("tray-msg-pump").start(() -> {
            try {
                runMessagePump(windowReady);
            } catch (Throwable e) {
                log.error("托盘消息泵异常", e);
            }
        });

        // 等待窗口创建完成
        windowReady.get();
        pumpThreadId = pumpThread.threadId();
    }

    private void runMessagePump(CompletableFuture<Void> windowReady) throws Throwable {
        // 创建窗口类名（UTF-16）
        String className = "RocoMapTrayWnd_" + System.nanoTime();
        byte[] clsBytes = className.getBytes(StandardCharsets.UTF_16LE);
        MemorySegment clsNameMem = arena.allocate(clsBytes.length + 2);
        clsNameMem.copyFrom(MemorySegment.ofArray(clsBytes));

        // 创建 window procedure upcall stub
        MethodHandle wndProcHandle = MethodHandles.lookup()
                .findVirtual(TrayManager.class, "onWndProc",
                        MethodType.methodType(long.class, long.class, int.class, long.class, long.class))
                .bindTo(this);
        MemorySegment wndProcStub = Win32TraySymbols.linker.upcallStub(
                wndProcHandle,
                FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG),
                arena);

        // 注册窗口类（WNDCLASSEXW）
        MemorySegment wcex = arena.allocate(Win32TraySymbols.WNDCLASSEX_SIZE);
        wcex.fill((byte) 0);
        wcex.set(ValueLayout.JAVA_INT, 0, Win32TraySymbols.WNDCLASSEX_SIZE);    // cbSize
        wcex.set(ValueLayout.JAVA_LONG, 8, wndProcStub.address()); // lpfnWndProc
        wcex.set(ValueLayout.JAVA_LONG, 64, clsNameMem.address()); // lpszClassName

        short atom = (short) Win32TraySymbols.RegisterClassExW.invokeExact(wcex);
        if (atom == 0) {
            windowReady.completeExceptionally(new RuntimeException("RegisterClassExW 失败"));
            return;
        }

        // 创建消息窗口（Win32TraySymbols.HWND_MESSAGE = 父窗口为消息-only）
        messageHwnd = (long) Win32TraySymbols.CreateWindowExW.invokeExact(
                0,                            // dwExStyle
                clsNameMem,                   // lpClassName
                MemorySegment.NULL,           // lpWindowName
                0,                            // dwStyle
                0, 0, 0, 0,                   // x, y, w, h
                Win32TraySymbols.HWND_MESSAGE,                 // hWndParent = Win32TraySymbols.HWND_MESSAGE
                0L,                           // hMenu
                0L,                           // hInstance
                MemorySegment.NULL);          // lpParam

        if (messageHwnd == 0) {
            windowReady.completeExceptionally(new RuntimeException("CreateWindowExW 失败"));
            return;
        }

        // 注册自定义消息编号
        callbackMsg = registerWindowMessage("RocoMapTrayCallback_" + System.nanoTime());

        windowReady.complete(null);

        // 消息泵循环
        MemorySegment msg = arena.allocate(Win32TraySymbols.MSG_SIZE);
        int result;
        while ((result = (int) Win32TraySymbols.GetMessageW.invokeExact(msg, 0L, 0, 0)) > 0) {
            int _tm = (int) Win32TraySymbols.TranslateMessage.invokeExact(msg);
            long _dm = (long) Win32TraySymbols.DispatchMessageW.invokeExact(msg);
        }
        pumpRunning = false;
    }

    /**
     * 窗口过程 — 在消息泵线程上由 DispatchMessageW 回调。
     */
    @SuppressWarnings("unused")
    private long onWndProc(long hwnd, int msg, long wParam, long lParam) {
        try {
            // TaskbarCreated：资源管理器重启，重建图标
            if (taskbarCreatedMsg != 0 && msg == taskbarCreatedMsg) {
                Platform.runLater(this::recreateIcon);
                return 0;
            }
            // 托盘图标通知
            if (msg == callbackMsg) {
                int notification = (int) lParam;
                if (notification == Win32TraySymbols.WM_RBUTTONUP) {
                    Platform.runLater(() -> {
                        MemorySegment pt = arena.allocate(8);
                        try {
                            int _gcp = (int) Win32TraySymbols.GetCursorPos.invokeExact(pt);
                            int x = pt.get(ValueLayout.JAVA_INT, 0);
                            int y = pt.get(ValueLayout.JAVA_INT, 4);
                            showMenu(x, y);
                        } catch (Throwable e) {
                            log.error("获取光标位置失败", e);
                        }
                    });
                    return 0;
                }
                if (notification == Win32TraySymbols.WM_LBUTTONDBLCLK) {
                    Platform.runLater(this::showWindow);
                    return 0;
                }
            }
            if (msg == Win32TraySymbols.WM_DESTROY) {
                return 0;
            }
            return (long) Win32TraySymbols.DefWindowProcW.invokeExact(hwnd, msg, wParam, lParam);
        } catch (Throwable e) {
            log.error("窗口过程异常", e);
            return 0;
        }
    }

    /**
     * 注册 Windows 消息（用于 TaskbarCreated 等）。
     */
    private int registerWindowMessage(String name) {
        try {
            // RegisterWindowMessageW(LPCWSTR lpString) → UINT
            MethodHandle regMsg = Linker.nativeLinker().downcallHandle(
                    SymbolLookup.libraryLookup("user32", Arena.global())
                            .findOrThrow("RegisterWindowMessageW"),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

            byte[] nameBytes = name.getBytes(StandardCharsets.UTF_16LE);
            MemorySegment nameMem = arena.allocate(nameBytes.length + 2);
            nameMem.copyFrom(MemorySegment.ofArray(nameBytes));

            return (int) regMsg.invokeExact(nameMem);
        } catch (Throwable e) {
            log.warn("RegisterWindowMessageW 失败", e);
            return 0;
        }
    }

    // ============================================================
    // HICON 渲染
    // ============================================================

    /**
     * 获取系统托盘图标物理像素尺寸（根据 DPI）。
     */
    private int getTrayIconSize() {
        double scale = Screen.getPrimary().getOutputScaleX();
        return Math.clamp((int) Math.round(16 * scale), 16, 64);
    }

    /**
     * 从 SVG 渲染为 HICON。
     */
    private long renderHICON(int size) {
        try {
            // SVG → JavaFX WritableImage → ARGB 像素
            WritableImage fxImg = new WritableImage(size, size);
            Node iconNode = SvgManager.createIcon(io.github.kedaya0209.roco.app.config.PathConfig.ICON, size);
            SnapshotParameters sp = new SnapshotParameters();
            sp.setFill(Color.TRANSPARENT);
            iconNode.snapshot(sp, fxImg);

            PixelReader reader = fxImg.getPixelReader();
            int[] argb = new int[size * size];
            reader.getPixels(0, 0, size, size, PixelFormat.getIntArgbInstance(), argb, 0, size);

            long hIcon = createHICON(size, size, argb);
            if (hIcon != 0) return hIcon;

            // 降级：PNG 回退
            return loadFallbackIcon(size);
        } catch (Exception e) {
            log.warn("SVG 渲染 HICON 失败", e);
            try {
                return loadFallbackIcon(size);
            } catch (Exception ex) {
                log.warn("降级图标加载也失败", ex);
                return 0;
            }
        }
    }

    private long loadFallbackIcon(int size) throws Exception {
        File iconFile = FilePathUtil.getExternalFile("icon", "/rmt.png");
        if (!iconFile.exists()) {
            File appDir = FilePathUtil.getAppRootDir().toFile();
            iconFile = new File(appDir, "rmt.png");
            if (!iconFile.exists()) {
                try (InputStream is = ResourceUtils.getResourceStream(io.github.kedaya0209.roco.app.config.PathConfig.ICON_PNG)) {
                    Files.copy(is, iconFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        if (iconFile.exists()) {
            javafx.scene.image.Image img = new javafx.scene.image.Image(iconFile.toURI().toString());
            if (!img.isError() && img.getWidth() > 0) {
                WritableImage wi = new WritableImage(img.getPixelReader(), (int) img.getWidth(), (int) img.getHeight());
                int[] argb = new int[size * size];
                wi.getPixelReader().getPixels(0, 0, size, size, PixelFormat.getIntArgbInstance(), argb, 0, size);
                return createHICON(size, size, argb);
            }
        }
        return 0;
    }

    /**
     * 从 ARGB 像素数据创建 HICON。
     * 使用 CreateDIBSection + CreateIconIndirect 绕过 AWT。
     */
    private long createHICON(int width, int height, int[] argb) {
        try {
            // BITMAPINFOHEADER + no palette = 40 bytes
            MemorySegment bmi = arena.allocate(40);
            bmi.fill((byte) 0);
            bmi.set(ValueLayout.JAVA_INT, 0, 40);         // biSize
            bmi.set(ValueLayout.JAVA_INT, 4, width);        // biWidth
            bmi.set(ValueLayout.JAVA_INT, 8, -height);       // biHeight (负数 = top-down)
            bmi.set(ValueLayout.JAVA_SHORT, 12, (short) 1); // biPlanes
            bmi.set(ValueLayout.JAVA_SHORT, 14, (short) 32);// biBitCount

            // 获取屏幕 DC
            long hdc = (long) Win32TraySymbols.GetDC.invokeExact(0L);

            // CreateDIBSection
            MemorySegment ppvBits = arena.allocate(ValueLayout.ADDRESS);
            long hBitmap = (long) Win32TraySymbols.CreateDIBSection.invokeExact(
                    hdc, bmi, 0, ppvBits, 0L, 0);
            int _rdc = (int) Win32TraySymbols.ReleaseDC.invokeExact(0L, hdc);

            if (hBitmap == 0) {
                log.warn("CreateDIBSection 失败");
                return 0;
            }

            // 复制像素（ARGB → BGRA）
            MemorySegment pixels = ppvBits.get(ValueLayout.ADDRESS, 0)
                    .reinterpret((long) width * height * 4L);
            for (int i = 0; i < width * height; i++) {
                int c = argb[i];
                int a = (c >> 24) & 0xFF;
                int r = (c >> 16) & 0xFF;
                int g = (c >> 8) & 0xFF;
                int b = c & 0xFF;
                // 32-bit DIBSection = BGRA
                pixels.set(ValueLayout.JAVA_INT, i * 4L, (b) | (g << 8) | (r << 16) | (a << 24));
            }

            // 创建单色掩码位图（全 0 = 使用 alpha 通道）
            long maskStride = ((width + 31L) / 32L) * 4L;
            MemorySegment maskData = arena.allocate(maskStride * height);
            maskData.fill((byte) 0);
            long hMask = (long) Win32TraySymbols.CreateBitmap.invokeExact(
                    width, height, 1, 1, maskData);

            if (hMask == 0) {
                log.warn("CreateBitmap(mask) 失败");
                int _do = (int) Win32TraySymbols.DeleteObject.invokeExact(hBitmap);
                return 0;
            }

            // 构造 ICONINFO
            MemorySegment ii = arena.allocate(Win32TraySymbols.ICONINFO_SIZE);
            ii.fill((byte) 0);
            ii.set(ValueLayout.JAVA_INT, 0, 1);              // fIcon = TRUE
            // xHotspot/yHotspot 对于图标忽略，保持 0
            ii.set(ValueLayout.JAVA_LONG, Win32TraySymbols.ICONINFO_HBMMASK, hMask);
            ii.set(ValueLayout.JAVA_LONG, Win32TraySymbols.ICONINFO_HBMCOLOR, hBitmap);

            long hIcon = (long) Win32TraySymbols.CreateIconIndirect.invokeExact(ii);
            if (hIcon == 0) {
                log.warn("CreateIconIndirect 失败");
            }

            // 清理临时 GDI 对象
            int _dm = (int) Win32TraySymbols.DeleteObject.invokeExact(hMask);
            int _db = (int) Win32TraySymbols.DeleteObject.invokeExact(hBitmap);

            return hIcon;
        } catch (Throwable e) {
            log.error("创建 HICON 失败", e);
            return 0;
        }
    }

    // ============================================================
    // 公共 API
    // ============================================================

    public void minimizeToTray() {
        if (!initialized) init();
        if (!trayAvailable) {
            Platform.runLater(() -> primaryStage.setIconified(true));
            return;
        }
        Platform.runLater(() -> {
            if (primaryStage.isShowing()) {
                primaryStage.hide();
                SiftConfig.SIFT_MATCHING_ENABLED = false;
                log.info("窗口最小化至托盘，已暂停匹配");
            }
        });
    }

    public void dispose() {
        if (!trayAvailable) return;

        // 1. 删除托盘图标
        if (nidData != null && messageHwnd != 0) {
            try {
                int _nim = (int) Win32TraySymbols.ShellNotifyIconW.invokeExact(Win32TraySymbols.NIM_DELETE, nidData);
            } catch (Throwable ignored) {
            }
        }

        // 2. 销毁 HICON
        if (currentHIcon != 0) {
            try {
                int _di = (int) Win32TraySymbols.DestroyIcon.invokeExact(currentHIcon);
            } catch (Throwable ignored) {
            }
            currentHIcon = 0;
        }

        // 3. 停止消息泵（线程为 daemon，JVM 退出时自动终止，无需 join 等待）
        pumpRunning = false;
        if (pumpThread != null && pumpThread.isAlive()) {
            try {
                int _ptm = (int) Win32TraySymbols.PostThreadMessageW.invokeExact((int) pumpThreadId, Win32TraySymbols.WM_QUIT, 0L, 0L);
            } catch (Throwable ignored) {
            }
            pumpThread = null;
        }

        // 4. 销毁窗口
        if (messageHwnd != 0) {
            try {
                int _dw = (int) Win32TraySymbols.DestroyWindow.invokeExact(messageHwnd);
            } catch (Throwable ignored) {
            }
            messageHwnd = 0;
        }

        // 5. 释放 Arena
        if (arena != null) {
            try {
                arena.close();
            } catch (Throwable ignored) {
            }
            arena = null;
        }

        trayAvailable = false;

        // 6. 隐藏菜单
        if (menuStage != null) {
            menuStage.hide();
            menuStage = null;
        }
        if (ownerStage != null) {
            ownerStage.hide();
            ownerStage = null;
        }

        log.info("系统托盘已销毁");
    }

    // ============================================================
    // JavaFX 菜单
    // ============================================================

    private void showWindow() {
        Platform.runLater(() -> {
            if (!primaryStage.isShowing()) {
                primaryStage.show();
                SiftConfig.SIFT_MATCHING_ENABLED = true;
                log.info("窗口恢复显示，已恢复匹配");
            }
            primaryStage.toFront();
            primaryStage.requestFocus();
        });
    }

    private void showMenu(int screenX, int screenY) {
        try {
            double scaleX = Screen.getPrimary().getOutputScaleX();
            double scaleY = Screen.getPrimary().getOutputScaleY();
            Rectangle2D vb = Screen.getPrimary().getVisualBounds();
            int mw = 170, mh = 80;

            double sx = screenX / scaleX;
            double sy = screenY / scaleY;
            double x = Math.min(sx, vb.getMaxX() - mw);
            double y = sy - mh;

            if (menuStage == null) {
                menuStage = new Stage();
                menuStage.initStyle(StageStyle.TRANSPARENT);
                menuStage.initOwner(ownerStage);
                menuStage.setWidth(mw);
                menuStage.setHeight(mh);
                menuStage.setAlwaysOnTop(true);
                menuStage.focusedProperty().addListener((_, _, newVal) -> {
                    if (!newVal) menuStage.hide();
                });

                VBox root = new VBox(2);
                root.setStyle("-fx-background-color: -color-bg-default;"
                        + " -fx-background-radius: 8; -fx-padding: 6;"
                        + " -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.35), 10, 0, 0, 3);");
                Scene scene = new Scene(root);
                scene.setFill(null);
                menuStage.setScene(scene);
            }

            VBox root = (VBox) menuStage.getScene().getRoot();
            root.getChildren().setAll(
                    createItem("显示", () -> {
                        showWindow();
                        menuStage.hide();
                    }),
                    createItem("退出", () -> {
                        menuStage.hide();
                        Platform.runLater(Platform::exit);
                    })
            );

            menuStage.setX(x);
            menuStage.setY(y);
            menuStage.show();
            menuStage.toFront();
        } catch (Exception e) {
            log.error("显示 JavaFX 菜单失败", e);
        }
    }

    private Label createItem(String text, Runnable action) {
        Label label = new Label(text);
        label.setPrefSize(150, 32);
        String normal = "-fx-padding: 4 12; -fx-font-size: 13;"
                + " -fx-background-radius: 6; -fx-text-fill: -color-fg-default;"
                + " -fx-background-color: transparent;";
        String hover = "-fx-padding: 4 12; -fx-font-size: 13;"
                + " -fx-background-radius: 6; -fx-text-fill: -color-fg-default;"
                + " -fx-background-color: -color-accent-subtle;";
        label.setStyle(normal);
        label.setOnMouseEntered(_ -> label.setStyle(hover));
        label.setOnMouseExited(_ -> label.setStyle(normal));
        label.setOnMouseClicked(_ -> action.run());
        return label;
    }

}
