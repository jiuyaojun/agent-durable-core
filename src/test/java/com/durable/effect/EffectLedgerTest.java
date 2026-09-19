package com.durable.effect;

import com.durable.db.Db;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("效果账本")
class EffectLedgerTest {

    private EffectLedger ledger;

    @BeforeEach
    void setUp() {
        Db.resetSchema();
        ledger = new EffectLedger(Db.dataSource());
    }

    @AfterAll
    static void tearDown() {
        Db.shutdown();
    }

    @Test
    @DisplayName("第一次触发副作用，第二次命中账本不再触发")
    void executesOnceThenReuses() {
        AtomicInteger effects = new AtomicInteger();

        EffectOutcome first = ledger.executeOnce("wf-1", 0, "createHost", conn -> {
            effects.incrementAndGet();
            return "host-1";
        });
        EffectOutcome second = ledger.executeOnce("wf-1", 0, "createHost", conn -> {
            effects.incrementAndGet();
            return "host-2";
        });

        System.out.println("[真实输出] 第一次 executed=" + first.executed() + " result=" + first.result());
        System.out.println("[真实输出] 第二次 executed=" + second.executed() + " result=" + second.result());
        System.out.println("[真实输出] 副作用实际次数 = " + effects.get());

        assertTrue(first.executed());
        assertFalse(second.executed(), "第二次应命中账本");
        assertEquals("host-1", second.result(), "复用第一次的结果，而不是产生新结果");
        assertEquals(1, effects.get(), "副作用只应触发一次");
    }

    @Test
    @DisplayName("副作用抛异常时事务回滚，位置不被占用")
    void rollsBackOnFailure() {
        assertThrows(IllegalStateException.class, () ->
                ledger.executeOnce("wf-1", 0, "createHost", conn -> {
                    throw new IllegalStateException("模拟副作用失败");
                }));

        long rows = Db.countRows("SELECT COUNT(*) FROM effect_ledger");
        System.out.println("[真实输出] 失败后账本行数 = " + rows);
        assertEquals(0L, rows, "回滚后位置应重新可用，不留下占了坑没干活的残局");

        EffectOutcome retry = ledger.executeOnce("wf-1", 0, "createHost", conn -> "host-ok");
        assertTrue(retry.executed(), "回滚后应能重新抢占");
    }

    @Test
    @DisplayName("不同工作流 / 不同步骤互不影响")
    void isolatesKeys() {
        ledger.executeOnce("wf-1", 0, "t", conn -> "a");
        ledger.executeOnce("wf-1", 1, "t", conn -> "b");
        ledger.executeOnce("wf-2", 0, "t", conn -> "c");

        long rows = Db.countRows("SELECT COUNT(*) FROM effect_ledger");
        System.out.println("[真实输出] 三个不同 key 后账本行数 = " + rows);
        System.out.println("[真实输出] wf-1 已执行数 = " + ledger.countExecuted("wf-1"));

        assertEquals(3L, rows);
        assertEquals(2L, ledger.countExecuted("wf-1"), "只有 DONE 才计入已执行");
        assertTrue(ledger.findResult("wf-1", 0).isPresent());
        assertTrue(ledger.findResult("wf-9", 0).isEmpty());
    }

    @Test
    @DisplayName("副作用与账本同事务：副作用失败则账本也不留痕")
    void effectAndLedgerShareOneTransaction() {
        assertThrows(IllegalStateException.class, () ->
                ledger.executeOnce("wf-tx", 0, "createHost", conn -> {
                    try (var ps = conn.prepareStatement(
                            "INSERT INTO host (id, name, status, created_by) "
                                    + "VALUES ('h1', 'n', 'RUNNING', 'test')")) {
                        ps.executeUpdate();
                    }
                    throw new IllegalStateException("副作用写到一半失败");
                }));

        System.out.println("[真实输出] 失败后 host 行数 = " + Db.countRows("SELECT COUNT(*) FROM host"));
        System.out.println("[真实输出] 失败后账本行数 = " + Db.countRows("SELECT COUNT(*) FROM effect_ledger"));

        assertEquals(0L, Db.countRows("SELECT COUNT(*) FROM host"), "副作用必须一起回滚");
        assertEquals(0L, Db.countRows("SELECT COUNT(*) FROM effect_ledger"), "账本也必须一起回滚");
    }
}
