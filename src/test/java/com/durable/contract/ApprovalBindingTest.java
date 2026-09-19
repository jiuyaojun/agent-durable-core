package com.durable.contract;

import com.durable.db.Db;
import com.durable.interrupt.ApprovalDriftException;
import com.durable.interrupt.ApprovalGate;
import com.durable.interrupt.Interrupt;
import com.durable.interrupt.ResumeCommand;
import com.durable.interrupt.ResumeKind;
import com.durable.json.Json;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 审批绑定（参数快照）—— 防 TOCTOU 参数漂移。
 *
 * 攻击场景：
 *   1. Agent 请求「删除测试机 test-1」→ 人工审批通过
 *   2. 执行前，Agent 被注入的指令把目标改成「删除生产机 prod-1」
 *   3. 执行 → 审批的是一个操作，执行的是另一个
 *
 * 论文的 RESUME CONTRACT 没有直接覆盖这一条（它管的是 resume 语义），
 * 但这是真实的、被中文技术社区在 2026-08 专门写文章讨论过的漏洞。
 * 修法：审批绑定参数快照，执行前比对指纹。
 */
@DisplayName("契约 · 审批绑定：防参数漂移（TOCTOU）")
class ApprovalBindingTest {

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
    @DisplayName("审批后参数被改：拒绝执行，并带出期望与实际指纹")
    void detectsParameterDrift() {
        gate.park("wf-drift", 0, "deleteHost", "{\"target\":\"test-1\"}");
        gate.resume(ResumeCommand.approve("wf-drift", 0, "r-1", "yes"), v -> "ok");

        // 审批通过后，参数被偷偷换成了生产机
        ApprovalDriftException ex = assertThrows(ApprovalDriftException.class, () ->
                gate.verifyBinding("wf-drift", 0, "deleteHost", "{\"target\":\"prod-1\"}"));

        System.out.println("[真实输出] " + ex.getMessage());
        System.out.println("[真实输出] 审批时指纹 = " + ex.expectedHash().substring(0, 16) + "...");
        System.out.println("[真实输出] 执行时指纹 = " + ex.actualHash().substring(0, 16) + "...");

        assertNotEquals(ex.expectedHash(), ex.actualHash());
    }

    @Test
    @DisplayName("审批后工具名被改：同样拒绝")
    void detectsToolNameDrift() {
        gate.park("wf-drift2", 0, "restartHost", "{\"target\":\"prod-1\"}");

        ApprovalDriftException ex = assertThrows(ApprovalDriftException.class, () ->
                gate.verifyBinding("wf-drift2", 0, "deleteHost", "{\"target\":\"prod-1\"}"));

        System.out.println("[真实输出] 工具名被换： " + ex.getMessage());
        assertNotEquals(ex.expectedHash(), ex.actualHash());
    }

    @Test
    @DisplayName("参数键序变化不算漂移（规范化生效）")
    void keyOrderChangeIsNotDrift() {
        gate.park("wf-order", 0, "createHost", "{\"name\":\"web\",\"zone\":\"cn-1\"}");

        gate.verifyBinding("wf-order", 0, "createHost", "{\"zone\":\"cn-1\",\"name\":\"web\"}");
        System.out.println("[真实输出] 键序变化：校验通过（未误判为漂移）");
    }

    @Test
    @DisplayName("参数值细微变化也会被发现")
    void detectsSubtleValueChange() {
        gate.park("wf-subtle", 0, "chargeAccount", "{\"account\":\"A-1\",\"amount\":100}");

        assertThrows(ApprovalDriftException.class, () ->
                gate.verifyBinding("wf-subtle", 0, "chargeAccount", "{\"account\":\"A-1\",\"amount\":1000}"));

        System.out.println("[真实输出] 金额从 100 改成 1000：已拒绝");
    }

    @Test
    @DisplayName("中断记录里存的是规范化后的参数，键序稳定")
    void interruptStoresCanonicalArgs() {
        Interrupt interrupt = gate.park("wf-canon", 0, "createHost",
                "{\"zone\":\"cn-1\",\"name\":\"web\"}");

        System.out.println("[真实输出] 存储的参数（MySQL 原样返回） = " + interrupt.args());
        System.out.println("[真实输出] 规范化后 = " + Json.canonical(interrupt.args()));

        // 注意：MySQL 的 JSON 列会重新序列化（键之间加空格），
        // 所以不能直接比对原始字符串 —— 要比对规范化后的结果。
        assertEquals("{\"name\":\"web\",\"zone\":\"cn-1\"}", Json.canonical(interrupt.args()),
                "应按字典序规范化，便于人工比对");
    }

    @Test
    @DisplayName("参数漂移的检测不影响正常的审批消费语义")
    void driftCheckIsIndependentOfConsumption() {
        gate.park("wf-indep", 0, "deleteHost", "{\"target\":\"test-1\"}");
        var outcome = gate.resume(ResumeCommand.approve("wf-indep", 0, "r-1", "yes"), v -> "ok");

        assertEquals(ResumeKind.CONSUMED, outcome.kind());
        assertEquals(1L, gate.consumedCount("wf-indep", 0));

        // 消费语义正确，与参数校验互不干扰
        assertThrows(ApprovalDriftException.class, () ->
                gate.verifyBinding("wf-indep", 0, "deleteHost", "{\"target\":\"other\"}"));
        System.out.println("[真实输出] 消费计数仍为 " + gate.consumedCount("wf-indep", 0)
                + "，参数漂移独立被检出");
    }
}
