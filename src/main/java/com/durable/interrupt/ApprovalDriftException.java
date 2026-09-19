package com.durable.interrupt;

/**
 * 审批参数漂移：审批通过后、真正执行前，参数发生了变化。
 *
 * 这是一个 TOCTOU（Time-of-Check to Time-of-Use）漏洞：
 *   1. Agent 请求「删除测试机 test-1」→ 人工审批通过
 *   2. 执行前，Agent（或被注入的指令）把目标改成「删除生产机 prod-1」
 *   3. 执行 → 审批的是一个操作，执行的是另一个
 *
 * 修法是把审批**绑定到参数快照**：park 时记录参数指纹，
 * 执行前重新计算并比对，不一致就拒绝执行。
 *
 * 腾讯云 2026-08 那篇《审批通过后 Agent 改了参数怎么办？》把这叫做
 * 记录 {@code tool_args_drift} 并拒绝执行。审批真正批准的，
 * 「不应该是一个模糊的『可以执行』，而应该是某个可信主体在某个任务中提出的那一次精确行动」。
 */
public class ApprovalDriftException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String expectedHash;
    private final String actualHash;

    public ApprovalDriftException(String message, String expectedHash, String actualHash) {
        super(message);
        this.expectedHash = expectedHash;
        this.actualHash = actualHash;
    }

    /** 审批时绑定的参数指纹。 */
    public String expectedHash() {
        return expectedHash;
    }

    /** 执行时实际参数的指纹。 */
    public String actualHash() {
        return actualHash;
    }
}
