package com.durable.fault;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("故障注入器")
class CrashInjectorTest {

    @Test
    @DisplayName("未武装时不产生任何影响")
    void doesNothingWhenNotArmed() {
        CrashInjector injector = new CrashInjector();

        assertFalse(injector.isArmed());
        assertDoesNotThrow(() -> injector.check(CrashPoint.BEFORE_STEP, 0));
        assertDoesNotThrow(() -> injector.check(CrashPoint.AFTER_JOURNAL, 7));
    }

    @Test
    @DisplayName("只在指定的位置和步骤号触发")
    void throwsOnlyAtTheArmedPointAndStep() {
        CrashInjector injector = new CrashInjector();
        injector.arm(CrashPoint.BEFORE_STEP, 1);

        assertDoesNotThrow(() -> injector.check(CrashPoint.BEFORE_STEP, 0), "步骤号不匹配不应触发");
        assertDoesNotThrow(() -> injector.check(CrashPoint.AFTER_JOURNAL, 1), "注入点不匹配不应触发");

        SimulatedCrash crash = assertThrows(SimulatedCrash.class,
                () -> injector.check(CrashPoint.BEFORE_STEP, 1));

        System.out.println("[真实输出] 触发崩溃: " + crash.getMessage());
        assertTrue(crash.getMessage().contains("BEFORE_STEP"));
        assertTrue(crash.getMessage().contains("step=1"));
    }

    @Test
    @DisplayName("只触发一次，之后自动解除武装")
    void firesOnlyOnce() {
        CrashInjector injector = new CrashInjector();
        injector.arm(CrashPoint.AFTER_JOURNAL, 0);

        assertThrows(SimulatedCrash.class, () -> injector.check(CrashPoint.AFTER_JOURNAL, 0));
        assertFalse(injector.isArmed(), "触发后应解除武装");
        assertDoesNotThrow(() -> injector.check(CrashPoint.AFTER_JOURNAL, 0),
                "模拟重启后的全新进程：同一位置不会再触发");
    }

    @Test
    @DisplayName("SimulatedCrash 必须是 Error，不能被 catch(Exception) 吞掉")
    void isNotCaughtByCatchException() {
        CrashInjector injector = new CrashInjector();
        injector.arm(CrashPoint.BEFORE_STEP, 0);

        boolean caughtAsException = false;
        try {
            try {
                injector.check(CrashPoint.BEFORE_STEP, 0);
            } catch (Exception e) {
                caughtAsException = true;
            }
        } catch (SimulatedCrash expected) {
            System.out.println("[真实输出] Error 正确穿透了 catch(Exception): " + expected.getMessage());
        }

        assertFalse(caughtAsException,
                "若这里为 true，说明 SimulatedCrash 被当成 Exception 吞掉了，测试将失去意义");
    }
}
