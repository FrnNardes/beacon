package com.beacon.server;

import com.beacon.protocol.Message;
import com.beacon.protocol.MessageType;
import com.beacon.protocol.ProtocolException;
import com.beacon.protocol.ProtocolUtil;
import com.beacon.server.persistence.MessageRepository;
import com.beacon.server.persistence.UserRepository;
import com.beacon.server.ui.ServerUI;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Handles one connected client in its own thread.
 * Lifecycle: accept socket → login (with password) → read messages in a loop → disconnect.
 */
public class ClientHandler implements Runnable {

    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final int HISTORY_LIMIT = 30;
    private static final int SEARCH_LIMIT = 20;

    private final Socket socket;
    private final ClientRegistry registry;
    private final UserRepository userRepo;
    private final MessageRepository messageRepo;

    private BufferedReader in;
    private PrintWriter out;
    private String username;
    private int messagesSentThisSession = 0;
    private long sessionStartTime;
    private volatile long lastPongTime;
    private volatile long currentRtt = 0;
    private Thread heartbeatThread;

    public ClientHandler(Socket socket, ClientRegistry registry,
                         UserRepository userRepo, MessageRepository messageRepo) {
        this.socket = socket;
        this.registry = registry;
        this.userRepo = userRepo;
        this.messageRepo = messageRepo;
    }

    @Override
    public void run() {
        try {
            setupStreams();
            if (!handleLogin()) return;
            sessionStartTime = System.currentTimeMillis();
            lastPongTime = System.currentTimeMillis();
            startHeartbeat();
            readLoop();
        } catch (IOException e) {
            ServerUI.log("[!] Connection error with " +
                    (username != null ? username : socket.getRemoteSocketAddress()) +
                    ": " + e.getMessage());
        } finally {
            disconnect();
        }
    }

    private void setupStreams() throws IOException {
        in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        out = new PrintWriter(socket.getOutputStream(), true);
    }

    // ── Login with password + auto-register ────────────────────

    private boolean handleLogin() throws IOException {
        String line = in.readLine();
        if (line == null) return false;

        Message loginMsg;
        try {
            loginMsg = ProtocolUtil.deserialize(line);
        } catch (ProtocolException e) {
            sendMessage(new Message(MessageType.LOGIN_ERROR).content("Invalid protocol message"));
            return false;
        }

        if (loginMsg.getType() != MessageType.LOGIN) {
            sendMessage(new Message(MessageType.LOGIN_ERROR).content("Expected LOGIN message"));
            return false;
        }

        String requestedName = loginMsg.getSender();
        if (requestedName == null || requestedName.isBlank()) {
            sendMessage(new Message(MessageType.LOGIN_ERROR).content("Username cannot be empty"));
            return false;
        }
        requestedName = requestedName.toLowerCase();

        // Check if user is already online (different from DB existence)
        if (registry.isOnline(requestedName)) {
            sendMessage(new Message(MessageType.LOGIN_ERROR).content("User already online"));
            return false;
        }

        // Authenticate against DB (auto-registers new users)
        String password = loginMsg.getContent();
        try {
            String error = userRepo.authenticateOrRegister(requestedName, password);
            if (error != null) {
                sendMessage(new Message(MessageType.LOGIN_ERROR).content(error));
                return false;
            }
        } catch (SQLException e) {
            ServerUI.logError("[!] DB error during login: " + e.getMessage());
            sendMessage(new Message(MessageType.LOGIN_ERROR).content("Server database error"));
            return false;
        }

        // Register in the live registry
        if (!registry.register(requestedName, this)) {
            sendMessage(new Message(MessageType.LOGIN_ERROR).content("User already online"));
            return false;
        }

        this.username = requestedName;

        // Assign a unique color from the server palette
        String color = registry.assignColor(username);
        sendMessage(new Message(MessageType.LOGIN_OK).color(color));
        ServerUI.log("[+] " + username + " logged in from " + socket.getRemoteSocketAddress());

        // Send recent message history (dimmed on client side)
        sendHistory();

        broadcast(new Message(MessageType.JOINED).sender(username).color(color));
        return true;
    }

    private void sendHistory() {
        try {
            List<Message> history = messageRepo.getRecentMessages(HISTORY_LIMIT);
            for (Message msg : history) {
                sendMessage(msg);
            }
        } catch (SQLException e) {
            ServerUI.logError("[!] Failed to load history: " + e.getMessage());
        }
    }

    // ── Main message loop ──────────────────────────────────────

    private void readLoop() throws IOException {
        String line;
        while ((line = in.readLine()) != null) {
            Message msg;
            try {
                msg = ProtocolUtil.deserialize(line);
            } catch (ProtocolException e) {
                sendMessage(new Message(MessageType.ERROR).content("Malformed message"));
                continue;
            }

            switch (msg.getType()) {
                case MESSAGE -> handleBroadcast(msg);
                case PRIVATE -> handlePrivate(msg);
                case LIST -> handleList();
                case SEARCH -> handleSearch(msg);
                case STATS -> handleStats();
                case FILE_META, FILE_DATA -> handleFileTransfer(msg);
                case QUIT -> { return; }
                case PONG -> handlePong(msg);
                default -> sendMessage(new Message(MessageType.ERROR)
                        .content("Unexpected message type: " + msg.getType()));
            }
        }
    }

    // ── Message handlers ───────────────────────────────────────

    private void handleBroadcast(Message msg) {
        registry.incrementMessages();
        String timestamp = now();
        int id;
        try {
            id = messageRepo.saveMessage(username, null, msg.getContent());
        } catch (SQLException e) {
            ServerUI.logError("[!] Failed to save message: " + e.getMessage());
            id = -1; // still send the message even if DB fails
        }

        messagesSentThisSession++;
        Message outgoing = new Message(MessageType.MESSAGE)
                .id(id)
                .sender(username)
                .content(msg.getContent())
                .timestamp(timestamp)
                .color(registry.getColor(username));

        broadcast(outgoing);
    }

    private void handlePrivate(Message msg) {
        registry.incrementMessages();
        String targetName = msg.getRecipient();
        if (targetName == null || targetName.isBlank()) {
            sendMessage(new Message(MessageType.ERROR).content("Recipient is required"));
            return;
        }
        targetName = targetName.toLowerCase();

        ClientHandler target = registry.getClient(targetName);
        if (target == null) {
            sendMessage(new Message(MessageType.ERROR).content("User not found: " + targetName));
            return;
        }

        String timestamp = now();
        int id;
        try {
            id = messageRepo.saveMessage(username, targetName, msg.getContent());
        } catch (SQLException e) {
            ServerUI.logError("[!] Failed to save private message: " + e.getMessage());
            id = -1;
        }

        messagesSentThisSession++;
        Message outgoing = new Message(MessageType.PRIVATE)
                .id(id)
                .sender(username)
                .recipient(targetName)
                .content(msg.getContent())
                .timestamp(timestamp)
                .color(registry.getColor(username));

        target.sendMessage(outgoing);
        sendMessage(outgoing);
    }

    private void handleList() {
        String usersJson = String.join(", ", registry.getAllUsernames());
        sendMessage(new Message(MessageType.USER_LIST).content(usersJson));
    }

    private void handleSearch(Message msg) {
        String keyword = msg.getContent();
        if (keyword == null || keyword.isBlank()) {
            sendMessage(new Message(MessageType.ERROR).content("Search keyword cannot be empty"));
            return;
        }

        try {
            List<Message> results = messageRepo.searchMessages(this.username, keyword, 20);
            if (results.isEmpty()) {
                sendMessage(new Message(MessageType.SEARCH_RESULT)
                        .content("No messages found for: " + keyword));
            } else {
                for (Message result : results) {
                    sendMessage(result);
                }
            }
        } catch (SQLException e) {
            sendMessage(new Message(MessageType.ERROR).content("Search failed: " + e.getMessage()));
        }
    }

    private void handleFileTransfer(Message msg) {
        String targetName = msg.getRecipient();
        if (targetName == null || targetName.isBlank()) {
            sendMessage(new Message(MessageType.ERROR).content("Recipient is required for file transfer"));
            return;
        }
        targetName = targetName.toLowerCase();

        ClientHandler target = registry.getClient(targetName);
        if (target == null) {
            sendMessage(new Message(MessageType.ERROR).content("User not found: " + targetName));
            return;
        }

        // Just forward the message directly to the recipient
        // We override the sender so the recipient knows who sent it
        msg.sender(username);
        target.sendMessage(msg);
    }

    private void handlePong(Message msg) {
        lastPongTime = System.currentTimeMillis();
        try {
            long sentTime = Long.parseLong(msg.getContent());
            currentRtt = lastPongTime - sentTime;
        } catch (NumberFormatException ignored) {}
    }

    private void startHeartbeat() {
        heartbeatThread = new Thread(() -> {
            while (!socket.isClosed()) {
                try {
                    Thread.sleep(10000); // 10 seconds
                    
                    if (System.currentTimeMillis() - lastPongTime > 30000) {
                        ServerUI.log("[!] " + username + " timed out (no PONG for 30s)");
                        disconnect();
                        break;
                    }
                    
                    sendMessage(new Message(MessageType.PING)
                            .content(String.valueOf(System.currentTimeMillis())));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "heartbeat-" + username);
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();
    }

    private void handleStats() {
        long sessionDurationMs = System.currentTimeMillis() - sessionStartTime;
        long minutes = sessionDurationMs / 60000;
        long seconds = (sessionDurationMs % 60000) / 1000;

        int totalMessages;
        try {
            totalMessages = messageRepo.getTotalMessageCount();
        } catch (SQLException e) {
            totalMessages = -1;
        }

        String stats = String.join("\n",
                "Messages sent this session: " + messagesSentThisSession,
                "Session duration: " + minutes + "m " + seconds + "s",
                "Users online: " + registry.getOnlineCount(),
                "Current latency (RTT): " + currentRtt + " ms",
                "Total messages (all time): " + totalMessages
        );

        sendMessage(new Message(MessageType.STATS_RESULT).content(stats));
    }

    // ── Output ─────────────────────────────────────────────────

    /**
     * Sends a message to THIS client's socket.
     * Synchronized to prevent interleaved output when multiple
     * threads broadcast simultaneously.
     */
    public synchronized void sendMessage(Message message) {
        if (out != null) {
            out.println(ProtocolUtil.serialize(message));
        }
    }

    private void broadcast(Message message) {
        for (ClientHandler client : registry.getAllClients()) {
            client.sendMessage(message);
        }
    }

    // ── Cleanup ────────────────────────────────────────────────

    private void disconnect() {
        if (username != null) {
            registry.unregister(username);
            broadcast(new Message(MessageType.LEFT).sender(username));
            ServerUI.log("[-] " + username + " disconnected");
        }
        try {
            socket.close();
        } catch (IOException ignored) {}
    }

    private String now() {
        return LocalDateTime.now().format(TIMESTAMP_FMT);
    }

    public String getUsername() {
        return username;
    }

    public long getCurrentRtt() {
        return currentRtt;
    }
}


