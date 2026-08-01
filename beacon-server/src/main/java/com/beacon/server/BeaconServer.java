package com.beacon.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Beacon chat server entry point.
 * Opens a TCP ServerSocket and spawns one thread per connecting client.
 */
public class BeaconServer {

    private final int port;
    private final ClientRegistry registry = new ClientRegistry();

    // Cached thread pool: creates new threads as needed, reuses idle ones.
    // Better than raw "new Thread()" because it manages thread lifecycle
    // and avoids the cost of creating/destroying threads repeatedly.
    private final ExecutorService threadPool = Executors.newCachedThreadPool();

    public BeaconServer(int port) {
        this.port = port;
    }

    /**
     * Starts the server. Blocks forever, accepting connections in a loop.
     */
    public void start() {
        // ServerSocket listens on a port and waits for incoming TCP connections.
        // try-with-resources ensures the socket is closed even if an exception occurs.
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Beacon Server listening on port " + port);
            System.out.println("Waiting for connections...");

            while (true) {
                // accept() blocks until a client connects. When it does,
                // it returns a new Socket representing that specific connection.
                // The ServerSocket keeps listening for more connections.
                Socket clientSocket = serverSocket.accept();

                System.out.println("[*] New connection from " + clientSocket.getRemoteSocketAddress());

                // Hand the socket to a ClientHandler and run it in the thread pool.
                // From this point, the ClientHandler thread owns this socket.
                ClientHandler handler = new ClientHandler(clientSocket, registry);
                threadPool.execute(handler);
            }
        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        // Default port — will be replaced with Picocli parsing in a later slice
        int port = 4040;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid port: " + args[0] + ". Using default " + port);
            }
        }

        new BeaconServer(port).start();
    }
}
