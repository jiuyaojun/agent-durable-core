package com.durable.interrupt;

import java.util.Objects;

/**
 * 一次 resume（审批）请求。
 *
 * forkIntent 是论文 Definition 2 里的「branch discriminator」——
 * 它区分「这是一次全新的分支决策」还是「这只是上一次审批的重复投递」。
 *
 * 没有它，FD 和 CO 在同样的线缆流量上无法同时满足：
 *   - 一次重复投递，FD 要求把新值当成新分支，CO 要求它惰性。
 * 论文原话：「Without a discriminator the two are jointly unsatisfiable on identical traffic.」
 * 所以 FI（分叉意图可表达性）是协议义务，不是可选项。
 *
 * @param resumeId   本次请求的唯一标识，用于识别字节相同的重复投递
 * @param forkIntent 是否携带分叉意图
 * @param branchId   分叉判别符，forkIntent=true 时必填
 * @param value      审批人给出的值
 */
public record ResumeCommand(String workflowId, int stepNo, String resumeId,
                            boolean forkIntent, String branchId, String value) {

    public ResumeCommand {
        Objects.requireNonNull(workflowId, "workflowId");
        Objects.requireNonNull(resumeId, "resumeId");
        Objects.requireNonNull(value, "value");
        if (forkIntent && (branchId == null || branchId.isBlank())) {
            throw new IllegalArgumentException(
                    "带分叉意图的 resume 必须提供 branchId（FI：判别符必须能在 API 上表达）");
        }
    }

    /** 无分叉意图的普通审批。 */
    public static ResumeCommand approve(String workflowId, int stepNo, String resumeId, String value) {
        return new ResumeCommand(workflowId, stepNo, resumeId, false, null, value);
    }

    /** 带分叉意图的审批：同一中断点用不同值走另一条分支。 */
    public static ResumeCommand fork(String workflowId, int stepNo, String resumeId,
                                     String branchId, String value) {
        return new ResumeCommand(workflowId, stepNo, resumeId, true, branchId, value);
    }
}
