package cn.keeprising.app;

import cn.keeprising.core.EmbeddingClient;
import cn.keeprising.core.Failure;
import cn.keeprising.core.RrfFusion;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 向量检索器：通过 pgvector 余弦距离 (<=>) 检索。
 * 严格在 SQL WHERE 阶段（LIMIT 截断之前）完成 user_id、owner、tags 与有效版本过滤，防止召回后再过滤导致跨区或空结果。
 */
@Component
public class VectorSearch {

    private final JdbcTemplate jdbc;

    @Autowired(required = false)
    public VectorSearch(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // 供纯单测假实现继承使用
    protected VectorSearch() {
        this.jdbc = null;
    }

    public List<RrfFusion.ScoredChunk> search(
            String userId,
            float[] queryVector,
            String embeddingModel,
            String ownerFilter,
            List<String> tagFilters,
            int limit) {
        if (queryVector == null || queryVector.length == 0 || limit <= 0) {
            return List.of();
        }
        String vecLiteral = EmbeddingClient.toVectorLiteral(queryVector);
        StringBuilder sql = new StringBuilder("""
                select c.id, c.document_id, d.title, c.heading, c.content, c.chunk_index,
                       (1.0 - (c.embedding <=> cast(? as vector))) as vec_score
                from chunks c
                join document d on c.document_id = d.id
                where c.user_id = ?
                  and d.user_id = ?
                  and c.version = d.active_version
                  and c.embedding is not null
                  and c.embedding_model = ?
                  and c.dim = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(vecLiteral);
        args.add(userId);
        args.add(userId);
        args.add(embeddingModel);
        args.add(queryVector.length);

        if (ownerFilter != null && !ownerFilter.isBlank()) {
            sql.append(" and d.user_id = ?");
            args.add(ownerFilter.trim());
        }
        if (tagFilters != null && !tagFilters.isEmpty()) {
            sql.append(" and d.tags && ?");
            args.add(DocumentRepository.textArray(tagFilters));
        }

        sql.append(" order by c.embedding <=> cast(? as vector) asc, c.id asc limit ?");
        args.add(vecLiteral);
        args.add(limit);

        try {
            return jdbc.query(sql.toString(), (rs, rowNum) -> {
                double score = rs.getDouble("vec_score");
                return new RrfFusion.ScoredChunk(
                        rs.getLong("id"),
                        rs.getObject("document_id", UUID.class),
                        rs.getString("title"),
                        rs.getString("heading"),
                        rs.getString("content"),
                        rs.getInt("chunk_index"),
                        score,
                        score,
                        0.0);
            }, args.toArray());
        } catch (DataAccessException e) {
            throw new Failure(Failure.ErrorCode.STORAGE_ERROR, "向量检索查询失败: " + e.getMessage(), e);
        }
    }
}
