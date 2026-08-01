package com.beacon.server;

import com.beacon.server.persistence.DatabaseManager;
import com.beacon.server.persistence.MessageRepository;
import com.beacon.server.persistence.UserRepository;

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
 */
public class BeaconServer {

    private final int port;
    private final ClientRegistry registry = new ClientRegistry();
    private final ExecutorService threadPool = Executors.newCachedThreadPool();

    private final UserRepository userRepo;
    private final MessageRepository messageRepo;

    public BeaconServer(int port, UserRepository userRepo, MessageRepository messageRepo) {
        this.port = port;
        this.userRepo = userRepo;
        this.messageRepo = messageRepo;
    }

    public void start() {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Beacon Server listening on port " + port);
            System.out.println("Waiting for connections...");

            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("[*] New connection from " + clientSocket.getRemoteSocketAddress());

                ClientHandler handler = new ClientHandler(
                        clientSocket, registry, userRepo, messageRepo);
                threadPool.execute(handler);
            }
        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        int port = 4040;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid port: " + args[0] + ". Using default " + port);
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
            System.err.println("Database error: " + e.getMessage());
        } catch (IOException e) {
            System.err.println("Failed to create database directory: " + e.getMessage());
        }
    }
}
