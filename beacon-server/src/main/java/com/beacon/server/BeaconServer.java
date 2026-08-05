package com.beacon.server;

import com.beacon.server.persistence.DatabaseManager;
import com.beacon.server.persistence.MessageRepository;
import com.beacon.server.persistence.UserRepository;
import com.beacon.server.ui.ServerUI;
import org.fusesource.jansi.AnsiConsole;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Beacon chat server entry point.
 * Opens a TCP ServerSocket and spawns one thread per connecting client.
 * Also starts a UDP server on TCP port + 1 for discovery and typing relay.
 */
public class BeaconServer {

    private final int port;
    private final ClientRegistry registry = new ClientRegistry();
    private final ExecutorService threadPool = Executors.newCachedThreadPool();

    private final UserRepository userRepo;
    private final MessageRepository messageRepo;
    private UdpServer udpServer;

    public BeaconServer(int port, UserRepository userRepo, MessageRepository messageRepo) {
        this.port = port;
        this.userRepo = userRepo;
        this.messageRepo = messageRepo;
    }

    public void start() {
        ServerUI ui = new ServerUI(registry);
        ui.start();

        // Start UDP server on port TCP+1 (convention: 4040 → 4041)
        int udpPort = port + 1;
        udpServer = new UdpServer(udpPort, port, registry);
        Thread udpThread = new Thread(udpServer, "udp-server");
        udpThread.setDaemon(true);
        udpThread.start();

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            ServerUI.log("Beacon Server listening on TCP port " + port
                    + ", UDP port " + udpPort);
            ServerUI.log("Waiting for connections...");

            while (true) {
                Socket clientSocket = serverSocket.accept();
                ServerUI.log("[*] New connection from " + clientSocket.getRemoteSocketAddress());

                ClientHandler handler = new ClientHandler(
                        clientSocket, registry, userRepo, messageRepo);
                threadPool.execute(handler);
            }
        } catch (IOException e) {
            ServerUI.logError("Server error: " + e.getMessage());
        } finally {
            if (udpServer != null) {
                udpServer.stop();
            }
            threadPool.shutdownNow();
        }
    }

    public static void main(String[] args) {
        // Must run before ANY System.out usage. This installs a filtered
        // PrintStream that translates ANSI escape codes into native Win32
        // console calls on Windows terminals that don't support ANSI natively
        // (e.g. old cmd.exe), and passes them through unchanged on terminals
        // that already support ANSI (Linux/macOS/modern Windows Terminal).
        System.setProperty("jansi.colors", "256");
        AnsiConsole.systemInstall();
        Runtime.getRuntime().addShutdownHook(new Thread(AnsiConsole::systemUninstall));

        int port = 4040;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                ServerUI.logError("Invalid port: " + args[0] + ". Using default " + port);
            }
        }

        // Initialize database in ~/.beacon/
        try {
            Path dbDir = Path.of(System.getProperty("user.home"), ".beacon");
            Files.createDirectories(dbDir);
            String dbPath = dbDir.resolve("beacon").toString();

            DatabaseManager db = new DatabaseManager(dbPath);
            db.initialize();

            UserRepository userRepo = new UserRepository(db);
            MessageRepository messageRepo = new MessageRepository(db);

            new BeaconServer(port, userRepo, messageRepo).start();

        } catch (SQLException e) {
            ServerUI.logError("Database error: " + e.getMessage());
        } catch (IOException e) {
            ServerUI.logError("Failed to create database directory: " + e.getMessage());
        }
    }
}


