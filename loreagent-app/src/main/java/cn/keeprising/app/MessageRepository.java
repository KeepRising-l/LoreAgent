package cn.keeprising.app;

import cn.keeprising.core.Failure;
import cn.keeprising.spi.ToolCall;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 消息表 JdbcTemplate 仓储：所有方法首参均为 userId，并在 SQL 中强制按 user_id 过滤。
 */
@Repository
public class MessageRepository {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<ToolCall>> TOOL_CALLS_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;

    private final RowMapper<ChatMessage> rowMapper = (rs, rowNum) -> new ChatMessage(
            rs.getLong("id"),
            rs.getObject("conversation_id", UUID.class),
            rs.getString("user_id"),
            rs.getInt("seq"),
            rs.getString("role"),
            rs.getString("content"),
            deserializeToolCalls(rs.getString("tool_calls")),
            rs.getString("citations"),
            rs.getString("request_id"),
            rs.getTimestamp("created_at").toInstant());

    @Autowired(required = false)
    public MessageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // 供纯单测假实现继承使用的无参构造，避免单测依赖数据库
    protected MessageRepository() {
        this.jdbc = null;
    }

    public ChatMessage append(
            String userId,
            UUID conversationId,
            String role,
            String content,
            List<ToolCall> toolCalls,
            String citationsJson,
            String requestId) {
        String toolCallsJson = serializeToolCalls(toolCalls);
        try {
            return jdbc.queryForObject(
                    """
                    insert into chat_message (
                        conversation_id, user_id, seq, role, content, tool_calls, citations, request_id
                    )
                    values (
                        ?, ?, (select coalesce(max(seq), 0) + 1 from chat_message where conversation_id = ?),
                        ?, ?, cast(? as jsonb), cast(? as jsonb), ?
                    )
                    returning id, conversation_id, user_id, seq, role, content, tool_calls, citations, request_id, created_at
                    """,
                    rowMapper,
                    conversationId,
                    userId,
                    conversationId,
                    role,
                    content == null ? "" : content,
                    new SqlParameterValue(Types.VARCHAR, toolCallsJson),
                    new SqlParameterValue(Types.VARCHAR, citationsJson),
                    requestId);
        } catch (DataAccessException e) {
            throw new Failure(Failure.ErrorCode.STORAGE_ERROR, "写入消息落库失败: " + e.getMessage(), e);
        }
    }

    public List<ChatMessage> listByConversation(String userId, UUID conversationId) {
        try {
            return jdbc.query(
                    """
                    select id, conversation_id, user_id, seq, role, content, tool_calls, citations, request_id, created_at
                    from chat_message
                    where user_id = ? and conversation_id = ?
                    order by seq asc
                    """,
                    rowMapper,
                    userId, conversationId);
        } catch (DataAccessException e) {
            throw new Failure(Failure.ErrorCode.STORAGE_ERROR, "查询会话历史消息失败: " + e.getMessage(), e);
        }
    }

    public List<ChatMessage> listRecent(String userId, UUID conversationId, int limit) {
        try {
            return jdbc.query(
                    """
                    select id, conversation_id, user_id, seq, role, content, tool_calls, citations, request_id, created_at
                    from (
                        select id, conversation_id, user_id, seq, role, content, tool_calls, citations, request_id, created_at
                        from chat_message
                        where user_id = ? and conversation_id = ?
                        order by seq desc
                        limit ?
                    ) sub
                    order by seq asc
                    """,
                    rowMapper,
                    userId, conversationId, limit);
        } catch (DataAccessException e) {
            throw new Failure(Failure.ErrorCode.STORAGE_ERROR, "查询最近消息失败: " + e.getMessage(), e);
        }
    }

    public Optional<ChatMessage> findById(String userId, UUID conversationId, long messageId) {
        try {
            List<ChatMessage> rows = jdbc.query(
                    """
                    select id, conversation_id, user_id, seq, role, content, tool_calls, citations, request_id, created_at
                    from chat_message
                    where user_id = ? and conversation_id = ? and id = ?
                    """,
                    rowMapper,
                    userId, conversationId, messageId);
            return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
        } catch (DataAccessException e) {
            throw new Failure(Failure.ErrorCode.STORAGE_ERROR, "查询单条消息失败: " + e.getMessage(), e);
        }
    }

    public boolean hasAssistantReplyAfter(String userId, UUID conversationId, int userSeq) {
        try {
            Boolean exists = jdbc.queryForObject(
                    """
                    select exists(
                        select 1 from chat_message
                        where user_id = ? and conversation_id = ? and seq > ?
                          and role = 'assistant' and request_id is not null
                    )
                    """,
                    Boolean.class,
                    userId, conversationId, userSeq);
            return Boolean.TRUE.equals(exists);
        } catch (DataAccessException e) {
            throw new Failure(Failure.ErrorCode.STORAGE_ERROR, "检查消息回复状态失败: " + e.getMessage(), e);
        }
    }

    public Optional<ChatMessage> findCompletedAssistantByRequestId(String userId, String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return Optional.empty();
        }
        try {
            List<ChatMessage> rows = jdbc.query(
                    """
                    select id, conversation_id, user_id, seq, role, content, tool_calls, citations, request_id, created_at
                    from chat_message
                    where user_id = ? and request_id = ? and role = 'assistant'
                    """,
                    rowMapper,
                    userId, requestId);
            return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
        } catch (DataAccessException e) {
            throw new Failure(Failure.ErrorCode.STORAGE_ERROR, "查询幂等终态消息失败: " + e.getMessage(), e);
        }
    }

    private String serializeToolCalls(List<ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(toolCalls);
        } catch (Exception e) {
            throw new Failure(Failure.ErrorCode.INTERNAL_ERROR, "序列化 toolCalls 失败: " + e.getMessage(), e);
        }
    }

    private List<ToolCall> deserializeToolCalls(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, TOOL_CALLS_TYPE);
        } catch (Exception e) {
            throw new Failure(Failure.ErrorCode.STORAGE_ERROR, "反序列化 toolCalls 失败: " + e.getMessage(), e);
        }
    }
}
