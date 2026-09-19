-- 决策日志表：只追加，不修改，不删除。
--
-- 主键 (workflow_id, step_no, type) 同时承担两个职责：
--   1. 唯一定位一条日志
--   2. 靠数据库唯一约束阻止重复写入 —— 这是 exactly-once 的最终防线
--
-- payload 用 JSON 类型而非 TEXT：让数据库校验它确实是合法 JSON。
CREATE TABLE IF NOT EXISTS journal (
    workflow_id VARCHAR(64)  NOT NULL,
    step_no     INT          NOT NULL,
    type        VARCHAR(32)  NOT NULL,
    payload     JSON         NOT NULL,
    created_at  TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (workflow_id, step_no, type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 效果账本：记录非幂等副作用是否已经触发过。
--
-- 主键 (workflow_id, step_no) 就是 claim 的抢占点：
-- 唯一索引保证同一个位置只有一个赢家，这是 EO 性质的落点。
-- status: CLAIMED（已抢占，副作用进行中） / DONE（副作用已完成，result 有值）
CREATE TABLE IF NOT EXISTS effect_ledger (
    workflow_id VARCHAR(64)  NOT NULL,
    step_no     INT          NOT NULL,
    tool_name   VARCHAR(128) NOT NULL,
    status      VARCHAR(16)  NOT NULL,
    result      JSON         NULL,
    created_at  TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (workflow_id, step_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 检查点：恢复用的状态快照。写入前必须通过 schema 校验（CV 性质）。
-- 不变量：results 的数量必须等于 frontier_step + 1。
CREATE TABLE IF NOT EXISTS checkpoint (
    workflow_id    VARCHAR(64)  NOT NULL,
    version        INT          NOT NULL,
    frontier_step  INT          NOT NULL,
    schema_version VARCHAR(16)  NOT NULL,
    state          JSON         NOT NULL,
    created_at     TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (workflow_id, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 外壳：被 Agent 操作的资源。创建主机是花钱且不可逆的副作用。
CREATE TABLE IF NOT EXISTS host (
    id         VARCHAR(64)  NOT NULL,
    name       VARCHAR(128) NOT NULL,
    status     VARCHAR(32)  NOT NULL,
    created_by VARCHAR(64)  NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 人工审批的中断点。一个 (workflow_id, step_no) 只能有一个中断。
-- status='PARKED' 等待审批；status='CONSUMED' 已被某次 resume 消费。
-- CO-c 的落点：抢占用的是条件更新 UPDATE ... WHERE status='PARKED'。
--
-- args_hash 是审批绑定的【参数快照指纹】：park 时算一次，执行前再算一次比对。
-- 不一致说明审批通过后参数被改过（TOCTOU），必须拒绝执行。
CREATE TABLE IF NOT EXISTS interrupt (
    workflow_id VARCHAR(64)  NOT NULL,
    step_no     INT          NOT NULL,
    status      VARCHAR(16)  NOT NULL,
    consumed_by VARCHAR(64)  NULL,
    tool_name   VARCHAR(128) NOT NULL,
    args        JSON         NOT NULL,
    args_hash   CHAR(64)     NOT NULL,
    created_at  TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (workflow_id, step_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 每一次 resume 尝试都留痕（含被惰性拒绝的）。
-- 论文指出：只做幂等的话副作用计数是对的，但「审批轨迹」会是错的 ——
-- 所以被拒绝的投递也必须记录，否则审计不成立。
CREATE TABLE IF NOT EXISTS resume_attempt (
    workflow_id VARCHAR(64)  NOT NULL,
    step_no     INT          NOT NULL,
    resume_id   VARCHAR(64)  NOT NULL,
    fork_intent TINYINT(1)   NOT NULL,
    branch_id   VARCHAR(64)  NULL,
    value       JSON         NOT NULL,
    outcome     VARCHAR(16)  NOT NULL,
    created_at  TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (workflow_id, step_no, resume_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 分叉分支的产出。同一 branchId 重复投递复用同一产出（FD 的确定性）。
CREATE TABLE IF NOT EXISTS branch (
    workflow_id VARCHAR(64)  NOT NULL,
    step_no     INT          NOT NULL,
    branch_id   VARCHAR(64)  NOT NULL,
    outcome     JSON         NOT NULL,
    created_at  TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (workflow_id, step_no, branch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
