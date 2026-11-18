package cn.keeprising.spi;

import java.util.Objects;

/**
 * 模型发起的一次工具调用指令（例如模型想算 "2+3"，就会吐出一条 ToolCall）。
 * id：模型给本次调用编的唯一号，后面工具算完回填时要靠它对号入座
 * name：要调哪个工具（例如 "calc"）
 * argumentsJson：模型填好的参数 JSON 字符串（例如 "{\"expr\":\"2+3\"}"）
 */
public record ToolCall(String id, String name, String argumentsJson) {

    public ToolCall {
        // 缺少调用号或工具名根本没法执行，构造时直接拦住
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("ToolCall.id 不能为空");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("ToolCall.name 不能为空");
        }
        // 模型流式解析前或无参工具可能给 null，归一化为空串避免下游空指针，交由解析器统一报错
        argumentsJson = Objects.requireNonNullElse(argumentsJson, "");
    }
}
