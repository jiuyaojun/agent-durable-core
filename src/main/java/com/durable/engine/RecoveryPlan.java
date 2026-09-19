package com.durable.engine;

import com.durable.journal.JournalEntry;
import com.durable.journal.JournalEntryType;
import com.durable.journal.JournalStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 恢复决策：从持久日志【纯计算】出来的执行计划。
 *
 * RD（Recovery determinism）性质要求：同一份日志必须得出同一个计划。
 * 所以这个类没有任何随机、时间或外部输入 ——
 * 它就是一个把 List&lt;JournalEntry&gt; 映射成计划的纯函数。
 *
 * PC（prefix continuation）允许前缀代码重跑，只要副作用从持久记录取。
 * 因此这里的策略是：**已完成的步骤仍然重跑一遍代码**，
 * 但它们的副作用会由效果账本拦住。resumeFromStep 只用来优化，不承担正确性。
 */
public record RecoveryPlan(int resumeFromStep, List<Integer> completedSteps) {

    public RecoveryPlan {
        Objects.requireNonNull(completedSteps, "completedSteps");
        completedSteps = List.copyOf(completedSteps);
    }

    /**
     * 从日志计算恢复计划。纯函数：相同日志 ⇒ 相同计划。
     *
     * @param totalSteps 本次要跑的步骤总数
     */
    public static RecoveryPlan from(JournalStore journal, String workflowId, int totalSteps) {
        Objects.requireNonNull(journal, "journal");
        Objects.requireNonNull(workflowId, "workflowId");

        List<JournalEntry> entries = journal.load(workflowId);
        List<Integer> completed = new ArrayList<>();
        int frontier = -1;
        for (JournalEntry entry : entries) {
            if (entry.type() == JournalEntryType.STEP_RESULT
                    && entry.stepNo() > frontier) {
                frontier = entry.stepNo();
            }
        }
        for (JournalEntry entry : entries) {
            if (entry.type() == JournalEntryType.STEP_RESULT && !completed.contains(entry.stepNo())) {
                completed.add(entry.stepNo());
            }
        }
        completed.sort(Integer::compareTo);
        int resumeFrom = (frontier + 1 >= totalSteps) ? totalSteps : frontier + 1;
        return new RecoveryPlan(resumeFrom, completed);
    }

    public boolean isCompleted(int stepNo) {
        return completedSteps.contains(stepNo);
    }
}
