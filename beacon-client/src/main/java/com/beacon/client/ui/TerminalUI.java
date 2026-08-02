package com.beacon.client.ui;

import com.beacon.protocol.BeaconBanner;
import com.beacon.protocol.Message;
import org.fusesource.jansi.Ansi;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Reference;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
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
    private final String promptString;

    public TerminalUI(Consumer<String> onKeystroke) throws IOException {
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
        this.promptString = ansi().fg(Ansi.Color.YELLOW).a("> ").reset().toString();

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
        // it's easier to let the Udp typing indicator be handled in a slightly different way,
        // or we just poll the buffer in a background thread if JLine widget binding is too complex.
        
        // Let's use a background thread for typing detection based on the buffer content changing!
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

    public void printBanner() {
        BeaconBanner.print("Beacon Client");
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
        if (status == null) return;
        
        if (typingUsers.isEmpty()) {
            status.update(Collections.emptyList());
        } else {
            String names = String.join(", ", typingUsers);
            String verb = typingUsers.size() == 1 ? "is" : "are";
            // Prepend a few spaces to prevent terminal from cutting off the first characters
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
        
        ansi.a(msg.getSender()).reset().a(": " + msg.getContent());
        return ansi.toString();
    }

    public String formatPrivate(Message msg, boolean isHistory) {
        String ts = formatTimestamp(msg);
        Ansi ansi = ansi();
        
        if (isHistory) {
            ansi.fgBrightBlack();
        } else {
            ansi.fgMagenta();
        }
        
        ansi.a(ts + "[PM] " + msg.getSender() + " → " + msg.getRecipient() + ": " + msg.getContent()).reset();
        return ansi.toString();
    }

    public String formatSystem(Message msg, String action, Ansi.Color color) {
        Ansi ansi = ansi().fg(color).a("[" + action + "] " + msg.getSender() + " " + action.toLowerCase() + " the beacon").reset();
        return ansi.toString();
    }
    
    public String formatError(String text) {
        return ansi().fgRed().bold().a("[!] " + text).reset().toString();
    }

    private String formatTimestamp(Message msg) {
        if (msg.getTimestamp() == null) return "";
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
