package com.durable.engine;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 一个可执行的步骤。
 *
 * 本计划不区分效果类型（只读 / 幂等 / 非幂等），所有步骤一律同等对待 ——
 * 这正是后面要修正的地方之一。计划 2 会引入 EffectType 来区分它们。
 */
public record Step(String name, Supplier<String> action) {

    public Step {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(action, "action");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name 不能为空白");
        }
    }
}
