package com.safefood.network;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 소켓 경계의 양끝 — 클라이언트가 <b>정확히 어떤 줄</b>을 내보내는지(전선 형식),
 * 받은 줄을 어떻게 화면 리스너로 넘기는지, 서버 열고 닫기가 한 바퀴 도는지 봅니다.
 *
 * <p>전선 형식은 프로토콜의 계약이라 글자 단위로 못박습니다 — 여기가 바뀌면
 * 구버전과 신버전이 같은 방에서 만났을 때 조용히 어긋납니다.
 */
class GroupClientServerTest {

    private static final String CODE = "TESTR00M99";
    private static final long ARRIVE_MS = 3_000;

    /** 받은 전문과 끊김을 큐로 모으는 리스너. 끊김은 null 로 표시합니다. */
    private static final class Inbox implements GroupClient.Listener {
        final BlockingQueue<Message> messages = new LinkedBlockingQueue<>();
        final CountDownLatch disconnected = new CountDownLatch(1);

        @Override
        public void onMessage(Message message) {
            messages.add(message);
        }

        @Override
        public void onDisconnected() {
            disconnected.countDown();
        }
    }

    // ── 전선 형식 (클라이언트 → 서버) ───────────────────────────

    @Test
    @DisplayName("보내는 전문의 전선 형식 — JOIN·INFO·READY·VOTE·CHAT·RETRY·REROLL·EXIT")
    void clientWritesExactWireFormat() throws IOException {
        try (ServerSocket server = new ServerSocket(0)) {
            GroupClient client = GroupClient.connect("localhost", server.getLocalPort());
            try (Socket serverSide = server.accept();
                 BufferedReader fromClient = new BufferedReader(new InputStreamReader(
                         serverSide.getInputStream(), StandardCharsets.UTF_8))) {

                client.join(CODE, "민수");
                assertEquals("JOIN|" + CODE + "|민수", fromClient.readLine());

                client.sendInfo(List.of("땅콩", "새우"), 3, 12000, 7);
                assertEquals("INFO|땅콩,새우|3|12000|7", fromClient.readLine());

                client.sendInfo(List.of(), 5, 0, 0);
                assertEquals("INFO||5|0|0", fromClient.readLine(),
                        "알레르기가 없어도 칸 수는 같아야 합니다");

                client.sendReady();
                assertEquals("READY", fromClient.readLine());

                client.sendVote(2);
                assertEquals("VOTE|2", fromClient.readLine());

                client.sendChat("안녕 세상");
                assertEquals("CHAT|안녕 세상", fromClient.readLine());

                client.sendRetry();
                assertEquals("RETRY", fromClient.readLine());

                client.sendReroll();
                assertEquals("REROLL", fromClient.readLine());

                client.close();
                assertEquals("EXIT", fromClient.readLine(), "닫기 전에 작별 전문을 보낸다");
                assertNull(fromClient.readLine(), "그 뒤 소켓이 닫힌다");
                assertFalse(client.isAlive());
            }
        }
    }

    // ── 수신과 끊김 통지 (서버 → 클라이언트) ────────────────────

    @Test
    @DisplayName("받은 줄은 해석해 리스너로 — 못 읽는 줄은 조용히 버린다")
    void receivedLinesReachListenerParsed() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            GroupClient client = GroupClient.connect("localhost", server.getLocalPort());
            Inbox inbox = new Inbox();
            client.setListener(inbox);
            try (Socket serverSide = server.accept()) {
                var toClient = new java.io.BufferedWriter(new java.io.OutputStreamWriter(
                        serverSide.getOutputStream(), StandardCharsets.UTF_8));
                toClient.write("JOINED|민수|1");
                toClient.newLine();
                toClient.write("trash|줄");   // 모르는 타입 — 버려져야 합니다
                toClient.newLine();
                toClient.write("READY|민수");
                toClient.newLine();
                toClient.flush();

                Message first = inbox.messages.poll(ARRIVE_MS, TimeUnit.MILLISECONDS);
                Message second = inbox.messages.poll(ARRIVE_MS, TimeUnit.MILLISECONDS);
                assertAll(
                        () -> assertEquals(Message.Type.JOINED, first.type()),
                        () -> assertEquals(Message.Type.READY, second.type(),
                                "불량 줄이 리스너까지 오면 안 됩니다"));
            } finally {
                client.close();
            }
        }
    }

    @Test
    @DisplayName("상대가 끊으면 onDisconnected — 내가 닫았을 때는 부르지 않는다")
    void disconnectCallbackOnlyForRemoteClose() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            // 1) 서버 쪽에서 끊은 경우 — 통지가 와야 합니다
            GroupClient dropped = GroupClient.connect("localhost", server.getLocalPort());
            Inbox droppedInbox = new Inbox();
            dropped.setListener(droppedInbox);
            server.accept().close();
            assertTrue(droppedInbox.disconnected.await(ARRIVE_MS, TimeUnit.MILLISECONDS),
                    "서버가 끊으면 화면이 알아야 합니다");
            assertFalse(dropped.isAlive());

            // 2) 내가 close() 한 경우 — 통지가 오면 화면이 '끊김'으로 오해합니다
            GroupClient closer = GroupClient.connect("localhost", server.getLocalPort());
            Inbox closerInbox = new Inbox();
            closer.setListener(closerInbox);
            try (Socket ignored = server.accept()) {
                closer.close();
                assertFalse(closerInbox.disconnected.await(500, TimeUnit.MILLISECONDS),
                        "스스로 닫은 것은 끊김이 아닙니다");
            }
        }
    }

    @Test
    @DisplayName("아무도 듣지 않는 포트로는 접속이 예외로 끝난다")
    void connectToDeadPortThrows() throws IOException {
        int deadPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            deadPort = probe.getLocalPort();
        }

        assertThrows(IOException.class, () -> GroupClient.connect("localhost", deadPort));
    }

    // ── 서버 한 바퀴 ───────────────────────────────────────────

    @Test
    @DisplayName("서버 열기 → 입장 → 종료 통지까지 실제 연결 한 바퀴")
    void serverLifecycleRoundTrip() throws Exception {
        GroupServer server = GroupServer.open(0, CODE);
        try {
            assertAll(
                    () -> assertTrue(server.port() > 0, "0을 주면 OS가 빈 포트를 골라 준다"),
                    () -> assertNotNull(server.room()));

            GroupClient client = GroupClient.connect("localhost", server.port());
            Inbox inbox = new Inbox();
            client.setListener(inbox);
            client.join(CODE, "민수");

            Message joined = inbox.messages.poll(ARRIVE_MS, TimeUnit.MILLISECONDS);
            assertNotNull(joined, "입장 확인이 와야 합니다");
            assertEquals(Message.Type.JOINED, joined.type());
            assertEquals("민수", joined.part(0));

            server.stop();

            Message closed = inbox.messages.poll(ARRIVE_MS, TimeUnit.MILLISECONDS);
            assertNotNull(closed, "종료 안내가 와야 합니다");
            assertEquals(Message.Type.CLOSED, closed.type());
            assertTrue(inbox.disconnected.await(ARRIVE_MS, TimeUnit.MILLISECONDS));

            server.stop();   // 두 번 닫아도 탈이 없어야 합니다
        } finally {
            server.stop();
        }
    }

    @Test
    @DisplayName("lanAddress — 언제나 IPv4 주소 하나를 내놓는다 (최악에도 루프백)")
    void lanAddressAlwaysResolvesToIpv4() {
        InetAddress address = GroupServer.lanAddress();

        assertAll(
                () -> assertNotNull(address),
                () -> assertEquals(4, address.getAddress().length,
                        "초대 코드에 담을 수 있는 건 IPv4뿐입니다"));
    }

    // ── 방 자동 탐색 ───────────────────────────────────────────

    @Test
    @DisplayName("응답할 방이 없으면 탐색은 제한 시간 안에 빈손으로 돌아온다")
    void finderReturnsEmptyWhenNobodyAnswers() {
        long started = System.nanoTime();
        var found = RoomFinder.find("ZZZZZZZZZZ9999", 150);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertAll(
                () -> assertTrue(found.isEmpty()),
                () -> assertTrue(elapsedMs < 2_500,
                        "제한 시간(150ms)을 크게 넘겨 붙들면 안 됩니다: " + elapsedMs + "ms"));
    }

    // ── 소켓 설정 ──────────────────────────────────────────────

    @Test
    @DisplayName("SocketConfig — 호스트는 비어 있지 않고 포트는 언제나 유효 범위")
    void socketConfigStaysInValidRange() {
        assertAll(
                () -> assertFalse(SocketConfig.host().isBlank()),
                () -> assertTrue(SocketConfig.port() >= 1 && SocketConfig.port() <= 0xFFFF,
                        "잘못된 설정이어도 기본값(5000)으로 물러서야 합니다"));
    }
}
