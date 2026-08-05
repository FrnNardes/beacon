package com.beacon.server.ui;

import com.beacon.protocol.BeaconBanner;
import com.beacon.server.ClientHandler;
import com.beacon.server.ClientRegistry;
import org.fusesource.jansi.Ansi;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

public class ServerUI {

    private final ClientRegistry registry;
    private final ConcurrentLinkedDeque<String> logs = new ConcurrentLinkedDeque<>();
    private static final int MAX_LOGS = 15;
    private volatile boolean running = false;
    private Thread renderThread;
    
    // To track Haste effect (messages per second)
    private int lastMessageCount = 0;
    private int frameCount = 0;
    
    // Singleton pattern so we can easily call ServerUI.log() from anywhere without passing it around
    private static ServerUI instance;

    public ServerUI(ClientRegistry registry) {
        this.registry = registry;
        instance = this;
    }

    public static void log(String message) {
        if (instance != null) {
            instance.addLog(message);
        } else {
            System.out.println(message);
        }
    }
    
    public static void logError(String message) {
        if (instance != null) {
            instance.addLog(Ansi.ansi().fgRed().bold().a("[ERROR] " + message).reset().toString());
        } else {
            System.err.println(message);
        }
    }

    private void addLog(String message) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        logs.add(Ansi.ansi().fgBrightBlack().a("[" + timestamp + "] ").reset().a(message).toString());
        while (logs.size() > MAX_LOGS) {
            logs.poll();
        }
    }

    public void start() {
        running = true;
        
        // Enter Alternate Screen Buffer (hides command history, gives a fresh blank screen)
        System.out.print("\033[?1049h");
        System.out.flush();

        // Ensure we restore the main screen buffer if the server is killed
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.print("\033[?1049l");
            System.out.flush();
        }));

        renderThread = new Thread(this::renderLoop, "server-ui-renderer");
        renderThread.setDaemon(true);
        renderThread.start();
    }

    public void stop() {
        running = false;
        if (renderThread != null) {
            renderThread.interrupt();
        }
        // Exit Alternate Screen Buffer (restores previous terminal state)
        System.out.print("\033[?1049l");
        System.out.flush();
    }

    private long lastMetricTime = System.currentTimeMillis();
    private double currentMsgsPerSec = 0;

    private void renderLoop() {
        while (running) {
            try {
                render();
                Thread.sleep(33); // 30 FPS for ultimate smoothness
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void render() {
        StringBuilder sb = new StringBuilder();
        frameCount++;

        // Calculate Metrics
        int onlineUsers = registry.getOnlineCount();
        long uptimeMs = registry.getUptimeMs();
        int totalMessages = registry.getTotalMessages();
        
        // Haste calculation (only update average once per second to avoid spiky metrics)
        long now = System.currentTimeMillis();
        if (now - lastMetricTime >= 1000) {
            int newMessages = totalMessages - lastMessageCount;
            lastMessageCount = totalMessages;
            currentMsgsPerSec = newMessages / ((now - lastMetricTime) / 1000.0);
            lastMetricTime = now;
        }
        double msgsPerSec = currentMsgsPerSec;

        // RTT calculation
        List<ClientHandler> clients = registry.getAllClients();
        long totalRtt = 0;
        int clientsWithRtt = 0;
        for (ClientHandler client : clients) {
            if (client.getCurrentRtt() > 0) {
                totalRtt += client.getCurrentRtt();
                clientsWithRtt++;
            }
        }
        long avgRtt = clientsWithRtt > 0 ? totalRtt / clientsWithRtt : 0;
        
        // Disconnects
        int disconnects = registry.consumeRecentDisconnects();

        // Determine Beacon Level
        String level = "0";
        if (onlineUsers > 0) level = "I";
        if (onlineUsers >= 5) level = "II";
        if (onlineUsers >= 10) level = "III";
        if (onlineUsers >= 20) level = "IV";

        // Determine Active Effect based on heuristics
        String activeEffect = "None";
        Ansi.Color effectColor = Ansi.Color.DEFAULT;

        if (disconnects > 0) {
            activeEffect = "Jump Boost (Reconnecting)";
            effectColor = Ansi.Color.GREEN;
        } else if (avgRtt > 150) {
            activeEffect = "Mining Fatigue (High Latency)";
            effectColor = Ansi.Color.RED;
        } else if (msgsPerSec > 2.0) {
            activeEffect = "Haste (High Throughput)";
            effectColor = Ansi.Color.YELLOW; // Amber
        } else if (clientsWithRtt > 0 && avgRtt < 50) {
            activeEffect = "Speed (Low Latency)";
            effectColor = Ansi.Color.BLUE;
        } else if (onlineUsers > 0) {
            activeEffect = "Regeneration (Stable)";
            effectColor = Ansi.Color.MAGENTA;
        }

        // Determine 256-color gradient for the Banner based on the Active Effect
        // Using WIDER mathematically adjacent 256-color codes (14-element palindromic arrays).
        // Since the banner is 6 lines tall, it acts as a "sliding window" across this wide wave,
        // making the color transition incredibly broad, slow, and smooth.
        String[] currentGradient;
        switch (activeEffect) {
            case "Regeneration (Stable)":
                // Plasma Core: Bright Purple to Light Magenta
                currentGradient = new String[]{"135", "141", "147", "177", "183", "213", "219", "225", "219", "213", "183", "177", "147", "141"}; 
                break;
            case "Speed (Low Latency)":
                // Mint Breeze: Bright Teal to Cyan
                currentGradient = new String[]{"37", "38", "43", "44", "45", "50", "51", "87", "51", "50", "45", "44", "43", "38"}; 
                break;
            case "Haste (High Throughput)":
                // Sunset: Bright Red to Neon Orange/Yellow
                currentGradient = new String[]{"160", "196", "202", "208", "214", "220", "226", "227", "226", "220", "214", "208", "202", "196"}; 
                break;
            case "Jump Boost (Reconnecting)":
                // Toxic Sludge: Bright Green to Neon Lime/Yellow
                currentGradient = new String[]{"34", "40", "46", "82", "118", "154", "190", "226", "190", "154", "118", "82", "46", "40"}; 
                break;
            case "Mining Fatigue (High Latency)":
                // Ember Glow: Solid Red to Dark Orange
                currentGradient = new String[]{"52", "88", "124", "130", "166", "202", "208", "214", "208", "202", "166", "130", "124", "88"}; 
                break;
            default:
                // Idle (0 Users): Sky Blue - Deep to Very Light Cyan
                currentGradient = new String[]{"26", "32", "33", "39", "45", "81", "117", "153", "117", "81", "45", "39", "33", "32"}; 
                break;
        }

        // Move cursor to top-left (without clearing the whole screen, to prevent flickering)
        sb.append("\033[H");
        
        String[] bannerLines = {
            "  ██████╗ ███████╗ █████╗  ██████╗ ██████╗ ███╗   ██╗",
            "  ██╔══██╗██╔════╝██╔══██╗██╔════╝██╔═══██╗████╗  ██║",
            "  ██████╦╝█████╗  ███████║██║     ██║   ██║██╔██╗ ██║",
            "  ██╔══██╗██╔══╝  ██╔══██║██║     ██║   ██║██║╚██╗██║",
            "  ██████╦╝███████╗██║  ██║╚██████╗╚██████╔╝██║ ╚████║",
            "  ╚═════╝ ╚══════╝╚═╝  ╚═╝ ╚═════╝ ╚═════╝ ╚═╝  ╚═══╝"
        };
        
        for (int i = 0; i < bannerLines.length; i++) {
            // Tie the wave speed to real time (e.g., shift color every 200ms) rather than raw frameCount.
            // This keeps the wave scrolling at a pleasant, constant speed even at 30 FPS!
            int colorOffset = (int) (System.currentTimeMillis() / 200);
            String colorCode = currentGradient[(colorOffset + i) % currentGradient.length];
            sb.append("\033[38;5;").append(colorCode).append("m").append(bannerLines[i]).append("\033[0m\n");
        }

        sb.append("\n");
        sb.append(Ansi.ansi().fgBrightYellow().a("                      Live Dashboard\n").reset());
        sb.append("\n\n");

        // Print Dashboard Box
        String formatTime = formatUptime(uptimeMs);
        sb.append(Ansi.ansi().fgBrightBlack().a("======================================================\n").reset());
        sb.append(String.format("  %s %s\033[K\n", 
                Ansi.ansi().fgBrightBlue().a("Uptime:").reset(), formatTime));
        sb.append(String.format("  %s %d\033[K\n", 
                Ansi.ansi().fgBrightBlue().a("Users Online:").reset(), onlineUsers));
        sb.append(String.format("  %s Level %s\033[K\n", 
                Ansi.ansi().fgBrightBlue().a("Beacon Level:").reset(), Ansi.ansi().fgYellow().bold().a(level).reset()));
        sb.append(String.format("  %s %s\033[K\n", 
                Ansi.ansi().fgBrightBlue().a("Avg Latency:").reset(), (avgRtt > 0 ? avgRtt + " ms" : "N/A")));
        sb.append(String.format("  %s %s\033[K\n", 
                Ansi.ansi().fgBrightBlue().a("Active Effect:").reset(), Ansi.ansi().fg(effectColor).bold().a(activeEffect).reset()));
        sb.append(Ansi.ansi().fgBrightBlack().a("======================================================\n").reset());
        sb.append("\033[K\n");

        // Print Logs
        sb.append(Ansi.ansi().a("\u001B[97m").a("Recent Logs:").reset()).append("\033[K\n");
        for (String log : logs) {
            sb.append(log).append("\033[K\n");
        }
        
        // Clear anything left below our logs (prevents garbage if logs shrink)
        sb.append("\033[J");
        
        // Write entire frame to console in one atomic operation
        System.out.print(sb.toString());
        System.out.flush();
    }

    private String formatUptime(long millis) {
        long seconds = millis / 1000;
        long s = seconds % 60;
        long m = (seconds / 60) % 60;
        long h = (seconds / (60 * 60)) % 24;
        return String.format("%02d:%02d:%02d", h, m, s);
    }
}
