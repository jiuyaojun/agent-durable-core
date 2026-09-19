package com.durable.journal;

import java.util.List;
import java.util.Optional;

/**
 * 决策日志的存储接口。
 *
 * 实现必须保证：同一 (workflowId, stepNo, type) 只能成功写入一次。
 * 这条不变量由存储层的唯一约束保证，而不是由调用方小心保证 ——
 * 因为「调用方小心」在崩溃和并发面前是不可靠的。
 */
public interface JournalStore {

    /**
     * 追加一条日志。
     *
     * @throws com.durable.journal.mysql.DuplicateJournalEntryException 该位置已有日志
     */
    void append(JournalEntry entry);

    /** 按 stepNo 升序读取某工作流的全部日志。 */
    List<JournalEntry> load(String workflowId);

    /** 查找指定位置的日志。 */
    Optional<JournalEntry> find(String workflowId, int stepNo, JournalEntryType type);

    /** 指定位置是否已有日志。 */
    boolean exists(String workflowId, int stepNo, JournalEntryType type);
}
