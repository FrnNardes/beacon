package com.beacon.protocol;

/**
 * Universal message envelope for the Beacon protocol.
 * Serialized as one JSON object per line over TCP/UDP.
 * Unused fields stay null and are omitted from the JSON by Gson.
 */
public class Message {

    private MessageType type;
    private Integer id;
    private String sender;
    private String recipient;
    private String content;
    private String timestamp;
    private String color;
    private String channel = "global";

    // Required by Gson for deserialization via reflection
    public Message() {
    }

    public Message(MessageType type) {
        this.type = type;
    }

    // Builder-style setters — return this for chaining:
    // new Message(LOGIN).sender("fernando").content("pass")

    public Message type(MessageType type) {
        this.type = type;
        return this;
    }

    public Message id(Integer id) {
        this.id = id;
        return this;
    }

    public Message sender(String sender) {
        this.sender = sender;
        return this;
    }

    public Message recipient(String recipient) {
        this.recipient = recipient;
        return this;
    }

    public Message content(String content) {
        this.content = content;
        return this;
    }

    public Message timestamp(String timestamp) {
        this.timestamp = timestamp;
        return this;
    }

    public Message color(String color) {
        this.color = color;
        return this;
    }

    public Message channel(String channel) {
        this.channel = channel;
        return this;
    }

    // Getters

    public MessageType getType() {
        return type;
    }

    public Integer getId() {
        return id;
    }

    public String getSender() {
        return sender;
    }

    public String getRecipient() {
        return recipient;
    }

    public String getContent() {
        return content;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public String getColor() {
        return color;
    }

    public String getChannel() {
        return channel;
    }

    @Override
    public String toString() {
        return "Message{type=" + type +
                ", id=" + id +
                ", sender='" + sender + '\'' +
                ", recipient='" + recipient + '\'' +
                ", content='" + content + '\'' +
                ", timestamp='" + timestamp + '\'' +
                ", channel='" + channel + '\'' +
                '}';
    }
}
