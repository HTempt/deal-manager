package com.dealmanager.service;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CustomerLocationMigrationTest {
    @Test
    void realV13BackfillsExistingCustomersAndAddsOptionalAddress() throws Exception {
        String url = "jdbc:h2:mem:location_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        var directory = Files.createTempDirectory("customer-location-migration-");
        try (var connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE customer(id BIGINT PRIMARY KEY, name VARCHAR(100))");
            connection.createStatement().execute("INSERT INTO customer(id, name) VALUES (1, '历史客户')");
            try (var script = getClass().getResourceAsStream("/db/migration/V13__customer_location.sql")) {
                assertNotNull(script);
                Files.copy(script, directory.resolve("V13__customer_location.sql"));
            }
            Flyway.configure().dataSource(url, "sa", "").locations("filesystem:" + directory)
                    .baselineOnMigrate(true).baselineVersion("12").load().migrate();

            try (var rows = connection.createStatement().executeQuery("SELECT city, address FROM customer WHERE id = 1")) {
                assertTrue(rows.next());
                assertEquals("未填写", rows.getString("city"));
                assertNull(rows.getString("address"));
            }
            try (var columns = connection.getMetaData().getColumns(null, "PUBLIC", "CUSTOMER", "CITY")) {
                assertTrue(columns.next());
                assertEquals("NO", columns.getString("IS_NULLABLE"));
            }
        } finally {
            Files.deleteIfExists(directory.resolve("V13__customer_location.sql"));
            Files.deleteIfExists(directory);
        }
    }
}
