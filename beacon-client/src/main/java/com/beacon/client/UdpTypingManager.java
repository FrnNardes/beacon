package com.beacon.client;

import com.beacon.protocol.Message;
import com.beacon.protocol.MessageType;
import com.beacon.protocol.ProtocolException;
import com.beacon.protocol.ProtocolUtil;
import com.beacon.client.ui.TerminalUI;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the background UDP socket and threads for the "User is typing..." indicator.
 * Decoupled from the main TCP chat client lifecycle.
 */
public class UdpTypingManager {

    private DatagramSocket udpSocket;
    private InetAddress serverAddress;
    private int udpPort;
    private String username;
    private TerminalUI ui;

    private final ConcurrentHashMap<String, Long> typingUsers = new ConcurrentHashMap<>();
    private static final long TYPING_EXPIRY_MS = 3000;
    private static final long TYPING_THROTTLE_MS = 1000;
    private long lastTypingSentMs = 0;

    public void start(InetAddress serverAddr, int tcpPort, String currentUsername, TerminalUI terminalUI) {
        this.serverAddress = serverAddr;
        this.udpPort = tcpPort + 1; // Convention: UDP = TCP + 1
        this.username = currentUsername;
        this.ui = terminalUI;

        try {
            this.udpSocket = new DatagramSocket(); // ephemeral port

            Thread typingReceiver = new Thread(this::receiveLoop, "udp-typing-receiver");
            typingReceiver.setDaemon(true);
            typingReceiver.start();

            Thread typingCleaner = new Thread(this::expiryCleaner, "typing-expiry-cleaner");
            typingCleaner.setDaemon(true);
            typingCleaner.start();

        } catch (SocketException e) {
            ui.printAbove(ui.formatError("[UDP] Failed to initialize typing indicator: " + e.getMessage()));
        }
    }

    public void sendTypingIndicator(String input) {
        if (udpSocket == null || udpSocket.isClosed()) return;

        long now = System.currentTimeMillis();
        if (now - lastTypingSentMs < TYPING_THROTTLE_MS) return;
        lastTypingSentMs = now;

        try {
            Message typing = new Message(MessageType.TYPING).sender(username);

            // Private-aware: detect /msg <recipient> pattern
            if (input.startsWith("/msg ")) {
                String[] parts = input.split("\\s+", 3);
                if (parts.length >= 2) {
                    typing.recipient(parts[1]);
                }
            }

            byte[] data = ProtocolUtil.toUdpBytes(typing);
            DatagramPacket packet = new DatagramPacket(data, data.length, serverAddress, udpPort);
            udpSocket.send(packet);
        } catch (IOException ignored) {}
    }

    private void receiveLoop() {
        byte[] buffer = new byte[512];
        while (!udpSocket.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                udpSocket.receive(packet);

                Message msg = ProtocolUtil.fromUdpBytes(packet.getData(), packet.getLength());
                if (msg.getType() == MessageType.TYPING && msg.getSender() != null) {
                    String sender = msg.getSender();
                    if (!sender.equals(username)) {
                        boolean isNew = !typingUsers.containsKey(sender);
                        typingUsers.put(sender, System.currentTimeMillis());
                        if (isNew) {
                            updateUI();
                        }
                    }
                }
            } catch (SocketException e) {
                break;
            } catch (IOException | ProtocolException ignored) {}
        }
    }

    private void expiryCleaner() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(500);

                long now = System.currentTimeMillis();
                boolean changed = false;
                Iterator<Map.Entry<String, Long>> it = typingUsers.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, Long> entry = it.next();
                    if (now - entry.getValue() > TYPING_EXPIRY_MS) {
                        it.remove();
                        changed = true;
                    }
                }
                if (changed) {
                    updateUI();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void updateUI() {
        if (ui != null) {
            Set<String> activeTypers = new HashSet<>(typingUsers.keySet());
            ui.updatePrompt(activeTypers);
        }
    }

    public void stop() {
        if (udpSocket != null && !udpSocket.isClosed()) {
            udpSocket.close();
        }
    }
}
