package com.safefood.network;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Collections;

/**
 * 방장 앱이 여는 서버 — 방을 만드는 순간 시작되고, 따로 켜 두는 중앙 서버는 없습니다.
 * {@code accept()}가 블로킹되므로 수락 루프는 별도 데몬 스레드에서 돕니다.
 */
public final class GroupServer {

    private final ServerSocket serverSocket;
    private final Room room;

    /** 자동 탐색 응답기 — 열지 못했으면 null입니다. 방 자체는 그대로 동작합니다. */
    private final RoomBeacon beacon;

    private GroupServer(ServerSocket serverSocket, Room room, RoomBeacon beacon) {
        this.serverSocket = serverSocket;
        this.room = room;
        this.beacon = beacon;
    }

    /**
     * @throws IOException 포트가 이미 사용 중이면 {@link java.net.BindException} —
     *                     호출한 쪽에서 안내하고 config.properties의 socket.port를 바꾸게 하세요.
     */
    public static GroupServer open(int port, String inviteCode) throws IOException {
        ServerSocket serverSocket = new ServerSocket(port);

        // 자동 탐색은 '코드에 담긴 주소가 낡았을 때'를 위한 보험일 뿐이라, 못 켜도 방은 정상입니다.
        // 그래서 예외를 올리지 않고 안내만 남긴 뒤 서버를 계속 엽니다.
        RoomBeacon beacon = null;
        try {
            beacon = RoomBeacon.start(inviteCode, port);
        } catch (IOException e) {
            System.err.println("[server] 방 자동 탐색을 켜지 못했습니다"
                    + " (초대 코드에 담긴 주소로는 그대로 접속됩니다): " + e.getMessage());
        }

        GroupServer server = new GroupServer(serverSocket, new Room(inviteCode), beacon);
        Thread acceptor = new Thread(server::acceptLoop, "group-server-accept");
        acceptor.setDaemon(true);
        acceptor.start();
        return server;
    }

    private void acceptLoop() {
        int connectionNo = 0;
        while (!serverSocket.isClosed()) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (IOException e) {
                if (serverSocket.isClosed()) {
                    return;   // stop()으로 닫힘 — 정상 종료
                }
                System.err.println("[server] 접속 수락 실패: " + e.getMessage());
                continue;
            }
            try {
                Thread handler = new Thread(new ClientHandler(socket, room),
                        "group-client-handler-" + ++connectionNo);
                handler.setDaemon(true);
                handler.start();
            } catch (IOException e) {
                System.err.println("[server] 접속을 준비하지 못했습니다: " + e.getMessage());
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    public Room room() {
        return room;
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    /** 꺼져 있으면 방장의 IP가 바뀌는 순간 초대 코드가 무효가 됩니다. */
    public boolean discoverable() {
        return beacon != null;
    }

    /**
     * 초대 코드에 담을 이 PC의 LAN 주소.
     *
     * <p>⚠️ {@code InetAddress.getLocalHost()}를 쓰면 안 됩니다 — VirtualBox·VMware·VPN 어댑터가
     * 깔린 PC에서는 {@code 192.168.56.1} 같은 가상 어댑터 주소가 나옵니다. 그 주소가 초대 코드에
     * 굳어 버리면 아무도 접속하지 못하므로, 실제 랜카드를 직접 훑어 고릅니다.
     *
     * <p>엉뚱한 주소가 잡히면 {@code config.properties}의 {@code socket.host}에 직접 적으세요.
     */
    public static InetAddress lanAddress() {
        String configured = SocketConfig.host();
        if (!configured.equals(SocketConfig.DEFAULT_HOST)) {
            try {
                return InetAddress.getByName(configured);
            } catch (UnknownHostException e) {
                System.err.println("[server] config.properties의 socket.host를 해석하지 못했습니다: "
                        + configured);
            }
        }

        InetAddress best = null;
        int bestScore = 0;
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback() || nic.isPointToPoint()) {
                    continue;
                }
                for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                    if (address.getAddress().length != 4          // IPv4만 코드에 담깁니다
                            || address.isLoopbackAddress()
                            || address.isLinkLocalAddress()) {    // 169.254.x — DHCP 실패 주소
                        continue;
                    }
                    int score = score(nic, address);
                    if (score > bestScore) {
                        bestScore = score;
                        best = address;
                    }
                }
            }
        } catch (SocketException e) {
            System.err.println("[server] 네트워크 어댑터를 읽지 못했습니다: " + e.getMessage());
        }
        // 랜선·와이파이가 다 빠져 있어도 같은 PC 안에서의 시험은 되도록 루프백으로 떨어집니다
        return best != null ? best : InetAddress.getLoopbackAddress();
    }

    /** 실물 랜카드 &gt; 가상 어댑터, 사설망 주소 &gt; 그 외 순으로 점수를 줍니다. */
    private static int score(NetworkInterface nic, InetAddress address) {
        boolean real = !looksVirtual(nic);
        boolean lan = address.isSiteLocalAddress();
        if (real && lan) {
            return 4;
        }
        if (real) {
            return 3;
        }
        return lan ? 2 : 1;
    }

    /**
     * {@code NetworkInterface.isVirtual()}은 하위 인터페이스만 true라서 VirtualBox·Hyper-V를
     * 못 걸러 냅니다. 이름 검사는 완벽하지 않으므로 제외가 아니라 <b>후순위</b>로만 씁니다.
     */
    private static boolean looksVirtual(NetworkInterface nic) {
        String name = (nic.getName() + " " + nic.getDisplayName()).toLowerCase();
        for (String keyword : new String[]{"virtual", "vbox", "vmware", "hyper-v", "vethernet",
                "vpn", "tap", "tunnel", "docker", "wsl", "bluetooth", "pseudo"}) {
            if (name.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /** 참여자 전원에게 알린 뒤 모든 소켓을 정리하고 서버를 내립니다. */
    public void stop() {
        room.close();
        if (beacon != null) {
            beacon.close();   // 닫은 방이 탐색에 계속 대답하지 않도록
        }
        try {
            serverSocket.close();
        } catch (IOException ignored) {
        }
    }
}
