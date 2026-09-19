package com.durable.shell;

import com.durable.effect.TransactionalEffect;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

/**
 * 外壳：一组有真实后果的资源操作。
 *
 * 存在的意义只是给持久化内核提供一个「副作用说真话」的试验田 ——
 * 创建主机花钱且不可逆，正好用来验证 exactly-once。
 * 内核不应该知道自己操作的是主机还是别的什么。
 *
 * 边界说明：这些操作把副作用写进**本项目自己的数据库**，
 * 因此可以和效果账本同事务，从而做到真正的 exactly-once。
 * 若换成调用外部支付网关，同事务不可能，只能 at-least-once + 幂等键。
 */
public class HostTools {

    private static final String SQL_INSERT =
            "INSERT INTO host (id, name, status, created_by) VALUES (?, ?, 'RUNNING', ?)";

    private final DataSource dataSource;

    public HostTools(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /**
     * 创建主机。**非幂等**：多跑一次就多花一份钱。
     * 用的是传进来的 conn —— 副作用必须和账本写入同事务。
     */
    public TransactionalEffect createHost(String hostId, String name, String createdBy) {
        return conn -> {
            insertHost(conn, hostId, name, createdBy);
            return hostId;
        };
    }

    /** 只读：列出主机。EffectType.NONE，可安全重放任意多次。 */
    public TransactionalEffect listHosts() {
        return conn -> {
            StringBuilder sb = new StringBuilder("[");
            try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM host ORDER BY id");
                 ResultSet rs = ps.executeQuery()) {
                boolean first = true;
                while (rs.next()) {
                    if (!first) {
                        sb.append(',');
                    }
                    sb.append('"').append(rs.getString(1)).append('"');
                    first = false;
                }
            }
            return sb.append(']').toString();
        };
    }

    private static void insertHost(Connection conn, String id, String name, String createdBy)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL_INSERT)) {
            ps.setString(1, id);
            ps.setString(2, name);
            ps.setString(3, createdBy);
            ps.executeUpdate();
        }
    }

    /** 当前主机数量。EO 的核心观测指标：崩溃恢复后它必须只增加一次。 */
    public long hostCount() {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM host");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            throw new IllegalStateException("统计主机失败", e);
        }
    }
}
