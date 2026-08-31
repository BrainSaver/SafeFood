package com.safefood.network;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

public final class GroupServer {

    /**
     * 동시에 물려 둘 수 있는 연결 수. 한 끼 같이 먹는 인원을 크게 웃도는 값입니다.
     *
     * <p>연결 하나가 스레드 두 개(읽기·쓰기)를 씁니다. 상한이 없으면 접속만 계속 열어도
     * 방장 앱의 스레드가 바닥나 방이 통째로 멎습니다.
     */
    private static final int MAX_CONNECTIONS = 32;

    private final ServerSocket serverSocket;
    private final Room room;

    private final RoomBeacon beacon;

    private final JoinThrottle throttle = new JoinThrottle();

    private final AtomicInteger liveConnections = new AtomicInteger();

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
                    return;
                }
                System.err.println("[server] 접속 수락 실패: " + e.getMessage());
                continue;
            }
            // 코드를 찍어 맞히려는 주소와 넘치는 연결은 붙기 전에 끊습니다
            if (!throttle.allows(socket.getInetAddress())
                    || liveConnections.get() >= MAX_CONNECTIONS) {
                closeQuietly(socket);
                continue;
            }

            ClientHandler handler;
            try {
                handler = new ClientHandler(socket, room, throttle);
            } catch (IOException e) {
                System.err.println("[server] 접속을 준비하지 못했습니다: " + e.getMessage());
                closeQuietly(socket);
                continue;
            }

            liveConnections.incrementAndGet();
            Thread worker = new Thread(() -> {
                try {
                    handler.run();
                } finally {
                    liveConnections.decrementAndGet();
                }
            }, "group-client-handler-" + ++connectionNo);
            worker.setDaemon(true);
            worker.start();
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    public Room room() {
        return room;
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    public boolean discoverable() {
        return beacon != null;
    }

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
                    if (address.getAddress().length != 4
                            || address.isLoopbackAddress()
                            || address.isLinkLocalAddress()) {
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
        return best != null ? best : InetAddress.getLoopbackAddress();
    }

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

    public void stop() {
        room.close();
        if (beacon != null) {
            beacon.close();
        }
        try {
            serverSocket.close();
        } catch (IOException ignored) {
        }
    }
}
