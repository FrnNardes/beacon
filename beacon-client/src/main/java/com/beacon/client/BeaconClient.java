package com.beacon.client;

import com.beacon.protocol.Message;
import com.beacon.protocol.MessageType;
import com.beacon.protocol.ProtocolException;
import com.beacon.protocol.ProtocolUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Collections;
import java.util.Scanner;

import com.beacon.client.ui.TerminalUI;
import org.jline.reader.UserInterruptException;
import org.jline.reader.EndOfFileException;
import org.fusesource.jansi.AnsiConsole;

/**
 * Beacon chat client entry point.
 * Connects to the server via TCP, logs in, then enters a send/receive loop.
 * Also manages UDP for server discovery (RF-08) and typing indicators (RF-09).
 */
public class BeaconClient {

    private final String host;
    private final int port;

    private final UdpTypingManager typingManager = new UdpTypingManager();

    public BeaconClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    private TerminalUI ui;

    public void start() {
        try {
            ui = new TerminalUI(typingManager::sendTypingIndicator);
            ui.printBanner();
        } catch (IOException e) {
            System.err.println("Failed to initialize terminal: " + e.getMessage());
            return;
        }

        while (true) {
            boolean quit = connectAndRun();
            if (quit) {
                break;
            }
            ui.printAbove(org.fusesource.jansi.Ansi.ansi().fgYellow().a("--- Retrying Login ---").reset().toString());
        }
    }

    private boolean connectAndRun() {
        String password = null;
        String username = null;
        try {
            String userPrompt = org.fusesource.jansi.Ansi.ansi().fgCyan().a("Username: ").reset().toString();
            username = ui.getLineReader().readLine(userPrompt).trim();
            if (username.equalsIgnoreCase("/quit") || username.equalsIgnoreCase("quit") || username.equalsIgnoreCase("exit")) {
                return true;
            }
            
            String passPrompt = org.fusesource.jansi.Ansi.ansi().fgCyan().a("Password: ").reset().toString();
            password = ui.getLineReader().readLine(passPrompt, '*').trim(); 
            if (password.equalsIgnoreCase("/quit") || password.equalsIgnoreCase("quit") || password.equalsIgnoreCase("exit")) {
                return true;
            }
            
            ui.printAbove("Connecting to " + host + ":" + port + "...");
        } catch (UserInterruptException | EndOfFileException e) {
            return true;
        }

        try (Socket socket = new Socket(host, port);
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

            // Send LOGIN message with password in the content field
            Message loginMsg = new Message(MessageType.LOGIN)
                    .sender(username)
                    .content(password);
            out.println(ProtocolUtil.serialize(loginMsg));
            
            // Synchronously wait for login response before starting reader
            String responseStr = in.readLine();
            if (responseStr == null) {
                ui.printAbove(ui.formatError("Server closed connection during login."));
                return false;
            }
            
            try {
                Message response = ProtocolUtil.deserialize(responseStr);
                if (response.getType() == MessageType.LOGIN_ERROR) {
                    ui.printAbove(ui.formatError("Login failed: " + response.getContent()));
                    return false;
                } else if (response.getType() == MessageType.LOGIN_OK) {
                    ui.setMyUsername(username);
                    ui.printAbove(org.fusesource.jansi.Ansi.ansi().fgGreen().a("[✓] Logged in successfully!").reset().toString());
                } else {
                    ui.printAbove(ui.formatError("Unexpected response: " + response.getType()));
                    return false;
                }
            } catch (ProtocolException e) {
                ui.printAbove(ui.formatError("Invalid login response from server"));
                return false;
            }

            // Start background thread to receive messages from server
            TcpServerReader reader = new TcpServerReader(in, out, ui);
            Thread readerThread = new Thread(reader, "server-reader");
            readerThread.setDaemon(true); // dies when main thread exits
            readerThread.start();

            // ── Start UDP typing indicator ───────────────────────────────
            typingManager.start(socket.getInetAddress(), port, username, ui);

            // Main thread: read user input and send to server
            inputLoop(out, username);

            reader.stop();
            return true; // Graceful exit (/quit)

        } catch (IOException e) {
            ui.printAbove(ui.formatError("Connection failed: " + e.getMessage()));
            ui.printAbove(ui.formatError("Make sure the server is running on " + host + ":" + port));
            return false;
        } finally {
            typingManager.stop();
        }
    }

    /**
     * Reads user input line by line and sends to server.
     * Parses commands (/msg, /list, /quit, etc.) or sends as broadcast.
     */
    private void inputLoop(PrintWriter out, String username) {
        ui.updatePrompt(Collections.emptySet()); // initialize prompt
        CommandParser parser = new CommandParser(ui, out);

        while (true) {
            try {
                // Pass the multi-line delimited prompt to readLine
                String input = ui.getLineReader().readLine(ui.getPromptString()).trim();

                if (input.isEmpty())
                    continue;

                Message msg = parser.parse(input);
                if (msg == null)
                    continue;

                out.println(ProtocolUtil.serialize(msg));

                if (msg.getType() == MessageType.QUIT) {
                    ui.printAbove("Disconnecting...");
                    break;
                }
            } catch (UserInterruptException | EndOfFileException e) {
                // Handle Ctrl-C / Ctrl-D
                out.println(ProtocolUtil.serialize(new Message(MessageType.QUIT)));
                break;
            }
        }
    }

    public static void main(String[] args) {
        // Must run before ANY System.out usage / JLine terminal creation.
        // Installs a filtered PrintStream that translates ANSI escape codes
        // into native Win32 console calls on terminals that don't support
        // ANSI natively (old cmd.exe), and passes them through unchanged on
        // terminals that already do (Linux/macOS/modern Windows Terminal).
        AnsiConsole.systemInstall();
        Runtime.getRuntime().addShutdownHook(new Thread(AnsiConsole::systemUninstall));

        String host = null;
        int port = 4040;

        if (args.length >= 1)
            host = args[0];
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
                @SuppressWarnings("resource")
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
