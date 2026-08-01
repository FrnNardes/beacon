package com.beacon.client;

import com.beacon.protocol.Message;
import com.beacon.protocol.MessageType;
import com.beacon.protocol.ProtocolException;
import com.beacon.protocol.ProtocolUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.util.Iterator;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Beacon chat client entry point.
 * Connects to the server via TCP, logs in, then enters a send/receive loop.
 * Also manages UDP for server discovery (RF-08) and typing indicators (RF-09).
 */
public class BeaconClient {

    private final String host;
    private final int port;

    /** UDP socket shared between typing sender (main thread) and receiver (daemon thread). */
    private DatagramSocket udpSocket;
    private InetAddress serverAddress;
    private int udpPort;
    private String username;

    /**
     * Tracks when each remote user was last seen typing.
     * Entries older than TYPING_EXPIRY_MS are cleaned up and the indicator is cleared.
     */
    private final ConcurrentHashMap<String, Long> typingUsers = new ConcurrentHashMap<>();
    private static final long TYPING_EXPIRY_MS = 3000; // 3 seconds

    /** Throttle: minimum interval between TYPING datagrams sent (1 per second). */
    private long lastTypingSentMs = 0;
    private static final long TYPING_THROTTLE_MS = 1000;

    public BeaconClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public void start() {
        System.out.println("Connecting to " + host + ":" + port + "...");

        // try-with-resources: Socket and streams are auto-closed on exit
        try (Socket socket = new Socket(host, port);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

            Scanner scanner = new Scanner(System.in);

            System.out.print("Username: ");
            username = scanner.nextLine().trim();
            System.out.print("Password: ");
            String password = scanner.nextLine().trim();

            // Send LOGIN message with password in the content field
            Message loginMsg = new Message(MessageType.LOGIN)
                    .sender(username)
                    .content(password);
            out.println(ProtocolUtil.serialize(loginMsg));

            // Start background thread to receive messages from server
            TcpServerReader reader = new TcpServerReader(in);
            Thread readerThread = new Thread(reader, "server-reader");
            readerThread.setDaemon(true); // dies when main thread exits
            readerThread.start();

            // ── Start UDP typing indicator ───────────────────────────────
            initUdpTyping(socket.getInetAddress());

            // Main thread: read user input and send to server
            inputLoop(scanner, out, username);

            reader.stop();

        } catch (IOException e) {
            System.err.println("Connection failed: " + e.getMessage());
            System.err.println("Make sure the server is running on " + host + ":" + port);
        } finally {
            if (udpSocket != null && !udpSocket.isClosed()) {
                udpSocket.close();
            }
        }
    }

    /**
     * Initializes the UDP socket for typing indicators and starts
     * the receiver daemon thread.
     *
     * The same DatagramSocket is used for both sending (main thread)
     * and receiving (daemon thread) — DatagramSocket is thread-safe
     * for concurrent send/receive operations.
     */
    private void initUdpTyping(InetAddress serverAddr) {
        try {
            this.serverAddress = serverAddr;
            this.udpPort = port + 1; // Convention: UDP = TCP + 1
            this.udpSocket = new DatagramSocket(); // ephemeral port

            // Start receiver thread
            Thread typingReceiver = new Thread(this::udpTypingReceiveLoop, "udp-typing-receiver");
            typingReceiver.setDaemon(true);
            typingReceiver.start();

            // Start expiry cleaner thread
            Thread typingCleaner = new Thread(this::typingExpiryCleaner, "typing-expiry-cleaner");
            typingCleaner.setDaemon(true);
            typingCleaner.start();

            System.out.println("[UDP] Typing indicator active (server UDP port " + udpPort + ")");
        } catch (SocketException e) {
            System.err.println("[UDP] Failed to initialize typing: " + e.getMessage());
            // Non-fatal — TCP chat continues to work without typing indicators
        }
    }

    /**
     * Sends a TYPING indicator to the server via UDP.
     * Throttled to at most one datagram per second to avoid flooding.
     *
     * Private-aware: if the user is typing a /msg command, the recipient
     * field is set so the server only relays to that specific user.
     *
     * @param input the current input line (used to detect /msg recipient)
     */
    private void sendTypingIndicator(String input) {
        if (udpSocket == null || udpSocket.isClosed()) return;

        long now = System.currentTimeMillis();
        if (now - lastTypingSentMs < TYPING_THROTTLE_MS) return;
        lastTypingSentMs = now;

        try {
            Message typing = new Message(MessageType.TYPING).sender(username);

            // Private-aware: detect /msg <recipient> pattern
            if (input.startsWith("/msg ")) {
                String[] parts = input.split("\\s+", 3);
                if (parts.length >= 2) {
                    typing.recipient(parts[1]);
                }
            }

            byte[] data = ProtocolUtil.toUdpBytes(typing);
            DatagramPacket packet = new DatagramPacket(
                    data, data.length, serverAddress, udpPort);
            udpSocket.send(packet);
        } catch (IOException e) {
            // Silently ignore — typing indicators are loss-tolerant
        }
    }

    /**
     * Daemon loop that receives TYPING datagrams from the server.
     * Displays "X is typing..." when a typing indicator arrives,
     * and tracks the timestamp for expiry.
     */
    private void udpTypingReceiveLoop() {
        byte[] buffer = new byte[512];

        while (!udpSocket.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                udpSocket.receive(packet);

                Message msg = ProtocolUtil.fromUdpBytes(
                        packet.getData(), packet.getLength());

                if (msg.getType() == MessageType.TYPING && msg.getSender() != null) {
                    String sender = msg.getSender();
                    // Don't show our own typing indicator
                    if (!sender.equals(username)) {
                        boolean isNew = !typingUsers.containsKey(sender);
                        typingUsers.put(sender, System.currentTimeMillis());

                        if (isNew) {
                            System.out.println("  " + sender + " is typing...");
                        }
                    }
                }
            } catch (SocketException e) {
                // Socket closed — clean shutdown
                break;
            } catch (IOException | ProtocolException e) {
                // Ignore malformed datagrams — loss-tolerant by design
            }
        }
    }

    /**
     * Periodically checks for expired typing indicators (older than 3s)
     * and clears them from the display.
     */
    private void typingExpiryCleaner() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(500); // check every 500ms

                long now = System.currentTimeMillis();
                Iterator<Map.Entry<String, Long>> it = typingUsers.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, Long> entry = it.next();
                    if (now - entry.getValue() > TYPING_EXPIRY_MS) {
                        it.remove();
                        // Typing indicator expired — no visual cleanup needed
                        // since the next message will push it off screen
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * Reads user input line by line and sends to server.
     * Parses commands (/msg, /list, /quit, etc.) or sends as broadcast.
     * Sends a TYPING indicator via UDP before each message.
     */
    private void inputLoop(Scanner scanner, PrintWriter out, String username) {
        while (scanner.hasNextLine()) {
            String input = scanner.nextLine().trim();
            if (input.isEmpty()) continue;

            // Send typing indicator via UDP before processing the message
            sendTypingIndicator(input);

            Message msg = parseInput(input);
            if (msg == null) continue;

            out.println(ProtocolUtil.serialize(msg));

            if (msg.getType() == MessageType.QUIT) {
                System.out.println("Disconnecting...");
                break;
            }
        }
    }

    /**
     * Converts user input into a protocol Message.
     * Commands start with /. Anything else is a broadcast message.
     */
    private Message parseInput(String input) {
        if (input.startsWith("/")) {
            return parseCommand(input);
        }
        // Regular text → broadcast MESSAGE
        return new Message(MessageType.MESSAGE).content(input);
    }

    private Message parseCommand(String input) {
        String[] parts = input.split("\\s+", 3); // split into max 3 parts
        String command = parts[0].toLowerCase();

        return switch (command) {
            case "/quit" -> new Message(MessageType.QUIT);

            case "/list" -> new Message(MessageType.LIST);

            case "/msg" -> {
                if (parts.length < 3) {
                    System.out.println("Usage: /msg <username> <message>");
                    yield null; // null means "don't send anything"
                }
                yield new Message(MessageType.PRIVATE)
                        .recipient(parts[1])
                        .content(parts[2]);
            }

            case "/search" -> {
                if (parts.length < 2) {
                    System.out.println("Usage: /search <keyword>");
                    yield null;
                }
                yield new Message(MessageType.SEARCH).content(parts[1]);
            }

            case "/stats" -> new Message(MessageType.STATS);

            default -> {
                System.out.println("Unknown command: " + command);
                System.out.println("Available: /msg /list /search /stats /quit");
                yield null;
            }
        };
    }

    public static void main(String[] args) {
        String host = null;
        int port = 4040;

        if (args.length >= 1) host = args[0];
        if (args.length >= 2) {
            try {
                port = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid port: " + args[1] + ". Using default " + port);
            }
        }

        // ── UDP Discovery fallback (RF-08) ───────────────────────────────
        // If no host was provided, try to discover the server on the LAN
        if (host == null) {
            int udpPort = port + 1; // Convention: UDP = TCP + 1
            String discovered = UdpDiscoveryClient.discover(udpPort);

            if (discovered != null) {
                // Parse "host:port" from discovery response
                String[] parts = discovered.split(":");
                host = parts[0];
                if (parts.length >= 2) {
                    try {
                        port = Integer.parseInt(parts[1]);
                    } catch (NumberFormatException e) {
                        // Keep default port
                    }
                }
            } else {
                // Discovery failed — prompt for manual input
                Scanner scanner = new Scanner(System.in);
                System.out.println("[Discovery] Could not find a server automatically.");
                System.out.print("Server host: ");
                host = scanner.nextLine().trim();
                System.out.print("Server port [" + port + "]: ");
                String portInput = scanner.nextLine().trim();
                if (!portInput.isEmpty()) {
                    try {
                        port = Integer.parseInt(portInput);
                    } catch (NumberFormatException e) {
                        System.err.println("Invalid port. Using default " + port);
                    }
                }
            }
        }

        new BeaconClient(host, port).start();
    }
}

