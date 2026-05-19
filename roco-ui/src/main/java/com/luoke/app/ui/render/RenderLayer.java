package com.luoke.app.ui.render;

import javafx.scene.Node;

/**
 * 渲染层接口 — 每帧由 MapRenderer 编排调用。
 * <p>
 * 实现类从上下文单例（MapContext / CameraContext 等）自行读取所需数据，
 * 无参数传递，降低编排器与渲染层的耦合。
 */
public interface RenderLayer {

    /**
     * @return 场景图节点，由 MapRenderer 按 z-order 组装到父容器
     */
    Node getNode();

    /**
     * 每帧回调。实现类自行维护脏标记和状态检测。
     */
    void onFrame();
}
