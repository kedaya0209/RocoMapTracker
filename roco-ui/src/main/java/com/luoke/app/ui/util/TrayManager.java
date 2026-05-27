package com.luoke.app.ui.util;

import com.luoke.app.config.CaptureConfig;
import com.luoke.app.config.SiftConfig;
import com.luoke.app.ui.service.SvgManager;
import com.luoke.app.utils.FilePathUtil;
import com.luoke.app.utils.ResourceUtils;
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
    // Win32 常量
    // ============================================================

    private static final long HWND_MESSAGE = -3L;

    private static final int WM_DESTROY = 0x0002;
    private static final int WM_QUIT = 0x0012;
    private static final int WM_LBUTTONDBLCLK = 0x0203;
    private static final int WM_RBUTTONUP = 0x0205;

    private static final int NIM_ADD = 0;
    private static final int NIM_MODIFY = 1;
    private static final int NIM_DELETE = 2;
    private static final int NIM_SETVERSION = 4;
    private static final int NIF_MESSAGE = 0x0001;
    private static final int NIF_ICON = 0x0002;
    private static final int NIF_TIP = 0x0004;
    private static final int NIF_SHOWTIP = 0x0080;
    private static final int NOTIFYICON_VERSION_4 = 4;

    // NOTIFYICONDATAW x64 字段偏移
    private static final long NID_CBSIZE = 0L;
    private static final long NID_HWND = 8L;
    private static final long NID_UID = 16L;
    private static final long NID_UFLAGS = 20L;
    private static final long NID_UCALLBACKMSG = 24L;
    private static final long NID_HICON = 32L;
    private static final long NID_SZTIP = 40L;
    private static final long NID_UVERSION = 816L;
    private static final long NID_SIZE = 1024L;

    // ============================================================
    // FFM 符号绑定（首次使用时惰性初始化）
    // ============================================================

    private static volatile boolean symbolsLoaded;
    private static MethodHandle ShellNotifyIconW;
    private static MethodHandle CreateWindowExW;
    private static MethodHandle DefWindowProcW;
    private static MethodHandle RegisterClassExW;
    private static MethodHandle DestroyWindow;
    private static MethodHandle GetMessageW;
    private static MethodHandle TranslateMessage;
    private static MethodHandle DispatchMessageW;
    private static MethodHandle PostThreadMessageW;
    private static MethodHandle GetWindowThreadProcessId;
    private static MethodHandle GetCursorPos;
    private static MethodHandle CreateDIBSection;
    private static MethodHandle CreateIconIndirect;
    private static MethodHandle DestroyIcon;
    private static MethodHandle DeleteObject;
    private static MethodHandle GetDC;
    private static MethodHandle ReleaseDC;
    private static MethodHandle CreateBitmap;

    private static void ensureSymbols() {
        if (symbolsLoaded) return;
        synchronized (TrayManager.class) {
            if (symbolsLoaded) return;
            try {
                Linker linker = Linker.nativeLinker();
                SymbolLookup user32 = SymbolLookup.libraryLookup("user32", Arena.global());
                SymbolLookup shell32 = SymbolLookup.libraryLookup("shell32", Arena.global());
                SymbolLookup gdi32 = SymbolLookup.libraryLookup("gdi32", Arena.global());

                ShellNotifyIconW = linker.downcallHandle(
                        shell32.findOrThrow("Shell_NotifyIconW"),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

                CreateWindowExW = linker.downcallHandle(
                        user32.findOrThrow("CreateWindowExW"),
                        FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
                                ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));

                DefWindowProcW = linker.downcallHandle(
                        user32.findOrThrow("DefWindowProcW"),
                        FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                                ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                                ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));

                RegisterClassExW = linker.downcallHandle(
                        user32.findOrThrow("RegisterClassExW"),
                        FunctionDescriptor.of(ValueLayout.JAVA_SHORT, ValueLayout.ADDRESS));

                DestroyWindow = linker.downcallHandle(
                        user32.findOrThrow("DestroyWindow"),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));

                GetMessageW = linker.downcallHandle(
                        user32.findOrThrow("GetMessageW"),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

                TranslateMessage = linker.downcallHandle(
                        user32.findOrThrow("TranslateMessage"),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

                DispatchMessageW = linker.downcallHandle(
                        user32.findOrThrow("DispatchMessageW"),
                        FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));

                PostThreadMessageW = linker.downcallHandle(
                        user32.findOrThrow("PostThreadMessageW"),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                                ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));

                GetWindowThreadProcessId = linker.downcallHandle(
                        user32.findOrThrow("GetWindowThreadProcessId"),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));

                GetCursorPos = linker.downcallHandle(
                        user32.findOrThrow("GetCursorPos"),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

                CreateDIBSection = linker.downcallHandle(
                        gdi32.findOrThrow("CreateDIBSection"),
                        FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                                ValueLayout.JAVA_LONG, ValueLayout.ADDRESS,
                                ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                                ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));

                CreateIconIndirect = linker.downcallHandle(
                        user32.findOrThrow("CreateIconIndirect"),
                        FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));

                DestroyIcon = linker.downcallHandle(
                        user32.findOrThrow("DestroyIcon"),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));

                DeleteObject = linker.downcallHandle(
                        gdi32.findOrThrow("DeleteObject"),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));

                GetDC = linker.downcallHandle(
                        user32.findOrThrow("GetDC"),
                        FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));

                ReleaseDC = linker.downcallHandle(
                        user32.findOrThrow("ReleaseDC"),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));

                CreateBitmap = linker.downcallHandle(
                        gdi32.findOrThrow("CreateBitmap"),
                        FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

                symbolsLoaded = true;
                log.info("FFM Win32 符号加载完成");
            } catch (Throwable e) {
                log.error("FFM Win32 符号加载失败，系统托盘不可用", e);
            }
        }
    }

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

    // WNDPROC upcall stub
    private MemorySegment wndProcStub;

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

        ensureSymbols();
        if (!symbolsLoaded) {
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
                    int _nim = (int) ShellNotifyIconW.invokeExact(NIM_DELETE, nidData);
                    int _di = (int) DestroyIcon.invokeExact(currentHIcon);
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
        nidData = arena.allocate(NID_SIZE);
        nidData.fill((byte) 0);
        nidData.set(ValueLayout.JAVA_INT, NID_CBSIZE, NID_CBSIZE_VAL);
        nidData.set(ValueLayout.JAVA_LONG, NID_HWND, messageHwnd);
        nidData.set(ValueLayout.JAVA_INT, NID_UID, 0);
        nidData.set(ValueLayout.JAVA_INT, NID_UFLAGS, NIF_MESSAGE | NIF_ICON | NIF_TIP | NIF_SHOWTIP);
        nidData.set(ValueLayout.JAVA_INT, NID_UCALLBACKMSG, callbackMsg);
        nidData.set(ValueLayout.JAVA_LONG, NID_HICON, currentHIcon);

        // 设置标题提示
        String tip = CaptureConfig.APP_MAIN_TITLE;
        byte[] tipBytes = tip.getBytes(StandardCharsets.UTF_16LE);
        nidData.asSlice(NID_SZTIP, Math.min(tipBytes.length, 256))
                .copyFrom(MemorySegment.ofArray(tipBytes));

        // 添加图标
        try {
            int result = (int) ShellNotifyIconW.invokeExact(NIM_ADD, nidData);
            if (result == 0) {
                log.warn("Shell_NotifyIconW(NIM_ADD) 失败");
                trayAvailable = false;
                return;
            }

            // 设置 NOTIFYICON_VERSION_4 以获得正确的消息通知
            MemorySegment verData = arena.allocate(NID_SIZE);
            verData.fill((byte) 0);
            verData.set(ValueLayout.JAVA_INT, NID_CBSIZE, NID_CBSIZE_VAL);
            verData.set(ValueLayout.JAVA_LONG, NID_HWND, messageHwnd);
            verData.set(ValueLayout.JAVA_INT, NID_UID, 0);
            verData.set(ValueLayout.JAVA_INT, NID_UFLAGS, NIF_MESSAGE);
            verData.set(ValueLayout.JAVA_INT, NID_UCALLBACKMSG, callbackMsg);
            verData.set(ValueLayout.JAVA_INT, NID_UVERSION, NOTIFYICON_VERSION_4);
            int _nsv = (int) ShellNotifyIconW.invokeExact(NIM_SETVERSION, verData);
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
        wndProcStub = linker.upcallStub(
                wndProcHandle,
                FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG),
                arena);

        // 注册窗口类（WNDCLASSEXW）
        MemorySegment wcex = arena.allocate(WNDCLASSEX_SIZE);
        wcex.fill((byte) 0);
        wcex.set(ValueLayout.JAVA_INT, 0, WNDCLASSEX_SIZE);    // cbSize
        wcex.set(ValueLayout.JAVA_LONG, 8, wndProcStub.address()); // lpfnWndProc
        wcex.set(ValueLayout.JAVA_LONG, 64, clsNameMem.address()); // lpszClassName

        short atom = (short) RegisterClassExW.invokeExact(wcex);
        if (atom == 0) {
            windowReady.completeExceptionally(new RuntimeException("RegisterClassExW 失败"));
            return;
        }

        // 创建消息窗口（HWND_MESSAGE = 父窗口为消息-only）
        messageHwnd = (long) CreateWindowExW.invokeExact(
                0,                            // dwExStyle
                clsNameMem,                   // lpClassName
                MemorySegment.NULL,           // lpWindowName
                0,                            // dwStyle
                0, 0, 0, 0,                   // x, y, w, h
                HWND_MESSAGE,                 // hWndParent = HWND_MESSAGE
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
        MemorySegment msg = arena.allocate(MSG_SIZE);
        int result;
        while ((result = (int) GetMessageW.invokeExact(msg, 0L, 0, 0)) > 0) {
            int _tm = (int) TranslateMessage.invokeExact(msg);
            long _dm = (long) DispatchMessageW.invokeExact(msg);
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
                if (notification == WM_RBUTTONUP) {
                    Platform.runLater(() -> {
                        MemorySegment pt = arena.allocate(8);
                        try {
                            int _gcp = (int) GetCursorPos.invokeExact(pt);
                            int x = pt.get(ValueLayout.JAVA_INT, 0);
                            int y = pt.get(ValueLayout.JAVA_INT, 4);
                            showMenu(x, y);
                        } catch (Throwable e) {
                            log.error("获取光标位置失败", e);
                        }
                    });
                    return 0;
                }
                if (notification == WM_LBUTTONDBLCLK) {
                    Platform.runLater(this::showWindow);
                    return 0;
                }
            }
            if (msg == WM_DESTROY) {
                return 0;
            }
            return (long) DefWindowProcW.invokeExact(hwnd, msg, wParam, lParam);
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
        return Math.max(16, Math.min(64, (int) Math.round(16 * scale)));
    }

    /**
     * 从 SVG 渲染为 HICON。
     */
    private long renderHICON(int size) {
        try {
            // SVG → JavaFX WritableImage → ARGB 像素
            WritableImage fxImg = new WritableImage(size, size);
            Node iconNode = SvgManager.createIcon(com.luoke.app.config.PathConfig.ICON, size);
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
                try (InputStream is = ResourceUtils.getResourceStream(com.luoke.app.config.PathConfig.ICON_PNG)) {
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
            long hdc = (long) GetDC.invokeExact(0L);

            // CreateDIBSection
            MemorySegment ppvBits = arena.allocate(ValueLayout.ADDRESS);
            long hBitmap = (long) CreateDIBSection.invokeExact(
                    hdc, bmi, 0, ppvBits, 0L, 0);
            int _rdc = (int) ReleaseDC.invokeExact(0L, hdc);

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
            long hMask = (long) CreateBitmap.invokeExact(
                    width, height, 1, 1, maskData);

            if (hMask == 0) {
                log.warn("CreateBitmap(mask) 失败");
                int _do = (int) DeleteObject.invokeExact(hBitmap);
                return 0;
            }

            // 构造 ICONINFO
            MemorySegment ii = arena.allocate(ICONINFO_SIZE);
            ii.fill((byte) 0);
            ii.set(ValueLayout.JAVA_INT, 0, 1);              // fIcon = TRUE
            // xHotspot/yHotspot 对于图标忽略，保持 0
            ii.set(ValueLayout.JAVA_LONG, ICONINFO_HBMMASK, hMask);
            ii.set(ValueLayout.JAVA_LONG, ICONINFO_HBMCOLOR, hBitmap);

            long hIcon = (long) CreateIconIndirect.invokeExact(ii);
            if (hIcon == 0) {
                log.warn("CreateIconIndirect 失败");
            }

            // 清理临时 GDI 对象
            int _dm = (int) DeleteObject.invokeExact(hMask);
            int _db = (int) DeleteObject.invokeExact(hBitmap);

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
                int _nim = (int) ShellNotifyIconW.invokeExact(NIM_DELETE, nidData);
            } catch (Throwable ignored) {
            }
        }

        // 2. 销毁 HICON
        if (currentHIcon != 0) {
            try {
                int _di = (int) DestroyIcon.invokeExact(currentHIcon);
            } catch (Throwable ignored) {
            }
            currentHIcon = 0;
        }

        // 3. 停止消息泵
        pumpRunning = false;
        if (pumpThread != null && pumpThread.isAlive()) {
            try {
                int _ptm = (int) PostThreadMessageW.invokeExact((int) pumpThreadId, WM_QUIT, 0L, 0L);
                pumpThread.join(1000);
            } catch (Throwable ignored) {
            }
            pumpThread = null;
        }

        // 4. 销毁窗口
        if (messageHwnd != 0) {
            try {
                int _dw = (int) DestroyWindow.invokeExact(messageHwnd);
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

    // ============================================================
    // FFM 静态字段（无法放入静态初始化块）
    // ============================================================

    private static final Linker linker = Linker.nativeLinker();

    // NOTIFYICONDATAW x64 cbSize 值（含 Vista+ hBalloonIcon 字段）
    private static final int NID_CBSIZE_VAL = 976;

    // ICONINFO x64 字段偏移
    // typedef struct { BOOL fIcon; DWORD xHotspot; DWORD yHotspot; HBITMAP hbmMask; HBITMAP hbmColor; } ICONINFO;
    // x64: fIcon(4)+xHotspot(4)+yHotspot(4)+pad(4)+hbmMask(8)+hbmColor(8) = 32
    private static final int ICONINFO_SIZE = 32;
    private static final long ICONINFO_HBMMASK = 16L;   // offset of hbmMask
    private static final long ICONINFO_HBMCOLOR = 24L;  // offset of hbmColor

    // WNDCLASSEXW x64 大小
    // cbSize(4)+style(4)+lpfnWndProc(8)+cbClsExtra(4)+cbWndExtra(4)+pad(4)
    // +hInstance(8)+hIcon(8)+hCursor(8)+hbrBackground(8)+lpszMenuName(8)+lpszClassName(8)+hIconSm(8) = 80
    private static final int WNDCLASSEX_SIZE = 80;

    // MSG x64 大小
    // hwnd(8)+message(4)+pad(4)+wParam(8)+lParam(8)+time(4)+pt(8) = 44 → 按 8 字节对齐 = 48
    private static final int MSG_SIZE = 48;
}
