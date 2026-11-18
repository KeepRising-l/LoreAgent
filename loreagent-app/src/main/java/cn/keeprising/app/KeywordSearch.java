package cn.keeprising.app;

import cn.keeprising.core.Failure;
import cn.keeprising.core.RrfFusion;
import cn.keeprising.core.Tokenizer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 中文关键词检索器：与入库共用 Tokenizer，把查询切成二元组与英文词并过滤停用词后用 | 拼成 to_tsquery('simple', ...)，
 * 严格避免 plainto_tsquery 将长问句全部用 AND 连接导致的零命中坑，同样在 SQL WHERE 中前置完成作用域过滤。
 */
@Component
public class KeywordSearch {

    private final JdbcTemplate jdbc;
    private final Tokenizer tokenizer;

    @Autowired(required = false)
    public KeywordSearch(JdbcTemplate jdbc, Tokenizer tokenizer) {
        this.jdbc = jdbc;
        this.tokenizer = tokenizer;
    }

    // 供纯单测假实现或测试Profile跳过JdbcTemplate时实例化
    protected KeywordSearch() {
        this.jdbc = null;
        this.tokenizer = null;
    }

    protected KeywordSearch(Tokenizer tokenizer) {
        this.jdbc = null;
        this.tokenizer = tokenizer;
    }

    public List<RrfFusion.ScoredChunk> search(
            String userId,
            String query,
            String ownerFilter,
            List<String> tagFilters,
            int limit) {
        if (limit <= 0) {
            return List.of();
        }
        String tsQuery = tokenizer.toOrTsQuery(query);
        if (tsQuery.isBlank()) {
            return List.of();
        }

        StringBuilder sql = new StringBuilder("""
                select c.id, c.document_id, d.title, c.heading, c.content, c.chunk_index,
                       ts_rank(to_tsvector('simple', c.tokens), q) as kw_score
                from chunks c
                join document d on c.document_id = d.id,
                     to_tsquery('simple', ?) q
                where c.user_id = ?
                  and d.user_id = ?
                  and c.version = d.active_version
                  and to_tsvector('simple', c.tokens) @@ q
                """);
        List<Object> args = new ArrayList<>();
        args.add(tsQuery);
        args.add(userId);
        args.add(userId);

        if (ownerFilter != null && !ownerFilter.isBlank()) {
            sql.append(" and d.user_id = ?");
            args.add(ownerFilter.trim());
        }
        if (tagFilters != null && !tagFilters.isEmpty()) {
            sql.append(" and d.tags && ?");
            args.add(DocumentRepository.textArray(tagFilters));
        }

        sql.append(" order by kw_score desc, c.id asc limit ?");
        args.add(limit);

        try {
            return jdbc.query(sql.toString(), (rs, rowNum) -> {
                double score = rs.getDouble("kw_score");
                return new RrfFusion.ScoredChunk(
                        rs.getLong("id"),
                        rs.getObject("document_id", UUID.class),
                        rs.getString("title"),
                        rs.getString("heading"),
                        rs.getString("content"),
                        rs.getInt("chunk_index"),
                        score,
                        0.0,
                        score);
            }, args.toArray());
        } catch (DataAccessException e) {
            throw new Failure(Failure.ErrorCode.STORAGE_ERROR, "关键词检索查询失败: " + e.getMessage(), e);
        }
    }
}
