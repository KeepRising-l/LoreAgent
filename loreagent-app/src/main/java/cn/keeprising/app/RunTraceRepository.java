package cn.keeprising.app;

import cn.keeprising.core.Failure;
import cn.keeprising.spi.StreamChunk;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 运行轨迹仓储：记录每次问答的召回片段、是否降级、结束原因与用量，支持随会话或文档级联清理。
 */
@Repository
public class RunTraceRepository {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;

    @Autowired(required = false)
    public RunTraceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // 供纯单测假实现继承使用
    protected RunTraceRepository() {
        this.jdbc = null;
    }

    public void save(
            String userId,
            UUID conversationId,
            String requestId,
            String query,
            List<RetrievedChunkTrace> retrievedChunks,
            boolean degraded,
            String finishReason,
            StreamChunk.Usage usage) {
        String chunksJson = toJson(retrievedChunks == null ? List.of() : retrievedChunks);
        String usageJson = usage == null ? null : toJson(usage);
        try {
            jdbc.update(
                    """
                    insert into run_trace (
                        conversation_id, request_id, user_id, query, retrieved_chunks, degraded, finish_reason, usage
                    )
                    values (?, ?, ?, ?, cast(? as jsonb), ?, ?, cast(? as jsonb))
                    """,
                    conversationId, requestId, userId, query == null ? "" : query,
                    chunksJson, degraded, finishReason, usageJson);
        } catch (DataAccessException e) {
            throw new Failure(Failure.ErrorCode.STORAGE_ERROR, "写入运行轨迹失败: " + e.getMessage(), e);
        }
    }

    public int deleteByDocumentId(String userId, UUID documentId) {
        String markerJson = toJson(List.of(Map.of("documentId", documentId.toString())));
        try {
            return jdbc.update(
                    "delete from run_trace where user_id = ? and retrieved_chunks @> cast(? as jsonb)",
                    userId, markerJson);
        } catch (DataAccessException e) {
            throw new Failure(Failure.ErrorCode.STORAGE_ERROR, "级联删除运行轨迹失败: " + e.getMessage(), e);
        }
    }

    private String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new Failure(Failure.ErrorCode.INTERNAL_ERROR, "序列化运行轨迹失败: " + e.getMessage(), e);
        }
    }

    public record RetrievedChunkTrace(
            UUID documentId, long chunkId, String docTitle, double score, double vectorScore, double keywordScore) {
    }
}
