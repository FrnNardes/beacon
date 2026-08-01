package com.beacon.client;

import com.beacon.protocol.Message;
import com.beacon.protocol.MessageType;
import com.beacon.protocol.ProtocolUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Scanner;

/**
 * Beacon chat client entry point.
 * Connects to the server via TCP, logs in, then enters a send/receive loop.
 */
public class BeaconClient {

    private final String host;
    private final int port;

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
            String username = scanner.nextLine().trim();
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

            // Main thread: read user input and send to server
            inputLoop(scanner, out, username);

            reader.stop();

        } catch (IOException e) {
            System.err.println("Connection failed: " + e.getMessage());
            System.err.println("Make sure the server is running on " + host + ":" + port);
        }
    }

    /**
     * Reads user input line by line and sends to server.
     * Parses commands (/msg, /list, /quit, etc.) or sends as broadcast.
     */
    private void inputLoop(Scanner scanner, PrintWriter out, String username) {
        while (scanner.hasNextLine()) {
            String input = scanner.nextLine().trim();
            if (input.isEmpty()) continue;

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
        // Defaults — will be replaced with Picocli parsing in a later slice
        String host = "localhost";
        int port = 4040;

        if (args.length >= 1) host = args[0];
        if (args.length >= 2) {
            try {
                port = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid port: " + args[1] + ". Using default " + port);
            }
        }

        new BeaconClient(host, port).start();
    }
}
