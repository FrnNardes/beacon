package com.beacon.client;

import com.beacon.protocol.Message;
import com.beacon.protocol.ProtocolException;
import com.beacon.protocol.ProtocolUtil;

import java.io.BufferedReader;
import java.io.IOException;

/**
 * Background thread that reads messages from the server's TCP socket.
 * Runs in parallel with the main thread (which handles user input).
 */
public class TcpServerReader implements Runnable {

    private final BufferedReader in;
    private volatile boolean running = true;

    public TcpServerReader(BufferedReader in) {
        this.in = in;
    }

    @Override
    public void run() {
        try {
            String line;
            while (running && (line = in.readLine()) != null) {
                try {
                    Message msg = ProtocolUtil.deserialize(line);
                    displayMessage(msg);
                } catch (ProtocolException e) {
                    System.out.println("[!] Bad message from server: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            if (running) {
                System.out.println("\n[!] Connection lost: " + e.getMessage());
                System.out.println("Press Enter to retry or type /quit to exit.");
            }
        }
    }

    /**
     * Renders a message to the terminal based on its type.
     * For now, simple System.out — Jansi colors come in Slice 9.
     */
    private void displayMessage(Message msg) {
        switch (msg.getType()) {
            case LOGIN_OK -> System.out.println("[✓] Logged in successfully!");
            case LOGIN_ERROR -> System.out.println("[✗] Login failed: " + msg.getContent());

            case MESSAGE -> System.out.println(
                    formatTimestamp(msg) + msg.getSender() + ": " + msg.getContent());

            case PRIVATE -> System.out.println(
                    formatTimestamp(msg) + "[PM] " + msg.getSender() + " → " +
                    msg.getRecipient() + ": " + msg.getContent());

            case JOINED -> System.out.println("[+] " + msg.getSender() + " joined the beacon");
            case LEFT -> System.out.println("[-] " + msg.getSender() + " left the beacon");

            case USER_LIST -> System.out.println("[users] " + msg.getContent());

            case ERROR -> System.out.println("[!] " + msg.getContent());

            default -> System.out.println("[?] " + msg);
        }
    }

    private String formatTimestamp(Message msg) {
        if (msg.getTimestamp() == null) return "";
        // Extract HH:mm from ISO timestamp (e.g., "2026-07-30T23:12:00" → "23:12")
        String ts = msg.getTimestamp();
        if (ts.length() >= 16) {
            return "[" + ts.substring(11, 16) + "] ";
        }
        return "[" + ts + "] ";
    }

    public void stop() {
        running = false;
    }
}
