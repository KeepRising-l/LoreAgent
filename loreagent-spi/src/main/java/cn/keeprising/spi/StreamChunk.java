package cn.keeprising.spi;

import java.util.List;
import java.util.Objects;

/**
 * 模型单次流式返回的增量分片（适配器每从 SSE 收到一小块响应，就解析成这个对象）。
 * textDelta：这一小块新增的文字（没有字就为空串 ""）
 * toolCallDeltas：如果模型在流式输出“调用工具指令”，这里是这一小块的参数增量
 * finishReason：结束原因（吐字过程中为 null，最后一片才出现 "stop" / "length" / "tool_calls"）
 * usage：整轮 token 用量统计（通常只在最后一帧出现，前面帧为 null）
 */
public record StreamChunk(
        String textDelta,
        List<ToolCallDelta> toolCallDeltas,
        String finishReason,
        Usage usage) {

    public StreamChunk {
        textDelta = Objects.requireNonNullElse(textDelta, "");
        toolCallDeltas = toolCallDeltas == null ? List.of() : List.copyOf(toolCallDeltas);
    }

    // 快捷构造一个纯文字增量分片（测试里最常用）
    public static StreamChunk text(String delta) {
        return new StreamChunk(delta, List.of(), null, null);
    }

    // 快捷构造一个结束分片
    public static StreamChunk finish(String finishReason, Usage usage) {
        return new StreamChunk("", List.of(), finishReason, usage);
    }

    /**
     * 模型流式分片中的工具调用增量；同一工具的参数 JSON 会被切成好几片，靠 index 对号拼接。
     */
    public record ToolCallDelta(int index, String id, String name, String argumentsDelta) {

        public ToolCallDelta {
            argumentsDelta = Objects.requireNonNullElse(argumentsDelta, "");
        }
    }

    /**
     * 单次模型调用的 token 用量统计（prompt 输入 + completion 输出 = total 总量）。
     */
    public record Usage(int promptTokens, int completionTokens, int totalTokens) {
    }
}
