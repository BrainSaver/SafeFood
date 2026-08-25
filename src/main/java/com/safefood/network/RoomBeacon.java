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

/**
 * 방 자동 탐색 응답기 — "이 코드의 방, 지금 어디 있나요?" 질의에 방장이 대답합니다.
 * 초대 코드에는 발급 당시의 주소가 굳어 있어, 방장의 IP가 바뀌었을 때의 보험입니다.
 *
 * <p>지킬 것 두 가지
 * <ul>
 *   <li>시크릿은 싣지 않습니다 — 방을 가리키는 값으로 코드의 주소부 10자만 씁니다.
 *       암호 역할을 하는 뒤 4자는 TCP {@code JOIN}에서 {@link Room}이 검사합니다.</li>
 *   <li>응답에 IP를 넣지 않습니다 — UDP 응답의 출발지 주소가 곧 방장의 현재 주소입니다.</li>
 * </ul>
 */
final class RoomBeacon implements Closeable {

    /** 방장과 참여자가 반드시 같은 값을 써야 해서 설정 파일로 빼지 않았습니다. */
    static final int DISCOVERY_PORT = 5001;

    /** 로컬 멀티캐스트 대역(239.0.0.0/8) — TTL 1이라 공유기를 넘지 않습니다. */
    static final String GROUP_ADDRESS = "239.255.42.99";

    /** 프로토콜 이름 + 판 번호 — 형식을 바꿔도 옛 버전과 섞이지 않게. */
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
        // MulticastSocket 생성자가 SO_REUSEADDR을 켜 줍니다 — 한 PC에서 앱을 여러 개 띄워도 겹치지 않습니다
        MulticastSocket socket = new MulticastSocket(DISCOVERY_PORT);
        try {
            InetSocketAddress group =
                    new InetSocketAddress(InetAddress.getByName(GROUP_ADDRESS), DISCOVERY_PORT);
            for (NetworkInterface nic : discoveryInterfaces()) {
                try {
                    socket.joinGroup(group, nic);
                } catch (IOException ignored) {
                    // 이 어댑터로는 못 받습니다 — 다른 어댑터나 브로드캐스트가 커버합니다
                }
            }
        } catch (IOException e) {
            socket.close();   // 열어 둔 포트를 물고 늘어지지 않게
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
                    continue;   // 다른 방을 찾는 질의 — 응답조차 하지 않아 방의 존재를 숨깁니다
                }

                byte[] answer = (ANSWER + "|" + tcpPort).getBytes(StandardCharsets.UTF_8);
                socket.send(new DatagramPacket(answer, answer.length,
                        request.getAddress(), request.getPort()));
            } catch (IOException e) {
                if (socket.isClosed()) {
                    return;   // close()로 닫힘 — 정상 종료
                }
                System.err.println("[beacon] 탐색 응답 실패: " + e.getMessage());
            }
        }
    }

    /** 멀티캐스트를 쓸 수 있는 실제 어댑터 목록 — 방장(참여)과 참여자(질의)가 함께 씁니다. */
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

    /** 소켓을 닫으면 {@code receive()}가 깨어나 응답 스레드가 끝납니다. */
    @Override
    public void close() {
        socket.close();
    }
}
