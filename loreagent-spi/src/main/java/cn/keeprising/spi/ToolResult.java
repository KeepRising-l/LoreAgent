package cn.keeprising.spi;

import java.util.Objects;

/**
 * 工具执行完成后回填给模型的结果（相当于把计算结果交还给模型继续说话）。
 * toolCallId：对应哪次 ToolCall.id
 * name：工具名
 * content：工具执行输出的文本（或报错说明）
 * success：这次执行是成功还是失败
 */
public record ToolResult(String toolCallId, String name, String content, boolean success) {

    public ToolResult {
        // 回填给模型时必须能对上 toolCallId，否则模型不知道这条结果属于哪次调用
        if (toolCallId == null || toolCallId.isBlank()) {
            throw new IllegalArgumentException("ToolResult.toolCallId 不能为空");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("ToolResult.name 不能为空");
        }
        // 结果为空时统一转成 ""，避免后面拼上下文或算长度时 NPE
        content = Objects.requireNonNullElse(content, "");
    }

    // 快捷构造成功结果
    public static ToolResult ok(String toolCallId, String name, String content) {
        return new ToolResult(toolCallId, name, content, true);
    }

    // 快捷构造失败结果（告知模型工具出错了，而不是直接崩掉）
    public static ToolResult error(String toolCallId, String name, String content) {
        return new ToolResult(toolCallId, name, content, false);
    }
}
