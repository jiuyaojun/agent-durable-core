package com.durable.contract;

import com.durable.db.Db;
import com.durable.interrupt.ApprovalGate;
import com.durable.interrupt.ResumeCommand;
import com.durable.interrupt.ResumeKind;
import com.durable.interrupt.ResumeOutcome;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * CO（Consume-once）性质，两个子条款分别验证。
 *
 * 论文原文：
 *   "(CO-c, consumption count) An interrupt is consumed by at most one resume."
 *   "(CO-e, effect inertness) A resume without fork intent addressed to a completed run
 *    or an already-consumed interrupt — including byte-identical re-delivery of a prior
 *    resume — is inert with respect to effects."
 *
 * 论文特别点出一个陷阱：
 *   "a gate that serves its effect idempotently from the durable record can consume one
 *    human approval twice while the effect count stays at one, which leaves the
 *    **approval trail wrong** and the effect ledger right."
 * 也就是说：只做幂等的话副作用计数是对的，但审批记录已经错了。
 * 所以本测试同时观测「消费计数」和「投递次数」两个指标。
 */
@DisplayName("契约 · CO：中断消费至多一次")
class ConsumeOnceTest {

    private static final Function<String, String> F = v -> "decision:" + v;

    private ApprovalGate gate;

    @BeforeEach
    void setUp() {
        Db.resetSchema();
        gate = new ApprovalGate(Db.dataSource());
        gate.park("wf-co", 0, "{\"action\":\"deleteHost\"}");
    }

    @AfterAll
    static void tearDown() {
        Db.shutdown();
    }

    @Test
    @DisplayName("CO-c：第二次审批是惰性的，不会再次消费中断")
    void secondResumeIsInert() {
        ResumeOutcome first = gate.resume(ResumeCommand.approve("wf-co", 0, "r-1", "yes"), F);
        ResumeOutcome second = gate.resume(ResumeCommand.approve("wf-co", 0, "r-2", "yes"), F);

        System.out.println("[真实输出] 第一次 = " + first.kind());
        System.out.println("[真实输出] 第二次 = " + second.kind());
        System.out.println("[真实输出] 中断消费计数 = " + gate.consumedCount("wf-co", 0));

        assertEquals(ResumeKind.CONSUMED, first.kind());
        assertEquals(ResumeKind.INERT, second.kind(), "CO-e：重复投递必须惰性");
        assertFalse(second.isEffectBearing(), "惰性结果不得携带副作用");
        assertEquals(1L, gate.consumedCount("wf-co", 0), "CO-c：至多被消费一次");
    }

    @Test
    @DisplayName("CO-c：连续 10 次审批，中断只被消费 1 次")
    void tenResumesConsumeOnce() {
        int consumed = 0;
        for (int i = 0; i < 10; i++) {
            ResumeOutcome o = gate.resume(ResumeCommand.approve("wf-co", 0, "r-" + i, "yes"), F);
            if (o.kind() == ResumeKind.CONSUMED) {
                consumed++;
            }
        }

        System.out.println("[真实输出] 10 次投递中被消费的次数 = " + consumed);
        System.out.println("[真实输出] 中断消费计数 = " + gate.consumedCount("wf-co", 0));
        System.out.println("[真实输出] 投递总次数（含被拒） = " + gate.attemptCount("wf-co", 0));

        assertEquals(1, consumed, "CO-c：只有一次能真正消费");
        assertEquals(1L, gate.consumedCount("wf-co", 0));
        assertEquals(10L, gate.attemptCount("wf-co", 0),
                "被拒绝的投递也要留痕 —— 否则「审批轨迹」是错的");
    }

    @Test
    @DisplayName("CO-e：已消费的中断收到迟到审批仍然惰性")
    void lateResumeOnConsumedInterruptIsInert() {
        gate.resume(ResumeCommand.approve("wf-co", 0, "r-1", "yes"), F);

        ResumeOutcome late = gate.resume(ResumeCommand.approve("wf-co", 0, "r-late", "yes"), F);

        System.out.println("[真实输出] 迟到审批结果 = " + late.kind());
        assertEquals(ResumeKind.INERT, late.kind());
        assertEquals(1L, gate.consumedCount("wf-co", 0));
    }

    @Test
    @DisplayName("⭐ 幂等 masking 陷阱：副作用只触发 1 次，但审批轨迹必须记录全部投递")
    void inertResumeStillRecordedInApprovalTrail() {
        gate.resume(ResumeCommand.approve("wf-co", 0, "r-1", "yes"), F);
        gate.resume(ResumeCommand.approve("wf-co", 0, "r-2", "yes"), F);
        gate.resume(ResumeCommand.approve("wf-co", 0, "r-3", "no"), F);

        long consumed = gate.consumedCount("wf-co", 0);
        long attempts = gate.attemptCount("wf-co", 0);

        System.out.println("[真实输出] 消费次数 = " + consumed + "（副作用只会触发 1 次）");
        System.out.println("[真实输出] 投递次数 = " + attempts + "（含 2 次被惰性拒绝）");

        assertEquals(1L, consumed);
        assertEquals(3L, attempts, "被拒绝的投递如果没留痕，审计轨迹就是错的");
    }
}
