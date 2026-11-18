package cn.keeprising.app;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 知识库文档领域模型（映射 PostgreSQL document 表）。
 */
public record Document(
        UUID id,
        String userId,
        String title,
        List<String> tags,
        LocalDate docDate,
        int activeVersion,
        Instant createdAt,
        Instant updatedAt) {
}
