package io.github.kedaya0209.roco.app.ui.component;

import net.jcip.annotations.NotThreadSafe;
import atlantafx.base.theme.Styles;
import io.github.kedaya0209.roco.app.hook.AbstractGenericHook;
import io.github.kedaya0209.roco.app.hook.HookEventType;
import io.github.kedaya0209.roco.app.hook.event.ProgressEvent;
import io.github.kedaya0209.roco.app.hook.multicast.HookRegistry;
import io.github.kedaya0209.roco.app.ui.util.FxRippleUtil;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.VBox;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;

@NotThreadSafe
@Slf4j
public class LoadingOverlay extends VBox {
    private final ProgressBar progressBar = new ProgressBar(0);
    private final Label statusLabel = new Label("正在初始化资源...");
    private final Button cancelBtn = new Button("取消下载");
    private final ProgressHook progressHook = new ProgressHook();

    public LoadingOverlay(Runnable onCancel) {
        this.setAlignment(Pos.CENTER);
        this.setSpacing(25);
        this.setStyle("-fx-background-color: -color-bg-default;");

        statusLabel.setStyle("-fx-text-fill: -color-fg-default; -fx-font-size: 18px; -fx-font-weight: bold;");

        progressBar.setPrefWidth(450);
        progressBar.getStyleClass().add(Styles.MEDIUM);

        FxRippleUtil.install(cancelBtn);
        if (onCancel == null) {
            cancelBtn.setVisible(false);
            cancelBtn.setManaged(false);
        } else {
            cancelBtn.getStyleClass().add(Styles.DANGER);
            cancelBtn.setOnAction(_ -> {
                cancelBtn.setDisable(true);
                statusLabel.setText("正在取消并清理中...");
                onCancel.run();
            });
        }

        this.getChildren().addAll(statusLabel, progressBar, cancelBtn);

        HookRegistry.INSTANCE.register(progressHook);
    }

    /**
     * 移除自身时必须注销 Hook，防止监听器泄漏
     */
    public void dispose() {
        HookRegistry.INSTANCE.unregister(progressHook);
    }

    /**
     * 手动更新接口（保留以备直接调用）
     */
    public void updateProgress(double progress, String text) {
        if (progress < 0) {
            progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        } else {
            progressBar.setProgress(progress);
        }
        statusLabel.setText(text);
    }

    // 在 LoadingOverlay 内部定义一个具名的 Hook 实现类
    @NotThreadSafe
    private class ProgressHook extends AbstractGenericHook<ProgressEvent> {
        @Override
        public void onEvent(HookEventType eventType, ProgressEvent data) {
            if (eventType == HookEventType.INIT_PROGRESS) {
                Platform.runLater(() -> updateProgress(data.value(), data.text()));
            }
        }

        @Override
        public Set<HookEventType> supportedEvents() {
            return Set.of(HookEventType.INIT_PROGRESS);
        }
    }
}