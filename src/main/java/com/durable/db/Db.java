package com.durable.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;

/**
 * 数据库连接与表结构管理。
 *
 * 连接信息来自 classpath:db.properties，改连接只需改那一个文件。
 * 不使用 Spring —— 这里只有连接池和几行 JDBC。
 */
public final class Db {

    private static final String CONFIG_RESOURCE = "db.properties";
    private static final String SCHEMA_RESOURCE = "schema.sql";

    /** 按依赖顺序列出所有表，重建时逐个 DROP。 */
    private static final List<String> TABLES =
            List.of("journal", "effect_ledger", "checkpoint", "host",
                    "branch", "resume_attempt", "interrupt");

    private static volatile HikariDataSource dataSource;

    private Db() {
    }

    /** 全局唯一的连接池（双重检查锁，避免重复创建）。 */
    public static DataSource dataSource() {
        HikariDataSource local = dataSource;
        if (local == null) {
            synchronized (Db.class) {
                local = dataSource;
                if (local == null) {
                    local = createDataSource();
                    dataSource = local;
                }
            }
        }
        return local;
    }

    private static HikariDataSource createDataSource() {
        Properties props = loadProperties();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(required(props, "db.url"));
        config.setUsername(required(props, "db.username"));
        config.setPassword(props.getProperty("db.password", ""));
        config.setDriverClassName(props.getProperty("db.driver", "com.mysql.cj.jdbc.Driver"));
        config.setMaximumPoolSize(Integer.parseInt(props.getProperty("db.poolSize", "8")));
        config.setPoolName("durable-pool");
        config.setInitializationFailTimeout(-1);
        return new HikariDataSource(config);
    }

    /** 重建表结构：先 DROP 再按 schema.sql 逐条执行。仅供测试使用。 */
    public static void resetSchema() {
        String ddl = readResource(SCHEMA_RESOURCE);
        try (Connection conn = dataSource().getConnection();
             Statement st = conn.createStatement()) {
            for (String table : TABLES) {
                st.execute("DROP TABLE IF EXISTS " + table);
            }
            for (String raw : ddl.split(";")) {
                String sql = raw.trim();
                if (!sql.isEmpty()) {
                    st.execute(sql);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("重建表结构失败，请确认 durable_test 库已创建且账号有权限", e);
        }
    }

    /** 执行一条 COUNT 查询并返回结果。 */
    public static long countRows(String sql, Object... args) {
        try (Connection conn = dataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询失败: " + sql, e);
        }
    }

    /** 关闭连接池。测试类收尾时调用。 */
    public static synchronized void shutdown() {
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
        }
    }

    private static String required(Properties props, String key) {
        String value = props.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("db.properties 缺少必填项: " + key);
        }
        return value;
    }

    private static Properties loadProperties() {
        Properties props = new Properties();
        try (InputStream in = Db.class.getClassLoader().getResourceAsStream(CONFIG_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("找不到配置文件: " + CONFIG_RESOURCE);
            }
            props.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("读取配置失败: " + CONFIG_RESOURCE, e);
        }
        return props;
    }

    private static String readResource(String resource) {
        try (InputStream in = Db.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("找不到资源: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取资源失败: " + resource, e);
        }
    }
}
