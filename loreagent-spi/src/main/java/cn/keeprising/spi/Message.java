package cn.keeprising.spi;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 发送给模型或由模型返回的不可变对话消息。
 * 与 app 层持久化实体 ChatMessage 分离，避免数据库字段泄露进 SPI。
 * role：角色（只允许 system / user / assistant / tool）
 * content：消息正文
 * toolCalls：当 role=assistant 且模型要调工具时，这里放它要调用的工具列表
 * toolCallId：当 role=tool 回填工具结果时，记录它对应哪次 ToolCall.id
 */
public record Message(String role, String content, List<ToolCall> toolCalls, String toolCallId) {

    // 用 String 角色直接对齐 OpenAI 协议，但加白名单防止拼错（如把 "user" 写成 "usr"）
    private static final Set<String> ALLOWED_ROLES = Set.of("system", "user", "assistant", "tool");

    public Message {
        if (role == null || !ALLOWED_ROLES.contains(role)) {
            throw new IllegalArgumentException("非法消息角色 role=" + role + "，仅允许 " + ALLOWED_ROLES);
        }
        // 助手仅发起 tool_calls 时 content 可能为 null，统一归一化为空串简化下游处理
        content = Objects.requireNonNullElse(content, "");
        // 防御性不可变拷贝，避免外部持有原 List 引用修改上下文
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        if ("tool".equals(role) && (toolCallId == null || toolCallId.isBlank())) {
            throw new IllegalArgumentException("tool 角色消息必须携带 toolCallId");
        }
    }

    // 快捷构造系统提示词消息
    public static Message system(String content) {
        return new Message("system", content, List.of(), null);
    }

    // 快捷构造用户输入消息
    public static Message user(String content) {
        return new Message("user", content, List.of(), null);
    }

    // 快捷构造普通助手回复消息
    public static Message assistant(String content) {
        return new Message("assistant", content, List.of(), null);
    }

    // 快捷构造“助手决定发起工具调用”的消息
    public static Message assistantTools(String content, List<ToolCall> toolCalls) {
        return new Message("assistant", content, toolCalls, null);
    }

    // 快捷构造“工具结果回填”消息
    public static Message tool(String toolCallId, String content) {
        return new Message("tool", content, List.of(), toolCallId);
    }
}
