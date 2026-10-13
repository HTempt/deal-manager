package com.dealmanager.common;

import com.dealmanager.testutil.LogCapture;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

class GlobalExceptionHandlerTest {
    @Test
    void unexpectedFailureKeepsTypeStackAndDatabaseErrorCodeWithoutSqlOrSecrets() {
        SQLException database = new SQLException("SELECT private-phone FROM customer; password=secret", "08001", 1045);
        RuntimeException error = new RuntimeException("https://vendor?secret=live-key", database);
        try (var logs = new LogCapture(GlobalExceptionHandler.class)) {
            var result = new GlobalExceptionHandler().handleUnknown(error);
            assertEquals(500, result.getStatusCode().value());
            assertTrue(logs.output().contains("java.sql.SQLException sqlState=08001 vendorCode=1045"));
            assertTrue(logs.output().contains("GlobalExceptionHandlerTest"));
            for (String secret : new String[]{"SELECT", "private-phone", "password=", "live-key", "https://vendor"}) {
                assertFalse(logs.output().contains(secret));
            }
            assertEquals("服务暂时不可用，请稍后重试", result.getBody().get("message"));
        }
    }
}
