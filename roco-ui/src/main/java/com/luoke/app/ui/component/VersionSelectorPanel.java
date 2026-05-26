package com.luoke.app.ui.component;

import net.jcip.annotations.NotThreadSafe;
import atlantafx.base.theme.Styles;
import com.luoke.app.ui.service.VersionManager;
import com.luoke.app.ui.util.DialogUtils;
import com.luoke.app.ui.util.FxRippleUtil;
import javafx.application.Platform;
import com.luoke.app.utils.FilePathUtil;
import com.luoke.app.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import javafx.animation.FadeTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;
import javafx.util.Duration;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 版本选择覆盖层 — 全屏半透明遮罩，左右两栏展示标准版/高级版功能对比。
 * <p>
 * 点击对应卡片上的按钮触发版本切换并自动关闭面板。
 */
@NotThreadSafe
@Slf4j
public class VersionSelectorPanel extends StackPane {

    private static final double FADE_MS = 200;
    private static final double CARD_W = 300;
    private static final double GAP = 40;

    private final StackPane rootStack;
    private final VersionManager vm = VersionManager.getInstance();
    private final VBox stdCard;
    private final VBox advCard;
    private final Button stdBtn;
    private final Button advBtn;

    public VersionSelectorPanel(StackPane rootStack) {
        this.rootStack = rootStack;
        setPickOnBounds(false);
        setStyle("-fx-background-color: rgba(0,0,0,0.55);");

        Label title = new Label("选择版本模式");
        title.getStyleClass().addAll(Styles.TITLE_2, Styles.TEXT_BOLD);
        title.setStyle("-fx-text-fill: white;");
        title.setMaxWidth(Double.MAX_VALUE);
        title.setTextAlignment(TextAlignment.CENTER);
        title.setAlignment(Pos.CENTER);

        stdCard = buildStdCard();
        advCard = buildAdvCard();
        stdBtn = findBtn(stdCard);
        advBtn = findBtn(advCard);

        stdBtn.setOnAction(_ -> select(VersionMode.STANDARD));
        advBtn.setOnAction(_ -> select(VersionMode.ADVANCED));

        HBox row = new HBox(GAP, stdCard, advCard);
        row.setAlignment(Pos.CENTER);

        VBox body = new VBox(30, title, row);
        body.setAlignment(Pos.CENTER);
        getChildren().add(body);
    }

    public void show() {
        refresh();
        setOpacity(0);
        rootStack.getChildren().add(this);
        FadeTransition ft = new FadeTransition(Duration.millis(FADE_MS), this);
        ft.setToValue(1);
        ft.play();
    }

    public void hide() {
        FadeTransition ft = new FadeTransition(Duration.millis(FADE_MS), this);
        ft.setToValue(0);
        ft.setOnFinished(_ -> rootStack.getChildren().remove(this));
        ft.play();
    }

    private void select(VersionMode mode) {
        if (mode == VersionMode.STANDARD) {
            vm.switchTo(mode);
            hide();
        } else {
            checkResourcesReady();
        }
    }

    // ── 抓包环境检测与懒下载 ──────────────────────────────────

    private static final String PCAP_EXE_PATH = "/plugins/RocoMapTracker-pcap.exe";
    private static final String PCAP_REPO = "kedaya0209/RocoMapTracker-pacp";
    private static final String GITHUB_API = "https://api.github.com/repos/" + PCAP_REPO + "/releases/latest";
    /** GitHub 下载镜像（国内用户可改为 <a href="https://gh-proxy.org/">...</a>） */
    private static final String GITHUB_DL_MIRROR = "https://gh-proxy.org/";

    /**
     * 检查抓包环境：npcap 驱动 + pcap 组件，缺失则弹窗引导处理。
     */
    private void checkResourcesReady() {
        if (!isNpcapInstalled()) {
            showNpcapDialog();
            return;
        }
        // npcap 已就绪，检查 pcap 组件是否已下载
        File pcapExe = new File(FilePathUtil.getExternalPath(PCAP_EXE_PATH, true));
        if (!pcapExe.exists()) {
            showPcapDownloadConfirmDialog();
            return;
        }
        vm.switchTo(VersionMode.ADVANCED);
        hide();
    }

    /** 检测 npcap 或 WinPcap 抓包驱动是否安装 */
    private boolean isNpcapInstalled() {
        try {
            Process process = new ProcessBuilder("reg", "query", "HKLM\\SOFTWARE\\Npcap")
                    .redirectErrorStream(true)
                    .start();
            if (process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0) return true;
        } catch (Exception ignored) {
            // fall through
        }
        // 备用：检查 wpcap.dll
        return new File("C:\\Windows\\System32\\wpcap.dll").exists()
            || new File("C:\\Windows\\SysWOW64\\wpcap.dll").exists();
    }

    // ── 弹窗通用组件 ──────────────────────────────────────────

    private void showAlert(String msg) {
        DialogUtils.showSimpleDialog(rootStack, "提示", msg, "确定", true, () -> {});
    }

    /** 创建统一样式的遮罩并淡入 */
    private StackPane fadeInMask() {
        StackPane mask = new StackPane();
        mask.setStyle("-fx-background-color: rgba(0, 0, 0, 0.8);");
        rootStack.getChildren().add(mask);
        mask.setOpacity(0);
        FadeTransition ft = new FadeTransition(Duration.millis(200), mask);
        ft.setToValue(1);
        ft.play();
        return mask;
    }

    /** 创建统一样式的对话框盒子 */
    private static VBox dialogBox(double maxHeight) {
        VBox box = new VBox(20);
        box.setMaxSize(420, maxHeight);
        box.setPadding(new Insets(30));
        box.setAlignment(Pos.CENTER);
        box.setStyle(
                "-fx-background-color: -color-bg-default; " +
                "-fx-border-color: -color-border-muted; " +
                "-fx-border-radius: 12; " +
                "-fx-background-radius: 12; " +
                "-fx-border-width: 1.5; " +
                "-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.5), 20, 0, 0, 10);");
        return box;
    }

    /** 创建统一样式的标题标签 */
    private static Label titleLabel(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: -color-fg-default;");
        return label;
    }

    /** 创建统一样式的消息标签 */
    private static Label msgLabel(String text) {
        Label label = new Label(text);
        label.setTextAlignment(TextAlignment.CENTER);
        label.setWrapText(true);
        label.setStyle("-fx-text-fill: -color-fg-muted;");
        return label;
    }

    /** 创建超链接（点击用浏览器打开） */
    private static Hyperlink createLink(String text, String url) {
        Hyperlink link = new Hyperlink(text);
        link.setStyle("-fx-font-size: 12px; -fx-text-fill: -color-accent-emphasis;");
        link.setOnAction(_ -> {
            try {
                new ProcessBuilder("cmd", "/c", "start", url).start();
            } catch (IOException ignored) {
                // ignore
            }
        });
        return link;
    }

    // ── npcap 未安装弹窗 ──────────────────────────────────────

    private void showNpcapDialog() {
        Button okBtn = new Button("我知道了");
        okBtn.setPrefWidth(140);
        okBtn.getStyleClass().addAll(Styles.BUTTON_OUTLINED, Styles.ACCENT);
        FxRippleUtil.install(okBtn);

        VBox dialogBox = dialogBox(260);
        dialogBox.getChildren().addAll(
                titleLabel("需要安装抓包驱动"),
                msgLabel("高级版需要 npcap 抓包驱动才能运行。\n请前往 npcap 官网下载安装："),
                createLink("https://npcap.com/", "https://npcap.com/"),
                okBtn);

        StackPane mask = fadeInMask();
        mask.getChildren().add(dialogBox);
        okBtn.setOnAction(_ -> rootStack.getChildren().remove(mask));
    }

    // ── pcap 组件下载确认 ─────────────────────────────────────

    private void showPcapDownloadConfirmDialog() {
        String repoUrl = "https://github.com/" + PCAP_REPO + "/releases/latest";

        HBox btnBox = new HBox(15);
        btnBox.setAlignment(Pos.CENTER);

        Button cancelBtn = new Button("取消");
        cancelBtn.setPrefWidth(120);
        cancelBtn.getStyleClass().addAll(Styles.BUTTON_OUTLINED);
        FxRippleUtil.install(cancelBtn);

        Button confirmBtn = new Button("开始下载");
        confirmBtn.setPrefWidth(120);
        confirmBtn.getStyleClass().addAll(Styles.BUTTON_OUTLINED, Styles.ACCENT);
        FxRippleUtil.install(confirmBtn);

        btnBox.getChildren().addAll(cancelBtn, confirmBtn);

        VBox dialogBox = dialogBox(300);
        dialogBox.getChildren().addAll(
                titleLabel("下载 pcap 组件"),
                msgLabel("需要下载 pcap 抓包组件，是否继续？"),
                createLink(repoUrl, repoUrl),
                btnBox);

        StackPane mask = fadeInMask();
        mask.getChildren().add(dialogBox);
        cancelBtn.setOnAction(_ -> rootStack.getChildren().remove(mask));
        confirmBtn.setOnAction(_ -> {
            rootStack.getChildren().remove(mask);
            startDownload();
        });
    }

    private void startDownload() {
        AtomicBoolean cancelFlag = new AtomicBoolean(false);

        // 进度弹窗（统一样式）
        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(360);

        Label statusLabel = new Label("准备下载...");
        statusLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 13px;");

        Button cancelBtn = new Button("取消下载");
        cancelBtn.setPrefWidth(120);
        cancelBtn.getStyleClass().addAll(Styles.BUTTON_OUTLINED, Styles.DANGER);
        FxRippleUtil.install(cancelBtn);

        VBox dialogBox = dialogBox(280);
        dialogBox.getChildren().addAll(
                titleLabel("正在下载 pcap 组件..."),
                progressBar, statusLabel, cancelBtn);

        StackPane mask = fadeInMask();
        mask.getChildren().add(dialogBox);
        cancelBtn.setOnAction(_ -> {
            cancelFlag.set(true);
            rootStack.getChildren().remove(mask);
        });

        // 异步下载流程：解析 URL → 下载（有进度）→ 解压 → 校验
        Thread.ofPlatform().daemon(true).name("pcap-download").start(() -> {
            try {
                String downloadUrl = resolveDownloadUrl();
                Platform.runLater(() -> statusLabel.setText("正在下载..."));
                Path zipPath = downloadFile(downloadUrl, (read, total) ->
                        Platform.runLater(() -> {
                            double p = Math.clamp((double) read / total, 0, 1);
                            progressBar.setProgress(p);
                            statusLabel.setText(formatProgress(p, read, total));
                        }), cancelFlag);

                if (zipPath == null) return; // 用户取消

                Platform.runLater(() -> statusLabel.setText("正在解压..."));
                extractZip(zipPath, PCAP_EXE_PATH);
                Files.deleteIfExists(zipPath);

                Platform.runLater(() -> {
                    rootStack.getChildren().remove(mask);
                    if (new File(FilePathUtil.getExternalPath(PCAP_EXE_PATH, true)).exists()) {
                        vm.switchTo(VersionMode.ADVANCED);
                        hide();
                    } else {
                        showAlert("下载完成但部分资源仍缺失，请重试");
                    }
                });
            } catch (Exception e) {
                log.error("下载 pcap 组件失败", e);
                Platform.runLater(() -> {
                    rootStack.getChildren().remove(mask);
                    if (!cancelFlag.get()) {
                        showAlert("下载失败，请检查网络连接后重试，或通过上方链接手动下载。");
                    }
                });
            }
        });
    }

    /** 调用 GitHub API 获取最新 release 的 zip 下载 URL */
    private String resolveDownloadUrl() throws Exception {
        try (HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()) {
            HttpRequest apiReq = HttpRequest.newBuilder()
                    .uri(URI.create(GITHUB_API))
                    .header("User-Agent", "RocoMapTracker")
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> apiRes = client.send(apiReq, HttpResponse.BodyHandlers.ofString());
            if (apiRes.statusCode() != 200) {
                throw new IOException("GitHub API 返回 " + apiRes.statusCode());
            }

            JsonNode root = JsonUtils.getMapper().readTree(apiRes.body());
            for (JsonNode asset : root.get("assets")) {
                String name = asset.get("name").asText();
                if (name.endsWith(".zip")) {
                    String url = asset.get("browser_download_url").asText();
                    log.info("pcap 下载 URL: {}", url);
                    return GITHUB_DL_MIRROR + url;
                }
            }
            throw new IOException("未在最新 release 中找到 zip 文件");
        }

    }

    /** 格式化进度文本：xx.x% (xx.x MB/xx.x MB) */
    private static String formatProgress(double pct, long read, long total) {
        String pctStr = String.format("%.1f%%", pct * 100);
        if (total <= 0) return pctStr;
        return pctStr + " (" + formatSize(read) + "/" + formatSize(total) + ")";
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /** 下载文件到 download/ 目录，实时报告进度，支持取消 */
    private Path downloadFile(String url, BiConsumer<Long, Long> progressCallback,
                              AtomicBoolean cancelFlag) throws Exception {
        try (HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "RocoMapTracker")
                    .GET()
                    .build();

            HttpResponse<InputStream> response = client.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            long totalBytes = response.headers().firstValueAsLong("Content-Length").orElse(-1L);

            Path downloadDir = Path.of("download");
            Files.createDirectories(downloadDir);
            Path tempFile = downloadDir.resolve("pcap.zip");
            try (InputStream in = response.body();
                 FileOutputStream fos = new FileOutputStream(tempFile.toFile())) {
                byte[] buf = new byte[8192];
                long readBytes = 0;
                int n;
                while ((n = in.read(buf)) >= 0) {
                    if (cancelFlag.get()) {
                        log.info("用户取消下载");
                        Files.deleteIfExists(tempFile);
                        return null;
                    }
                    fos.write(buf, 0, n);
                    readBytes += n;
                    if (totalBytes > 0) {
                        progressCallback.accept(readBytes, totalBytes);
                    }
                }
            }
            return tempFile;
        }
    }

    /** 解压 zip 到外部 pcap 目录 */
    private void extractZip(Path zipPath, String exeClasspath) throws IOException {
        File targetDir = new File(FilePathUtil.getExternalPath(exeClasspath, true)).getParentFile();
        if (!targetDir.exists()) targetDir.mkdirs();

        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipPath.toFile()))) {
            ZipEntry entry;
            byte[] buf = new byte[8192];
            while ((entry = zis.getNextEntry()) != null) {
                File outFile = new File(targetDir, entry.getName());
                if (!outFile.getCanonicalPath().startsWith(targetDir.getCanonicalPath() + File.separator)) continue;
                if (entry.isDirectory()) {
                    outFile.mkdirs();
                } else {
                    outFile.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(outFile)) {
                        int n;
                        while ((n = zis.read(buf)) >= 0) fos.write(buf, 0, n);
                    }
                }
                zis.closeEntry();
            }
        }
        log.info("pcap 组件已解压到 {}", targetDir);
    }

    // ── 标准版卡片 ──────────────────────────────────────────

    private VBox buildStdCard() {
        return card("标准版", "基于图像识别技术",
                new String[]{
                        "导航功能 — 小地图识别与坐标定位",
                        "自制路线 — 绘制、编辑、导入/导出",
                        "资源点标记 — 地图标记与快速查询",
                        "视角跟随 — 自动跟随与导航模式"
                },
                null);
    }

    // ── 高级版卡片 ──────────────────────────────────────────

    private VBox buildAdvCard() {
        Label risk = new Label("注意：抓包涉及网络数据采集，请自行承担使用风险");
        risk.setStyle("-fx-text-fill: #FF9800; -fx-font-size: 11px;"
                + "-fx-padding: 8 12; -fx-background-color: rgba(255,152,0,0.1);"
                + "-fx-background-radius: 6;");
        risk.setWrapText(true);
        risk.setMaxWidth(CARD_W - 40);
        risk.setMaxWidth(Double.MAX_VALUE);

        return card("高级版", "包含标准版全部功能",
                new String[]{
                        "标准版全部功能",
                        "网络抓包资源统计 — 实时物资拾取监控",
                        "场景变更自动识别 — 跨区域自动适配",
                        "物品拾取日志 — 历史记录与背包总数"
                },
                risk);
    }

    // ── 卡片构建 ─────────────────────────────────────────────

    private VBox card(String title, String subtitle, String[] features, Node extra) {
        Label t = new Label(title);
        t.getStyleClass().addAll(Styles.TITLE_3, Styles.TEXT_BOLD);
        t.setStyle("-fx-text-fill: -color-fg-default;");
        t.setMaxWidth(Double.MAX_VALUE);

        Label sub = new Label(subtitle);
        sub.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 12px;");
        sub.setMaxWidth(Double.MAX_VALUE);

        VBox feats = new VBox(6);
        feats.setAlignment(Pos.CENTER_LEFT);
        for (String f : features) {
            Label l = new Label("• " + f);
            l.setStyle("-fx-text-fill: -color-fg-default; -fx-font-size: 12px;");
            l.setWrapText(true);
            l.setMaxWidth(Double.MAX_VALUE);
            feats.getChildren().add(l);
        }

        Button btn = new Button("启动 " + title);
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.setPrefHeight(38);
        btn.getStyleClass().addAll(Styles.ACCENT, Styles.BUTTON_OUTLINED);
        btn.setStyle("-fx-background-radius: 8; -fx-font-size: 13px; -fx-cursor: hand;");

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        VBox c = new VBox(12, t, sub, feats);
        c.setAlignment(Pos.TOP_LEFT);
        if (extra != null) c.getChildren().add(extra);
        c.getChildren().addAll(spacer, btn);

        c.setMaxWidth(CARD_W);
        c.setPrefWidth(CARD_W);
        c.setPadding(new Insets(24, 20, 20, 20));
        c.setStyle("-fx-background-color: -color-bg-default; -fx-background-radius: 12;"
                + "-fx-border-color: -color-border-muted; -fx-border-radius: 12;"
                + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.3), 20, 0, 0, 8);");
        return c;
    }

    // ── 状态刷新 ─────────────────────────────────────────────

    private void refresh() {
        boolean isStd = vm.getCurrentMode() == VersionMode.STANDARD;
        updateBtn(stdBtn, isStd, "标准版");
        updateBtn(advBtn, !isStd, "高级版");
        frame(stdCard, isStd);
        frame(advCard, !isStd);
    }

    private void updateBtn(Button btn, boolean active, String name) {
        if (active) {
            btn.setText("当前版本");
            btn.setDisable(false);
            btn.setStyle("-fx-background-color: -color-success-emphasis; -fx-text-fill: white;"
                    + "-fx-background-radius: 8; -fx-font-size: 13px; -fx-cursor: hand;");
        } else {
            btn.setText("启动 " + name);
            btn.setDisable(false);
            btn.setStyle("-fx-background-radius: 8; -fx-font-size: 13px; -fx-cursor: hand;");
        }
    }

    private void frame(VBox card, boolean active) {
        card.setStyle(active
                ? "-fx-background-color: -color-bg-default; -fx-background-radius: 12;"
                + "-fx-border-color: -color-accent-emphasis; -fx-border-width: 2; -fx-border-radius: 12;"
                + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.3), 20, 0, 0, 8);"
                : "-fx-background-color: -color-bg-default; -fx-background-radius: 12;"
                + "-fx-border-color: -color-border-muted; -fx-border-width: 1; -fx-border-radius: 12;"
                + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.3), 20, 0, 0, 8);");
    }

    private static Button findBtn(VBox card) {
        return (Button) card.getChildren().stream()
                .filter(n -> n instanceof Button).findFirst().orElse(null);
    }
}
