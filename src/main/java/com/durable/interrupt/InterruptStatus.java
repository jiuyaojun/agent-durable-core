package com.durable.interrupt;

/** 中断的生命周期。 */
public enum InterruptStatus {

    /** 已挂起，等待审批。 */
    PARKED,

    /** 已被某次 resume 消费（至多一次）。 */
    CONSUMED
}
