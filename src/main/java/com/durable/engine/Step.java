package com.durable.engine;

import com.durable.effect.EffectType;
import com.durable.effect.TransactionalEffect;

import java.util.Objects;

/**
 * 一个可执行的步骤。
 *
 * toolName + effectType 让执行器知道这一步「危不危险」：
 * 只读的直接跑，非幂等的必须过效果账本。
 */
public record Step(String name, String toolName, EffectType effectType, TransactionalEffect action) {

    public Step {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(effectType, "effectType");
        Objects.requireNonNull(action, "action");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name 不能为空白");
        }
        if (toolName.isBlank()) {
            throw new IllegalArgumentException("toolName 不能为空白");
        }
    }

    /** 是否需要效果账本保护。 */
    public boolean needsLedger() {
        return effectType == EffectType.NON_IDEMPOTENT;
    }
}
