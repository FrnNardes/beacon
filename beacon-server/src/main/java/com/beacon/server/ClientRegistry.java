package com.beacon.server;

import com.beacon.protocol.Message;
import com.beacon.protocol.ProtocolUtil;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe registry of all connected clients.
 * Maps username → ClientHandler (TCP) and username → InetSocketAddress (UDP).
 * Also manages server-assigned user colors from a rotative palette.
 */
public class ClientRegistry {

    private final ConcurrentHashMap<String, ClientHandler> clients = new ConcurrentHashMap<>();

    /**
     * UDP addresses of clients, learned from the source address of their first TYPING datagram.
     */
    private final ConcurrentHashMap<String, InetSocketAddress> udpAddresses = new ConcurrentHashMap<>();

    /**
     * Server-assigned ANSI color for each user (e.g., "cyan", "magenta").
     * Rotated from the palette to ensure distinct colors among online users.
     */
    private final ConcurrentHashMap<String, String> userColors = new ConcurrentHashMap<>();

    /**
     * Palette of distinguishable ANSI color names.
     * These map to 256-color ANSI codes on the client side.
     */
    private static final String[] COLOR_PALETTE = {
            "cyan", "magenta", "yellow", "green", "blue",
            "red", "orange", "pink", "lime", "teal"
    };

    private final AtomicInteger colorIndex = new AtomicInteger(0);

    /**
     * Assigns the next color from the palette (round-robin).
     */
    public String assignColor(String username) {
        String color = COLOR_PALETTE[colorIndex.getAndIncrement() % COLOR_PALETTE.length];
        userColors.put(username, color);
        return color;
    }

    public String getColor(String username) {
        return userColors.getOrDefault(username, "white");
    }

    // ── TCP registry ─────────────────────────────────────────────────────


    /**
     * Registers a client. Returns false if the username is already taken.
     */
    public boolean register(String username, ClientHandler handler) {
        // putIfAbsent is atomic — two threads can't register the same name
        return clients.putIfAbsent(username, handler) == null;
    }

    /**
     * Unregisters a client from both TCP and UDP registries.
     */
    public void unregister(String username) {
        clients.remove(username);
        udpAddresses.remove(username);
    }

    public ClientHandler getClient(String username) {
        return clients.get(username);
    }

    public boolean isOnline(String username) {
        return clients.containsKey(username);
    }

    /**
     * Returns a snapshot of all connected handlers.
     * Snapshot avoids ConcurrentModificationException if someone
     * disconnects while we're iterating (e.g., during broadcast).
     */
    public List<ClientHandler> getAllClients() {
        return new ArrayList<>(clients.values());
    }

    public List<String> getAllUsernames() {
        return new ArrayList<>(clients.keySet());
    }

    public int getOnlineCount() {
        return clients.size();
    }

    // ── UDP address tracking ─────────────────────────────────────────────

    /**
     * Registers or updates the UDP address for a client.
     * Called by UdpServer when it receives a TYPING datagram — the source
     * address of the datagram reveals the client's UDP endpoint.
     */
    public void registerUdpAddress(String username, InetSocketAddress address) {
        udpAddresses.put(username, address);
    }

    /**
     * Returns the UDP address for a specific client, or null if unknown.
     */
    public InetSocketAddress getUdpAddress(String username) {
        return udpAddresses.get(username);
    }

    /**
     * Sends a UDP message to a specific client by username.
     * Silently does nothing if the target has no known UDP address.
     */
    public void sendUdpTo(String username, Message message, DatagramSocket socket) {
        InetSocketAddress addr = udpAddresses.get(username);
        if (addr == null) return;

        try {
            byte[] data = ProtocolUtil.toUdpBytes(message);
            DatagramPacket packet = new DatagramPacket(
                    data, data.length, addr.getAddress(), addr.getPort());
            socket.send(packet);
        } catch (IOException e) {
            System.err.println("[UDP] Failed to send to " + username + ": " + e.getMessage());
        }
    }

    /**
     * Broadcasts a UDP message to all clients except the excluded one.
     * Used for typing indicators when no specific recipient is set.
     */
    public void broadcastUdp(Message message, String excludeUsername, DatagramSocket socket) {
        for (Map.Entry<String, InetSocketAddress> entry : udpAddresses.entrySet()) {
            if (!entry.getKey().equals(excludeUsername)) {
                try {
                    byte[] data = ProtocolUtil.toUdpBytes(message);
                    DatagramPacket packet = new DatagramPacket(
                            data, data.length,
                            entry.getValue().getAddress(),
                            entry.getValue().getPort());
                    socket.send(packet);
                } catch (IOException e) {
                    System.err.println("[UDP] Failed to broadcast to "
                            + entry.getKey() + ": " + e.getMessage());
                }
            }
        }
    }
}
