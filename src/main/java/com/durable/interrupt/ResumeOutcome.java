package com.durable.interrupt;

import java.util.Objects;

/**
 * 一次 resume 的结果。
 *
 * @param kind     结果类别
 * @param branchId 分叉时对应的分支标识；非分叉为 null
 * @param value    产出值（CONSUMED 时是审批值；FORKED/REPLAYED 时是分支产出）
 */
public record ResumeOutcome(ResumeKind kind, String branchId, String value) {

    public ResumeOutcome {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(value, "value");
    }

    public static ResumeOutcome consumed(String value) {
        return new ResumeOutcome(ResumeKind.CONSUMED, null, value);
    }

    public static ResumeOutcome inert() {
        return new ResumeOutcome(ResumeKind.INERT, null, "");
    }

    public static ResumeOutcome forked(String branchId, String value) {
        return new ResumeOutcome(ResumeKind.FORKED, branchId, value);
    }

    public static ResumeOutcome replayed(String branchId, String value) {
        return new ResumeOutcome(ResumeKind.REPLAYED, branchId, value);
    }

    /**
     * 本次 resume 是否应该触发被门控的副作用。
     *
     * INERT 时返回 false —— 这正是 CO-e「inert with respect to effects」的落点：
     * 重复投递必须不产生任何副作用。
     */
    public boolean isEffectBearing() {
        return kind == ResumeKind.CONSUMED || kind == ResumeKind.FORKED;
    }
}
