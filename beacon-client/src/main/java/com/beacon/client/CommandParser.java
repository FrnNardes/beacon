package com.beacon.client;

import com.beacon.protocol.Message;
import com.beacon.protocol.MessageType;
import com.beacon.protocol.ProtocolUtil;
import com.beacon.client.ui.TerminalUI;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;

/**
 * Parses raw text input from the user and translates it into protocol Messages.
 * Orchestrates local client-side UI updates (like changing channels).
 */
public class CommandParser {

    private final TerminalUI ui;
    private final PrintWriter out;

    public CommandParser(TerminalUI ui, PrintWriter out) {
        this.ui = ui;
        this.out = out;
    }

    public Message parse(String input) {
        if (input.startsWith("/")) {
            return parseCommand(input);
        }
        return new Message(MessageType.MESSAGE).content(input);
    }

    private Message parseCommand(String input) {
        String[] parts = input.split("\\s+", 3);
        String command = parts[0].toLowerCase();

        return switch (command) {
            case "/quit" -> new Message(MessageType.QUIT);
            case "/list" -> new Message(MessageType.LIST);
            case "/listall" -> new Message(MessageType.LIST_ALL);
            case "/stats" -> new Message(MessageType.STATS);

            case "/msg" -> {
                if (parts.length < 3) {
                    ui.printAbove(ui.formatError("Usage: /msg <username> <message>"));
                    yield null;
                }
                yield new Message(MessageType.PRIVATE)
                        .recipient(parts[1])
                        .content(parts[2]);
            }

            case "/search" -> {
                if (parts.length < 2) {
                    ui.printAbove(ui.formatError("Usage: /search <keyword>"));
                    yield null;
                }
                yield new Message(MessageType.SEARCH).content(parts[1]);
            }

            case "/join" -> {
                if (parts.length < 2) {
                    ui.printAbove(ui.formatError("Usage: /join <channel>"));
                    yield null;
                }
                String newChannel = parts[1].toLowerCase();
                ui.setChannel(newChannel);
                yield new Message(MessageType.MESSAGE).content("/join " + newChannel);
            }

            case "/sendfile" -> {
                if (parts.length < 3) {
                    ui.printAbove(ui.formatError("Usage: /sendfile <username> <filepath>"));
                    yield null;
                }
                sendFile(parts[1], parts[2]);
                yield null; 
            }

            default -> {
                ui.printAbove(ui.formatError("Unknown command: " + command));
                ui.printAbove(ui.formatError("Available: /join /msg /list /listall /search /stats /sendfile /quit"));
                yield null;
            }
        };
    }

    private void sendFile(String recipient, String filepath) {
        Path filePath = Paths.get(filepath);
        if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            ui.printAbove(ui.formatError("File not found: " + filePath));
            return;
        }
        try {
            long size = Files.size(filePath);
            if (size > 5 * 1024 * 1024) { 
                ui.printAbove(ui.formatError("File too large (limit 5MB)."));
                return;
            }
            ui.printAbove(org.fusesource.jansi.Ansi.ansi().fgYellow().a("[*] Reading file and encoding...").reset().toString());
            byte[] data = Files.readAllBytes(filePath);
            String base64 = Base64.getEncoder().encodeToString(data);
            
            String metaContent = filePath.getFileName().toString() + "|" + size;
            Message meta = new Message(MessageType.FILE_META)
                    .recipient(recipient)
                    .content(metaContent);
            out.println(ProtocolUtil.serialize(meta));
            
            Message fileData = new Message(MessageType.FILE_DATA)
                    .recipient(recipient)
                    .content(base64);
            out.println(ProtocolUtil.serialize(fileData));
            
            ui.printAbove(org.fusesource.jansi.Ansi.ansi().fgGreen().a("[✓] File sent to " + recipient).reset().toString());
        } catch (IOException e) {
            ui.printAbove(ui.formatError("Failed to read file: " + e.getMessage()));
        }
    }
}
