package com.durable.engine;

import com.durable.effect.EffectLedger;
import com.durable.effect.EffectOutcome;
import com.durable.fault.CrashInjector;
import com.durable.fault.CrashPoint;
import com.durable.journal.JournalEntry;
import com.durable.journal.JournalEntryType;
import com.durable.journal.JournalStore;
import com.durable.journal.mysql.DuplicateJournalEntryException;
import com.durable.json.Json;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 带恢复语义的步骤执行器。
 *
 * 每一步的执行顺序【本身就是设计】—— 三个崩溃点的含义由此确定：
 *
 *   1. check(BEFORE_STEP, i)                    崩溃则副作用尚未发生，安全
 *   2. 非幂等 → 账本 claim + 副作用 + 结果，**同一个事务**（EO 的关键）
 *      其余   → 有连接但无 claim
 *   3. check(AFTER_EXECUTE_BEFORE_JOURNAL, i)   副作用已发生、日志未写 —— 计划 1 复现的窗口
 *   4. 写 STEP_RESULT
 *   5. check(AFTER_JOURNAL, i)
 *
 * 第 3 步那个窗口现在为什么安全：第 2 步已经把副作用记进账本并提交了。
 * 恢复时重跑第 2 步会命中账本、复用结果、不再触发副作用。
 *
 * 注意第 1 步的位置：它在账本 claim 之前。所以 BEFORE_STEP 崩溃不会占用账本位置，
 * 重跑时能正常抢占。
 */
public class DurableExecutor {

    private final JournalStore journal;
    private final EffectLedger ledger;
    private final CrashInjector crashInjector;

    public DurableExecutor(JournalStore journal, EffectLedger ledger, CrashInjector crashInjector) {
        this.journal = Objects.requireNonNull(journal, "journal");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.crashInjector = Objects.requireNonNull(crashInjector, "crashInjector");
    }

    /** 只读地计算恢复计划，不产生任何副作用。RD 性质的观测点。 */
    public RecoveryPlan plan(String workflowId, int totalSteps) {
        return RecoveryPlan.from(journal, workflowId, totalSteps);
    }

    public ExecutionResult run(String workflowId, List<Step> steps) {
        Objects.requireNonNull(workflowId, "workflowId");
        Objects.requireNonNull(steps, "steps");

        int executed = 0;
        int fired = 0;
        int reused = 0;

        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);

            crashInjector.check(CrashPoint.BEFORE_STEP, i);
            EffectOutcome outcome = executeStep(workflowId, i, step);
            executed++;
            // 只统计【受账本保护】的副作用：只读步骤不算副作用，
            // 否则 effectsFired 会被重放安全的操作污染，失去作为 EO 观测点的意义。
            if (step.needsLedger()) {
                if (outcome.executed()) {
                    fired++;
                } else {
                    reused++;
                }
            }

            crashInjector.check(CrashPoint.AFTER_EXECUTE_BEFORE_JOURNAL, i);
            recordResult(workflowId, i, step, outcome.result());
            crashInjector.check(CrashPoint.AFTER_JOURNAL, i);
        }
        return new ExecutionResult(executed, fired, reused);
    }

    /**
     * 写入步骤结果。
     *
     * 恢复时会重跑前缀代码，所以同一个位置可能被写到第二次。
     * 这里用「直接写、捕获重复键」而不是「先查再写」：
     * 先查再写是 TOCTOU 竞态，而重复键是数据库层面的原子保证。
     *
     * 重复时保留【先写入的那条】：日志是权威来源，
     * 重跑得到的新值不应覆盖历史（这正是 PC 里「状态是日志的纯函数」的含义）。
     */
    private void recordResult(String workflowId, int stepNo, Step step, String value) {
        try {
            journal.append(JournalEntry.of(workflowId, stepNo, JournalEntryType.STEP_RESULT,
                    payloadOf(step, value)));
        } catch (DuplicateJournalEntryException ignored) {
            // 该位置已有记录 —— 恢复时重跑前缀的正常情况，无需处理
        }
    }

    /** 非幂等步骤走账本；其余直接执行。 */
    private EffectOutcome executeStep(String workflowId, int stepNo, Step step) {
        if (step.needsLedger()) {
            return ledger.executeOnce(workflowId, stepNo, step.toolName(), step.action());
        }
        return EffectOutcome.executed(ledger.runUnprotected(step.action()));
    }

    private static String payloadOf(Step step, String value) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("step", step.name());
        payload.put("tool", step.toolName());
        payload.put("effectType", step.effectType().name());
        payload.put("value", value);
        return Json.write(payload);
    }
}
