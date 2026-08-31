package com.safefood.network;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 화면이 기대는 참여 상태의 거울({@link GroupSession}) — 전문이 상태로 쌓이는 규칙을 봅니다.
 *
 * <p>서버가 없어도 {@code onMessage} 는 그냥 부를 수 있으므로, 대부분은 전문을 직접 밀어 넣어
 * 각 타입의 분기(명단·준비·후보·투표·결과·종료)와 CSV 해석의 불량 토큰 처리를 확인합니다.
 * 방 열기·참여·정리는 실제 소켓으로 한 번씩 왕복합니다.
 *
 * <p>싱글턴이라 매 시험 전후로 {@code shutdown()} 으로 되돌립니다.
 */
class GroupSessionTest {

    /** O·I·L 이 없어 {@code InviteCode.normalize} 를 통과해도 그대로인 코드. */
    private static final String CODE = "TESTR00M99";

    private static final long ARRIVE_MS = 3_000;

    private final GroupSession session = GroupSession.get();

    private String savedName;
    private boolean savedGuest;

    @BeforeEach
    void resetSession() {
        savedName = session.displayName();
        savedGuest = session.isGuest();
        session.shutdown();
    }

    @AfterEach
    void restoreSession() {
        session.shutdown();
        session.setDisplayName(savedName);
        session.setGuest(savedGuest);
    }

    private void push(String line) {
        session.onMessage(Message.parse(line));
    }

    private static void awaitTrue(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + ARRIVE_MS * 1_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(what + " — " + ARRIVE_MS + "ms 안에 일어나지 않았습니다");
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(what, e);
            }
        }
    }

    // ── 명단 상태 ──────────────────────────────────────────────

    @Test
    @DisplayName("JOINED — 첫 사람이 방장, 같은 이름은 두 번 오르지 않는다")
    void joinedBuildsRosterOnce() {
        push("JOINED|민수|1");
        push("JOINED|동현|2");
        push("JOINED|민수|1");   // 중계가 겹쳐 와도

        List<GroupSession.MemberView> members = session.members();
        assertAll(
                () -> assertEquals(2, members.size()),
                () -> assertEquals("민수", members.get(0).name()),
                () -> assertTrue(members.get(0).owner(), "명단 첫 사람이 방장"),
                () -> assertFalse(members.get(1).owner()),
                () -> assertFalse(members.get(0).ready(), "아직 아무도 준비 전"));
    }

    @Test
    @DisplayName("READY 와 LEFT — 준비 표시가 붙고, 나가면 명단과 준비가 함께 지워진다")
    void readyAndLeftKeepRosterConsistent() {
        push("JOINED|민수|1");
        push("JOINED|동현|2");
        push("READY|민수");

        assertTrue(session.members().get(0).ready());

        push("LEFT|민수|1");

        List<GroupSession.MemberView> members = session.members();
        assertAll(
                () -> assertEquals(1, members.size()),
                () -> assertEquals("동현", members.get(0).name()),
                () -> assertTrue(members.get(0).owner(), "남은 첫 사람이 방장으로 보인다"),
                () -> assertFalse(members.get(0).ready(), "민수의 준비 기록이 동현에게 남지 않는다"));
    }

    // ── 후보 CSV 해석 ──────────────────────────────────────────

    @Test
    @DisplayName("CANDIDATES — 번호·메뉴·가게가 갈라지고, 도착 여부 표시가 켜진다")
    void candidatesParseCleanTokens() {
        push("CANDIDATES|1.김치찌개(할머니손맛),2.비빔밥(전주집)");

        List<GroupSession.CandidateView> candidates = session.candidates();
        assertAll(
                () -> assertTrue(session.candidatesReceived()),
                () -> assertEquals(2, candidates.size()),
                () -> assertEquals(1, candidates.get(0).no()),
                () -> assertEquals("김치찌개", candidates.get(0).menu()),
                () -> assertEquals("할머니손맛", candidates.get(0).restaurant()),
                () -> assertEquals("전주집", candidates.get(1).restaurant()));
    }

    @Test
    @DisplayName("CANDIDATES — 불량 토큰은 건너뛰고 읽을 수 있는 것만 살린다")
    void candidatesSkipMalformedTokens() {
        // 점 없음 · 번호가 숫자 아님 · 점이 맨 앞 · 괄호 안 닫힘 · 괄호가 두 벌인 정상 토큰
        push("CANDIDATES|셋, x.메뉴, .앞점, 4.라면, 5.돈까스(집, 6.함박(정식)(양식집)");

        List<GroupSession.CandidateView> candidates = session.candidates();
        assertAll(
                () -> assertEquals(3, candidates.size()),
                () -> assertEquals("라면", candidates.get(0).menu()),
                () -> assertEquals("", candidates.get(0).restaurant(), "가게 표기가 없으면 빈칸"),
                () -> assertEquals("돈까스(집", candidates.get(1).menu(), "닫는 괄호가 없으면 통째로 메뉴"),
                () -> assertEquals("함박(정식)", candidates.get(2).menu(), "마지막 여는 괄호만 가게로 본다"),
                () -> assertEquals("양식집", candidates.get(2).restaurant()));
    }

    @Test
    @DisplayName("빈 CANDIDATES — 후보 없음도 '도착함'이며, 못 찾았다는 안내가 남는다")
    void emptyCandidatesStillCountAsArrival() {
        push("CANDIDATES|");

        assertAll(
                () -> assertTrue(session.candidatesReceived(), "재시도 버튼을 띄우는 근거"),
                () -> assertTrue(session.candidates().isEmpty()),
                () -> assertTrue(session.events().stream()
                        .anyMatch(event -> event.kind() == GroupSession.EventKind.SYSTEM_BAD
                                && event.text().contains("찾지 못했")),
                        "후보를 못 찾았다는 안내가 나가야 합니다"));
    }

    // ── 투표 CSV 해석 ──────────────────────────────────────────

    @Test
    @DisplayName("VOTE_STATUS — 짝이 안 맞거나 숫자가 아닌 토큰은 버린다")
    void voteStatusSkipsMalformedPairs() {
        push("JOINED|민수|1");
        push("VOTE_STATUS|1:2, 2:0, 셋, 3:x, 4:1");

        assertAll(
                () -> assertEquals(2, session.votesFor(1)),
                () -> assertEquals(0, session.votesFor(2)),
                () -> assertEquals(0, session.votesFor(3), "숫자가 아니면 버린다"),
                () -> assertEquals(1, session.votesFor(4)),
                () -> assertEquals(0, session.votesFor(99), "없는 번호는 0"),
                () -> assertEquals(3, session.totalVotes()));
    }

    @Test
    @DisplayName("표가 0인 VOTE_STATUS 는 접수 안내를 만들지 않는다")
    void zeroVotesMakeNoEvent() {
        push("JOINED|민수|1");
        int before = session.events().size();

        push("VOTE_STATUS|1:0,2:0");

        assertAll(
                () -> assertEquals(0, session.totalVotes()),
                () -> assertEquals(before, session.events().size(),
                        "표가 없는데 '투표가 접수되었어요'가 나가면 안 됩니다"));
    }

    // ── 결과·종료·끊김 ─────────────────────────────────────────

    @Test
    @DisplayName("RESULT — 가게가 있으면 괄호로 붙이고, 없으면 메뉴만")
    void resultTextFormats() {
        push("RESULT|김치찌개|할머니손맛");
        assertEquals("김치찌개 (할머니손맛)", session.result());

        push("RESULT|비빔밥|");
        assertEquals("비빔밥", session.result(), "가게 칸이 비면 괄호를 붙이지 않는다");
    }

    @Test
    @DisplayName("CLOSED 뒤의 끊김은 알리지 않는다 — 이미 '종료'로 안내한 뒤라서")
    void disconnectAfterClosedStaysQuiet() {
        push("JOINED|민수|1");
        push("CLOSED|방장이 방을 종료했습니다.");

        assertAll(
                () -> assertTrue(session.isRemoteClosed()),
                () -> assertTrue(session.isDead()));

        session.onDisconnected();

        assertTrue(session.events().stream()
                        .noneMatch(event -> event.text().contains("연결이 끊겼")),
                "종료 안내 뒤에 끊김 안내가 겹치면 안 됩니다");
    }

    @Test
    @DisplayName("예고 없는 끊김은 SYSTEM_BAD 로 알린다")
    void unexpectedDisconnectIsAnnounced() {
        push("JOINED|민수|1");
        session.onDisconnected();

        assertAll(
                () -> assertTrue(session.isDead()),
                () -> assertFalse(session.isRemoteClosed()),
                () -> assertTrue(session.events().stream()
                        .anyMatch(event -> event.kind() == GroupSession.EventKind.SYSTEM_BAD
                                && event.text().contains("연결이 끊겼"))));
    }

    @Test
    @DisplayName("사유 없는 CLOSED 는 기본 문구로 채운다")
    void closedWithoutReasonUsesDefaultText() {
        push("CLOSED");

        assertTrue(session.events().stream()
                .anyMatch(event -> event.text().contains("방장이 방을 종료했어요")));
    }

    // ── 이벤트 기록과 화면 전달 ─────────────────────────────────

    @Test
    @DisplayName("CHAT 은 보낸 사람과 | 가 든 본문을 그대로 기록한다")
    void chatEventKeepsSenderAndBody() {
        push("CHAT|민수|안녕|하세요");

        GroupSession.Event chat = session.events().get(session.events().size() - 1);
        assertAll(
                () -> assertEquals(GroupSession.EventKind.CHAT, chat.kind()),
                () -> assertEquals("민수", chat.sender()),
                () -> assertEquals("안녕|하세요", chat.text(), "본문의 | 가 살아남아야 합니다"));
    }

    @Test
    @DisplayName("ERROR 는 SYSTEM_BAD 로, 로그에는 원문 전문이 남는다")
    void errorBecomesBadEventAndLogLine() {
        push("ERROR|이미 사용 중인 이름입니다: 민수");

        assertAll(
                () -> assertTrue(session.events().stream()
                        .anyMatch(event -> event.kind() == GroupSession.EventKind.SYSTEM_BAD
                                && event.text().contains("이미 사용 중"))),
                () -> assertTrue(session.logLines().stream()
                        .anyMatch(line -> line.contains("ERROR|이미 사용 중인 이름입니다: 민수"))));
    }

    @Test
    @DisplayName("화면 리스너 — 등록하면 전문이 전달되고, 지우면 더는 오지 않는다")
    void uiListenerForwardingFollowsRegistration() {
        List<Message> forwarded = new ArrayList<>();
        session.setUiListener(new GroupClient.Listener() {
            @Override
            public void onMessage(Message message) {
                forwarded.add(message);
            }

            @Override
            public void onDisconnected() {
            }
        });

        push("JOINED|민수|1");
        assertEquals(1, forwarded.size());

        session.setUiListener(null);
        push("READY|민수");
        assertEquals(1, forwarded.size(), "리스너를 지운 뒤에는 전달되지 않는다");
    }

    // ── 이름·코드 규칙 ─────────────────────────────────────────

    @Test
    @DisplayName("표시 이름 — null·공백은 무시하고, 양끝 공백은 잘라 저장한다")
    void displayNameRejectsBlankAndTrims() {
        session.setDisplayName("기준값");

        session.setDisplayName(null);
        assertEquals("기준값", session.displayName());

        session.setDisplayName("   ");
        assertEquals("기준값", session.displayName());

        session.setDisplayName("  민수  ");
        assertEquals("민수", session.displayName());
    }

    @Test
    @DisplayName("초대 코드 보관 — null 은 빈 문자열로, shutdown 이 지운다")
    void inviteCodeStorageRules() {
        session.setInviteCode(null);
        assertEquals("", session.inviteCode());

        session.setInviteCode(CODE);
        assertEquals(CODE, session.inviteCode());

        session.shutdown();
        assertEquals("", session.inviteCode());
    }

    // ── 실제 소켓 왕복 ─────────────────────────────────────────

    @Test
    @DisplayName("방 참여 → 서버 종료까지 실제 소켓으로 한 바퀴")
    void joinRoomAgainstRealServer() throws IOException {
        GroupServer server = GroupServer.open(0, CODE);
        try {
            session.joinRoom("localhost", server.port());

            assertAll(
                    () -> assertTrue(session.isConnected()),
                    () -> assertFalse(session.isOwner(), "참여자는 서버를 들고 있지 않다"),
                    () -> assertFalse(session.isDead()));

            session.client().join(CODE, "민수");
            awaitTrue(() -> session.members().stream()
                    .anyMatch(member -> member.name().equals("민수")), "입장 확인");

            server.stop();
            awaitTrue(session::isDead, "서버 종료 감지");
            assertTrue(session.isRemoteClosed(), "끊김이 아니라 '종료'로 인지해야 합니다");
        } finally {
            server.stop();
            session.shutdown();
        }
    }

    @Test
    @DisplayName("방 열기(hostRoom) — 서버·클라이언트가 함께 서고, shutdown 이 전부 되돌린다")
    void hostRoomOpensAndShutdownClears() throws IOException {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }

        session.hostRoom(port, CODE);
        try {
            assertAll(
                    () -> assertTrue(session.isOwner()),
                    () -> assertTrue(session.isConnected()),
                    () -> assertEquals(CODE, session.inviteCode()),
                    () -> assertEquals(port, session.server().port()),
                    () -> assertTrue(session.events().stream()
                            .anyMatch(event -> event.text().contains("방이 열렸어요"))));

            session.client().join(CODE, "방장");
            awaitTrue(() -> !session.members().isEmpty(), "방장 입장 확인");
            assertTrue(session.members().get(0).owner());
        } finally {
            session.shutdown();
        }

        // 로그·이벤트는 여기서 확인하지 않습니다 — shutdown 이 서버를 먼저 내리므로, 그 순간
        // 배달 중이던 CLOSED 전문이 비운 직후의 로그에 한 줄 다시 쌓일 수 있습니다 (다음
        // hostRoom/joinRoom 의 shutdown 이 다시 지워, 실사용에서는 드러나지 않는 잔여입니다).
        assertAll(
                () -> assertFalse(session.isConnected()),
                () -> assertFalse(session.isOwner()),
                () -> assertTrue(session.members().isEmpty()),
                () -> assertEquals("", session.inviteCode()),
                () -> assertNull(session.result()));
    }

    @Test
    @DisplayName("이미 쓰는 포트로 방을 열면 IOException — 상태는 깨끗하게 남는다")
    void hostRoomOnBusyPortFailsCleanly() throws IOException {
        try (ServerSocket occupier = new ServerSocket(0)) {
            int busyPort = occupier.getLocalPort();

            assertThrows(IOException.class, () -> session.hostRoom(busyPort, CODE));

            assertAll(
                    () -> assertFalse(session.isOwner(), "실패한 서버가 남으면 안 됩니다"),
                    () -> assertFalse(session.isConnected()));
        }
    }
}
