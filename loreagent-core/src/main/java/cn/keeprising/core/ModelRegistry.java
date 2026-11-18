package cn.keeprising.core;

import cn.keeprising.spi.LlmAdapter;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型注册表：相当于一张“模型名字 → 发请求客户端”的通讯录。
 * 目前只有环境变量里的默认模型；以后接多个模型（比如对话用 A 模型、总结用 B 模型）全靠它找人。
 */
public class ModelRegistry {

    private final String defaultModel;
    private final Map<String, LlmAdapter> adapters = new ConcurrentHashMap<>();

    public ModelRegistry(String defaultModel, LlmAdapter defaultAdapter) {
        if (defaultModel == null || defaultModel.isBlank()) {
            throw new IllegalArgumentException("defaultModel 不能为空");
        }
        Objects.requireNonNull(defaultAdapter, "defaultAdapter 不能为空");
        this.defaultModel = defaultModel;
        this.adapters.put(defaultModel, defaultAdapter);
    }

    // 往通讯录里额外登记一个模型和它对应的客户端
    public void register(String modelName, LlmAdapter adapter) {
        if (modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException("modelName 不能为空");
        }
        adapters.put(modelName, Objects.requireNonNull(adapter, "adapter 不能为空"));
    }

    // 前端没传模型名时，兜底返回默认模型名
    public String resolveModel(String requestedModel) {
        return (requestedModel == null || requestedModel.isBlank()) ? defaultModel : requestedModel;
    }

    // 根据模型名找客户端；找不到时明确抛 MODEL_NOT_FOUND，不静默回退
    public LlmAdapter get(String modelName) {
        String resolved = resolveModel(modelName);
        LlmAdapter adapter = adapters.get(resolved);
        if (adapter == null) {
            throw new Failure(Failure.ErrorCode.MODEL_NOT_FOUND, "未注册的模型: " + resolved);
        }
        return adapter;
    }
}
