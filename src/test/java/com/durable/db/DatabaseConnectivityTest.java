package com.durable.db;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("数据库连通性")
class DatabaseConnectivityTest {

    @AfterAll
    static void tearDown() {
        Db.shutdown();
    }

    @Test
    @DisplayName("能连上 MySQL 并按 schema.sql 建出 journal 表")
    void canConnectAndCreateSchema() {
        Db.resetSchema();

        String version = queryForString("SELECT VERSION()");
        long rows = Db.countRows("SELECT COUNT(*) FROM journal");

        System.out.println("[真实输出] MySQL 版本 = " + version);
        System.out.println("[真实输出] journal 表行数 = " + rows);
        System.out.println("[真实输出] 连接串 = " + System.getProperty("user.dir"));

        assertTrue(version.startsWith("8."), "应为 MySQL 8.x，实际: " + version);
        assertEquals(0L, rows, "刚重建的表应为空");
    }

    private static String queryForString(String sql) {
        try (var conn = Db.dataSource().getConnection();
             var st = conn.createStatement();
             var rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        } catch (Exception e) {
            throw new IllegalStateException("查询失败: " + sql, e);
        }
    }
}
