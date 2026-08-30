package com.safefood.network;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

public final class RoomFinder {
    public static final int DEFAULT_TIMEOUT_MS = 2_000;

    private RoomFinder() {
    }

    /**
     * @return 못 찾으면 {@link Optional#empty()} — 보조 수단이라 예외를 던지지 않습니다.
     */
    public static Optional<InetSocketAddress> find(String inviteCode, int timeoutMs) {
        byte[] question = (RoomBeacon.ASK + "|" + InviteCode.addressPart(inviteCode))
                .getBytes(StandardCharsets.UTF_8);

        try (MulticastSocket socket = new MulticastSocket()) {
            socket.setBroadcast(true);
            ask(socket, question);

            long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
            byte[] buffer = new byte[RoomBeacon.MAX_PACKET];
            while (true) {
                long remainingMs = (deadline - System.nanoTime()) / 1_000_000L;
                if (remainingMs <= 0) {
                    return Optional.empty();
                }
                socket.setSoTimeout((int) remainingMs);

                DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                socket.receive(response);

                String answer = new String(response.getData(), response.getOffset(),
                        response.getLength(), StandardCharsets.UTF_8).trim();
                if (!answer.startsWith(RoomBeacon.ANSWER + "|")) {
                    continue;
                }
                int port = parsePort(answer.substring(RoomBeacon.ANSWER.length() + 1));
                if (port > 0) {
                    return Optional.of(new InetSocketAddress(response.getAddress(), port));
                }
            }
        } catch (SocketTimeoutException e) {
            return Optional.empty();
        } catch (IOException e) {
            System.err.println("[finder] 방 탐색 실패: " + e.getMessage());
            return Optional.empty();
        }
    }

    public static Optional<InetSocketAddress> find(String inviteCode) {
        return find(inviteCode, DEFAULT_TIMEOUT_MS);
    }

    private static void ask(MulticastSocket socket, byte[] question) throws IOException {
        InetAddress group = InetAddress.getByName(RoomBeacon.GROUP_ADDRESS);

        send(socket, question, group);

        for (NetworkInterface nic : RoomBeacon.discoveryInterfaces()) {
            try {
                socket.setNetworkInterface(nic);
                send(socket, question, group);
            } catch (IOException ignored) {
            }
            for (InterfaceAddress address : nic.getInterfaceAddresses()) {
                InetAddress broadcast = address.getBroadcast();
                if (broadcast != null) {
                    send(socket, question, broadcast);
                }
            }
        }

        send(socket, question, InetAddress.getByName("255.255.255.255"));
    }

    private static void send(MulticastSocket socket, byte[] question, InetAddress target) {
        try {
            socket.send(new DatagramPacket(question, question.length,
                    target, RoomBeacon.DISCOVERY_PORT));
        } catch (IOException ignored) {
        }
    }

    private static int parsePort(String raw) {
        try {
            int port = Integer.parseInt(raw.trim());
            return port >= 1 && port <= 0xFFFF ? port : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
