package com.durable.effect;

import java.util.Objects;
import java.util.Optional;

/**
 * 一次受保护副作用执行的结果。
 *
 * @param executed true = 本次真的触发了副作用；false = 命中账本，副作用没有再次触发
 * @param result   副作用返回的结果（两种情况都有值，这正是「复用」的前提）
 */
public record EffectOutcome(boolean executed, String result) {

    public EffectOutcome {
        Objects.requireNonNull(result, "result");
    }

    public static EffectOutcome executed(String result) {
        return new EffectOutcome(true, result);
    }

    public static EffectOutcome reused(String result) {
        return new EffectOutcome(false, result);
    }

    public Optional<String> resultIfReused() {
        return executed ? Optional.empty() : Optional.of(result);
    }
}
