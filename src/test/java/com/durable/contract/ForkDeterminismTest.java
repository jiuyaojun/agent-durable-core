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
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * FD（Fork determinism）性质。
 *
 * 论文原文：
 *   "If resumes carrying fork intent with values v1..vm are addressed to the same
 *    interrupt checkpoint, then each branch outcome satisfies o_k = f(v_k);
 *    in particular v_j != v_1 => o_j != o_1 whenever f is injective."
 *
 * 注意 f 是「决定函数」：把审批值路由进被门控的分支决策。
 * 模型或工具在决策之后的不确定性不算在内，所以 FD 对非确定性 Agent 仍然是良定义的。
 */
@DisplayName("契约 · FD：分叉确定性")
class ForkDeterminismTest {

    /** 单射的分支决策函数：不同的审批值必然产生不同的产出。 */
    private static final Function<String, String> INJECTIVE = v -> "decision:" + v;

    private ApprovalGate gate;

    @BeforeEach
    void setUp() {
        Db.resetSchema();
        gate = new ApprovalGate(Db.dataSource());
        gate.park("wf-fd", 0, "{\"action\":\"deleteHost\"}");
    }

    @AfterAll
    static void tearDown() {
        Db.shutdown();
    }

    @Test
    @DisplayName("同一中断点用不同值分叉 ⇒ 产出不同")
    void differentValuesProduceDifferentBranches() {
        ResumeOutcome a = gate.resume(
                ResumeCommand.fork("wf-fd", 0, "r-a", "branch-a", "approve"), INJECTIVE);
        ResumeOutcome b = gate.resume(
                ResumeCommand.fork("wf-fd", 0, "r-b", "branch-b", "reject"), INJECTIVE);

        System.out.println("[真实输出] 分支 A = " + a.value() + " (kind=" + a.kind() + ")");
        System.out.println("[真实输出] 分支 B = " + b.value() + " (kind=" + b.kind() + ")");
        System.out.println("[真实输出] 分支总数 = " + gate.forkCount("wf-fd", 0));

        assertEquals(ResumeKind.FORKED, a.kind());
        assertEquals(ResumeKind.FORKED, b.kind());
        assertEquals("decision:approve", a.value(), "产出必须等于 f(v)");
        assertEquals("decision:reject", b.value());
        assertNotEquals(a.value(), b.value(), "f 单射时 v 不同 ⇒ 产出不同");
        assertEquals(2L, gate.forkCount("wf-fd", 0));
    }

    @Test
    @DisplayName("同一分支重复投递 ⇒ 复用产出，不重复执行分支决策")
    void sameBranchIsDeterministic() {
        ResumeOutcome first = gate.resume(
                ResumeCommand.fork("wf-fd", 0, "r-1", "branch-x", "approve"), INJECTIVE);
        ResumeOutcome second = gate.resume(
                ResumeCommand.fork("wf-fd", 0, "r-2", "branch-x", "approve"), INJECTIVE);

        System.out.println("[真实输出] 第一次 = " + first.kind() + " / " + first.value());
        System.out.println("[真实输出] 第二次 = " + second.kind() + " / " + second.value());
        System.out.println("[真实输出] 分支总数 = " + gate.forkCount("wf-fd", 0));

        assertEquals(ResumeKind.FORKED, first.kind());
        assertEquals(ResumeKind.REPLAYED, second.kind(), "同一分支重复投递应复用");
        assertEquals(first.value(), second.value());
        assertEquals(1L, gate.forkCount("wf-fd", 0), "不应开出第二个分支");
    }

    @Test
    @DisplayName("分叉不会消费中断：消费计数仍为 0")
    void forkingDoesNotConsumeInterrupt() {
        gate.resume(ResumeCommand.fork("wf-fd", 0, "r-a", "branch-a", "approve"), INJECTIVE);
        gate.resume(ResumeCommand.fork("wf-fd", 0, "r-b", "branch-b", "reject"), INJECTIVE);

        System.out.println("[真实输出] 分叉两次后，中断消费计数 = " + gate.consumedCount("wf-fd", 0));
        assertEquals(0L, gate.consumedCount("wf-fd", 0), "分叉是开新分支，不是消费中断");
    }

    @Test
    @DisplayName("先分叉再普通审批：分叉不占用消费名额")
    void forkThenConsumeBothSucceed() {
        ResumeOutcome forked = gate.resume(
                ResumeCommand.fork("wf-fd", 0, "r-fork", "branch-a", "approve"), INJECTIVE);
        ResumeOutcome consumed = gate.resume(
                ResumeCommand.approve("wf-fd", 0, "r-consume", "approve"), INJECTIVE);

        System.out.println("[真实输出] 分叉 = " + forked.kind()
                + "，随后普通审批 = " + consumed.kind());

        assertEquals(ResumeKind.FORKED, forked.kind());
        assertEquals(ResumeKind.CONSUMED, consumed.kind(), "中断仍处于 PARKED，应能被消费");
        assertEquals(1L, gate.consumedCount("wf-fd", 0));
    }
}
