package com.luoke.app.ui.service;

/**
 * 资源初始化 UI 回调接口。
 * ResourceInitService 不直接依赖 UI 类，通过此接口回调驱动 UI 变化。
 */
public interface ResourceInitUiDelegate {

    /**
     * 首次运行对话框：下载 / 内置资源 / 退出
     */
    void showFirstRunDialog(Runnable onDownload, Runnable onBuiltIn, Runnable onExit);

    /**
     * 显示下载进度覆盖层
     */
    void showDownloadOverlay(Runnable onCancel);

    /**
     * 移除下载覆盖层
     */
    void removeDownloadOverlay();

    /**
     * 资源就绪，构建主界面
     */
    void onResourceReady(Runnable buildMainUi);
}
