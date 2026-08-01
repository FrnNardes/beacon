package com.beacon.server.persistence;

import org.mindrot.jbcrypt.BCrypt;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Handles user accounts: creation with BCrypt hashing, password verification.
 * Implements auto-register on first login.
 */
public class UserRepository {

    private final DatabaseManager db;

    public UserRepository(DatabaseManager db) {
        this.db = db;
    }

    /**
     * Checks if a username exists in the database.
     */
    public boolean userExists(String username) throws SQLException {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM users WHERE username = ?")) {
            ps.setString(1, username);
            return ps.executeQuery().next();
        }
    }

    /**
     * Creates a new user with a BCrypt-hashed password.
     * BCrypt.hashpw() generates a random salt and hashes the password
     * in one call — the salt is embedded in the output string, so we
     * don't need a separate salt column.
     */
    public void createUser(String username, String password) throws SQLException {
        String hash = BCrypt.hashpw(password, BCrypt.gensalt());

        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (username, password_hash) VALUES (?, ?)")) {
            ps.setString(1, username);
            ps.setString(2, hash);
            ps.executeUpdate();
        }
    }

    /**
     * Verifies a password against the stored hash.
     * BCrypt.checkpw() extracts the salt from the stored hash,
     * re-hashes the input password with the same salt, and compares.
     * Returns false if user doesn't exist.
     */
    public boolean verifyPassword(String username, String password) throws SQLException {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT password_hash FROM users WHERE username = ?")) {
            ps.setString(1, username);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return false;

            String storedHash = rs.getString("password_hash");
            return BCrypt.checkpw(password, storedHash);
        }
    }

    /**
     * Auto-register logic: if user doesn't exist, create account.
     * If user exists, verify password. Returns null on success,
     * or an error message string on failure.
     */
    public String authenticateOrRegister(String username, String password) throws SQLException {
        if (password == null || password.isBlank()) {
            return "Password cannot be empty";
        }

        if (!userExists(username)) {
            createUser(username, password);
            System.out.println("[db] New user registered: " + username);
            return null; // success
        }

        if (!verifyPassword(username, password)) {
            return "Wrong password";
        }

        return null; // success
    }
}
