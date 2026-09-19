package com.durable.journal.mysql;

import com.durable.journal.JournalEntry;
import com.durable.journal.JournalEntryType;
import com.durable.journal.JournalStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 基于 MySQL 的日志存储。
 *
 * 唯一性由主键 (workflow_id, step_no, type) 保证。
 *
 * 关键点：append() 【不】先查再写 —— 那是典型的竞态写法，两个并发请求会同时通过检查。
 * 我们直接 INSERT，让数据库的唯一约束拒绝重复。这是唯一可靠的做法，
 * 也是「唯一索引而不是应用层判断」这个面试考点的由来。
 */
public class MySqlJournalStore implements JournalStore {

    /** MySQL ER_DUP_ENTRY。 */
    private static final int ER_DUP_ENTRY = 1062;

    private static final String SQL_INSERT =
            "INSERT INTO journal (workflow_id, step_no, type, payload, created_at) VALUES (?, ?, ?, ?, ?)";

    private static final String COLUMNS =
            "SELECT workflow_id, step_no, type, payload, created_at FROM journal";

    private static final String SQL_LOAD_ALL =
            COLUMNS + " WHERE workflow_id = ? ORDER BY step_no ASC, type ASC";

    private static final String SQL_FIND_ONE =
            COLUMNS + " WHERE workflow_id = ? AND step_no = ? AND type = ?";

    private static final String SQL_EXISTS =
            "SELECT COUNT(*) FROM journal WHERE workflow_id = ? AND step_no = ? AND type = ?";

    private final DataSource dataSource;

    public MySqlJournalStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public void append(JournalEntry entry) {
        Objects.requireNonNull(entry, "entry");
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_INSERT)) {
            ps.setString(1, entry.workflowId());
            ps.setInt(2, entry.stepNo());
            ps.setString(3, entry.type().name());
            ps.setString(4, entry.payload());
            ps.setTimestamp(5, Timestamp.from(entry.createdAt()));
            ps.executeUpdate();
        } catch (SQLException e) {
            if (isDuplicateKey(e)) {
                throw new DuplicateJournalEntryException(
                        "日志位置已被占用: workflowId=" + entry.workflowId()
                                + ", stepNo=" + entry.stepNo() + ", type=" + entry.type(), e);
            }
            throw new IllegalStateException("写入日志失败: " + entry, e);
        }
    }

    @Override
    public List<JournalEntry> load(String workflowId) {
        Objects.requireNonNull(workflowId, "workflowId");
        List<JournalEntry> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_LOAD_ALL)) {
            ps.setString(1, workflowId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取日志失败: workflowId=" + workflowId, e);
        }
        return result;
    }

    @Override
    public Optional<JournalEntry> find(String workflowId, int stepNo, JournalEntryType type) {
        Objects.requireNonNull(workflowId, "workflowId");
        Objects.requireNonNull(type, "type");
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_FIND_ONE)) {
            ps.setString(1, workflowId);
            ps.setInt(2, stepNo);
            ps.setString(3, type.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查找日志失败: " + workflowId + "/" + stepNo + "/" + type, e);
        }
    }

    @Override
    public boolean exists(String workflowId, int stepNo, JournalEntryType type) {
        Objects.requireNonNull(workflowId, "workflowId");
        Objects.requireNonNull(type, "type");
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_EXISTS)) {
            ps.setString(1, workflowId);
            ps.setInt(2, stepNo);
            ps.setString(3, type.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getLong(1) > 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("判断日志是否存在失败: " + workflowId + "/" + stepNo, e);
        }
    }

    private static boolean isDuplicateKey(SQLException e) {
        return e.getErrorCode() == ER_DUP_ENTRY || "23000".equals(e.getSQLState());
    }

    private static JournalEntry mapRow(ResultSet rs) throws SQLException {
        return new JournalEntry(
                rs.getString("workflow_id"),
                rs.getInt("step_no"),
                JournalEntryType.valueOf(rs.getString("type")),
                rs.getString("payload"),
                rs.getTimestamp("created_at").toInstant());
    }
}
