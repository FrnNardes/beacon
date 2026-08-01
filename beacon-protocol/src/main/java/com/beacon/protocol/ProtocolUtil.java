package com.beacon.protocol;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

/**
 * Single point of contact with Gson.
 * Converts between {@link Message} objects and JSON strings.
 * Every other class works with Message objects — never raw JSON.
 */
public final class ProtocolUtil {

    // Gson is thread-safe — one shared instance for the whole app
    private static final Gson GSON = new GsonBuilder().create();

    private ProtocolUtil() {}

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
}
