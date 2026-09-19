package com.durable.effect;

import com.durable.json.Json;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 效果账本：保证非幂等副作用【至多触发一次】。
 *
 * 做法是 claim-then-execute：
 *   1. 在事务里先 INSERT 一行 CLAIMED，抢占 (workflow_id, step_no)
 *      —— 主键唯一索引保证同一位置只有一个赢家
 *   2. 在【同一个事务、同一个连接】里执行副作用本身
 *   3. 把账本更新为 DONE 并写入结果，然后提交
 *
 * 任何一步失败都回滚，位置重新可用，不会留下「占了坑但没干活」的残局。
 *
 * 为什么必须同事务：如果先执行副作用、再单独写账本，崩溃落在两者之间就会出现
 * 「副作用已发生、账本没记录」→ 恢复时重复执行。
 * 这正是计划 1 用刻画测试固化下来的那个缺陷。
 *
 * 并发行为（InnoDB）：若 T1 已抢占但未提交，T2 的 INSERT 会在唯一索引上阻塞，
 * 直到 T1 提交（T2 拿到重复键错误，读到 DONE 复用结果）或回滚（T2 抢占成功）。
 * 也就是说，拿到重复键错误时，对方事务必然已经有结论了 —— 这是本实现正确的前提。
 */
public class EffectLedger {

    private static final String SQL_CLAIM =
            "INSERT INTO effect_ledger (workflow_id, step_no, tool_name, status, result, created_at) "
                    + "VALUES (?, ?, ?, 'CLAIMED', NULL, ?)";

    private static final String SQL_COMPLETE =
            "UPDATE effect_ledger SET status = 'DONE', result = ? "
                    + "WHERE workflow_id = ? AND step_no = ?";

    private static final String SQL_FIND =
            "SELECT result FROM effect_ledger "
                    + "WHERE workflow_id = ? AND step_no = ? AND status = 'DONE'";

    private static final String SQL_COUNT =
            "SELECT COUNT(*) FROM effect_ledger WHERE workflow_id = ? AND status = 'DONE'";

    private static final int ER_DUP_ENTRY = 1062;

    private final DataSource dataSource;

    public EffectLedger(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /**
     * 至多执行一次。
     *
     * @return executed=true 表示本次真的触发了副作用；false 表示命中账本、复用了旧结果
     */
    public EffectOutcome executeOnce(String workflowId, int stepNo, String toolName,
                                     TransactionalEffect effect) {
        Objects.requireNonNull(workflowId, "workflowId");
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(effect, "effect");

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                if (!claim(conn, workflowId, stepNo, toolName)) {
                    return reuseExisting(conn, workflowId, stepNo);
                }
                String result = effect.run(conn);
                complete(conn, workflowId, stepNo, result);
                conn.commit();
                return EffectOutcome.executed(result);
            } catch (SQLException e) {
                rollbackQuietly(conn);
                throw new IllegalStateException(
                        "受保护副作用执行失败并已回滚: " + workflowId + "/" + stepNo, e);
            } catch (RuntimeException e) {
                rollbackQuietly(conn);
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("获取数据库连接失败", e);
        }
    }

    /**
     * 执行一个**不需要账本保护**的步骤（只读或天然幂等）。
     *
     * 之所以放在这里而不是让执行器自己拿 DataSource：
     * 连接的生命周期管理只应有一处，否则很容易泄漏。
     */
    public String runUnprotected(TransactionalEffect effect) {
        Objects.requireNonNull(effect, "effect");
        try (Connection conn = dataSource.getConnection()) {
            return effect.run(conn);
        } catch (SQLException e) {
            throw new IllegalStateException("无保护步骤执行失败", e);
        }
    }

    /** 返回 false 表示位置已被占用（重复键）。 */
    private boolean claim(Connection conn, String workflowId, int stepNo, String toolName)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL_CLAIM)) {
            ps.setString(1, workflowId);
            ps.setInt(2, stepNo);
            ps.setString(3, toolName);
            ps.setTimestamp(4, Timestamp.from(Instant.now()));
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            if (e.getErrorCode() == ER_DUP_ENTRY) {
                return false;
            }
            throw e;
        }
    }

    private EffectOutcome reuseExisting(Connection conn, String workflowId, int stepNo)
            throws SQLException {
        Optional<String> existing = readResult(conn, workflowId, stepNo);
        conn.rollback();
        if (existing.isEmpty()) {
            throw new IllegalStateException(
                    "位置已被占用但结果尚未落库，说明存在未提交的并发事务: "
                            + workflowId + "/" + stepNo);
        }
        return EffectOutcome.reused(existing.get());
    }

    private void complete(Connection conn, String workflowId, int stepNo, String result)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL_COMPLETE)) {
            ps.setString(1, Json.write(result));
            ps.setString(2, workflowId);
            ps.setInt(3, stepNo);
            ps.executeUpdate();
        }
    }

    private Optional<String> readResult(Connection conn, String workflowId, int stepNo)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL_FIND)) {
            ps.setString(1, workflowId);
            ps.setInt(2, stepNo);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? Optional.of(Json.read(rs.getString(1), String.class))
                        : Optional.empty();
            }
        }
    }

    /** 读取某步已记录的结果（若有）。 */
    public Optional<String> findResult(String workflowId, int stepNo) {
        try (Connection conn = dataSource.getConnection()) {
            return readResult(conn, workflowId, stepNo);
        } catch (SQLException e) {
            throw new IllegalStateException("读取账本失败: " + workflowId + "/" + stepNo, e);
        }
    }

    /** 某工作流已真正触发的副作用次数。EO 性质的核心观测指标。 */
    public long countExecuted(String workflowId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_COUNT)) {
            ps.setString(1, workflowId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("统计账本失败: " + workflowId, e);
        }
    }

    private static void rollbackQuietly(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException ignored) {
            // 回滚失败无法补救，交由外层异常表达
        }
    }
}
