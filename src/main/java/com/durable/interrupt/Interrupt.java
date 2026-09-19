package com.durable.interrupt;

import java.time.Instant;
import java.util.Objects;

/**
 * 一个挂起的中断点：Agent 走到高风险操作前停下，等人工放行。
 *
 * question 保存的是「要审批什么」 —— 实际系统里应包含操作、目标、参数。
 * 注意：审批必须绑定参数快照，否则审批通过后 Agent 可以改参数再执行（TOCTOU）。
 * 参数快照机制在计划 4 补上。
 */
public record Interrupt(String workflowId, int stepNo, InterruptStatus status,
                        String consumedBy, String question, Instant createdAt) {

    public Interrupt {
        Objects.requireNonNull(workflowId, "workflowId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(question, "question");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public boolean isParked() {
        return status == InterruptStatus.PARKED;
    }
}
