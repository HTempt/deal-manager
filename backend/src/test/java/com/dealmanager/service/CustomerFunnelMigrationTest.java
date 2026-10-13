package com.dealmanager.service;

import com.dealmanager.domain.Customer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.*;

class CustomerFunnelMigrationTest {
    @Test
    void realV11BackfillsAndSubsequentRegressionOrLossPreservesHistory() throws Exception {
        String url = "jdbc:h2:mem:funnel_" + java.util.UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (var connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE customer(id BIGINT PRIMARY KEY, stage VARCHAR(30), status VARCHAR(20), user_id BIGINT DEFAULT 7, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            String[] stages = {"新客户", "需求沟通", "方案中", "报价中", "谈判中", "已成交", "未知", "已流失", "报价中"};
            for (int i = 0; i < stages.length; i++) {
                try (var insert = connection.prepareStatement("INSERT INTO customer(id,stage,status) VALUES(?,?,?)")) {
                    insert.setLong(1, i); insert.setString(2, stages[i]);
                    insert.setString(3, i == 6 ? "DEAL" : i >= 7 ? "LOST" : "ACTIVE"); insert.executeUpdate();
                }
            }
            // Use the actual migration with Flyway, isolating it from MySQL-specific older schema migrations.
            var directory = Files.createTempDirectory("funnel-migration-");
            try {
                try (var script = getClass().getResourceAsStream("/db/migration/V11__customer_funnel.sql")) {
                    assertNotNull(script);
                    Files.copy(script, directory.resolve("V11__customer_funnel.sql"));
                }
                Flyway.configure().dataSource(url, "sa", "").locations("filesystem:" + directory)
                        .baselineOnMigrate(true).baselineVersion("10").load().migrate();
            } finally {
                Files.deleteIfExists(directory.resolve("V11__customer_funnel.sql")); Files.deleteIfExists(directory);
            }
            try (var indexes = connection.getMetaData().getIndexInfo(null, "PUBLIC", "CUSTOMER", false, false)) {
                boolean cohortIndexFound = false;
                while (indexes.next()) {
                    if ("IDX_CUSTOMER_FUNNEL_COHORT".equalsIgnoreCase(indexes.getString("INDEX_NAME"))) cohortIndexFound = true;
                }
                assertTrue(cohortIndexFound, "Monthly owner-scoped cohort queries must have their composite index");
            }
            int[] expected = {0, 1, 2, 3, 4, 5, 5, 0, 3};
            try (var rows = connection.createStatement().executeQuery("SELECT * FROM customer ORDER BY id")) {
                for (int rank : expected) { assertTrue(rows.next()); assertEquals(rank, rows.getInt("funnel_stage_rank")); }
                assertFalse(rows.next());
            }
            Customer c = new Customer(); c.setFunnelStageRank(4); c.setStage("需求沟通"); c.setStatus("ACTIVE");
            CustomerFunnel.advance(c); c.setStage("已流失"); c.setStatus("LOST"); CustomerFunnel.advance(c);
            try (var update = connection.prepareStatement("UPDATE customer SET stage=?,status=?,funnel_stage_rank=? WHERE id=4")) {
                update.setString(1, c.getStage()); update.setString(2, c.getStatus()); update.setInt(3, c.getFunnelStageRank()); update.executeUpdate();
            }
            try (var rows = connection.createStatement().executeQuery("SELECT funnel_stage_rank FROM customer WHERE id=4")) {
                assertTrue(rows.next()); assertEquals(4, rows.getInt(1));
            }
        }
    }
}
