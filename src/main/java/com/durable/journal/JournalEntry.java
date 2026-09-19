package com.durable.journal;

import java.time.Instant;
import java.util.Objects;

/**
 * 一条不可变的日志条目。
 *
 * payload 保持为 JSON 字符串 —— 让存储层对内容完全无感知，
 * 这样将来加新的事件类型不需要改表结构。
 */
public record JournalEntry(
        String workflowId,
        int stepNo,
        JournalEntryType type,
        String payload,
        Instant createdAt
) {
    public JournalEntry {
        Objects.requireNonNull(workflowId, "workflowId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(createdAt, "createdAt");
        if (workflowId.isBlank()) {
            throw new IllegalArgumentException("workflowId 不能为空白");
        }
        if (stepNo < 0) {
            throw new IllegalArgumentException("stepNo 不能为负数: " + stepNo);
        }
    }

    /** 便捷工厂：createdAt 取当前时刻。 */
    public static JournalEntry of(String workflowId, int stepNo, JournalEntryType type, String payload) {
        return new JournalEntry(workflowId, stepNo, type, payload, Instant.now());
    }
}
