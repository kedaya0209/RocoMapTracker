package io.github.kedaya0209.roco.app.ui.component.widget;

import atlantafx.base.theme.Styles;
import io.github.kedaya0209.roco.app.config.SnifferConfig;
import io.github.kedaya0209.roco.app.ui.service.VersionMode;
import io.github.kedaya0209.roco.app.ui.service.ui.VersionManager;
import io.github.kedaya0209.roco.app.ui.util.FxRippleUtil;
import io.github.kedaya0209.roco.app.update.plugin.PluginStatus;
import io.github.kedaya0209.roco.app.update.plugin.PluginUpdateManager;
import javafx.animation.FadeTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.layout.*;
import javafx.scene.text.TextAlignment;
import javafx.util.Duration;
import lombok.extern.slf4j.Slf4j;
import net.jcip.annotations.NotThreadSafe;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

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
        setViewOrder(-40);
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

    /**
     * 检测 sniffer 插件是否已就绪
     */
    private static boolean isSnifferReady() {
        PluginUpdateManager pm = PluginUpdateManager.getInstance();
        pm.scanPlugins();
        return pm.getPlugin("sniffer")
                .filter(p -> p.status() != PluginStatus.DAMAGED
                        && p.status() != PluginStatus.DISABLED)
                .isPresent();
    }

    private void select(VersionMode mode) {
        if (mode == VersionMode.STANDARD) {
            vm.switchTo(mode);
            hide();
        } else {
            if (!isNpcapInstalled()) {
                showNpcapDialog();
                return;
            }
            if (isSnifferReady()) {
                vm.switchTo(VersionMode.ADVANCED);
                hide();
            } else {
                showConfirmDownloadDialog();
            }
        }
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

    /** 创建统一样式的遮罩并淡入 */
    private StackPane fadeInMask() {
        StackPane mask = new StackPane();
        mask.setViewOrder(-50);
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
                createLink(SnifferConfig.NPCAP_LINK, SnifferConfig.NPCAP_LINK),
                okBtn);

        StackPane mask = fadeInMask();
        mask.getChildren().add(dialogBox);
        okBtn.setOnAction(_ -> rootStack.getChildren().remove(mask));
    }

    // ── 确认下载 sniffer 弹窗 ──────────────────────────────────

    private void showConfirmDownloadDialog() {
        Button confirmBtn = new Button("确认下载");
        confirmBtn.setPrefWidth(140);
        confirmBtn.getStyleClass().addAll(Styles.BUTTON_OUTLINED, Styles.ACCENT);
        FxRippleUtil.install(confirmBtn);

        Button cancelBtn = new Button("取消");
        cancelBtn.setPrefWidth(140);
        cancelBtn.getStyleClass().addAll(Styles.BUTTON_OUTLINED);
        FxRippleUtil.install(cancelBtn);

        HBox btnRow = new HBox(12, confirmBtn, cancelBtn);
        btnRow.setAlignment(Pos.CENTER);

        VBox dialogBox = dialogBox(220);
        dialogBox.getChildren().addAll(
                titleLabel("下载高级版组件"),
                msgLabel("切换至高级版需要下载 sniffer 抓包组件。\n是否下载？"),
                btnRow);

        StackPane mask = fadeInMask();
        mask.getChildren().add(dialogBox);
        confirmBtn.setOnAction(_ -> {
            rootStack.getChildren().remove(mask);
            vm.switchTo(VersionMode.ADVANCED);
            hide();
        });
        cancelBtn.setOnAction(_ -> rootStack.getChildren().remove(mask));
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
        card.setStyle("-fx-background-color: -color-bg-default; -fx-background-radius: 14;"
                + "-fx-border-color: " + (active ? "-color-accent-emphasis" : "-color-border-muted")
                + "; -fx-border-width: 3; -fx-border-radius: 12;"
                + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.3), 20, 0, 0, 8);");
    }

    private static Button findBtn(VBox card) {
        return (Button) card.getChildren().stream()
                .filter(n -> n instanceof Button).findFirst().orElse(null);
    }
}
