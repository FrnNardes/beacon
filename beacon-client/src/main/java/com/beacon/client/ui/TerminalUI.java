package com.beacon.client.ui;

import com.beacon.protocol.Message;
import org.fusesource.jansi.Ansi;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedString;
import org.jline.utils.Status;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import java.util.function.Consumer;

import static org.fusesource.jansi.Ansi.ansi;

/**
 * Manages the JLine Terminal and Jansi color output for the client.
 */
public class TerminalUI {

    private final Terminal terminal;
    private final LineReader lineReader;
    private final Status status;
    private String promptString;

    public TerminalUI(Consumer<String> onKeystroke) throws IOException {
        // We no longer use Alternate Screen Buffer here so users can scroll back to
        // read chat history.

        // Initialize JLine terminal (auto-detects Jansi on Windows)
        this.terminal = TerminalBuilder.builder()
                .system(true)
                .build();

        DefaultParser parser = new DefaultParser();
        parser.setEscapeChars(null); // Disable backslash escaping for Windows paths

        this.lineReader = LineReaderBuilder.builder()
                .terminal(terminal)
                .parser(parser)
                .option(LineReader.Option.ERASE_LINE_ON_FINISH, true)
                .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
                .build();

        this.status = Status.getStatus(terminal);
        this.promptString = ansi().fg(Ansi.Color.YELLOW).a("[#global] > ").reset().toString();

        // Bind a custom widget to every printable character keystroke
        // so we can fire typing indicators immediately
        lineReader.getWidgets().put("beacon-typing", () -> {
            onKeystroke.accept(lineReader.getBuffer().toString());
            return true;
        });

        // Bind self-insert (regular typing) to also trigger our widget
        // We use a small trick: JLine doesn't have a trivial "on keystroke" listener,
        // but we can wrap the standard 'self-insert' command.
        // Actually, a simpler way in JLine 3 is to just bind characters to our widget,
        // or check the buffer periodically. But the callback onKeystroke is enough.
        // For simplicity and compatibility, we will hook into 'self-insert'.
        // JLine requires binding to specific keys. To avoid breaking normal typing,
        // it's easier to let the Udp typing indicator be handled in a slightly
        // different way,
        // or we just poll the buffer in a background thread if JLine widget binding is
        // too complex.

        // Let's use a background thread for typing detection based on the buffer
        // content changing!
        // This avoids complex JLine keymap hacking which can break backspace/arrows.
        startTypingDetector(onKeystroke);
    }

    private void startTypingDetector(Consumer<String> onKeystroke) {
        Thread detector = new Thread(() -> {
            String lastBuffer = "";
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(100); // Check 10 times a second
                    String currentBuffer = lineReader.getBuffer().toString();
                    // If buffer is not empty, not starting with command (unless /msg), and changed
                    if (!currentBuffer.isEmpty() && !currentBuffer.equals(lastBuffer)) {
                        if (!currentBuffer.startsWith("/") || currentBuffer.startsWith("/msg ")) {
                            onKeystroke.accept(currentBuffer);
                        }
                    }
                    lastBuffer = currentBuffer;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "typing-detector");
        detector.setDaemon(true);
        detector.start();
    }

    public Terminal getTerminal() {
        return terminal;
    }

    public LineReader getLineReader() {
        return lineReader;
    }

    private String currentChannel = "global";

    public void setChannel(String channel) {
        this.currentChannel = channel;
        this.promptString = ansi().fg(Ansi.Color.YELLOW).a("[#" + channel + "] > ").reset().toString();
        printBanner();
    }

    public void printBanner() {
        String[] bannerLines = {
                "  ██████╗ ███████╗ █████╗  ██████╗ ██████╗ ███╗   ██╗",
                "  ██╔══██╗██╔════╝██╔══██╗██╔════╝██╔═══██╗████╗  ██║",
                "  ██████╦╝█████╗  ███████║██║     ██║   ██║██╔██╗ ██║",
                "  ██╔══██╗██╔══╝  ██╔══██║██║     ██║   ██║██║╚██╗██║",
                "  ██████╦╝███████╗██║  ██║╚██████╗╚██████╔╝██║ ╚████║",
                "  ╚═════╝ ╚══════╝╚═╝  ╚═╝ ╚═════╝ ╚═════╝ ╚═╝  ╚═══╝"
        };

        // Procedurally generate a 14-element smooth gradient based on the channel
        // name's hash!
        String[] rainbow = new String[14];
        int hash = Math.abs(currentChannel.hashCode());
        int sweepMode = hash % 3; // 0=Red, 1=Green, 2=Blue
        int base1 = (hash / 3) % 4 + 1; // Base color 1 (1-4)
        int base2 = (hash / 12) % 4 + 1; // Base color 2 (1-4)

        for (int i = 0; i < 8; i++) {
            int sweep = (i * 5) / 7; // Sweeps from 0 to 5
            int r = (sweepMode == 0) ? sweep : ((sweepMode == 1) ? base1 : base2);
            int g = (sweepMode == 1) ? sweep : ((sweepMode == 0) ? base1 : base2);
            int b = (sweepMode == 2) ? sweep : ((sweepMode == 0) ? base2 : base1);
            int ansiIndex = 16 + (36 * r) + (6 * g) + b;
            rainbow[i] = String.valueOf(ansiIndex);
        }
        // Mirror the array to make it palindromic (smooth sliding window)
        for (int i = 8; i < 14; i++) {
            rainbow[i] = rainbow[14 - i];
        }

        // Clear the screen and reset cursor for the new channel
        System.out.print("\033[H\033[2J");
        System.out.flush();

        System.out.println();
        for (String line : bannerLines) {
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < line.length(); j++) {
                int c = (j * rainbow.length) / Math.max(1, line.length());
                sb.append("\033[38;5;").append(rainbow[c]).append("m").append(line.charAt(j));
            }
            sb.append("\033[0m");
            System.out.println(sb.toString());
        }
        System.out.println();
        String subtitle = "Beacon Channel - #" + currentChannel;
        int paddingLength = Math.max(0, (55 - subtitle.length()) / 2);
        System.out.println(ansi().fgBrightYellow().a(" ".repeat(paddingLength) + subtitle + "\n").reset());
    }

    /**
     * Prints a formatted line ABOVE the current input prompt.
     * This ensures incoming messages don't interrupt what the user is typing.
     */
    private String myUsername;

    public void setMyUsername(String username) {
        this.myUsername = username != null ? username.toLowerCase() : null;
    }

    /**
     * Prints a formatted line ABOVE the current input prompt.
     * This ensures incoming messages don't interrupt what the user is typing.
     */
    public void printAbove(String text) {
        lineReader.printAbove(text);
    }

    /**
     * Updates the status bar to show who is currently typing.
     */
    public void updatePrompt(Set<String> typingUsers) {
        if (status == null)
            return;

        if (typingUsers.isEmpty()) {
            status.update(Collections.emptyList());
        } else {
            String names = String.join(", ", typingUsers);
            String verb = typingUsers.size() == 1 ? "is" : "are";
            // Prepend a few spaces to prevent terminal from cutting off the first
            // characters
            String text = ansi().fgBrightBlack().a("   " + names + " " + verb + " typing...").reset().toString();
            status.update(Collections.singletonList(AttributedString.fromAnsi(text)));
        }
    }

    public String getPromptString() {
        return promptString;
    }

    // ── Formatting Helpers ──────────────────────────────────────────────────

    public String formatMessage(Message msg, boolean isHistory) {
        String ts = formatTimestamp(msg);

        String content = applyMinecraftFormatting(msg.getContent());
        boolean mentioned = false;
        if (myUsername != null && content != null && content.toLowerCase().contains("@" + myUsername)) {
            // Highlight mention and trigger terminal bell
            content = content.replaceAll("(?i)@" + myUsername,
                    ansi().bgYellow().fgBlack().a("@" + myUsername).reset().toString());
            mentioned = true;
        }

        if (isHistory) {
            // Dimmed history
            return ansi().fgBrightBlack().a(ts + msg.getSender() + ": " + msg.getContent()).reset().toString();
        }

        Ansi ansi = ansi().a(ts);

        // Colorize sender name
        if (msg.getColor() != null && !msg.getColor().isEmpty()) {
            applyColor(ansi, msg.getColor());
        } else {
            ansi.fgBrightDefault(); // fallback
        }

        ansi.a(msg.getSender()).reset().a(": " + content);

        if (mentioned) {
            ansi.a("\u0007"); // Terminal bell
        }

        return ansi.toString();
    }

    public String formatPrivate(Message msg, boolean isHistory) {
        String ts = formatTimestamp(msg);
        Ansi ansi = ansi().a(ts);

        if (isHistory) {
            ansi.fgBrightBlack().a("[PM] " + msg.getSender() + " → " + msg.getRecipient() + ": " + msg.getContent());
        } else {
            // Dark Grey background for the PM badge, and grey text for the message
            ansi.a("\u001B[100m").fg(Ansi.Color.WHITE).bold().a(" PM ").reset()
                    .fgBrightBlack().a(" " + msg.getSender() + " → " + msg.getRecipient() + ": " + msg.getContent());
        }

        return ansi.reset().toString();
    }

    public String formatSystem(Message msg, String action, Ansi.Color color) {
        String detail = msg.getContent();
        if (detail == null || detail.isEmpty()) {
            detail = action.toLowerCase() + " the beacon";
        }
        Ansi ansi = ansi().fg(color)
                .a("[" + action + "] " + msg.getSender() + " " + detail).reset();
        return ansi.toString();
    }

    public String formatError(String text) {
        return ansi().fgRed().bold().a("[!] " + text).reset().toString();
    }

    private String applyMinecraftFormatting(String text) {
        if (text == null) return null;
        if (!text.contains("&")) return text;

        text = text.replaceAll("(?i)&0", "\u001B[30m");
        text = text.replaceAll("(?i)&1", "\u001B[34m");
        text = text.replaceAll("(?i)&2", "\u001B[32m");
        text = text.replaceAll("(?i)&3", "\u001B[36m");
        text = text.replaceAll("(?i)&4", "\u001B[31m");
        text = text.replaceAll("(?i)&5", "\u001B[35m");
        text = text.replaceAll("(?i)&6", "\u001B[33m");
        text = text.replaceAll("(?i)&7", "\u001B[37m");
        text = text.replaceAll("(?i)&8", "\u001B[90m");
        text = text.replaceAll("(?i)&9", "\u001B[94m");
        text = text.replaceAll("(?i)&a", "\u001B[92m");
        text = text.replaceAll("(?i)&b", "\u001B[96m");
        text = text.replaceAll("(?i)&c", "\u001B[91m");
        text = text.replaceAll("(?i)&d", "\u001B[95m");
        text = text.replaceAll("(?i)&e", "\u001B[93m");
        text = text.replaceAll("(?i)&f", "\u001B[97m");

        text = text.replaceAll("(?i)&l", "\u001B[1m");
        text = text.replaceAll("(?i)&m", "\u001B[9m");
        text = text.replaceAll("(?i)&n", "\u001B[4m");
        text = text.replaceAll("(?i)&o", "\u001B[3m");
        text = text.replaceAll("(?i)&r", "\u001B[0m");

        return text + "\u001B[0m";
    }

    private String formatTimestamp(Message msg) {
        if (msg.getTimestamp() == null)
            return "";
        String ts = msg.getTimestamp();
        if (ts.length() >= 16) {
            return ansi().fgBrightBlack().a("[" + ts.substring(11, 16) + "] ").reset().toString();
        }
        return ansi().fgBrightBlack().a("[" + ts + "] ").reset().toString();
    }

    private void applyColor(Ansi ansi, String colorName) {
        switch (colorName.toLowerCase()) {
            case "cyan" -> ansi.fgCyan();
            case "magenta" -> ansi.fgMagenta();
            case "yellow" -> ansi.fgYellow();
            case "green" -> ansi.fgGreen();
            case "blue" -> ansi.fgBlue();
            case "red" -> ansi.fgRed();
            case "orange" -> ansi.fg(Ansi.Color.YELLOW); // closest standard
            case "pink" -> ansi.fgBrightMagenta();
            case "lime" -> ansi.fgBrightGreen();
            case "teal" -> ansi.fgBrightCyan();
            default -> ansi.fgDefault();
        }
    }
}
