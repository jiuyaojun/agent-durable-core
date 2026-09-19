package com.durable.checkpoint;

/** 试图持久化 schema 非法的检查点时抛出。此时不得有任何数据落库。 */
public class InvalidCheckpointException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidCheckpointException(String message) {
        super(message);
    }
}
