package com.durable.interrupt;

import com.durable.db.Db;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("审批闸门：基本生命周期")
class ApprovalGateTest {

    private ApprovalGate gate;

    @BeforeEach
    void setUp() {
        Db.resetSchema();
        gate = new ApprovalGate(Db.dataSource());
    }

    @AfterAll
    static void tearDown() {
        Db.shutdown();
    }

    @Test
    @DisplayName("挂起后可以被一次审批消费")
    void parkThenConsume() {
        gate.park("wf-1", 0, "deleteHost", "{\"target\":\"prod-1\"}");

        Interrupt parked = gate.find("wf-1", 0).orElseThrow();
        System.out.println("[真实输出] 挂起状态 = " + parked.status()
                + ", 审批目标 = " + parked.toolName() + " " + parked.args()
                + ", 指纹 = " + parked.argsHash().substring(0, 16) + "...");
        assertTrue(parked.isParked());

        ResumeOutcome outcome = gate.resume(
                ResumeCommand.approve("wf-1", 0, "r-1", "yes"), v -> "decision:" + v);

        System.out.println("[真实输出] 审批结果 = " + outcome.kind() + " / " + outcome.value());
        assertEquals(ResumeKind.CONSUMED, outcome.kind());
        assertEquals("yes", outcome.value());
        assertTrue(outcome.isEffectBearing(), "被消费的审批应当能触发副作用");

        Interrupt after = gate.find("wf-1", 0).orElseThrow();
        assertEquals(InterruptStatus.CONSUMED, after.status());
        assertEquals("r-1", after.consumedBy());
    }

    @Test
    @DisplayName("同一中断点重复 park 会被拒绝")
    void parkIsUnique() {
        gate.park("wf-1", 0, "deleteHost", "{}");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> gate.park("wf-1", 0, "deleteHost", "{}"));
        System.out.println("[真实输出] 重复 park 被拒绝: " + ex.getMessage());
    }

    @Test
    @DisplayName("未挂起的中断收到 resume 视为惰性")
    void resumeOnUnknownInterruptIsInert() {
        ResumeOutcome outcome = gate.resume(
                ResumeCommand.approve("wf-none", 0, "r-1", "yes"), v -> "decision:" + v);

        System.out.println("[真实输出] 未知中断的 resume 结果 = " + outcome.kind());
        assertEquals(ResumeKind.INERT, outcome.kind());
        assertTrue(!outcome.isEffectBearing(), "惰性结果不得触发副作用");
    }

    @Test
    @DisplayName("带分叉意图但缺 branchId 在构造时就被拒绝（FI）")
    void forkWithoutBranchIdRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                new ResumeCommand("wf-1", 0, "r-1", true, null, "yes"));
        System.out.println("[真实输出] 拒绝原因: " + ex.getMessage());
    }

    @Test
    @DisplayName("参数指纹与 JSON 键序无关（规范化）")
    void fingerprintIsOrderIndependent() {
        String a = ApprovalGate.fingerprint("deleteHost", "{\"a\":1,\"b\":2}");
        String b = ApprovalGate.fingerprint("deleteHost", "{ \"b\": 2, \"a\": 1 }");

        System.out.println("[真实输出] 指纹A = " + a.substring(0, 16));
        System.out.println("[真实输出] 指纹B = " + b.substring(0, 16));

        assertEquals(a, b, "键序变化不应被误判为参数漂移");
    }

    @Test
    @DisplayName("工具名不同则指纹不同")
    void fingerprintDependsOnToolName() {
        String a = ApprovalGate.fingerprint("deleteHost", "{}");
        String b = ApprovalGate.fingerprint("restartHost", "{}");
        System.out.println("[真实输出] 不同工具的指纹是否相同 = " + a.equals(b));
        assertTrue(!a.equals(b));
    }

    @Test
    @DisplayName("参数未变时绑定校验通过")
    void verifyBindingPassesWhenUnchanged() {
        gate.park("wf-bind", 0, "deleteHost", "{\"target\":\"prod-1\"}");

        assertDoesNotThrow(() ->
                gate.verifyBinding("wf-bind", 0, "deleteHost", "{\"target\":\"prod-1\"}"));
        System.out.println("[真实输出] 参数未变：校验通过");
    }
}
