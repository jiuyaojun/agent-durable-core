package com.durable.shell;

/** 被 Agent 操作的资源。创建主机是花钱且不可逆的副作用。 */
public record Host(String id, String name, String status, String createdBy) {
}
