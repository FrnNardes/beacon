package com.beacon.server;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry of all connected clients.
 * Maps username → ClientHandler. Accessed by every client thread.
 */
public class ClientRegistry {

    private final ConcurrentHashMap<String, ClientHandler> clients = new ConcurrentHashMap<>();

    /**
     * Registers a client. Returns false if the username is already taken.
     */
    public boolean register(String username, ClientHandler handler) {
        // putIfAbsent is atomic — two threads can't register the same name
        return clients.putIfAbsent(username, handler) == null;
    }

    public void unregister(String username) {
        clients.remove(username);
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
}
