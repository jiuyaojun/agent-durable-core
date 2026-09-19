package com.durable.journal;

/** 日志条目类型。 */
public enum JournalEntryType {
    /**
     * LLM 做出的决策：选了哪个工具、参数是什么。
     * 恢复时读它，而不是重新调用 LLM —— 这是保证重放确定性的关键。
     */
    LLM_DECISION,

    /** 一个执行步骤的结果（`(workflow_id, step_no, STEP_RESULT)` 唯一）。 */
    STEP_RESULT,

    /** 状态检查点。 */
    CHECKPOINT
}
