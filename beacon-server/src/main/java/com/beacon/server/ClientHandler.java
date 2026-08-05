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

/**
 * Handles one connected client in its own thread.
 * Lifecycle: accept socket → login (with password) → read messages in a loop → disconnect.
 */
public class ClientHandler implements Runnable {

    private static final String[] DEATH_MESSAGES = {
        "fell out of the world",
        "experienced kinetic energy",
        "was blown up by Creeper",
        "tried to swim in lava",
        "was slain by Zombie",
        "starved to death",
        "went up in flames",
        "hit the ground too hard",
        "was squashed by a falling anvil",
        "was struck by lightning"
    };

    private final Socket socket;
    private final ClientRegistry registry;
    private final UserRepository userRepo;
    private final ClientCommandProcessor commandProcessor;

    private BufferedReader in;
    private PrintWriter out;
    private String username;
    private int messagesSentThisSession = 0;
    private long sessionStartTime;
    private volatile long lastPongTime;
    private volatile long currentRtt = 0;
    private String currentChannel = "global";
    private Thread heartbeatThread;

    public ClientHandler(Socket socket, ClientRegistry registry,
                         UserRepository userRepo, MessageRepository messageRepo) {
        this.socket = socket;
        this.registry = registry;
        this.userRepo = userRepo;
        this.commandProcessor = new ClientCommandProcessor(registry, messageRepo);
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

        // Send recent message history for global (dimmed on client side)
        commandProcessor.sendHistory(currentChannel, this);

        broadcast(new Message(MessageType.JOINED).sender(username).color(color), currentChannel);
        return true;
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

            if (msg.getType() == MessageType.QUIT) {
                return;
            } else if (msg.getType() == MessageType.PONG) {
                handlePong(msg);
            } else {
                commandProcessor.process(msg, this);
            }
        }
    }

    // ── Heartbeat & Transport Helpers ───────────────────────────

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

    public void broadcast(Message message, String targetChannel) {
        message.channel(targetChannel);
        for (ClientHandler client : registry.getAllClients()) {
            if (targetChannel.equals(client.getCurrentChannel())) {
                client.sendMessage(message);
            }
        }
    }

    // ── Cleanup ────────────────────────────────────────────────

    private void disconnect() {
        if (username != null) {
            registry.unregister(username);
            String deathMsg = DEATH_MESSAGES[new java.util.Random().nextInt(DEATH_MESSAGES.length)];
            broadcast(new Message(MessageType.LEFT).sender(username).content(deathMsg), currentChannel);
            ServerUI.log("[-] " + username + " disconnected");
        }
        try {
            socket.close();
        } catch (IOException ignored) {}
    }

    public String getUsername() {
        return username;
    }

    public long getCurrentRtt() {
        return currentRtt;
    }
    
    public String getCurrentChannel() {
        return currentChannel;
    }

    public void setCurrentChannel(String channel) {
        this.currentChannel = channel;
    }

    public int getMessagesSent() {
        return messagesSentThisSession;
    }

    public void incrementMessagesSent() {
        this.messagesSentThisSession++;
    }

    public long getSessionStartTime() {
        return sessionStartTime;
    }

}


