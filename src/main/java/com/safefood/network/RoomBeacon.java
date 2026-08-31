package com.safefood.network;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class RoomBeacon implements Closeable {
    static final int DISCOVERY_PORT = 5001;

    static final String GROUP_ADDRESS = "239.255.42.99";

    static final String ASK = "SAFEFOOD1-FIND";
    static final String ANSWER = "SAFEFOOD1-HERE";

    static final int MAX_PACKET = 128;

    private final MulticastSocket socket;
    private final String addressPart;
    private final int tcpPort;

    private RoomBeacon(MulticastSocket socket, String addressPart, int tcpPort) {
        this.socket = socket;
        this.addressPart = addressPart;
        this.tcpPort = tcpPort;
    }

    /**
     * @throws IOException 탐색 포트를 열지 못한 경우. <b>방 자체는 정상 동작하므로</b>
     *                     호출한 쪽에서 안내만 하고 계속 진행하세요.
     */
    static RoomBeacon start(String inviteCode, int tcpPort) throws IOException {
        MulticastSocket socket = new MulticastSocket(DISCOVERY_PORT);
        try {
            InetSocketAddress group =
                    new InetSocketAddress(InetAddress.getByName(GROUP_ADDRESS), DISCOVERY_PORT);
            for (NetworkInterface nic : discoveryInterfaces()) {
                try {
                    socket.joinGroup(group, nic);
                } catch (IOException ignored) {
                }
            }
        } catch (IOException e) {
            socket.close();
            throw e;
        }

        RoomBeacon beacon = new RoomBeacon(socket, InviteCode.addressPart(inviteCode), tcpPort);
        Thread responder = new Thread(beacon::listenLoop, "room-beacon");
        responder.setDaemon(true);
        responder.start();
        return beacon;
    }

    private void listenLoop() {
        byte[] buffer = new byte[MAX_PACKET];
        while (!socket.isClosed()) {
            try {
                DatagramPacket request = new DatagramPacket(buffer, buffer.length);
                socket.receive(request);

                String asked = new String(request.getData(), request.getOffset(),
                        request.getLength(), StandardCharsets.UTF_8).trim();
                if (!asked.equals(ASK + "|" + addressPart)) {
                    continue;
                }

                byte[] answer = (ANSWER + "|" + tcpPort).getBytes(StandardCharsets.UTF_8);
                socket.send(new DatagramPacket(answer, answer.length,
                        request.getAddress(), request.getPort()));
            } catch (IOException e) {
                if (socket.isClosed()) {
                    return;
                }
                System.err.println("[beacon] 탐색 응답 실패: " + e.getMessage());
            }
        }
    }

    static List<NetworkInterface> discoveryInterfaces() {
        List<NetworkInterface> usable = new ArrayList<>();
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (nic.isUp() && !nic.isLoopback() && nic.supportsMulticast()) {
                    usable.add(nic);
                }
            }
        } catch (SocketException e) {
            System.err.println("[discovery] 네트워크 어댑터를 읽지 못했습니다: " + e.getMessage());
        }
        return usable;
    }

    @Override
    public void close() {
        socket.close();
    }
}
