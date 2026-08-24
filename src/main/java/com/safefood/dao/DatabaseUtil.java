package com.safefood.dao;

import com.safefood.AppConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;

public class DatabaseUtil {

    private static final int PROBE_TIMEOUT_SECONDS = 4;
    private static final String REQUIRED_TABLE = "user";

    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(
                AppConfig.get("db.url", ""),
                AppConfig.get("db.user", ""),
                AppConfig.get("db.password", ""));
    }

    public static boolean isReady() {
        if (AppConfig.get("db.url", "").isBlank()) {
            return false;
        }
        int previousTimeout = DriverManager.getLoginTimeout();
        DriverManager.setLoginTimeout(PROBE_TIMEOUT_SECONDS);
        try (Connection conn = getConnection()) {
            if (!conn.isValid(PROBE_TIMEOUT_SECONDS)) {
                return false;
            }
            try (ResultSet tables = conn.getMetaData()
                    .getTables(conn.getCatalog(), null, REQUIRED_TABLE, null)) {
                return tables.next();
            }
        } catch (SQLException e) {
            System.err.println("[db] 연결 확인 실패: " + e.getMessage());
            return false;
        } finally {
            DriverManager.setLoginTimeout(previousTimeout);
        }
    }
}
