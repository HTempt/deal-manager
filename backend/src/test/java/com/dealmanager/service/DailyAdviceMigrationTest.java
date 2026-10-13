package com.dealmanager.service;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DailyAdviceMigrationTest {
    @Test
    void realV12KeepsOneAdviceRowPerOwnerAndBeijingDateWithFullAuditColumns() throws Exception {
        String url = "jdbc:h2:mem:daily_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        var directory = Files.createTempDirectory("daily-advice-migration-");
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            try (var script = getClass().getResourceAsStream("/db/migration/V12__daily_advice.sql")) {
                assertNotNull(script);
                Files.copy(script, directory.resolve("V12__daily_advice.sql"));
            }
            Flyway.configure().dataSource(url, "sa", "").locations("filesystem:" + directory).load().migrate();

            insert(connection, 7L, LocalDate.of(2026, 2, 14), "今天先联系报价未回复的客户");
            insert(connection, 7L, LocalDate.of(2026, 2, 15), "第二天重新生成");
            insert(connection, 8L, LocalDate.of(2026, 2, 14), "另一个账号同一天互不影响");
            // 同一账号同一天只能有一份当天建议，重新生成走覆盖更新。
            SQLException duplicate = assertThrows(SQLException.class,
                    () -> insert(connection, 7L, LocalDate.of(2026, 2, 14), "重复写入"));
            assertTrue(duplicate.getMessage().toLowerCase().contains("unique"));

            try (var rows = connection.createStatement().executeQuery(
                    "SELECT user_id, advice_date, overview, items, candidate_count, source_hash, model, token_usage FROM daily_advice ORDER BY user_id, advice_date")) {
                assertTrue(rows.next());
                assertEquals(7L, rows.getLong("user_id"));
                assertEquals(Date.valueOf("2026-02-14"), rows.getDate("advice_date"));
                assertEquals("今天先联系报价未回复的客户", rows.getString("overview"));
                assertEquals("[{\"customerId\":8,\"priority\":\"S\",\"advice\":\"建议今天下午电话联系\"}]", rows.getString("items"));
                assertEquals(6, rows.getInt("candidate_count"));
                assertEquals("test-model", rows.getString("model"));
                assertEquals(88, rows.getInt("token_usage"));
                assertTrue(rows.next());
                assertEquals(Date.valueOf("2026-02-15"), rows.getDate("advice_date"));
                assertTrue(rows.next());
                assertEquals(8L, rows.getLong("user_id"));
                assertFalse(rows.next());
            }
        } finally {
            Files.deleteIfExists(directory.resolve("V12__daily_advice.sql"));
            Files.deleteIfExists(directory);
        }
    }

    private static void insert(Connection connection, long userId, LocalDate date, String overview) throws SQLException {
        try (var insert = connection.prepareStatement("INSERT INTO daily_advice(user_id, advice_date, overview, items, candidate_count, source_hash, prompt, model, token_usage, created_at, updated_at) VALUES(?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)")) {
            insert.setLong(1, userId);
            insert.setDate(2, Date.valueOf(date));
            insert.setString(3, overview);
            insert.setString(4, "[{\"customerId\":8,\"priority\":\"S\",\"advice\":\"建议今天下午电话联系\"}]");
            insert.setInt(5, 6);
            insert.setString(6, "0".repeat(64));
            insert.setString(7, "候选客户 {\"schemaVersion\":1}");
            insert.setString(8, "test-model");
            insert.setInt(9, 88);
            insert.executeUpdate();
        }
    }
}