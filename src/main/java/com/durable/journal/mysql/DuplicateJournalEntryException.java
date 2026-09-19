package com.durable.journal.mysql;

/** 试图向日志的同一位置写入第二条记录时抛出。 */
public class DuplicateJournalEntryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DuplicateJournalEntryException(String message, Throwable cause) {
        super(message, cause);
    }
}
