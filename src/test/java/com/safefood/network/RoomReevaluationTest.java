package com.safefood.network;

import com.safefood.service.GroupRecommendService;
import com.safefood.service.GroupRecommendService.GroupCandidate;
import com.safefood.service.GroupRecommendService.GroupCondition;
import com.safefood.service.GroupRecommendService.GroupResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 방이 <b>언제 후보를 다시 뽑는지</b>를 진짜 소켓 위에서 확인합니다.
 *
 * <p>예전에는 이 판단이 {@code markReady} 안에만 있어 세 가지가 어긋났습니다 — 준비하지 않은
 * 사람이 나가면 영영 시작되지 않았고, 추천이 도는 사이 합류한 사람의 알레르기는 병합에서 빠졌으며,
 * 조건이 그대로인데도 다시 뽑아 투표가 초기화됐습니다. 셋 다 여기서 막습니다.
 *
 * <p>DB는 타지 않습니다 — 추천 조회를 {@link StubRecommendService}로 갈아 끼워, 방의 흐름만 봅니다.
 */
class RoomReevaluationTest {

    /** 이미 표준형이라 {@code InviteCode.normalize}를 통과해도 그대로입니다 (O·I·L 없음). */
    private static final String CODE = "TESTR00M";

    private static final long SETTLE_MS = 500;    // "일어나지 않아야 한다"를 확인할 때 기다리는 시간
    private static final long ARRIVE_MS = 3_000;  // "일어나야 한다"를 기다리는 시간

    private ServerSocket serverSocket;
    private StubRecommendService recommendService;
    private Room room;

    private final List<Peer> peers = new ArrayList<>();
    private final List<Socket> serverSides = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        serverSocket = new ServerSocket(0);   // 빈 포트를 OS가 골라 줍니다
        recommendService = new StubRecommendService();
        room = new Room(CODE, recommendService);
    }

    @AfterEach
    void tearDown() {
        recommendService.releaseFirstCall();   // 붙잡아 둔 조회가 있으면 풀어 줍니다
        for (Peer peer : peers) {
            peer.close();
        }
        for (Socket socket : serverSides) {
            closeQuietly(socket);
        }
        closeQuietly(serverSocket);
    }

    // ── 1. 준비하지 않은 참여자가 나간 경우 ──────────────────────

    @Test
    @DisplayName("준비하지 않은 참여자가 나가면 남은 전원으로 추천이 시작된다")
    void leavingUnreadyMemberStartsRecommendation() throws IOException {
        Peer minsu = join("민수");
        Peer donghyeon = join("동현");

        minsu.send("INFO||3|10000");
        minsu.send("READY");

        assertNull(minsu.await(Message.Type.MERGED, SETTLE_MS),
                "동현이 아직 준비하지 않았으므로 시작되면 안 됩니다");

        donghyeon.close();   // 준비를 누르지 않고 창을 닫은 상황

        assertNotNull(minsu.await(Message.Type.MERGED, ARRIVE_MS),
                "준비하지 않은 사람이 나갔으면 남은 전원으로 추천이 시작되어야 합니다");
    }

    // ── 2. 조건이 그대로인 새 참여자 ────────────────────────────

    @Test
    @DisplayName("조건이 그대로면 새 참여자가 준비해도 후보를 다시 뽑지 않는다")
    void unchangedConditionDoesNotRerun() throws IOException {
        Peer minsu = join("민수");
        minsu.send("INFO|땅콩|3|10000");
        minsu.send("READY");
        assertNotNull(minsu.await(Message.Type.CANDIDATES, ARRIVE_MS), "첫 후보가 나가야 합니다");

        // 알레르기 없음 · 맵기 5 · 예산 제한없음 — 병합 조건이 조금도 빡빡해지지 않습니다
        Peer donghyeon = join("동현");
        donghyeon.send("INFO||5|0");
        donghyeon.send("READY");
        assertNotNull(minsu.awaitReady("동현"), "동현의 READY가 처리되어야 합니다");

        assertNull(minsu.await(Message.Type.MERGED, SETTLE_MS),
                "조건이 그대로면 다시 뽑지 않아야 합니다 — 다시 뽑으면 진행 중이던 투표가 사라집니다");
        assertEquals(1, recommendService.callCount(), "추천 조회는 한 번만 돌아야 합니다");
    }

    // ── 3. 새 참여자의 알레르기가 더해진 경우 ────────────────────

    @Test
    @DisplayName("새 참여자의 알레르기가 더해지면 후보를 다시 뽑는다")
    void addedAllergyForcesRerun() throws IOException {
        Peer minsu = join("민수");
        minsu.send("INFO||5|0");
        minsu.send("READY");
        assertNotNull(minsu.await(Message.Type.CANDIDATES, ARRIVE_MS), "첫 후보가 나가야 합니다");

        Peer donghyeon = join("동현");
        donghyeon.send("INFO|새우|5|0");
        donghyeon.send("READY");

        Message merged = minsu.await(Message.Type.MERGED, ARRIVE_MS);
        assertNotNull(merged, "알레르기가 늘었으면 반드시 다시 뽑아야 합니다");
        assertTrue(merged.part(0).contains("새우"), "병합 조건에 새 알레르기가 들어가야 합니다: " + merged);

        // MERGED 는 조회 스레드를 띄우기 '전에' 나가므로, 조회 횟수는 후보가 도착한 뒤에 셉니다
        assertNotNull(minsu.await(Message.Type.CANDIDATES, ARRIVE_MS), "다시 뽑은 후보가 나가야 합니다");
        assertEquals(2, recommendService.callCount());
        assertTrue(recommendService.lastCondition().allergies().contains("새우"),
                "다시 뽑을 때 새 참여자의 알레르기가 조건에 들어가야 합니다");
    }

    // ── 4. 추천이 도는 사이에 합류한 경우 (가장 위험했던 경로) ────

    @Test
    @DisplayName("추천이 도는 사이 합류한 사람의 알레르기가 빠진 후보는 내보내지 않는다")
    void conditionChangedMidRunDiscardsStaleResult() throws IOException, InterruptedException {
        recommendService.holdFirstCall();

        Peer minsu = join("민수");
        minsu.send("INFO||5|0");
        minsu.send("READY");
        assertTrue(recommendService.awaitFirstCallStarted(ARRIVE_MS), "첫 조회가 시작되어야 합니다");

        // 조회가 붙잡혀 있는 사이에 새우 알레르기가 있는 사람이 들어와 준비를 마칩니다
        Peer donghyeon = join("동현");
        donghyeon.send("INFO|새우|5|0");
        donghyeon.send("READY");
        assertNotNull(minsu.awaitReady("동현"), "동현의 READY가 먼저 처리되어야 합니다");

        recommendService.releaseFirstCall();

        assertNotNull(minsu.await(Message.Type.CANDIDATES, ARRIVE_MS), "후보가 결국 나가야 합니다");
        assertEquals(2, recommendService.callCount(),
                "새우가 빠진 첫 결과는 버리고 다시 뽑아야 합니다");
        assertTrue(recommendService.lastCondition().allergies().contains("새우"),
                "실제로 나간 후보는 새우를 반영한 조건으로 뽑힌 것이어야 합니다");
    }

    // ── 5. 후보를 뽑지 못한 뒤의 재시도 ──────────────────────────

    @Test
    @DisplayName("후보를 뽑지 못하면 RETRY 로 다시 뽑을 수 있다")
    void retryRerunsAfterEmptyResult() throws IOException {
        recommendService.returnNoCandidates();

        Peer minsu = join("민수");
        minsu.send("INFO||5|0");
        minsu.send("READY");
        assertNotNull(minsu.await(Message.Type.CANDIDATES, ARRIVE_MS), "빈 후보라도 결과는 나갑니다");
        assertEquals(1, recommendService.callCount());

        minsu.send("RETRY");

        assertNotNull(minsu.await(Message.Type.CANDIDATES, ARRIVE_MS), "재시도 결과가 나가야 합니다");
        assertEquals(2, recommendService.callCount(), "RETRY 는 조회를 한 번 더 돌려야 합니다");
    }

    // ── 거들이 ──────────────────────────────────────────────────

    /** 접속 → JOIN 까지 마친 참여자 하나를 만듭니다. */
    private Peer join(String name) throws IOException {
        Socket clientSide = new Socket("localhost", serverSocket.getLocalPort());
        Socket serverSide = serverSocket.accept();
        serverSides.add(serverSide);

        Thread handler = new Thread(new ClientHandler(serverSide, room), "test-handler-" + name);
        handler.setDaemon(true);
        handler.start();

        Peer peer = new Peer(clientSide);
        peers.add(peer);
        peer.send("JOIN|" + CODE + "|" + name);

        // JOIN 은 참여자별 스레드가 처리하므로, 확인을 기다리지 않으면 다음 줄이 먼저 도착할 수
        // 있습니다 — 아직 명단에 없는 사람을 빼고 '전원 준비'가 성립해 버립니다
        assertNotNull(peer.await(Message.Type.JOINED,
                        message -> name.equals(message.part(0)), ARRIVE_MS),
                name + " 의 입장이 확인되어야 합니다");
        return peer;
    }

    private static void closeQuietly(java.io.Closeable target) {
        try {
            target.close();
        } catch (IOException ignored) {
        }
    }

    /** 시험용 참여자 — 줄을 보내고, 원하는 메시지가 올 때까지 읽습니다. */
    private static final class Peer implements AutoCloseable {

        private final Socket socket;
        private final BufferedReader in;
        private final BufferedWriter out;

        Peer(Socket socket) throws IOException {
            this.socket = socket;
            this.in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            this.out = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        }

        void send(String line) throws IOException {
            out.write(line);
            out.newLine();
            out.flush();
        }

        /** 해당 타입이 올 때까지 읽습니다. 시간 안에 오지 않으면 null — 다른 타입은 흘려보냅니다. */
        Message await(Message.Type wanted, long millis) throws IOException {
            return await(wanted, message -> true, millis);
        }

        Message awaitReady(String who) throws IOException {
            return await(Message.Type.READY, message -> who.equals(message.part(0)), ARRIVE_MS);
        }

        Message await(Message.Type wanted, Predicate<Message> matching, long millis)
                throws IOException {
            long deadline = System.nanoTime() + millis * 1_000_000L;
            while (true) {
                long leftMillis = (deadline - System.nanoTime()) / 1_000_000L;
                if (leftMillis <= 0) {
                    return null;
                }
                socket.setSoTimeout((int) Math.max(1, leftMillis));
                String line;
                try {
                    line = in.readLine();
                } catch (SocketTimeoutException expired) {
                    return null;
                }
                if (line == null) {
                    return null;   // 상대가 끊었습니다
                }
                Message message = Message.parse(line);
                if (message != null && message.type() == wanted && matching.test(message)) {
                    return message;
                }
            }
        }

        @Override
        public void close() {
            closeQuietly(socket);
        }
    }

    /**
     * DB 대신 쓰는 추천 조회 — 몇 번 어떤 조건으로 불렸는지 기록하고,
     * 필요하면 첫 조회를 붙잡아 '도는 중' 상태를 만들어 줍니다.
     */
    private static final class StubRecommendService extends GroupRecommendService {

        private final List<GroupCondition> conditions =
                Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger calls = new AtomicInteger();

        private volatile CountDownLatch firstCallGate;
        private final CountDownLatch firstCallStarted = new CountDownLatch(1);
        private volatile boolean noCandidates;

        @Override
        public GroupResult recommend(GroupCondition condition, int limit) {
            int callNo = calls.incrementAndGet();
            conditions.add(condition);
            if (callNo == 1) {
                firstCallStarted.countDown();
                CountDownLatch gate = firstCallGate;
                if (gate != null) {
                    try {
                        gate.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            if (noCandidates) {
                return new GroupResult(List.of(), 1, 1, false);
            }
            return new GroupResult(
                    List.of(new GroupCandidate(1, "김치찌개", "할머니손맛", 100, "시험용 후보")),
                    1, 0, false);
        }

        void returnNoCandidates() {
            noCandidates = true;
        }

        void holdFirstCall() {
            firstCallGate = new CountDownLatch(1);
        }

        boolean awaitFirstCallStarted(long millis) throws InterruptedException {
            return firstCallStarted.await(millis, TimeUnit.MILLISECONDS);
        }

        void releaseFirstCall() {
            CountDownLatch gate = firstCallGate;
            if (gate != null) {
                gate.countDown();
            }
        }

        int callCount() {
            return calls.get();
        }

        GroupCondition lastCondition() {
            synchronized (conditions) {
                return conditions.isEmpty() ? null : conditions.get(conditions.size() - 1);
            }
        }
    }
}
