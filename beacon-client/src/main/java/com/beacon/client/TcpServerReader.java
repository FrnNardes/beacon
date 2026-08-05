package com.beacon.client;

import com.beacon.client.ui.TerminalUI;
import com.beacon.protocol.Message;
import com.beacon.protocol.ProtocolException;
import com.beacon.protocol.ProtocolUtil;
import org.fusesource.jansi.Ansi;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import com.beacon.protocol.MessageType;

/**
 * Background thread that reads messages from the server's TCP socket.
 * Runs in parallel with the main thread (which handles user input).
 */
public class TcpServerReader implements Runnable {

    private final BufferedReader in;
    private final PrintWriter out;
    private final TerminalUI ui;
    private volatile boolean running = true;
    private final Map<String, String> incomingFiles = new HashMap<>();

    public TcpServerReader(BufferedReader in, PrintWriter out, TerminalUI ui) {
        this.in = in;
        this.out = out;
        this.ui = ui;
    }

    @Override
    public void run() {
        try {
            String line;
            while (running && (line = in.readLine()) != null) {
                try {
                    Message msg = ProtocolUtil.deserialize(line);
                    if (msg.getType() == MessageType.PING) {
                        Message pong = new Message(MessageType.PONG).content(msg.getContent());
                        out.println(ProtocolUtil.serialize(pong));
                    } else {
                        displayMessage(msg);
                    }
                } catch (ProtocolException e) {
                    ui.printAbove(ui.formatError("Bad message from server: " + e.getMessage()));
                }
            }
        } catch (IOException e) {
            if (running) {
                ui.printAbove(ui.formatError("Connection lost: " + e.getMessage()));
                ui.printAbove("Press Enter to retry or type /quit to exit.");
            }
        }
    }

    /**
     * Renders a message to the terminal via JLine printAbove.
     */
    private void displayMessage(Message msg) {
        switch (msg.getType()) {
            case LOGIN_OK -> ui.printAbove(Ansi.ansi().fgGreen().a("[✓] Logged in successfully!").reset().toString());
            case LOGIN_ERROR -> ui.printAbove(ui.formatError("Login failed: " + msg.getContent()));

            case MESSAGE -> ui.printAbove(ui.formatMessage(msg, false));
            case PRIVATE -> ui.printAbove(ui.formatPrivate(msg, false));

            // History messages are past messages sent on login
            case HISTORY -> ui.printAbove(ui.formatMessage(msg, true));

            case JOINED -> ui.printAbove(ui.formatSystem(msg, "JOINED", Ansi.Color.GREEN));
            case LEFT -> ui.printAbove(ui.formatSystem(msg, "LEFT", Ansi.Color.RED));
            case SYSTEM -> ui.printAbove(Ansi.ansi().fgYellow().a("[SYSTEM] " + msg.getContent()).reset().toString());

            case USER_LIST -> ui.printAbove(Ansi.ansi().fgCyan().a("[users] " + msg.getContent()).reset().toString());

            case SEARCH_RESULT ->
                ui.printAbove(Ansi.ansi().fgYellow().a("  🔍 ").reset() + ui.formatMessage(msg, true));

            case STATS_RESULT -> {
                ui.printAbove(Ansi.ansi().fgCyan().a("────────── STATS ───────────").reset().toString());
                ui.printAbove(msg.getContent());
                ui.printAbove(Ansi.ansi().fgCyan().a("────────────────────────────").reset().toString());
            }

            case ERROR -> ui.printAbove(ui.formatError(msg.getContent()));

            case FILE_META -> {
                String[] parts = msg.getContent().split("\\|");
                String filename = parts[0];
                incomingFiles.put(msg.getSender(), filename);
                ui.printAbove(Ansi.ansi().fgYellow().a("[↓] Receiving file '" + filename + "' from " + msg.getSender() + "...").reset().toString());
            }

            case FILE_DATA -> {
                String filename = incomingFiles.remove(msg.getSender());
                if (filename == null) {
                    filename = "unknown_file_" + System.currentTimeMillis() + ".dat";
                }
                try {
                    byte[] data = Base64.getDecoder().decode(msg.getContent());
                    Path downloadDir = Paths.get(System.getProperty("user.home"), ".beacon", "downloads");
                    Files.createDirectories(downloadDir);
                    Path filePath = downloadDir.resolve(filename);
                    Files.write(filePath, data);
                    ui.printAbove(Ansi.ansi().fgGreen().a("[✓] File saved to " + filePath).reset().toString());
                } catch (Exception e) {
                    ui.printAbove(ui.formatError("Failed to save incoming file: " + e.getMessage()));
                }
            }

            default -> ui.printAbove("[?] " + msg);
        }
    }

    public void stop() {
        running = false;
    }
}
