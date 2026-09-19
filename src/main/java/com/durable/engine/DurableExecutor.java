package com.durable.engine;

import com.durable.fault.CrashInjector;
import com.durable.fault.CrashPoint;
import com.durable.journal.JournalEntry;
import com.durable.journal.JournalEntryType;
import com.durable.journal.JournalStore;
import com.durable.json.Json;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 最简步骤执行器。
 *
 * ⚠️ 本版本【没有恢复逻辑】：每次 run 都从第 0 步重新执行。
 * 这不是疏忽，而是刻意为之 —— 目的是让 {@code DurableExecutorCrashTest}
 * 能用一个刻画测试把「崩溃后副作用被重复执行」这个缺陷固化下来。
 * 修复在计划 2，届时该测试的断言会从 2 改成 1。
 */
public class DurableExecutor {

    private final JournalStore journal;
    private final CrashInjector crashInjector;

    public DurableExecutor(JournalStore journal, CrashInjector crashInjector) {
        this.journal = Objects.requireNonNull(journal, "journal");
        this.crashInjector = Objects.requireNonNull(crashInjector, "crashInjector");
    }

    /**
     * 从头执行全部步骤。
     *
     * @return 本次调用实际执行的步骤数（不含被崩溃中断的那一步）
     */
    public int run(String workflowId, List<Step> steps) {
        Objects.requireNonNull(workflowId, "workflowId");
        Objects.requireNonNull(steps, "steps");

        int executed = 0;
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);

            crashInjector.check(CrashPoint.BEFORE_STEP, i);
            String value = step.action().get();
            crashInjector.check(CrashPoint.AFTER_EXECUTE_BEFORE_JOURNAL, i);

            journal.append(JournalEntry.of(
                    workflowId, i, JournalEntryType.TOOL_RESULT, payloadOf(step, value)));
            executed++;

            crashInjector.check(CrashPoint.AFTER_JOURNAL, i);
        }
        return executed;
    }

    private static String payloadOf(Step step, String value) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("step", step.name());
        payload.put("value", value);
        return Json.write(payload);
    }
}
