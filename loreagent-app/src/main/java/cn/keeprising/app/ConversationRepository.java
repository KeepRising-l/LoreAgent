package cn.keeprising.app;

import cn.keeprising.core.Failure;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 会话表 JdbcTemplate 仓储：所有方法首参均为 userId，并在 SQL 中强制按 user_id 过滤。
 */
@Repository
public class ConversationRepository {

    private static final RowMapper<Conversation> ROW_MAPPER = (rs, rowNum) -> new Conversation(
            rs.getObject("id", UUID.class),
            rs.getString("user_id"),
            rs.getString("title"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private final JdbcTemplate jdbc;

    @Autowired(required = false)
    public ConversationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // 供纯单测假实现继承使用的无参构造，避免单测依赖数据库
    protected ConversationRepository() {
        this.jdbc = null;
    }

    public Conversation create(String userId, UUID id, String title) {
        try {
            return jdbc.queryForObject(
                    """
                    insert into conversation (id, user_id, title)
                    values (?, ?, ?)
                    returning id, user_id, title, created_at, updated_at
                    """,
                    ROW_MAPPER,
                    id, userId, title);
        } catch (DataAccessException e) {
            throw new Failure(Failure.ErrorCode.STORAGE_ERROR, "创建会话落库失败: " + e.getMessage(), e);
        }
    }

    public Optional<Conversation> findById(String userId, UUID id) {
        try {
            List<Conversation> rows = jdbc.query(
                    """
                    select id, user_id, title, created_at, updated_at
                    from conversation
                    where user_id = ? and id = ?
                    """,
                    ROW_MAPPER,
                    userId, id);
            return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
        } catch (DataAccessException e) {
            throw new Failure(Failure.ErrorCode.STORAGE_ERROR, "查询会话失败: " + e.getMessage(), e);
        }
    }

    public List<Conversation> list(String userId) {
        try {
            return jdbc.query(
                    """
                    select id, user_id, title, created_at, updated_at
                    from conversation
                    where user_id = ?
                    order by updated_at desc, id desc
                    """,
                    ROW_MAPPER,
                    userId);
        } catch (DataAccessException e) {
            throw new Failure(Failure.ErrorCode.STORAGE_ERROR, "查询会话列表失败: " + e.getMessage(), e);
        }
    }

    public boolean rename(String userId, UUID id, String title) {
        try {
            int updated = jdbc.update(
                    """
                    update conversation
                    set title = ?, updated_at = now()
                    where user_id = ? and id = ?
                    """,
                    title, userId, id);
            return updated > 0;
        } catch (DataAccessException e) {
            throw new Failure(Failure.ErrorCode.STORAGE_ERROR, "重命名会话失败: " + e.getMessage(), e);
        }
    }

    public void touch(String userId, UUID id) {
        try {
            jdbc.update(
                    "update conversation set updated_at = now() where user_id = ? and id = ?",
                    userId, id);
        } catch (DataAccessException e) {
            throw new Failure(Failure.ErrorCode.STORAGE_ERROR, "更新会话活跃时间失败: " + e.getMessage(), e);
        }
    }

    public boolean delete(String userId, UUID id) {
        try {
            int deleted = jdbc.update(
                    "delete from conversation where user_id = ? and id = ?",
                    userId, id);
            return deleted > 0;
        } catch (DataAccessException e) {
            throw new Failure(Failure.ErrorCode.STORAGE_ERROR, "删除会话失败: " + e.getMessage(), e);
        }
    }
}
