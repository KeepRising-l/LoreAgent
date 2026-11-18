package cn.keeprising.app;

import cn.keeprising.core.Failure;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.SqlValue;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 文档表 JdbcTemplate 仓储：所有方法首参均为 userId，SQL 强制按 user_id 过滤，支持同名覆盖版本升级。
 */
@Repository
public class DocumentRepository {

    private static final RowMapper<Document> ROW_MAPPER = (rs, rowNum) -> {
        Array arr = rs.getArray("tags");
        List<String> tags = arr == null ? List.of() : List.of((String[]) arr.getArray());
        Date date = rs.getDate("doc_date");
        return new Document(
                rs.getObject("id", UUID.class),
                rs.getString("user_id"),
                rs.getString("title"),
                tags,
                date == null ? null : date.toLocalDate(),
                rs.getInt("active_version"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    };

    private final JdbcTemplate jdbc;

    @Autowired(required = false)
    public DocumentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // 供纯单测假实现继承使用
    protected DocumentRepository() {
        this.jdbc = null;
    }

    // 按 (user_id, title) 原子写入或升级文档版本：重传同名文档时 active_version + 1
    public Document upsertVersion(String userId, UUID id, String title, List<String> tags, LocalDate docDate) {
        try {
            return jdbc.queryForObject(
                    """
                    insert into document (id, user_id, title, tags, doc_date, active_version)
                    values (?, ?, ?, ?, ?, 1)
                    on conflict (user_id, title) do update
                    set tags = excluded.tags,
                        doc_date = excluded.doc_date,
                        active_version = document.active_version + 1,
                        updated_at = now()
                    returning id, user_id, title, tags, doc_date, active_version, created_at, updated_at
                    """,
                    ROW_MAPPER,
                    id, userId, title, textArray(tags), docDate == null ? null : Date.valueOf(docDate));
        } catch (DataAccessException e) {
            throw new Failure(Failure.ErrorCode.STORAGE_ERROR, "写入文档元信息失败: " + e.getMessage(), e);
        }
    }

    public Optional<Document> findById(String userId, UUID id) {
        try {
            List<Document> rows = jdbc.query(
                    """
                    select id, user_id, title, tags, doc_date, active_version, created_at, updated_at
                    from document
                    where user_id = ? and id = ?
                    """,
                    ROW_MAPPER,
                    userId, id);
            return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
        } catch (DataAccessException e) {
            throw new Failure(Failure.ErrorCode.STORAGE_ERROR, "查询文档失败: " + e.getMessage(), e);
        }
    }

    public List<Document> list(String userId, String tagFilter) {
        String tag = (tagFilter == null || tagFilter.isBlank()) ? null : tagFilter.trim();
        try {
            return jdbc.query(
                    """
                    select id, user_id, title, tags, doc_date, active_version, created_at, updated_at
                    from document
                    where user_id = ? and (?::text is null or ?::text = any(tags))
                    order by updated_at desc, id desc
                    """,
                    ROW_MAPPER,
                    userId, tag, tag);
        } catch (DataAccessException e) {
            throw new Failure(Failure.ErrorCode.STORAGE_ERROR, "查询文档列表失败: " + e.getMessage(), e);
        }
    }

    public long count(String userId) {
        try {
            Long n = jdbc.queryForObject("select count(*) from document where user_id = ?", Long.class, userId);
            return n == null ? 0L : n;
        } catch (DataAccessException e) {
            throw new Failure(Failure.ErrorCode.STORAGE_ERROR, "统计文档数失败: " + e.getMessage(), e);
        }
    }

    public boolean delete(String userId, UUID id) {
        try {
            int deleted = jdbc.update("delete from document where user_id = ? and id = ?", userId, id);
            return deleted > 0;
        } catch (DataAccessException e) {
            throw new Failure(Failure.ErrorCode.STORAGE_ERROR, "删除文档失败: " + e.getMessage(), e);
        }
    }

    static SqlValue textArray(List<String> tags) {
        String[] arr = (tags == null || tags.isEmpty())
                ? new String[0]
                : tags.stream().filter(t -> t != null && !t.isBlank()).map(String::trim).distinct().toArray(String[]::new);
        return new SqlValue() {
            @Override
            public void setValue(PreparedStatement ps, int paramIndex) throws SQLException {
                ps.setArray(paramIndex, ps.getConnection().createArrayOf("text", Arrays.copyOf(arr, arr.length)));
            }

            @Override
            public void cleanup() {
            }
        };
    }
}
