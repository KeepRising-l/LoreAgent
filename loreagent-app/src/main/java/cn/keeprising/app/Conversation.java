package cn.keeprising.app;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 持久化会话实体，对应数据库 conversation 表。
 */
public record Conversation(UUID id, String userId, String title, Instant createdAt, Instant updatedAt) {

    public Conversation {
        Objects.requireNonNull(id, "Conversation.id 不能为空");
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("Conversation.userId 不能为空");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Conversation.title 不能为空");
        }
    }
}
