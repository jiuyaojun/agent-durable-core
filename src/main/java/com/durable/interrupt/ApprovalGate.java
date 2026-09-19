package com.durable.interrupt;

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
import java.util.function.Function;

/**
 * 审批闸门：高风险操作在执行前必须经过人工放行。
 *
 * 核心是 CO-c 的实现 —— 用【数据库条件更新】抢占中断：
 *
 *   UPDATE interrupt SET status='CONSUMED', consumed_by=?
 *   WHERE workflow_id=? AND step_no=? AND status='PARKED'
 *
 * 影响行数为 1 ⇒ 抢到了；为 0 ⇒ 已被别人消费。
 * **判断和执行是同一条 SQL**，所以跨线程、跨进程、跨主机都成立。
 *
 * 为什么不用「先 SELECT 查状态，再 UPDATE」：那是 TOCTOU 竞态。
 * 论文实测 k 个进程同时恢复一个挂起中断，会让被门控的副作用执行 k 次
 * （40 格中 36 格饱和度为 1.0，且故障跨主机）。本类就是为了不落入那个格子。
 */
public class ApprovalGate {

    private static final int ER_DUP_ENTRY = 1062;

    private static final String SQL_PARK =
            "INSERT INTO interrupt (workflow_id, step_no, status, consumed_by, question, created_at) "
                    + "VALUES (?, ?, 'PARKED', NULL, ?, ?)";

    private static final String SQL_SELECT =
            "SELECT workflow_id, step_no, status, consumed_by, question, created_at "
                    + "FROM interrupt WHERE workflow_id = ? AND step_no = ?";

    private static final String SQL_CLAIM =
            "UPDATE interrupt SET status = 'CONSUMED', consumed_by = ? "
                    + "WHERE workflow_id = ? AND step_no = ? AND status = 'PARKED'";

    private static final String SQL_INSERT_ATTEMPT =
            "INSERT INTO resume_attempt (workflow_id, step_no, resume_id, fork_intent, "
                    + "branch_id, value, outcome, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String SQL_INSERT_BRANCH =
            "INSERT INTO branch (workflow_id, step_no, branch_id, outcome, created_at) "
                    + "VALUES (?, ?, ?, ?, ?)";

    private static final String SQL_FIND_BRANCH =
            "SELECT outcome FROM branch WHERE workflow_id = ? AND step_no = ? AND branch_id = ?";

    private static final String SQL_COUNT_BRANCH =
            "SELECT COUNT(*) FROM branch WHERE workflow_id = ? AND step_no = ?";

    private static final String SQL_COUNT_CONSUMED =
            "SELECT COUNT(*) FROM interrupt WHERE workflow_id = ? AND step_no = ? AND status = 'CONSUMED'";

    private static final String SQL_COUNT_ATTEMPT =
            "SELECT COUNT(*) FROM resume_attempt WHERE workflow_id = ? AND step_no = ?";

    private final DataSource dataSource;

    public ApprovalGate(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /** 挂起一个中断点，等待人工审批。 */
    public Interrupt park(String workflowId, int stepNo, String question) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_PARK)) {
            ps.setString(1, workflowId);
            ps.setInt(2, stepNo);
            ps.setString(3, question);
            ps.setTimestamp(4, Timestamp.from(Instant.now()));
            ps.executeUpdate();
        } catch (SQLException e) {
            if (e.getErrorCode() == ER_DUP_ENTRY) {
                throw new IllegalStateException("该中断点已存在: " + workflowId + "/" + stepNo, e);
            }
            throw new IllegalStateException("挂起中断失败", e);
        }
        return find(workflowId, stepNo).orElseThrow();
    }

    public Optional<Interrupt> find(String workflowId, int stepNo) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_SELECT)) {
            ps.setString(1, workflowId);
            ps.setInt(2, stepNo);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Interrupt(
                        rs.getString("workflow_id"),
                        rs.getInt("step_no"),
                        InterruptStatus.valueOf(rs.getString("status")),
                        rs.getString("consumed_by"),
                        rs.getString("question"),
                        rs.getTimestamp("created_at").toInstant()));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取中断失败", e);
        }
    }

    /**
     * 审批一次中断。
     *
     * @param branchDecision 分支决策函数 f —— 把审批值映射成分支产出。
     *                       论文要求：f 单射时 v 不同则产出不同。
     */
    public ResumeOutcome resume(ResumeCommand command, Function<String, String> branchDecision) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(branchDecision, "branchDecision");

        if (command.forkIntent()) {
            return fork(command, branchDecision);
        }
        return consume(command);
    }

    /** 无分叉意图：走 CO 路径。 */
    private ResumeOutcome consume(ResumeCommand command) {
        boolean claimed = claimInterrupt(command.workflowId(), command.stepNo(), command.resumeId());
        ResumeOutcome outcome = claimed
                ? ResumeOutcome.consumed(command.value())
                : ResumeOutcome.inert();
        recordAttempt(command, outcome.kind());
        return outcome;
    }

    /** 有分叉意图：走 FD 路径。同一 branchId 重复投递复用已有产出。 */
    private ResumeOutcome fork(ResumeCommand command, Function<String, String> branchDecision) {
        Optional<String> existing = findBranchOutcome(
                command.workflowId(), command.stepNo(), command.branchId());
        if (existing.isPresent()) {
            ResumeOutcome outcome = ResumeOutcome.replayed(command.branchId(), existing.get());
            recordAttempt(command, outcome.kind());
            return outcome;
        }

        String produced = branchDecision.apply(command.value());
        if (!insertBranch(command, produced)) {
            String winner = findBranchOutcome(
                    command.workflowId(), command.stepNo(), command.branchId()).orElse(produced);
            ResumeOutcome outcome = ResumeOutcome.replayed(command.branchId(), winner);
            recordAttempt(command, outcome.kind());
            return outcome;
        }

        ResumeOutcome outcome = ResumeOutcome.forked(command.branchId(), produced);
        recordAttempt(command, outcome.kind());
        return outcome;
    }

    /** CAS 抢占。返回 true 表示本次抢到了。 */
    private boolean claimInterrupt(String workflowId, int stepNo, String resumeId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_CLAIM)) {
            ps.setString(1, resumeId);
            ps.setString(2, workflowId);
            ps.setInt(3, stepNo);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("抢占中断失败", e);
        }
    }

    private boolean insertBranch(ResumeCommand command, String outcome) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_INSERT_BRANCH)) {
            ps.setString(1, command.workflowId());
            ps.setInt(2, command.stepNo());
            ps.setString(3, command.branchId());
            ps.setString(4, Json.write(outcome));
            ps.setTimestamp(5, Timestamp.from(Instant.now()));
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            if (e.getErrorCode() == ER_DUP_ENTRY) {
                return false;
            }
            throw new IllegalStateException("写入分支失败", e);
        }
    }

    private Optional<String> findBranchOutcome(String workflowId, int stepNo, String branchId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_FIND_BRANCH)) {
            ps.setString(1, workflowId);
            ps.setInt(2, stepNo);
            ps.setString(3, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? Optional.of(Json.read(rs.getString(1), String.class))
                        : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取分支失败", e);
        }
    }

    /** 记录每一次审批投递，包含被惰性拒绝的 —— 否则审批轨迹是错的。 */
    private void recordAttempt(ResumeCommand command, ResumeKind kind) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_INSERT_ATTEMPT)) {
            ps.setString(1, command.workflowId());
            ps.setInt(2, command.stepNo());
            ps.setString(3, command.resumeId());
            ps.setBoolean(4, command.forkIntent());
            ps.setString(5, command.branchId());
            ps.setString(6, Json.write(command.value()));
            ps.setString(7, kind.name());
            ps.setTimestamp(8, Timestamp.from(Instant.now()));
            ps.executeUpdate();
        } catch (SQLException e) {
            if (e.getErrorCode() != ER_DUP_ENTRY) {
                throw new IllegalStateException("记录审批尝试失败", e);
            }
        }
    }

    /** 已开出的分支数（FD 的观测点）。 */
    public long forkCount(String workflowId, int stepNo) {
        return count(SQL_COUNT_BRANCH, workflowId, stepNo);
    }

    /** 该中断被消费的次数。正常恒为 0 或 1（CO-c 的观测点）。 */
    public long consumedCount(String workflowId, int stepNo) {
        return count(SQL_COUNT_CONSUMED, workflowId, stepNo);
    }

    /** 收到的审批投递次数（含被拒绝的）—— 审计轨迹的观测点。 */
    public long attemptCount(String workflowId, int stepNo) {
        return count(SQL_COUNT_ATTEMPT, workflowId, stepNo);
    }

    private long count(String sql, String workflowId, int stepNo) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workflowId);
            ps.setInt(2, stepNo);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("统计失败", e);
        }
    }
}
