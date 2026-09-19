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
