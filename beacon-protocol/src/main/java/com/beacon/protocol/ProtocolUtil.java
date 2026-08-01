package com.beacon.protocol;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.nio.charset.StandardCharsets;

/**
 * Single point of contact with Gson.
 * Converts between {@link Message} objects and JSON strings.
 * Every other class works with Message objects — never raw JSON.
 */
public final class ProtocolUtil {

    // Gson is thread-safe — one shared instance for the whole app
    private static final Gson GSON = new GsonBuilder().create();

    private ProtocolUtil() {}

    // ── TCP helpers (JSON-per-line framing) ───────────────────────────────

    /**
     * Message → JSON string (without trailing newline).
     * The caller adds \n when writing to the socket.
     */
    public static String serialize(Message message) {
        if (message == null) {
            throw new ProtocolException("Cannot serialize null message");
        }
        try {
            return GSON.toJson(message);
        } catch (Exception e) {
            throw new ProtocolException("Failed to serialize message: " + message, e);
        }
    }

    /**
     * JSON string (one line from the socket) → Message object.
     * Throws ProtocolException if the JSON is malformed or missing a type.
     */
    public static Message deserialize(String json) {
        if (json == null || json.isBlank()) {
            throw new ProtocolException("Cannot deserialize null or blank JSON");
        }
        try {
            Message message = GSON.fromJson(json.trim(), Message.class);
            if (message == null) {
                throw new ProtocolException("Deserialization returned null for: " + json);
            }
            if (message.getType() == null) {
                throw new ProtocolException("Message has no type field: " + json);
            }
            return message;
        } catch (JsonSyntaxException e) {
            throw new ProtocolException("Malformed JSON: " + json, e);
        }
    }

    // ── UDP helpers (datagram = one message, no delimiter needed) ─────────

    /**
     * Serializes a {@link Message} to a UTF-8 byte array for a UDP datagram.
     * No newline delimiter — each datagram is already a discrete message boundary.
     */
    public static byte[] toUdpBytes(Message message) {
        return serialize(message).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Deserializes a UDP datagram payload into a {@link Message}.
     *
     * @param data   raw datagram buffer
     * @param length number of valid bytes (from DatagramPacket.getLength())
     * @return parsed Message
     * @throws ProtocolException if payload is not valid protocol JSON
     */
    public static Message fromUdpBytes(byte[] data, int length) {
        String json = new String(data, 0, length, StandardCharsets.UTF_8);
        return deserialize(json);
    }
}
