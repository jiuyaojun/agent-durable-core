package com.durable.fault;

/**
 * 模拟进程被强杀。
 *
 * 故意继承 {@link Error} 而不是 {@code Exception}：
 * 业务代码里常见的 {@code catch (Exception e)} 会把它吞掉，而真实崩溃不会。
 * 用 Error 能让测试更接近真实崩溃时调用栈的行为 —— 这一点是刻意的，不是笔误。
 */
public class SimulatedCrash extends Error {

    private static final long serialVersionUID = 1L;

    public SimulatedCrash(String message) {
        super(message);
    }
}
