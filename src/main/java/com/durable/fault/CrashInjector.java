package com.durable.fault;

/**
 * 应用内故障注入器，用于在测试中确定性复现崩溃窗口。
 *
 * 行为刻意设计成「单次武装、单次触发」：触发后自动解除武装，
 * 以此模拟「进程已经死了，重启的是一个全新进程」。
 * 如果不这样做，重试时会再次立刻崩溃，永远走不到恢复逻辑。
 */
public class CrashInjector {

    private CrashPoint armedPoint;
    private int armedStepNo = -1;

    /** 在指定步骤的指定位置武装一次崩溃。 */
    public void arm(CrashPoint point, int stepNo) {
        if (point == null) {
            throw new IllegalArgumentException("point 不能为空");
        }
        this.armedPoint = point;
        this.armedStepNo = stepNo;
    }

    /** 是否还有未触发的崩溃计划。 */
    public boolean isArmed() {
        return armedPoint != null;
    }

    /** 到达某位置时调用。与武装位置匹配则抛崩溃，否则什么也不做。 */
    public void check(CrashPoint point, int stepNo) {
        if (armedPoint == point && armedStepNo == stepNo) {
            armedPoint = null;
            armedStepNo = -1;
            throw new SimulatedCrash("注入崩溃于 " + point + ", step=" + stepNo);
        }
    }
}
