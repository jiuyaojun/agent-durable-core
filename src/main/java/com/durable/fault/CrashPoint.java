package com.durable.fault;

/**
 * 可以注入崩溃的位置。
 *
 * 三个位置覆盖了「副作用」与「日志写入」之间的完整窗口：
 *
 *   BEFORE_STEP                    副作用尚未发生 → 安全，重跑无副作用
 *   AFTER_EXECUTE_BEFORE_JOURNAL   副作用已发生，日志未写 → 最危险，重跑会重复副作用
 *   AFTER_JOURNAL                  副作用与日志都已完成 → 安全，重跑会被日志拦住
 *
 * 中间那个就是本项目要解决的核心窗口。
 */
public enum CrashPoint {
    BEFORE_STEP,
    AFTER_EXECUTE_BEFORE_JOURNAL,
    AFTER_JOURNAL
}
