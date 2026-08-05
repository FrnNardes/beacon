package com.beacon.server.persistence;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Manages the H2 embedded database lifecycle.
 * Creates the schema on first run, provides connections for repositories.
 */
public class DatabaseManager {

    private final String jdbcUrl;

    public DatabaseManager(String dbPath) {
        // H2 file-mode URL: creates the file if it doesn't exist.
        // ;AUTO_SERVER=TRUE allows multiple processes to access
        // the same DB file (useful if you accidentally start two servers).
        this.jdbcUrl = "jdbc:h2:file:" + dbPath + ";AUTO_SERVER=TRUE";
    }

    /**
     * Creates tables if they don't exist. Called once on server startup.
     * IF NOT EXISTS makes this idempotent — safe to run every time.
     */
    public void initialize() throws SQLException {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS users (
                    id       INTEGER PRIMARY KEY AUTO_INCREMENT,
                    username VARCHAR(30)  UNIQUE NOT NULL,
                    password_hash VARCHAR(255) NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS messages (
                    id        INTEGER PRIMARY KEY AUTO_INCREMENT,
                    sender    VARCHAR(30) NOT NULL,
                    recipient VARCHAR(30),
                    channel   VARCHAR(50) DEFAULT 'global',
                    content   TEXT NOT NULL,
                    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);

            // Migration: Add channel column if it doesn't exist (for older DBs)
            try {
                stmt.execute("ALTER TABLE messages ADD COLUMN channel VARCHAR(50) DEFAULT 'global'");
            } catch (SQLException e) {
                // Ignore: column already exists
            }

            System.out.println("[db] Database initialized at " + jdbcUrl);
        }
    }

    /**
     * Returns a new connection. Each call = new connection.
     * For our scale (dozens of clients), this is fine — no need for
     * a connection pool like HikariCP.
     */
    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, "sa", "");
    }
}
