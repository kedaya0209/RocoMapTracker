package io.github.kedaya0209.roco.app.platform;

import io.github.kedaya0209.roco.app.config.PathConfig;
import io.github.kedaya0209.roco.app.utils.FilePathUtil;
import lombok.extern.slf4j.Slf4j;
import net.jcip.annotations.NotThreadSafe;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static io.github.kedaya0209.roco.app.capture.CaptureProtocol.*;

/**
 * 预览捕获 — 通过独立的 capture.exe 子进程接收 WGC 帧数据。
 * <p>
 * 每个实例在随机端口创建 ServerSocket，启动 capture.exe 连接该端口，
 * 实现最小化握手协议后持续接收 BGRA 帧。与全局 SocketServer 完全隔离。
 * <p>
 * {@link #start()} 启动子进程后立即返回，accept + 握手 + 帧循环在后台线程进行。
 */
@NotThreadSafe
@Slf4j
public class PreviewCapture implements AutoCloseable {

    private static final AtomicInteger INSTANCE_COUNTER = new AtomicInteger(0);
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int IO_TIMEOUT_MS = 15000;

    // HELLO (type=1) 由 SocketServer 定义，capture.exe 连接时先发此消息
    private static final int MSG_HELLO = 1;

    private final long hwnd;
    private final int maxFps;
    private final Consumer<FrameData> frameCallback;
    private final int instanceId;

    private ServerSocket serverSocket;
    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private Thread connectorThread;
    private Thread stdoutThread;
    private Process process;
    private volatile boolean active;

    /**
     * WGC 帧数据。
     *
     * @param bgra   BGRA 像素数据（stride 可能 != width*4）
     * @param width  帧宽度（像素）
     * @param height 帧高度（像素）
     * @param stride 每行字节数（含对齐填充）
     */
    public record FrameData(byte[] bgra, int width, int height, int stride) {}

    /**
     * @param hwnd          目标窗口句柄
     * @param maxFps        最大帧率（建议 2~5）
     * @param frameCallback 帧回调（可能在非 FX 线程调用）
     */
    public PreviewCapture(long hwnd, int maxFps, Consumer<FrameData> frameCallback) {
        this.hwnd = hwnd;
        this.maxFps = maxFps;
        this.frameCallback = frameCallback;
        this.instanceId = INSTANCE_COUNTER.incrementAndGet();
    }

    /**
     * 启动 capture.exe 子进程。
     * <p>
     * 非阻塞 — 创建 ServerSocket + 启动子进程后立即返回。
     * accept 连接 + 握手 + 帧循环在后台 connector 线程执行。
     *
     * @return 子进程启动成功返回 true
     */
    public boolean start() {
        if (active) return false;

        try {
            serverSocket = new ServerSocket(0);
            int port = serverSocket.getLocalPort();

            String exePath = FilePathUtil.getExternalPath(PathConfig.CAPTURE_EXE, true);
            File exeFile = new File(exePath);
            if (!exeFile.exists()) {
                log.error("PreviewCapture[{}] capture.exe 不存在: {}", instanceId, exePath);
                return false;
            }

            ProcessBuilder pb = new ProcessBuilder(
                    exePath,
                    String.valueOf(hwnd),
                    String.valueOf(port),
                    String.valueOf(maxFps));
            pb.redirectErrorStream(true);
            process = pb.start();

            log.info("PreviewCapture[{}] 子进程已启动: hwnd=0x{} port={} maxFps={}",
                    instanceId, Long.toHexString(hwnd), port, maxFps);

            // 读取 stdout（capture.exe 日志 — 与主 capture 一致使用 INFO 级别）
            stdoutThread = Thread.ofPlatform()
                    .daemon(true)
                    .name("preview-stdout-" + instanceId)
                    .start(() -> {
                        try (BufferedReader r = new BufferedReader(
                                new InputStreamReader(process.getInputStream()))) {
                            String line;
                            while ((line = r.readLine()) != null) {
                                log.info("[preview-capture {}] {}", instanceId, line);
                            }
                            log.info("PreviewCapture[{}] stdout 流已关闭", instanceId);
                        } catch (IOException ignored) {
                        }
                    });

            active = true;

            // 后台线程：accept → 握手 → 帧循环
            connectorThread = Thread.ofPlatform()
                    .daemon(true)
                    .name("preview-connector-" + instanceId)
                    .start(this::connectorLoop);

            log.info("PreviewCapture[{}] 已启动, 等待连接: hwnd=0x{} port={}",
                    instanceId, Long.toHexString(hwnd), port);
            return true;

        } catch (IOException e) {
            log.warn("PreviewCapture[{}] 启动失败: {}", instanceId, e.getMessage());
            close();
            return false;
        }
    }

    /**
     * 后台 connector 线程：accept → 握手 → 帧循环。
     */
    private void connectorLoop() {
        try {
            // 等待 capture.exe 连接
            serverSocket.setSoTimeout(CONNECT_TIMEOUT_MS);
            socket = serverSocket.accept();
            log.info("PreviewCapture[{}] TCP 连接已接受, hwnd=0x{}",
                    instanceId, Long.toHexString(hwnd));
            socket.setSoTimeout(IO_TIMEOUT_MS);
            in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

            doHandshake();
            // 握手成功后进入帧循环
            if (active) {
                recvLoop();
            }

        } catch (SocketTimeoutException e) {
            log.warn("PreviewCapture[{}] 等待 capture.exe 连接超时 ({}ms)",
                    instanceId, CONNECT_TIMEOUT_MS);
        } catch (IOException e) {
            if (active) {
                log.warn("PreviewCapture[{}] 连接异常: {}", instanceId, e.getMessage());
            }
        } finally {
            close();
        }
    }

    /**
     * 握手：HELLO → MSG_REQUEST_ROI → MSG_RETURN_ROI → MSG_CAPTURE_READY → PROCESSING_DONE
     */
    private void doHandshake() throws IOException {
        // 1. HELLO (capture.exe 连接后先发注册消息)
        Message hello = readMessage();
        if (hello == null || hello.type != MSG_HELLO) {
            log.warn("PreviewCapture[{}] 握手失败: 预期 HELLO 但收到 type={}",
                    instanceId, hello != null ? hello.type : -1);
            close();
            return;
        }

        // 2. MSG_REQUEST_ROI (capture.exe 请求 ROI 配置)
        Message requestRoi = readMessage();
        if (requestRoi == null || requestRoi.type != MSG_REQUEST_ROI) {
            log.warn("PreviewCapture[{}] 握手失败: 预期 MSG_REQUEST_ROI 但收到 type={}",
                    instanceId, requestRoi != null ? requestRoi.type : -1);
            close();
            return;
        }

        // 发送全窗口 ROI (0, 0, 10000, 10000) 万分数
        ByteBuffer roiBuf = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN);
        roiBuf.putShort((short) 1);          // count = 1
        roiBuf.putShort((short) 0);           // x
        roiBuf.putShort((short) 0);           // y
        roiBuf.putShort((short) 10000);       // w
        roiBuf.putShort((short) 10000);       // h
        sendMessage(MSG_RETURN_ROI, roiBuf.array());

        // 3. MSG_CAPTURE_READY (capture.exe ROI 配置完毕)
        Message captureReady = readMessage();
        if (captureReady == null || captureReady.type != MSG_CAPTURE_READY) {
            log.warn("PreviewCapture[{}] 握手失败: 预期 MSG_CAPTURE_READY 但收到 type={}",
                    instanceId, captureReady != null ? captureReady.type : -1);
            close();
            return;
        }

        // 发送 PROCESSING_DONE 通知 capture.exe 开始发送帧
        sendMessage(MSG_PROCESSING_DONE, null);

        log.info("PreviewCapture[{}] 握手完成, 进入帧接收循环", instanceId);
    }

    /**
     * 帧循环：读取 MSG_FRAME_DATA → 回调 → 发送 PROCESSING_DONE
     */
    private void recvLoop() throws IOException {
        while (active) {
            Message msg = readMessage();
            if (msg == null) break;

            switch (msg.type) {
                case MSG_FRAME_DATA -> {
                    if (msg.body != null) {
                        handleFrame(msg.body);
                    }
                    sendMessage(MSG_PROCESSING_DONE, null);
                }
                case MSG_WINDOW_CLOSED -> {
                    log.info("PreviewCapture[{}] 窗口已关闭, hwnd=0x{}",
                            instanceId, Long.toHexString(hwnd));
                    return;
                }
                case MSG_WINDOW_STATE -> {
                    // 窗口最小化/恢复通知，忽略
                }
                default ->
                    log.debug("PreviewCapture[{}] 未知消息 type={}", instanceId, msg.type);
            }
        }
    }

    /**
     * 解析帧数据，提取第一个 ROI slot（全窗口捕获），回调通知。
     */
    private void handleFrame(byte[] body) {
        if (body == null || body.length < 2) return;

        ByteBuffer buf = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN);
        int roiCount = buf.getShort() & 0xFFFF;
        if (roiCount == 0) return;

        // 读取第一个 ROI slot
        if (buf.remaining() < 11) {
            log.debug("PreviewCapture[{}] 帧数据过短: {} bytes", instanceId, body.length);
            return;
        }
        buf.get(); // skip index
        int w = buf.getShort() & 0xFFFF;
        int h = buf.getShort() & 0xFFFF;
        int stride = buf.getShort() & 0xFFFF;
        int dataLen = buf.getInt();

        if (dataLen <= 0 || buf.remaining() < dataLen) {
            log.debug("PreviewCapture[{}] 帧数据不完整: dataLen={} remaining={}",
                    instanceId, dataLen, buf.remaining());
            return;
        }
        if (w <= 0 || h <= 0 || stride <= 0) return;

        byte[] pixels = new byte[dataLen];
        buf.get(pixels);

        frameCallback.accept(new FrameData(pixels, w, h, stride));
    }

    @Override
    public void close() {
        if (!active && process == null) return;
        active = false;

        // 发送停止请求
        if (out != null) {
            try {
                sendMessage(MSG_STOP_REQUEST, null);
            } catch (IOException ignored) {}
        }

        // 关闭 socket → connector 线程的 readMessage 会抛出异常退出
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) {}
            socket = null;
        }
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (IOException ignored) {}
            serverSocket = null;
        }

        // 销毁子进程
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
            process = null;
        }

        // join stdout 线程（短等待）
        if (stdoutThread != null) {
            try { stdoutThread.join(500); } catch (InterruptedException ignored) {}
            stdoutThread = null;
        }

        in = null;
        out = null;
        connectorThread = null;

        log.info("PreviewCapture[{}] 已关闭, hwnd=0x{}", instanceId, Long.toHexString(hwnd));
    }

    public boolean isActive() {
        return active;
    }

    public long getHwnd() {
        return hwnd;
    }

    // ==================== Socket 协议 ====================

    private record Message(int type, byte[] body) {}

    private Message readMessage() throws IOException {
        int type = in.readInt();
        int len = in.readInt();
        byte[] body = null;
        if (len > 0) {
            body = new byte[len];
            in.readFully(body);
        }
        return new Message(type, body);
    }

    private void sendMessage(int type, byte[] body) throws IOException {
        out.writeInt(type);
        out.writeInt(body != null ? body.length : 0);
        if (body != null && body.length > 0) {
            out.write(body);
        }
        out.flush();
    }
}
