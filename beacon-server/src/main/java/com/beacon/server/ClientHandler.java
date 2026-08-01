package com.beacon.server;

import com.beacon.protocol.Message;
import com.beacon.protocol.MessageType;
import com.beacon.protocol.ProtocolException;
import com.beacon.protocol.ProtocolUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Handles one connected client in its own thread.
 * Lifecycle: accept socket → login → read messages in a loop → disconnect.
 */
public class ClientHandler implements Runnable {

    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final Socket socket;
    private final ClientRegistry registry;
    private BufferedReader in;
    private PrintWriter out;
    private String username;

    public ClientHandler(Socket socket, ClientRegistry registry) {
        this.socket = socket;
        this.registry = registry;
    }

    @Override
    public void run() {
        try {
            setupStreams();
            if (!handleLogin()) {
                return; // login failed or client disconnected
            }
            readLoop();
        } catch (IOException e) {
            System.out.println("[!] Connection error with " +
                    (username != null ? username : socket.getRemoteSocketAddress()) + ": " + e.getMessage());
        } finally {
            disconnect();
        }
    }

    // ── Connection setup ───────────────────────────────────────

    private void setupStreams() throws IOException {
        // BufferedReader reads text line-by-line from the socket's byte stream.
        // Each readLine() call blocks until a full line (\n) arrives — this is
        // how we solve the TCP framing problem (one JSON per line).
        in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

        // PrintWriter wraps the socket's output stream for convenient text writing.
        // autoFlush=true ensures println() sends data immediately instead of
        // buffering it (critical for a chat — messages must arrive instantly).
        out = new PrintWriter(socket.getOutputStream(), true);
    }

    // ── Login flow ─────────────────────────────────────────────

    /**
     * Waits for a LOGIN message, validates the username, and registers the client.
     * Returns true if login succeeded, false if the client should be disconnected.
     *
     * For now (Slice 2): password is ignored, just checks for unique username.
     * Slice 5 will add BCrypt password verification against the database.
     */
    private boolean handleLogin() throws IOException {
        String line = in.readLine();
        if (line == null) return false; // client disconnected before sending anything

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

        // Try to register — fails atomically if name is already taken
        if (!registry.register(requestedName, this)) {
            sendMessage(new Message(MessageType.LOGIN_ERROR).content("Username already in use"));
            return false;
        }

        this.username = requestedName;
        sendMessage(new Message(MessageType.LOGIN_OK));
        System.out.println("[+] " + username + " logged in from " + socket.getRemoteSocketAddress());

        // Notify everyone that this user joined
        broadcast(new Message(MessageType.JOINED).sender(username));
        return true;
    }

    // ── Main message loop ──────────────────────────────────────

    /**
     * Reads messages in a loop until the client disconnects or sends QUIT.
     * Each line from the socket is one JSON message.
     */
    private void readLoop() throws IOException {
        String line;
        // readLine() blocks until data arrives. Returns null when the
        // connection is closed (TCP FIN received).
        while ((line = in.readLine()) != null) {
            Message msg;
            try {
                msg = ProtocolUtil.deserialize(line);
            } catch (ProtocolException e) {
                sendMessage(new Message(MessageType.ERROR).content("Malformed message"));
                continue; // skip bad messages, don't disconnect
            }

            switch (msg.getType()) {
                case MESSAGE -> handleBroadcast(msg);
                case PRIVATE -> handlePrivate(msg);
                case LIST -> handleList();
                case QUIT -> { return; } // exits readLoop, finally block handles cleanup
                case PONG -> {} // heartbeat response — will be used in Slice 11
                default -> sendMessage(new Message(MessageType.ERROR)
                        .content("Unexpected message type: " + msg.getType()));
            }
        }
    }

    // ── Message handlers ───────────────────────────────────────

    private void handleBroadcast(Message msg) {
        Message outgoing = new Message(MessageType.MESSAGE)
                .sender(username)
                .content(msg.getContent())
                .timestamp(now());

        broadcast(outgoing);
    }

    private void handlePrivate(Message msg) {
        String targetName = msg.getRecipient();
        if (targetName == null || targetName.isBlank()) {
            sendMessage(new Message(MessageType.ERROR).content("Recipient is required for private messages"));
            return;
        }

        ClientHandler target = registry.getClient(targetName);
        if (target == null) {
            sendMessage(new Message(MessageType.ERROR).content("User not found: " + targetName));
            return;
        }

        Message outgoing = new Message(MessageType.PRIVATE)
                .sender(username)
                .recipient(targetName)
                .content(msg.getContent())
                .timestamp(now());

        // Send to recipient AND back to sender (so sender sees confirmation)
        target.sendMessage(outgoing);
        sendMessage(outgoing);
    }

    private void handleList() {
        String usersJson = String.join(", ", registry.getAllUsernames());
        sendMessage(new Message(MessageType.USER_LIST).content(usersJson));
    }

    // ── Output methods ─────────────────────────────────────────

    /**
     * Sends a message to THIS client's socket.
     * Synchronized to prevent interleaved output when multiple threads
     * call this simultaneously (e.g., two clients broadcasting at once).
     */
    public synchronized void sendMessage(Message message) {
        if (out != null) {
            out.println(ProtocolUtil.serialize(message));
        }
    }

    /**
     * Sends a message to ALL connected clients.
     * Takes a snapshot of the registry to avoid ConcurrentModificationException.
     */
    private void broadcast(Message message) {
        for (ClientHandler client : registry.getAllClients()) {
            client.sendMessage(message);
        }
    }

    // ── Cleanup ────────────────────────────────────────────────

    private void disconnect() {
        if (username != null) {
            registry.unregister(username);
            // Notify everyone that this user left
            broadcast(new Message(MessageType.LEFT).sender(username));
            System.out.println("[-] " + username + " disconnected");
        }
        try {
            socket.close();
        } catch (IOException ignored) {}
    }

    // ── Utilities ──────────────────────────────────────────────

    private String now() {
        return LocalDateTime.now().format(TIMESTAMP_FMT);
    }

    public String getUsername() {
        return username;
    }
}
