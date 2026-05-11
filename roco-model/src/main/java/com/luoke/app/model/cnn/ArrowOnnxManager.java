package com.luoke.app.model.cnn;

import ai.djl.ndarray.NDList;
import ai.djl.repository.zoo.Criteria;
import com.luoke.app.model.BaseOnnxManager;

/**
 * 箭头方向 CNN 模型管理器。
 */
public class ArrowOnnxManager extends BaseOnnxManager {

    public ArrowOnnxManager(String modelName) throws Exception {
        super(modelName);
    }

    @Override
    protected void configureCriteria(Criteria.Builder<NDList, NDList> builder) {
        builder.optOption("interOpNumThreads", "1")
                .optOption("intraOpNumThreads", "1")
                .optOption("optimizationLevel", "ORT_ENABLE_ALL");
    }
}
