package com.beacon.server.persistence;

import com.beacon.protocol.Message;
import com.beacon.protocol.MessageType;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists chat messages and provides history/search queries.
 */
public class MessageRepository {

    private final DatabaseManager db;

    public MessageRepository(DatabaseManager db) {
        this.db = db;
    }

    /**
     * Saves a message to the database. Returns the server-assigned ID.
     * recipient is null for broadcast messages.
     */
    public int saveMessage(String sender, String recipient, String content, String channel) throws SQLException {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO messages (sender, recipient, content, channel) VALUES (?, ?, ?, ?)",
                     Statement.RETURN_GENERATED_KEYS)) {

            ps.setString(1, sender);
            ps.setString(2, recipient); // null for broadcast — JDBC handles null correctly
            ps.setString(3, content);
            ps.setString(4, channel);
            ps.executeUpdate();

            // Retrieve the auto-generated ID
            ResultSet keys = ps.getGeneratedKeys();
            keys.next();
            return keys.getInt(1);
        }
    }

    /**
     * Returns the N most recent broadcast messages (for login history).
     * Only returns messages where recipient IS NULL (public messages).
     */
    public List<Message> getRecentMessages(int limit, String channel) throws SQLException {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT id, sender, content, timestamp, channel
                     FROM messages
                     WHERE recipient IS NULL AND channel = ?
                     ORDER BY timestamp DESC
                     LIMIT ?
                     """)) {

            ps.setString(1, channel);
            ps.setInt(2, limit);
            ResultSet rs = ps.executeQuery();

            List<Message> messages = new ArrayList<>();
            while (rs.next()) {
                messages.add(rowToMessage(rs, MessageType.HISTORY));
            }

            // Reverse so oldest is first (we queried DESC for the LIMIT,
            // but we want chronological order for display)
            java.util.Collections.reverse(messages);
            return messages;
        }
    }

    /**
     * Searches message content for a keyword. Returns up to 20 recent matches.
     * Uses SQL LIKE with wildcards — simple but sufficient for our scale.
     */
    public List<Message> searchMessages(String username, String keyword, int limit, String currentChannel) throws SQLException {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT id, sender, recipient, content, timestamp, channel
                     FROM messages
                     WHERE LOWER(content) LIKE LOWER(?)
                     AND channel = ?
                     AND (recipient IS NULL OR sender = ? OR recipient = ?)
                     ORDER BY timestamp DESC
                     LIMIT ?
                     """)) {

            ps.setString(1, "%" + keyword + "%");
            ps.setString(2, currentChannel);
            ps.setString(3, username);
            ps.setString(4, username);
            ps.setInt(5, limit);
            ResultSet rs = ps.executeQuery();

            List<Message> messages = new ArrayList<>();
            while (rs.next()) {
                messages.add(rowToMessage(rs, MessageType.SEARCH_RESULT));
            }

            java.util.Collections.reverse(messages);
            return messages;
        }
    }

    /**
     * Counts total messages in the database (for /stats).
     */
    public int getTotalMessageCount() throws SQLException {
        try (Connection conn = db.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM messages")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private Message rowToMessage(ResultSet rs, MessageType type) throws SQLException {
        String channel;
        try {
            channel = rs.getString("channel");
        } catch (SQLException e) {
            channel = "global"; // Fallback if column wasn't queried
        }
        return new Message(type)
                .id(rs.getInt("id"))
                .sender(rs.getString("sender"))
                .content(rs.getString("content"))
                .channel(channel)
                .timestamp(rs.getTimestamp("timestamp").toString());
    }
}
