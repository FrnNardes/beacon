package com.beacon.client;

import com.beacon.protocol.Message;
import com.beacon.protocol.MessageType;
import com.beacon.protocol.ProtocolException;
import com.beacon.protocol.ProtocolUtil;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;

/**
 * UDP-based server discovery for the Beacon client (RF-08).
 *
 * Sends a DISCOVER_SERVER broadcast to 255.255.255.255 on the UDP port
 * and waits for a SERVER_HERE reply containing the TCP host:port.
 *
 * This is called once at startup as a fallback when the user doesn't
 * provide --host/--port arguments. If no server responds within the
 * timeout, returns null and the client falls back to manual input.
 *
 * Design note: UDP broadcast is the only way to discover a server
 * without knowing its IP in advance. TCP doesn't support broadcast.
 * This is a classic use case for UDP in local network service discovery
 * (similar to DHCP, mDNS, SSDP).
 */
public class UdpDiscoveryClient {

    private static final int BUFFER_SIZE = 512;
    private static final int DISCOVERY_TIMEOUT_MS = 3000; // 3 seconds

    /**
     * Attempts to discover the Beacon server on the local network.
     *
     * Sends a DISCOVER_SERVER broadcast and waits for a SERVER_HERE reply.
     * Returns the TCP address as "host:port", or null if no server responds.
     *
     * @param udpPort the UDP port to broadcast to (convention: TCP port + 1)
     * @return "host:port" string for TCP connection, or null if timeout
     */
    public static String discover(int udpPort) {
        System.out.println("[Discovery] Searching for Beacon server on the network...");
        System.out.println("[Discovery] Broadcasting to 255.255.255.255:" + udpPort
                + " (timeout: " + DISCOVERY_TIMEOUT_MS + "ms)");

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.setSoTimeout(DISCOVERY_TIMEOUT_MS);

            // Build and send DISCOVER_SERVER broadcast
            Message discover = new Message(MessageType.DISCOVER_SERVER);
            byte[] requestData = ProtocolUtil.toUdpBytes(discover);
            DatagramPacket request = new DatagramPacket(
                    requestData, requestData.length,
                    InetAddress.getByName("255.255.255.255"), udpPort);
            socket.send(request);

            // Wait for SERVER_HERE reply
            byte[] buffer = new byte[BUFFER_SIZE];
            DatagramPacket response = new DatagramPacket(buffer, buffer.length);
            socket.receive(response); // blocks until reply or timeout

            Message reply = ProtocolUtil.fromUdpBytes(
                    response.getData(), response.getLength());

            if (reply.getType() == MessageType.SERVER_HERE && reply.getContent() != null) {
                System.out.println("[Discovery] Server found: " + reply.getContent());
                return reply.getContent();
            }

            System.out.println("[Discovery] Received unexpected reply: " + reply.getType());
            return null;

        } catch (SocketTimeoutException e) {
            System.out.println("[Discovery] No server responded within "
                    + DISCOVERY_TIMEOUT_MS + "ms.");
            return null;
        } catch (ProtocolException e) {
            System.err.println("[Discovery] Malformed server reply: " + e.getMessage());
            return null;
        } catch (IOException e) {
            System.err.println("[Discovery] Network error: " + e.getMessage());
            return null;
        }
    }
}
