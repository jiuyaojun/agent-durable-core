package com.durable.effect;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 一个会在数据库里产生副作用的操作。
 *
 * 注意签名：它拿到的是 {@link Connection}，而不是自己去开连接。
 * 这是刻意的 —— 副作用必须和效果账本的写入在【同一个事务】里，
 * 否则崩溃落在两者之间就会重复执行。真正的 exactly-once 靠的就是这一点。
 *
 * 边界说明：这只适用于**副作用发生在同一个数据库**的场景。
 * 如果副作用在外部系统（调支付网关、发短信），同事务是不可能的，
 * 只能退化为 at-least-once + 幂等键 + 对方幂等接收。
 */
@FunctionalInterface
public interface TransactionalEffect {

    /**
     * @param conn 由 {@link EffectLedger} 提供，已开启事务；实现不得自行提交或关闭
     * @return 副作用的可观测结果
     */
    String run(Connection conn) throws SQLException;
}
