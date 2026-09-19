package com.durable.interrupt;

import java.time.Instant;
import java.util.Objects;

/**
 * 一个挂起的中断点：Agent 走到高风险操作前停下，等人工放行。
 *
 * 注意这里记录的是【精确的一次行动】，而不是一个模糊的「可以执行」：
 * toolName + args 描述要做什么，argsHash 是它们的指纹。
 * 执行前会重新算一次指纹比对，不一致就拒绝（防 TOCTOU 参数漂移）。
 */
public record Interrupt(String workflowId, int stepNo, InterruptStatus status,
                        String consumedBy, String toolName, String args, String argsHash,
                        Instant createdAt) {

    public Interrupt {
        Objects.requireNonNull(workflowId, "workflowId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(argsHash, "argsHash");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public boolean isParked() {
        return status == InterruptStatus.PARKED;
    }
}
