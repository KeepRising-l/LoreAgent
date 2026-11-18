package cn.keeprising.app;

import cn.keeprising.core.Failure;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.util.List;
import java.util.UUID;

/**
 * 切片与向量表 JdbcTemplate 仓储：所有方法首参均为 userId，负责新版本切片批量写入与旧版本切片彻底清理。
 */
@Repository
public class ChunkRepository {

    private static final RowMapper<ChunkRow> ROW_MAPPER = (rs, rowNum) -> new ChunkRow(
            rs.getLong("id"),
            rs.getObject("document_id", UUID.class),
            rs.getString("user_id"),
            rs.getInt("version"),
            rs.getInt("chunk_index"),
            rs.getString("heading"),
            rs.getString("content"),
            rs.getString("tokens"),
            rs.getString("embedding_text"),
            rs.getString("embedding_model"),
            (Integer) rs.getObject("dim"));

    private final JdbcTemplate jdbc;

    @Autowired(required = false)
    public ChunkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // 供纯单测假实现继承使用
    protected ChunkRepository() {
        this.jdbc = null;
    }

    public void insertBatch(String userId, UUID documentId, int version, List<ChunkInsert> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        String sql = """
                insert into chunks (
                    document_id, user_id, version, chunk_index, heading, content, tokens, embedding, embedding_model, dim
                )
                values (?, ?, ?, ?, ?, ?, ?, cast(? as vector), ?, ?)
                """;
        try {
            jdbc.batchUpdate(sql, chunks, chunks.size(), (ps, c) -> {
                ps.setObject(1, documentId);
                ps.setString(2, userId);
                ps.setInt(3, version);
                ps.setInt(4, c.chunkIndex());
                ps.setString(5, c.heading() == null ? "" : c.heading());
                ps.setString(6, c.content());
                ps.setString(7, c.tokens() == null ? "" : c.tokens());
                if (c.vectorLiteral() == null) {
                    ps.setNull(8, Types.VARCHAR);
                    ps.setNull(9, Types.VARCHAR);
                    ps.setNull(10, Types.INTEGER);
                } else {
                    ps.setString(8, c.vectorLiteral());
                    ps.setString(9, c.embeddingModel());
                    ps.setInt(10, c.dim());
                }
            });
        } catch (DataAccessException e) {
            throw new Failure(Failure.ErrorCode.STORAGE_ERROR, "批量写入文档切片失败: " + e.getMessage(), e);
        }
    }

    public int deleteOldVersions(String userId, UUID documentId, int activeVersion) {
        try {
            return jdbc.update(
                    "delete from chunks where user_id = ? and document_id = ? and version < ?",
                    userId, documentId, activeVersion);
        } catch (DataAccessException e) {
            throw new Failure(Failure.ErrorCode.STORAGE_ERROR, "清理旧版本切片失败: " + e.getMessage(), e);
        }
    }

    public List<ChunkRow> listActiveByDocument(String userId, UUID documentId) {
        try {
            return jdbc.query(
                    """
                    select c.id, c.document_id, c.user_id, c.version, c.chunk_index,
                           c.heading, c.content, c.tokens, c.embedding::text as embedding_text,
                           c.embedding_model, c.dim
                    from chunks c
                    join document d on c.document_id = d.id
                    where c.user_id = ? and d.user_id = ? and c.document_id = ? and c.version = d.active_version
                    order by c.chunk_index asc
                    """,
                    ROW_MAPPER,
                    userId, userId, documentId);
        } catch (DataAccessException e) {
            throw new Failure(Failure.ErrorCode.STORAGE_ERROR, "查询文档切片失败: " + e.getMessage(), e);
        }
    }

    public record ChunkInsert(
            int chunkIndex, String heading, String content, String tokens,
            String vectorLiteral, String embeddingModel, Integer dim) {
    }

    public record ChunkRow(
            long id, UUID documentId, String userId, int version, int chunkIndex,
            String heading, String content, String tokens,
            String embeddingText, String embeddingModel, Integer dim) {
    }
}
