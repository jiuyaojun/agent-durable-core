package com.durable.interrupt;

/** 一次 resume 的结果类别。 */
public enum ResumeKind {

    /** 无分叉意图，抢到了中断 —— 本次消费有效，可以触发被门控的副作用。 */
    CONSUMED,

    /** 无分叉意图，但中断已被消费或不存在 —— 惰性拒绝，副作用不得触发（CO-e）。 */
    INERT,

    /** 有分叉意图，开辟了新分支（FD）。 */
    FORKED,

    /** 有分叉意图，但该分支已存在 —— 复用已有产出（FD 的确定性）。 */
    REPLAYED
}
