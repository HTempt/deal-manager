package com.dealmanager.common;

import java.sql.SQLException;

/** Log metadata and stack locations without leaking payloads in exception messages. */
public final class LogSafety {
    private LogSafety() {}

    public static String text(String value) {
        if (value == null) return "-";
        String clean = value.replaceAll("[\\p{Cntrl}]", "_");
        return clean.substring(0, Math.min(clean.length(), 200));
    }

    public static Throwable exception(Throwable error) { return exception(error, 0); }

    private static Throwable exception(Throwable error, int depth) {
        if (error == null || depth >= 8) return null;
        String type = error.getClass().getName();
        if (error instanceof SQLException sql) {
            type += " sqlState=" + text(sql.getSQLState()) + " vendorCode=" + sql.getErrorCode();
        }
        RuntimeException safe = new RuntimeException(type, exception(error.getCause(), depth + 1));
        safe.setStackTrace(error.getStackTrace());
        return safe;
    }
}
