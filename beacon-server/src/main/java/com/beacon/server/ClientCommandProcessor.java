package com.beacon.server;

import com.beacon.protocol.Message;
import com.beacon.protocol.MessageType;
import com.beacon.server.persistence.MessageRepository;
import com.beacon.server.ui.ServerUI;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Handles the business logic for incoming messages from a client.
 * Decouples command processing from the raw TCP socket handling.
 */
public class ClientCommandProcessor {

    private static final DateTimeFormatter TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final int HISTORY_LIMIT = 30;

    private final ClientRegistry registry;
    private final MessageRepository messageRepo;

    public ClientCommandProcessor(ClientRegistry registry, MessageRepository messageRepo) {
        this.registry = registry;
        this.messageRepo = messageRepo;
    }

    public void process(Message msg, ClientHandler client) {
        switch (msg.getType()) {
            case MESSAGE -> handleBroadcast(msg, client);
            case PRIVATE -> handlePrivate(msg, client);
            case LIST -> handleList(client);
            case LIST_ALL -> handleListAll(client);
            case SEARCH -> handleSearch(msg, client);
            case STATS -> handleStats(client);
            case FILE_META, FILE_DATA -> handleFileTransfer(msg, client);
            default -> client.sendMessage(new Message(MessageType.ERROR).content("Unexpected message type"));
        }
    }

    private void handleBroadcast(Message msg, ClientHandler client) {
        String username = client.getUsername();
        String currentChannel = client.getCurrentChannel();

        if (msg.getContent() != null && msg.getContent().startsWith("/join ")) {
            String newChannel = msg.getContent().substring(6).trim().toLowerCase();
            if (newChannel.isEmpty()) return;
            
            client.broadcast(new Message(MessageType.SYSTEM).sender("SYSTEM").content(username + " left the channel"), currentChannel);
            client.setCurrentChannel(newChannel);
            
            sendHistory(newChannel, client);
            
            client.broadcast(new Message(MessageType.SYSTEM).sender("SYSTEM").content(username + " joined the channel"), newChannel);
            ServerUI.log("[*] " + username + " joined channel #" + newChannel);
            return;
        }

        registry.incrementMessages();
        int id;
        try {
            id = messageRepo.saveMessage(username, null, msg.getContent(), currentChannel);
        } catch (SQLException e) {
            ServerUI.logError("[!] Failed to save message: " + e.getMessage());
            id = -1;
        }

        client.incrementMessagesSent();
        Message outgoing = new Message(MessageType.MESSAGE)
                .id(id)
                .sender(username)
                .content(msg.getContent())
                .timestamp(now())
                .channel(currentChannel)
                .color(registry.getColor(username));

        client.broadcast(outgoing, currentChannel);
    }

    private void handlePrivate(Message msg, ClientHandler client) {
        registry.incrementMessages();
        String targetName = msg.getRecipient();
        if (targetName == null || targetName.isBlank()) {
            client.sendMessage(new Message(MessageType.ERROR).content("Recipient is required"));
            return;
        }
        targetName = targetName.toLowerCase();

        ClientHandler target = registry.getClient(targetName);
        if (target == null) {
            client.sendMessage(new Message(MessageType.ERROR).content("User not found: " + targetName));
            return;
        }

        int id;
        try {
            id = messageRepo.saveMessage(client.getUsername(), targetName, msg.getContent(), client.getCurrentChannel());
        } catch (SQLException e) {
            ServerUI.logError("[!] Failed to save private message: " + e.getMessage());
            id = -1;
        }

        client.incrementMessagesSent();
        Message outgoing = new Message(MessageType.PRIVATE)
                .id(id)
                .sender(client.getUsername())
                .recipient(targetName)
                .content(msg.getContent())
                .timestamp(now())
                .color(registry.getColor(client.getUsername()));

        target.sendMessage(outgoing);
        client.sendMessage(outgoing);
    }

    private void handleList(ClientHandler client) {
        List<String> channelUsers = registry.getAllClients().stream()
                .filter(c -> client.getCurrentChannel().equals(c.getCurrentChannel()))
                .map(ClientHandler::getUsername)
                .toList();
                
        String usersJson = formatUserList(channelUsers);
        client.sendMessage(new Message(MessageType.USER_LIST).content("Channel #" + client.getCurrentChannel() + " (" + channelUsers.size() + "): " + usersJson));
    }

    private void handleListAll(ClientHandler client) {
        List<String> allUsers = registry.getAllUsernames();
        String usersJson = formatUserList(allUsers);
        client.sendMessage(new Message(MessageType.USER_LIST).content("Global (" + allUsers.size() + " online): " + usersJson));
    }

    private String formatUserList(List<String> users) {
        if (users.size() > 10) {
            List<String> truncated = users.subList(0, 10);
            return String.join(", ", truncated) + " ... and " + (users.size() - 10) + " more";
        }
        return String.join(", ", users);
    }

    private void handleSearch(Message msg, ClientHandler client) {
        String keyword = msg.getContent();
        if (keyword == null || keyword.isBlank()) {
            client.sendMessage(new Message(MessageType.ERROR).content("Search keyword cannot be empty"));
            return;
        }

        try {
            List<Message> results = messageRepo.searchMessages(client.getUsername(), keyword, 20, client.getCurrentChannel());
            if (results.isEmpty()) {
                client.sendMessage(new Message(MessageType.SEARCH_RESULT).content("No messages found for: " + keyword));
            } else {
                for (Message result : results) {
                    client.sendMessage(result);
                }
            }
        } catch (SQLException e) {
            client.sendMessage(new Message(MessageType.ERROR).content("Search failed: " + e.getMessage()));
        }
    }

    private void handleFileTransfer(Message msg, ClientHandler client) {
        String targetName = msg.getRecipient();
        if (targetName == null || targetName.isBlank()) {
            client.sendMessage(new Message(MessageType.ERROR).content("Recipient is required for file transfer"));
            return;
        }

        ClientHandler target = registry.getClient(targetName.toLowerCase());
        if (target == null) {
            client.sendMessage(new Message(MessageType.ERROR).content("User not found: " + targetName));
            return;
        }

        msg.sender(client.getUsername());
        target.sendMessage(msg);
    }

    private void handleStats(ClientHandler client) {
        long sessionDurationMs = System.currentTimeMillis() - client.getSessionStartTime();
        long minutes = sessionDurationMs / 60000;
        long seconds = (sessionDurationMs % 60000) / 1000;

        int totalMessages;
        try {
            totalMessages = messageRepo.getTotalMessageCount();
        } catch (SQLException e) {
            totalMessages = -1;
        }

        String stats = String.join("\n",
                "Messages sent this session: " + client.getMessagesSent(),
                "Session duration: " + minutes + "m " + seconds + "s",
                "Users online: " + registry.getOnlineCount(),
                "Current latency (RTT): " + client.getCurrentRtt() + " ms",
                "Total messages (all time): " + totalMessages
        );

        client.sendMessage(new Message(MessageType.STATS_RESULT).content(stats));
    }

    public void sendHistory(String channel, ClientHandler client) {
        try {
            List<Message> history = messageRepo.getRecentMessages(HISTORY_LIMIT, channel);
            for (Message msg : history) {
                client.sendMessage(msg);
            }
        } catch (SQLException e) {
            ServerUI.logError("[!] Failed to load history: " + e.getMessage());
        }
    }

    private String now() {
        return LocalDateTime.now().format(TIMESTAMP_FMT);
    }
}
