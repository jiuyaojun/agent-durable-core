package com.durable.engine;

/**
 * 一次 run() 的结果统计。
 *
 * EO 性质的观测点就是 effectsFired —— 它必须恒 ≤ 1（每个非幂等步骤）。
 *
 * @param stepsExecuted 实际执行的步骤数
 * @param effectsFired  本次真正触发的非幂等副作用次数
 * @param effectsReused 命中效果账本、复用了旧结果的次数
 */
public record ExecutionResult(int stepsExecuted, int effectsFired, int effectsReused) {
}
