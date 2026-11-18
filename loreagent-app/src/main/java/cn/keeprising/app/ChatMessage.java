package cn.keeprising.app;

import cn.keeprising.spi.ToolCall;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 持久化消息实体，对应数据库 chat_message 表。
 * 与 spi 的 Message 故意不合并，防止 id、seq、citations、requestId 等落库字段泄露进内核契约。
 */
public record ChatMessage(
        long id,
        UUID conversationId,
        String userId,
        int seq,
        String role,
        String content,
        List<ToolCall> toolCalls,
        String citationsJson,
        String requestId,
        Instant createdAt) {

    public ChatMessage {
        Objects.requireNonNull(conversationId, "ChatMessage.conversationId 不能为空");
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("ChatMessage.userId 不能为空");
        }
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("ChatMessage.role 不能为空");
        }
        content = Objects.requireNonNullElse(content, "");
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }
}
