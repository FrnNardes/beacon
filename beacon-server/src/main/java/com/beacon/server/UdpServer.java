package com.beacon.server;

import com.beacon.protocol.Message;
import com.beacon.protocol.MessageType;
import com.beacon.protocol.ProtocolException;
import com.beacon.protocol.ProtocolUtil;
import com.beacon.server.ui.ServerUI;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;

/**
 * UDP server thread handling two responsibilities:
 *
 * 1. **Discovery (RF-08):** Listens for DISCOVER_SERVER broadcasts from clients
 *    and replies with SERVER_HERE containing the TCP host:port.
 *
 * 2. **Typing relay (RF-09):** Receives TYPING datagrams from clients and
 *    relays them to the appropriate recipients. Private-aware: if the typing
 *    message has a recipient, only that user is notified; otherwise, all
 *    connected users are notified (broadcast).
 *
 * Runs as a daemon thread — dies when the main server process exits.
 *
 * Design note: UDP is used here because typing indicators are ephemeral
 * and loss-tolerant. Losing a "typing..." notification has zero impact
 * on the chat experience, so the overhead of TCP (ACK, retransmit,
 * ordering) is unnecessary.
 */
public class UdpServer implements Runnable {

    private static final int BUFFER_SIZE = 1024; // UDP datagrams are small JSON

    private final int udpPort;
    private final int tcpPort;
    private final ClientRegistry registry;
    private volatile boolean running = true;
    private DatagramSocket socket;

    public UdpServer(int udpPort, int tcpPort, ClientRegistry registry) {
        this.udpPort = udpPort;
        this.tcpPort = tcpPort;
        this.registry = registry;
    }

    @Override
    public void run() {
        try {
            socket = new DatagramSocket(udpPort);
            ServerUI.log("[UDP] Listening on port " + udpPort
                    + " (discovery + typing relay)");

            byte[] buffer = new byte[BUFFER_SIZE];

            while (running) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet); // blocks until a datagram arrives

                try {
                    Message msg = ProtocolUtil.fromUdpBytes(
                            packet.getData(), packet.getLength());
                    handleMessage(msg, packet);
                } catch (ProtocolException e) {
                    ServerUI.logError("[UDP] Malformed datagram from "
                            + packet.getAddress() + ": " + e.getMessage());
                }
            }
        } catch (SocketException e) {
            if (running) {
                ServerUI.logError("[UDP] Socket error: " + e.getMessage());
            }
            // If !running, this was a clean shutdown via stop()
        } catch (IOException e) {
            ServerUI.logError("[UDP] I/O error: " + e.getMessage());
        } finally {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
            ServerUI.log("[UDP] Server stopped.");
        }
    }

    /**
     * Dispatches incoming UDP messages by type.
     */
    private void handleMessage(Message msg, DatagramPacket packet) throws IOException {
        switch (msg.getType()) {
            case DISCOVER_SERVER -> handleDiscovery(packet);
            case TYPING -> handleTyping(msg, packet);
            default -> ServerUI.logError("[UDP] Unexpected message type: " + msg.getType());
        }
    }

    /**
     * Responds to a DISCOVER_SERVER broadcast with SERVER_HERE.
     * The response is sent unicast back to the source address of the request.
     * Content contains the TCP address in "host:port" format.
     */
    private void handleDiscovery(DatagramPacket request) throws IOException {
        // Use the server's own address as seen by the network interface
        // that received the broadcast — this ensures the client gets a
        // routable address, not 0.0.0.0 or localhost.
        String tcpAddress = socket.getLocalAddress().getHostAddress();

        // If bound to wildcard (0.0.0.0), use the destination address
        // from the client's perspective — the address they sent to
        if (socket.getLocalAddress().isAnyLocalAddress()) {
            // Best effort: use the local address of a temporary connection
            // to the client's subnet to determine our routable IP
            try (DatagramSocket probe = new DatagramSocket()) {
                probe.connect(request.getAddress(), request.getPort());
                tcpAddress = probe.getLocalAddress().getHostAddress();
            } catch (Exception e) {
                tcpAddress = InetAddress.getLocalHost().getHostAddress();
            }
        }

        Message reply = new Message(MessageType.SERVER_HERE)
                .content(tcpAddress + ":" + tcpPort);

        byte[] data = ProtocolUtil.toUdpBytes(reply);
        DatagramPacket response = new DatagramPacket(
                data, data.length,
                request.getAddress(), request.getPort());
        socket.send(response);

        ServerUI.log("[UDP] Discovery reply sent to "
                + request.getAddress().getHostAddress() + ":" + request.getPort()
                + " → " + tcpAddress + ":" + tcpPort);
    }

    /**
     * Handles a TYPING indicator from a client.
     *
     * First, registers/updates the sender's UDP address in the registry
     * (learned from the datagram's source address — no explicit handshake needed).
     *
     * Then relays the typing indicator:
     *   - If recipient is set → send only to that user (private typing)
     *   - If recipient is null → broadcast to all other connected users
     */
    private void handleTyping(Message msg, DatagramPacket packet) {
        String sender = msg.getSender();
        if (sender == null || sender.isBlank()) return;
        sender = sender.toLowerCase();

        // Only relay typing for users who are actually logged in via TCP
        if (!registry.isOnline(sender)) return;

        // Learn/update the sender's UDP address from the datagram source
        InetSocketAddress senderUdpAddr = new InetSocketAddress(
                packet.getAddress(), packet.getPort());
        registry.registerUdpAddress(sender, senderUdpAddr);

        // Relay to the appropriate recipients
        String recipient = msg.getRecipient();
        if (recipient != null && !recipient.isBlank()) {
            recipient = recipient.toLowerCase();
            // Private typing: forward only to the specific recipient
            registry.sendUdpTo(recipient, msg, socket);
        } else {
            // Broadcast typing: forward to everyone except the sender
            registry.broadcastUdp(msg, sender, socket);
        }
    }

    /**
     * Gracefully stops the UDP server by closing the socket,
     * which unblocks the receive() call in the run loop.
     */
    public void stop() {
        running = false;
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }
}

