package com.luoke.app.model;

import ai.djl.inference.Predictor;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.NoopTranslator;
import com.luoke.app.config.AppConfig;
import com.luoke.app.utils.ResourceUtils;
import lombok.Getter;

import java.nio.file.Path;

/**
 * ONNX 模型管理器基类 — 消除 Arrow/Det/Rec 三个 Manager 的重复代码。
 * 子类只需实现 {@link #configureCriteria} 提供各自优化参数。
 */
@Getter
public abstract class BaseOnnxManager implements AutoCloseable {

    protected final String modelName;
    protected volatile ZooModel<NDList, NDList> model;
    protected volatile Predictor<NDList, NDList> predictor;

    protected BaseOnnxManager(String modelName) throws Exception {
        this.modelName = modelName;
        loadModel();
    }

    protected void loadModel() throws Exception {
        String path = ResourceUtils.getExternalPath(AppConfig.MODEL_DIR + modelName, true);

        Criteria.Builder<NDList, NDList> builder = Criteria.builder()
                .setTypes(NDList.class, NDList.class)
                .optEngine("OnnxRuntime")
                .optModelPath(Path.of(path))
                .optOption("enable_cpu_mem_arena", "0")
                .optTranslator(new NoopTranslator());

        configureCriteria(builder);

        this.model = builder.build().loadModel();
        this.predictor = model.newPredictor();
    }

    /**
     * 子类自定义 ONNX Runtime 选项（线程数、优化级别等）
     */
    protected abstract void configureCriteria(Criteria.Builder<NDList, NDList> builder);

    public NDManager getNDManager() {
        return model.getNDManager();
    }

    /**
     * 创建新的子 NDManager，挂载到当前 model 的 NDManager。
     */
    public NDManager newSubManager() {
        return model.getNDManager().newSubManager();
    }

    /**
     * 重建 Model + Predictor，释放 ONNX Runtime 内部 Arena 内存。
     * 子类应在重建前自行处理子 NDManager 的关闭。
     */
    public synchronized void rebuild() throws Exception {
        ZooModel<NDList, NDList> oldModel = this.model;
        Predictor<NDList, NDList> oldPredictor = this.predictor;

        loadModel();

        if (oldPredictor != null) oldPredictor.close();
        if (oldModel != null) oldModel.close();
    }

    @Override
    public void close() {
        if (predictor != null) predictor.close();
        if (model != null) model.close();
    }
}
