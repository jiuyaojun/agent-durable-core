package com.durable.checkpoint;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 一次执行状态的检查点（不可变）。
 *
 * 不变量（构造时校验，违反即抛 {@link InvalidCheckpointException}）：
 *   1. workflowId / schemaVersion 非空白
 *   2. version &gt;= 0，frontierStep &gt;= 0
 *   3. results 非空，每一项非 null
 *   4. **results.size() == frontierStep + 1**  ← 核心不变量
 *
 * 第 4 条把「检查点说自己走到第几步」和「它实际记了几个结果」绑死。
 * 允许两者不一致，就是论文实测里 LangGraph 1.2.9
 * 「persists schema-invalid state silently」那个漏洞。
 */
public record Checkpoint(
        String workflowId,
        int version,
        int frontierStep,
        String schemaVersion,
        List<String> results,
        Instant createdAt
) {
    public static final String CURRENT_SCHEMA = "v1";

    public Checkpoint {
        requireNonBlank(workflowId, "workflowId");
        requireNonBlank(schemaVersion, "schemaVersion");
        Objects.requireNonNull(results, "results");
        Objects.requireNonNull(createdAt, "createdAt");

        if (version < 0) {
            throw new InvalidCheckpointException("version 不能为负数: " + version);
        }
        if (frontierStep < 0) {
            throw new InvalidCheckpointException("frontierStep 不能为负数: " + frontierStep);
        }
        if (results.isEmpty()) {
            throw new InvalidCheckpointException("results 不能为空");
        }
        if (results.size() != frontierStep + 1) {
            throw new InvalidCheckpointException(
                    "results 数量与 frontierStep 不一致: frontierStep=" + frontierStep
                            + " 期望 results.size()=" + (frontierStep + 1)
                            + " 实际=" + results.size());
        }
        for (int i = 0; i < results.size(); i++) {
            if (results.get(i) == null) {
                throw new InvalidCheckpointException("results 第 " + i + " 项为 null");
            }
        }
        results = List.copyOf(results);
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidCheckpointException(field + " 不能为空白");
        }
    }

    /** 便捷工厂：由结果列表自动推导 frontierStep。 */
    public static Checkpoint of(String workflowId, int version, List<String> results) {
        return new Checkpoint(workflowId, version, results.size() - 1,
                CURRENT_SCHEMA, results, Instant.now());
    }
}
