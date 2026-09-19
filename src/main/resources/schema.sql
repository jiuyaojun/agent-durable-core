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
