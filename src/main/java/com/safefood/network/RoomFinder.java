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

/**
 * 방 자동 탐색 — 같은 네트워크에 "이 코드의 방 있나요?"를 뿌리고 방장의 현재 주소를 받아 옵니다.
 * 코드에 담긴 주소로 바로 접속되지 않을 때만 폴백으로 호출합니다 ({@link RoomBeacon}이 대답).
 */
public final class RoomFinder {

    public static final int DEFAULT_TIMEOUT_MS = 2_000;

    private RoomFinder() {
    }

    /**
     * 주소를 찾아 줘도 <b>입장 자격을 준 것은 아닙니다</b> — 질의에는 코드의 주소부만 실리고,
     * 자격 검사는 이어지는 TCP {@code JOIN}에서 {@link Room}이 코드 전체로 수행합니다.
     *
     * @return 못 찾으면 {@link Optional#empty()} — 보조 수단이라 예외를 던지지 않습니다.
     */
    public static Optional<InetSocketAddress> find(String inviteCode, int timeoutMs) {
        byte[] question = (RoomBeacon.ASK + "|" + InviteCode.addressPart(inviteCode))
                .getBytes(StandardCharsets.UTF_8);

        try (MulticastSocket socket = new MulticastSocket()) {   // 임의 포트 — 응답은 여기로 돌아옵니다
            socket.setBroadcast(true);
            ask(socket, question);

            long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
            byte[] buffer = new byte[RoomBeacon.MAX_PACKET];
            while (true) {
                long remainingMs = (deadline - System.nanoTime()) / 1_000_000L;
                if (remainingMs <= 0) {
                    return Optional.empty();
                }
                socket.setSoTimeout((int) remainingMs);   // 남은 시간만큼만 — 전체 대기가 길어지지 않게

                DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                socket.receive(response);

                String answer = new String(response.getData(), response.getOffset(),
                        response.getLength(), StandardCharsets.UTF_8).trim();
                if (!answer.startsWith(RoomBeacon.ANSWER + "|")) {
                    continue;   // 내가 뿌린 질의가 되돌아온 것 등 — 버리고 계속 기다립니다
                }
                int port = parsePort(answer.substring(RoomBeacon.ANSWER.length() + 1));
                if (port > 0) {
                    // 방장의 '지금' 주소 = 이 응답이 날아온 곳
                    return Optional.of(new InetSocketAddress(response.getAddress(), port));
                }
            }
        } catch (SocketTimeoutException e) {
            return Optional.empty();   // 아무도 대답하지 않음 — 방이 없거나 탐색이 막힌 네트워크
        } catch (IOException e) {
            System.err.println("[finder] 방 탐색 실패: " + e.getMessage());
            return Optional.empty();
        }
    }

    public static Optional<InetSocketAddress> find(String inviteCode) {
        return find(inviteCode, DEFAULT_TIMEOUT_MS);
    }

    /**
     * 멀티캐스트와 브로드캐스트 양쪽으로 뿌립니다 — 공유기가 멀티캐스트를 거르는 경우와,
     * 한 PC에서 앱을 여러 개 띄워 시험하는 경우(멀티캐스트라야 닿음)를 한 번에 덮기 위해서입니다.
     * 닿는 경로가 하나만 있으면 되므로 실패한 경로는 조용히 넘어갑니다.
     */
    private static void ask(MulticastSocket socket, byte[] question) throws IOException {
        InetAddress group = InetAddress.getByName(RoomBeacon.GROUP_ADDRESS);

        // 1) 기본 경로 멀티캐스트 — 어댑터가 하나뿐인 보통의 PC는 여기서 끝납니다
        send(socket, question, group);

        for (NetworkInterface nic : RoomBeacon.discoveryInterfaces()) {
            // 2) 어댑터별 멀티캐스트 — 기본 경로가 가상 어댑터로 빠지는 PC 대비
            try {
                socket.setNetworkInterface(nic);
                send(socket, question, group);
            } catch (IOException ignored) {
            }
            // 3) 어댑터별 브로드캐스트 — 공유기가 멀티캐스트를 걸러 낼 때의 대비
            for (InterfaceAddress address : nic.getInterfaceAddresses()) {
                InetAddress broadcast = address.getBroadcast();   // IPv6에는 없어 null
                if (broadcast != null) {
                    send(socket, question, broadcast);
                }
            }
        }

        // 4) 전체 브로드캐스트 — 마지막 그물
        send(socket, question, InetAddress.getByName("255.255.255.255"));
    }

    private static void send(MulticastSocket socket, byte[] question, InetAddress target) {
        try {
            socket.send(new DatagramPacket(question, question.length,
                    target, RoomBeacon.DISCOVERY_PORT));
        } catch (IOException ignored) {
            // 이 경로로는 안 나갑니다 — 다른 경로에 기댑니다
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
