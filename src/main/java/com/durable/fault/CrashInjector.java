package com.durable.fault;

/**
 * 应用内故障注入器，用于在测试中确定性复现崩溃窗口。
 *
 * 两种模式：
 *   1. {@link #arm}     —— 到点抛 {@link SimulatedCrash}，模拟进程崩溃（快、可进 CI）
 *   2. {@link #armPause} —— 到点挂起指定毫秒，让**父进程把本进程强杀**（真实 kill -9）
 *
 * 为什么两种都要：模拟崩溃能确定性复现特定窗口，但它验证不了
 * 「真实强杀时数据库连接会怎样、未提交事务会不会回滚」。
 * 所以两者互补，都要有。
 *
 * 行为刻意设计成「单次武装、单次触发」：触发后自动解除武装，
 * 以此模拟「进程已经死了，重启的是一个全新进程」。
 */
public class CrashInjector {

    private CrashPoint armedPoint;
    private int armedStepNo = -1;
    private long pauseMillis = -1L;

    /** 在指定步骤的指定位置武装一次崩溃（抛出 SimulatedCrash）。 */
    public void arm(CrashPoint point, int stepNo) {
        setArmed(point, stepNo, -1L);
    }

    /**
     * 在指定步骤的指定位置武装一次【暂停】。
     * 到达该位置时本线程睡 {@code millis} 毫秒，给外部进程留出强杀的时间窗。
     */
    public void armPause(CrashPoint point, int stepNo, long millis) {
        if (millis <= 0) {
            throw new IllegalArgumentException("millis 必须为正数: " + millis);
        }
        setArmed(point, stepNo, millis);
    }

    private void setArmed(CrashPoint point, int stepNo, long pause) {
        if (point == null) {
            throw new IllegalArgumentException("point 不能为空");
        }
        this.armedPoint = point;
        this.armedStepNo = stepNo;
        this.pauseMillis = pause;
    }

    /** 是否还有未触发的崩溃计划。 */
    public boolean isArmed() {
        return armedPoint != null;
    }

    /** 到达某位置时调用。与武装位置匹配则抛崩溃或暂停，否则什么也不做。 */
    public void check(CrashPoint point, int stepNo) {
        if (armedPoint != point || armedStepNo != stepNo) {
            return;
        }
        long pause = pauseMillis;
        armedPoint = null;
        armedStepNo = -1;
        pauseMillis = -1L;

        if (pause > 0) {
            try {
                Thread.sleep(pause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("暂停被中断", e);
            }
            return;
        }
        throw new SimulatedCrash("注入崩溃于 " + point + ", step=" + stepNo);
    }
}
