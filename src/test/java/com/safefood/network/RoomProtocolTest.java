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
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 방 규칙의 나머지 절반 — {@link RoomReevaluationTest} 가 '언제 다시 뽑는지'를 본다면,
 * 여기서는 입장 거절·전문 순서·투표·확정·정리의 <b>모든 분기</b>를 실제 소켓 위에서 봅니다.
 *
 * <p>거절당한 사람은 연결이 끊겨야 하고(EOF), 늦게 온 사람은 지나간 전문을 순서대로
 * 돌려받아야 하며, 동점이면 앞 번호가 이겨야 합니다 — 전부 코드를 읽고 정한 기대값입니다.
 *
 * <p>DB는 타지 않습니다. 추천 조회는 스텁이고, 참여자 전원이 게스트(userId 0)라
 * 결과 확정 때도 기록 저장 스레드가 뜨지 않습니다.
 */
class RoomProtocolTest {

    /** {@code InviteCode.normalize} 를 통과해도 그대로인 표준형 코드 (O·I·L 없음). */
    private static final String CODE = "TESTR00M99";

    private static final long SETTLE_MS = 400;    // "일어나지 않아야 한다"를 기다리는 시간
    private static final long ARRIVE_MS = 3_000;  // "일어나야 한다"를 기다리는 시간

    private ServerSocket serverSocket;
    private StubRecommend recommendService;
    private Room room;

    private final List<Peer> peers = new ArrayList<>();
    private final List<Socket> serverSides = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        serverSocket = new ServerSocket(0);
        recommendService = new StubRecommend();
        room = new Room(CODE, recommendService);
    }

    @AfterEach
    void tearDown() {
        for (Peer peer : peers) {
            peer.close();
        }
        for (Socket socket : serverSides) {
            closeQuietly(socket);
        }
        closeQuietly(serverSocket);
    }

    // ── 입장 거절 분기 ─────────────────────────────────────────

    @Test
    @DisplayName("틀린 초대 코드 — 안내 후 연결을 끊는다")
    void wrongCodeIsRejectedAndDisconnected() throws IOException {
        Peer peer = connect();
        peer.send("JOIN|WR0NGC0DE9|민수");

        Message error = peer.await(Message.Type.ERROR, ARRIVE_MS);
        assertNotNull(error);
        assertTrue(error.part(0).contains("존재하지 않는 초대 코드"), error.part(0));
        assertTrue(peer.awaitEof(ARRIVE_MS), "거절된 연결은 닫혀야 합니다");
    }

    @Test
    @DisplayName("빈 이름 — '이름을 입력해 주세요'")
    void blankNameIsRejected() throws IOException {
        Peer peer = connect();
        peer.send("JOIN|" + CODE + "|   ");

        Message error = peer.await(Message.Type.ERROR, ARRIVE_MS);
        assertNotNull(error);
        assertTrue(error.part(0).contains("이름을 입력"), error.part(0));
    }

    @Test
    @DisplayName("겹치는 이름은 거절하고, 먼저 온 사람은 그대로 남는다")
    void duplicateNameIsRejected() throws IOException {
        Peer minsu = join("민수");

        Peer imposter = connect();
        imposter.send("JOIN|" + CODE + "|민수");
        Message error = imposter.await(Message.Type.ERROR, ARRIVE_MS);
        assertNotNull(error);
        assertTrue(error.part(0).contains("이미 사용 중인 이름"), error.part(0));

        minsu.send("CHAT|잘 있나요");
        Message chat = minsu.await(Message.Type.CHAT, ARRIVE_MS);
        assertNotNull(chat, "먼저 들어온 사람의 연결은 살아 있어야 합니다");
        assertEquals("민수", chat.part(0));
    }

    @Test
    @DisplayName("종료된 방에는 들어갈 수 없다")
    void closedRoomRejectsJoin() throws IOException {
        room.close();

        Peer late = connect();
        late.send("JOIN|" + CODE + "|민수");

        Message error = late.await(Message.Type.ERROR, ARRIVE_MS);
        assertNotNull(error);
        assertTrue(error.part(0).contains("이미 종료된 방"), error.part(0));
    }

    @Test
    @DisplayName("JOIN 전에 다른 전문을 보내면 안내 후 끊는다")
    void mustJoinBeforeAnythingElse() throws IOException {
        Peer peer = connect();
        peer.send("READY");

        Message error = peer.await(Message.Type.ERROR, ARRIVE_MS);
        assertNotNull(error);
        assertTrue(error.part(0).contains("먼저 JOIN"), error.part(0));
        assertTrue(peer.awaitEof(ARRIVE_MS));
    }

    @Test
    @DisplayName("빈 줄·모르는 전문은 조용히 버리고 연결을 유지한다")
    void garbageBeforeJoinIsIgnored() throws IOException {
        Peer peer = connect();
        peer.send("");
        peer.send("WHAT|is|this");
        peer.send("JOIN|" + CODE + "|민수");

        assertNotNull(peer.await(Message.Type.JOINED,
                message -> "민수".equals(message.part(0)), ARRIVE_MS));
    }

    // ── 늦게 온 사람의 재생 ────────────────────────────────────

    @Test
    @DisplayName("늦게 합류하면 명단→준비→병합→후보→투표→결과 순서로 되돌려받는다")
    void lateJoinerReceivesFullReplayInOrder() throws IOException {
        Peer minsu = join("민수");
        minsu.send("INFO||5|0");
        minsu.send("READY");
        assertNotNull(minsu.await(Message.Type.CANDIDATES, ARRIVE_MS));
        minsu.send("VOTE|1");
        assertNotNull(minsu.await(Message.Type.VOTE_STATUS,
                message -> message.part(0).startsWith("1:1"), ARRIVE_MS));
        assertTrue(room.finishResult());
        assertNotNull(minsu.await(Message.Type.RESULT, ARRIVE_MS));

        // 다 끝난 방에 들어온 사람 — 재생은 입장 확인(JOINED)보다 먼저, 그리고 순서대로
        Peer late = connect();
        late.send("JOIN|" + CODE + "|동현");

        Message roster = late.await(Message.Type.JOINED, ARRIVE_MS);
        assertEquals("민수", roster.part(0), "명단 재생이 가장 먼저");
        assertEquals("민수", late.await(Message.Type.READY, ARRIVE_MS).part(0));
        assertNotNull(late.await(Message.Type.MERGED, ARRIVE_MS));
        assertNotNull(late.await(Message.Type.CANDIDATES, ARRIVE_MS));
        assertNotNull(late.await(Message.Type.VOTE_STATUS, ARRIVE_MS));
        Message result = late.await(Message.Type.RESULT, ARRIVE_MS);
        assertNotNull(result, "확정된 결과까지 되돌려받아야 합니다");
        assertEquals("김치찌개", result.part(0));
        assertNotNull(late.await(Message.Type.JOINED,
                message -> "동현".equals(message.part(0)), ARRIVE_MS), "재생 끝에 자기 입장 확인");
    }

    // ── 투표 분기 ──────────────────────────────────────────────

    @Test
    @DisplayName("후보가 나가기 전의 투표는 조용히 무시한다")
    void voteBeforeCandidatesIsIgnored() throws IOException {
        Peer minsu = join("민수");
        minsu.send("VOTE|1");

        assertNull(minsu.await(Message.Type.VOTE_STATUS, SETTLE_MS));
        assertNull(minsu.await(Message.Type.ERROR, SETTLE_MS));
    }

    @Test
    @DisplayName("없는 후보 번호(9·0·숫자 아님)는 번호를 알려 주며 거절한다")
    void invalidCandidateNumbersAreRejected() throws IOException {
        Peer minsu = readyWithCandidates("민수");

        minsu.send("VOTE|9");
        assertTrue(minsu.await(Message.Type.ERROR, ARRIVE_MS).part(0).contains("9"));

        minsu.send("VOTE|0");
        assertTrue(minsu.await(Message.Type.ERROR, ARRIVE_MS).part(0).contains("0"));

        minsu.send("VOTE|abc");   // 숫자가 아니면 0으로 읽혀 같은 분기에 걸립니다
        assertTrue(minsu.await(Message.Type.ERROR, ARRIVE_MS).part(0).contains("0"));
    }

    @Test
    @DisplayName("다시 투표하면 표가 옮겨진다 — 한 사람 한 표")
    void revoteMovesTheBallot() throws IOException {
        Peer minsu = readyWithCandidates("민수");

        minsu.send("VOTE|2");
        assertEquals("1:0,2:1,3:0",
                minsu.await(Message.Type.VOTE_STATUS, ARRIVE_MS).part(0));

        minsu.send("VOTE|1");
        assertEquals("1:1,2:0,3:0",
                minsu.await(Message.Type.VOTE_STATUS, ARRIVE_MS).part(0),
                "이전 표는 사라지고 새 표만 남아야 합니다");
    }

    // ── 결과 확정 분기 ─────────────────────────────────────────

    @Test
    @DisplayName("후보도 표도 없으면 확정은 실패한다")
    void finishNeedsCandidatesAndVotes() throws IOException {
        assertFalse(room.finishResult(), "후보가 없으면 false");

        Peer minsu = readyWithCandidates("민수");
        assertFalse(room.finishResult(), "표가 하나도 없으면 false");

        minsu.send("VOTE|1");
        assertNotNull(minsu.await(Message.Type.VOTE_STATUS, ARRIVE_MS));
        assertTrue(room.finishResult());
    }

    @Test
    @DisplayName("동점이면 앞 번호가 이기고, 두 번 확정해도 결과는 한 번만 나간다")
    void tieGoesToFirstAndResultIsFinal() throws IOException {
        Peer minsu = readyWithCandidates("민수");
        Peer donghyeon = joinReady("동현", minsu);

        minsu.send("VOTE|1");
        assertNotNull(minsu.await(Message.Type.VOTE_STATUS,
                message -> message.part(0).startsWith("1:1"), ARRIVE_MS));
        donghyeon.send("VOTE|2");
        assertNotNull(minsu.await(Message.Type.VOTE_STATUS,
                message -> "1:1,2:1,3:0".equals(message.part(0)), ARRIVE_MS));

        assertTrue(room.finishResult());
        Message result = minsu.await(Message.Type.RESULT, ARRIVE_MS);
        assertAll(
                () -> assertEquals("김치찌개", result.part(0), "1:1 동점 — 앞 번호가 이깁니다"),
                () -> assertEquals("할머니손맛", result.part(1)),
                () -> assertNotNull(donghyeon.await(Message.Type.RESULT, ARRIVE_MS),
                        "결과는 전원에게 나가야 합니다"));

        assertTrue(room.finishResult(), "이미 확정된 방은 true 를 돌려주기만 한다");
        assertNull(minsu.await(Message.Type.RESULT, SETTLE_MS), "결과가 두 번 나가면 안 됩니다");

        minsu.send("VOTE|3");
        assertNull(minsu.await(Message.Type.VOTE_STATUS, SETTLE_MS), "확정 뒤의 투표는 무시");
    }

    // ── 채팅·퇴장·종료 ─────────────────────────────────────────

    @Test
    @DisplayName("빈 채팅은 버리고, 본문의 | 는 살아서 전원에게 간다")
    void chatSkipsBlankAndKeepsPipes() throws IOException {
        Peer minsu = join("민수");
        Peer donghyeon = join("동현");
        assertNotNull(minsu.await(Message.Type.JOINED,
                message -> "동현".equals(message.part(0)), ARRIVE_MS));

        minsu.send("CHAT|");
        assertNull(donghyeon.await(Message.Type.CHAT, SETTLE_MS), "빈 채팅은 나가지 않는다");

        minsu.send("CHAT|안녕|세상");
        Message chat = donghyeon.await(Message.Type.CHAT, ARRIVE_MS);
        assertAll(
                () -> assertEquals("민수", chat.part(0)),
                () -> assertEquals("안녕|세상", chat.rest(1), "구분자가 든 본문 그대로"));
    }

    @Test
    @DisplayName("EXIT — 곱게 나가면 남은 사람에게 LEFT 가 가고 소켓이 닫힌다")
    void exitLeavesGracefully() throws IOException {
        Peer minsu = join("민수");
        Peer donghyeon = join("동현");
        assertNotNull(minsu.await(Message.Type.JOINED,
                message -> "동현".equals(message.part(0)), ARRIVE_MS));

        donghyeon.send("EXIT");

        Message left = minsu.await(Message.Type.LEFT, ARRIVE_MS);
        assertAll(
                () -> assertEquals("동현", left.part(0)),
                () -> assertEquals("1", left.part(1), "남은 인원수"));
        assertTrue(donghyeon.awaitEof(ARRIVE_MS));
    }

    @Test
    @DisplayName("투표한 사람이 나가면 표가 빠진 집계를 다시 알린다")
    void leaverTakesTheirVoteAlong() throws IOException {
        Peer minsu = readyWithCandidates("민수");
        Peer donghyeon = joinReady("동현", minsu);

        donghyeon.send("VOTE|1");
        assertNotNull(minsu.await(Message.Type.VOTE_STATUS,
                message -> "1:1,2:0,3:0".equals(message.part(0)), ARRIVE_MS));

        donghyeon.close();   // 창을 그냥 닫은 상황

        assertEquals("동현", minsu.await(Message.Type.LEFT, ARRIVE_MS).part(0));
        assertNotNull(minsu.await(Message.Type.VOTE_STATUS,
                        message -> "1:0,2:0,3:0".equals(message.part(0)), ARRIVE_MS),
                "떠난 사람의 표가 집계에서 빠져야 합니다");
    }

    @Test
    @DisplayName("방 종료 — CLOSED 안내 후 전원의 연결을 닫는다")
    void closeAnnouncesAndDisconnectsEveryone() throws IOException {
        Peer minsu = join("민수");

        room.close();

        assertNotNull(minsu.await(Message.Type.CLOSED, ARRIVE_MS));
        assertTrue(minsu.awaitEof(ARRIVE_MS));
    }

    // ── 병합 규칙과 전문 안전성 ─────────────────────────────────

    @Test
    @DisplayName("INFO 정제 — 빈 알레르기 항목은 버리고, 맵기 9는 5로 눌러 담는다")
    void infoValuesAreCleanedAndClamped() throws IOException {
        Peer minsu = join("민수");
        minsu.send("INFO|땅콩, ,새우|9|-5");
        minsu.send("READY");

        Message merged = minsu.await(Message.Type.MERGED, ARRIVE_MS);
        assertAll(
                () -> assertEquals("알레르기 (합집합) — 땅콩 · 새우 제외", merged.part(0)),
                () -> assertEquals("매운맛 (최솟값) — 5단계 이하", merged.part(1)),
                () -> assertEquals("예산 (교집합) — 제한없음", merged.part(2), "음수 예산은 '없음'"));
    }

    @Test
    @DisplayName("INFO 정제 — 맵기 음수는 0으로 올라온다")
    void negativeSpicyClampsToZero() throws IOException {
        Peer minsu = join("민수");
        minsu.send("INFO||-1|0");
        minsu.send("READY");

        assertEquals("매운맛 (최솟값) — 0단계 이하",
                minsu.await(Message.Type.MERGED, ARRIVE_MS).part(1));
    }

    @Test
    @DisplayName("병합 — 맵기는 최솟값, 예산은 적어 낸 사람들 중 최솟값")
    void mergeTakesMinimumSpicyAndBudget() throws IOException {
        Peer minsu = join("민수");
        Peer donghyeon = join("동현");
        assertNotNull(minsu.await(Message.Type.JOINED,
                message -> "동현".equals(message.part(0)), ARRIVE_MS));

        minsu.send("INFO||3|15000");
        donghyeon.send("INFO||1|12000");
        minsu.send("READY");
        donghyeon.send("READY");

        Message merged = minsu.await(Message.Type.MERGED, ARRIVE_MS);
        assertAll(
                () -> assertEquals("매운맛 (최솟값) — 1단계 이하", merged.part(1)),
                () -> assertEquals("예산 (교집합) — 12,000원 이하", merged.part(2)));
    }

    @Test
    @DisplayName("메뉴·가게 이름의 구분자(|·쉼표·괄호)는 공백으로 눌러 전문을 지킨다")
    void protocolUnsafeNamesAreSanitized() throws IOException {
        recommendService.candidates = List.of(
                new GroupCandidate(1, "김치|찌개(얼큰,진함)", "가게(본점)", 100, "시험용"),
                new GroupCandidate(2, "비빔밥", "전주집", 90, "시험용"));

        Peer minsu = join("민수");
        minsu.send("INFO||5|0");
        minsu.send("READY");

        Message candidates = minsu.await(Message.Type.CANDIDATES, ARRIVE_MS);
        assertAll(
                () -> assertEquals(1, candidates.body().size(),
                        "이름의 | 가 살아 있으면 전문이 여러 칸으로 쪼개집니다"),
                () -> assertEquals("1.김치 찌개 얼큰 진함(가게 본점),2.비빔밥(전주집)",
                        candidates.part(0)));
    }

    // ── 후보를 못 뽑은 세 가지 사유 ─────────────────────────────

    @Test
    @DisplayName("조회 실패 — 빈 후보와 함께 DB 확인 안내가 나간다")
    void queryFailureSendsNotice() throws IOException {
        recommendService.mode = StubMode.FAIL;
        Peer minsu = join("민수");
        minsu.send("INFO||5|0");
        minsu.send("READY");

        assertEquals("", minsu.await(Message.Type.CANDIDATES, ARRIVE_MS).part(0));
        assertTrue(minsu.await(Message.Type.ERROR, ARRIVE_MS).part(0).contains("불러오지 못했"));
    }

    @Test
    @DisplayName("메뉴 데이터 없음 — '먼저 넣어 주세요' 안내")
    void noMenuDataSendsNotice() throws IOException {
        recommendService.mode = StubMode.NO_DATA;
        Peer minsu = join("민수");
        minsu.send("INFO||5|0");
        minsu.send("READY");

        assertNotNull(minsu.await(Message.Type.CANDIDATES, ARRIVE_MS));
        assertTrue(minsu.await(Message.Type.ERROR, ARRIVE_MS).part(0).contains("등록된 메뉴가 없어"));
    }

    @Test
    @DisplayName("전원 알레르기로 전부 차단 — 알레르겐과 살펴본 메뉴 수를 알려 준다")
    void allBlockedByAllergySendsNotice() throws IOException {
        recommendService.mode = StubMode.ALL_BLOCKED;
        Peer minsu = join("민수");
        minsu.send("INFO|땅콩|5|0");
        minsu.send("READY");

        assertNotNull(minsu.await(Message.Type.CANDIDATES, ARRIVE_MS));
        Message notice = minsu.await(Message.Type.ERROR, ARRIVE_MS);
        assertAll(
                () -> assertTrue(notice.part(0).contains("전원 알레르기"), notice.part(0)),
                () -> assertTrue(notice.part(0).contains("땅콩"), notice.part(0)),
                () -> assertTrue(notice.part(0).contains("7개"), "살펴본 메뉴 수: " + notice.part(0)));
    }

    // ── 재시도 분기 ────────────────────────────────────────────

    @Test
    @DisplayName("후보가 이미 있으면 RETRY 는 무시된다 — 투표를 지키기 위해")
    void retryIsIgnoredWhileCandidatesExist() throws IOException {
        Peer minsu = readyWithCandidates("민수");

        minsu.send("RETRY");

        assertNull(minsu.await(Message.Type.MERGED, SETTLE_MS));
        assertEquals(1, recommendService.callCount(), "조회가 다시 돌면 안 됩니다");
    }

    @Test
    @DisplayName("전원이 준비하기 전의 RETRY 는 이유를 알려 준다")
    void retryBeforeEveryoneReadyExplains() throws IOException {
        recommendService.mode = StubMode.NO_DATA;
        Peer minsu = join("민수");
        Peer donghyeon = join("동현");
        assertNotNull(minsu.await(Message.Type.JOINED,
                message -> "동현".equals(message.part(0)), ARRIVE_MS));

        minsu.send("INFO||5|0");
        minsu.send("READY");
        minsu.send("RETRY");   // 동현이 아직 준비 전

        assertTrue(minsu.await(Message.Type.ERROR, ARRIVE_MS).part(0)
                .contains("준비를 마치지 않은"), "이유 없는 침묵이면 안 됩니다");
    }

    // ── 리롤 분기 ──────────────────────────────────────────────

    @Test
    @DisplayName("리롤 — 이미 본 메뉴를 빼고 다시 부르고, 표는 초기화한다")
    void rerollExcludesShownMenusAndResetsVotes() throws IOException {
        recommendService.candidates = sixMenus();
        Peer minsu = readyWithCandidates("민수");   // 앞 3개(1·2·3)가 나갑니다

        minsu.send("VOTE|1");
        assertNotNull(minsu.await(Message.Type.VOTE_STATUS,
                message -> message.part(0).startsWith("1:1"), ARRIVE_MS));

        minsu.send("REROLL");

        Message next = minsu.await(Message.Type.CANDIDATES, ARRIVE_MS);
        assertNotNull(next, "새 후보가 나가야 합니다");
        Message tally = minsu.await(Message.Type.VOTE_STATUS, ARRIVE_MS);
        assertNotNull(tally, "새 후보에는 빈 집계가 따라와야 합니다");
        assertAll(
                () -> assertEquals("1.제육볶음(고기집),2.돈까스(까스집),3.우동(면집)", next.part(0),
                        "이미 본 3개가 아니라 그다음 3개여야 합니다"),
                () -> assertEquals(Set.of(1, 2, 3), recommendService.lastExcluded(),
                        "이미 본 메뉴를 빼고 불러야 합니다"),
                () -> assertEquals("1:0,2:0,3:0", tally.part(0),
                        "후보가 바뀌었으니 지난 표는 남으면 안 됩니다"));
    }

    @Test
    @DisplayName("더 보여 줄 메뉴가 없으면 리롤은 지금 후보와 표를 지키고 이유만 알린다")
    void rerollWithNothingLeftKeepsCandidates() throws IOException {
        Peer minsu = readyWithCandidates("민수");   // 기본 풀은 3개 — 첫 발표로 다 나갑니다

        minsu.send("VOTE|2");
        assertNotNull(minsu.await(Message.Type.VOTE_STATUS,
                message -> message.part(0).contains("2:1"), ARRIVE_MS));

        minsu.send("REROLL");

        assertNotNull(minsu.await(Message.Type.ERROR,
                        message -> message.part(0).contains("모두 보여"), ARRIVE_MS),
                "왜 그대로인지 알려 줘야 합니다");
        assertNull(minsu.await(Message.Type.CANDIDATES, SETTLE_MS),
                "멀쩡한 후보를 빈 목록으로 갈아 끼우면 안 됩니다");
    }

    @Test
    @DisplayName("조회가 실패해도 리롤은 있던 후보를 지우지 않는다")
    void rerollFailureKeepsCandidates() throws IOException {
        recommendService.candidates = sixMenus();
        Peer minsu = readyWithCandidates("민수");

        recommendService.mode = StubMode.FAIL;
        minsu.send("REROLL");

        assertNotNull(minsu.await(Message.Type.ERROR,
                        message -> message.part(0).contains("불러오지 못해"), ARRIVE_MS),
                "실패를 알려 줘야 합니다");
        assertNull(minsu.await(Message.Type.CANDIDATES, SETTLE_MS),
                "조회가 실패했다고 있던 후보를 날리면 안 됩니다");
    }

    @Test
    @DisplayName("확정된 뒤의 리롤은 무시된다")
    void rerollAfterResultIsIgnored() throws IOException {
        recommendService.candidates = sixMenus();
        Peer minsu = readyWithCandidates("민수");
        minsu.send("VOTE|1");
        assertNotNull(minsu.await(Message.Type.VOTE_STATUS,
                message -> message.part(0).startsWith("1:1"), ARRIVE_MS));
        assertTrue(room.finishResult());
        assertNotNull(minsu.await(Message.Type.RESULT, ARRIVE_MS));

        minsu.send("REROLL");

        assertNull(minsu.await(Message.Type.CANDIDATES, SETTLE_MS));
        assertEquals(1, recommendService.callCount(), "확정 뒤에는 조회가 돌면 안 됩니다");
    }

    @Test
    @DisplayName("후보를 받기 전의 리롤은 이유를 알려 준다")
    void rerollBeforeAnyCandidateExplains() throws IOException {
        recommendService.mode = StubMode.NO_DATA;
        Peer minsu = join("민수");
        minsu.send("INFO||5|0");
        minsu.send("READY");
        assertNotNull(minsu.await(Message.Type.CANDIDATES, ARRIVE_MS), "빈 후보 발표는 나갑니다");

        minsu.send("REROLL");

        assertNotNull(minsu.await(Message.Type.ERROR,
                        message -> message.part(0).contains("아직 받은 후보가 없어요"), ARRIVE_MS),
                "조용히 무시하면 안 됩니다");
    }

    // ── 거들이 ─────────────────────────────────────────────────

    /** 리롤이 갈아 끼울 여지가 있는 풀 — 앞 3개가 나가고 뒤 3개가 남습니다. */
    private static List<GroupCandidate> sixMenus() {
        return List.of(
                new GroupCandidate(1, "김치찌개", "할머니손맛", 100, "시험용"),
                new GroupCandidate(2, "비빔밥", "전주집", 90, "시험용"),
                new GroupCandidate(3, "라면", "분식집", 80, "시험용"),
                new GroupCandidate(4, "제육볶음", "고기집", 70, "시험용"),
                new GroupCandidate(5, "돈까스", "까스집", 60, "시험용"),
                new GroupCandidate(6, "우동", "면집", 50, "시험용"));
    }

    /** 접속만 한 참여자 — JOIN 은 각 시험이 직접 보냅니다. */
    private Peer connect() throws IOException {
        Socket clientSide = new Socket("localhost", serverSocket.getLocalPort());
        Socket serverSide = serverSocket.accept();
        serverSides.add(serverSide);

        Thread handler = new Thread(new ClientHandler(serverSide, room, new JoinThrottle()),
                "test-handler-" + serverSides.size());
        handler.setDaemon(true);
        handler.start();

        Peer peer = new Peer(clientSide);
        peers.add(peer);
        return peer;
    }

    /** 접속 → JOIN → 입장 확인까지 마친 참여자. */
    private Peer join(String name) throws IOException {
        Peer peer = connect();
        peer.send("JOIN|" + CODE + "|" + name);
        assertNotNull(peer.await(Message.Type.JOINED,
                        message -> name.equals(message.part(0)), ARRIVE_MS),
                name + " 의 입장이 확인되어야 합니다");
        return peer;
    }

    /**
     * 혼자 입장·준비를 마치고 후보까지 받은 참여자.
     *
     * <p>후보 발표에는 빈 집계(전부 0표)가 한 장 따라옵니다 — 여기서 미리 읽어 두지 않으면
     * 다음 {@code await(VOTE_STATUS)} 가 투표 결과 대신 이 빈 집계를 집어 듭니다.
     */
    private Peer readyWithCandidates(String name) throws IOException {
        Peer peer = join(name);
        peer.send("INFO||5|0");
        peer.send("READY");
        assertNotNull(peer.await(Message.Type.CANDIDATES, ARRIVE_MS), "후보가 나가야 합니다");
        assertNotNull(peer.await(Message.Type.VOTE_STATUS, ARRIVE_MS), "빈 집계가 따라와야 합니다");
        return peer;
    }

    /**
     * 후보가 이미 나간 방에 조건을 더 빡빡하게 하지 않고 합류·준비합니다 —
     * 다시 뽑기가 돌지 않아 표와 후보가 그대로 남습니다.
     */
    private Peer joinReady(String name, Peer watcher) throws IOException {
        Peer peer = join(name);
        peer.send("INFO||5|0");
        peer.send("READY");
        assertNotNull(watcher.await(Message.Type.READY,
                message -> name.equals(message.part(0)), ARRIVE_MS));
        return peer;
    }

    private static void closeQuietly(java.io.Closeable target) {
        try {
            target.close();
        } catch (IOException ignored) {
        }
    }

    /** 시험용 참여자 — 줄을 보내고, 원하는 전문이 올 때까지 읽습니다. */
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

        Message await(Message.Type wanted, long millis) throws IOException {
            return await(wanted, message -> true, millis);
        }

        /** 해당 전문이 올 때까지 읽습니다. 시간 안에 오지 않으면 null — 다른 전문은 흘려보냅니다. */
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
                    return null;
                }
                Message message = Message.parse(line);
                if (message != null && message.type() == wanted && matching.test(message)) {
                    return message;
                }
            }
        }

        /** 상대가 연결을 닫을 때까지 읽습니다. 시간 안에 닫히면 true. */
        boolean awaitEof(long millis) throws IOException {
            long deadline = System.nanoTime() + millis * 1_000_000L;
            while (true) {
                long leftMillis = (deadline - System.nanoTime()) / 1_000_000L;
                if (leftMillis <= 0) {
                    return false;
                }
                socket.setSoTimeout((int) Math.max(1, leftMillis));
                try {
                    if (in.readLine() == null) {
                        return true;
                    }
                } catch (SocketTimeoutException expired) {
                    return false;
                } catch (IOException reset) {
                    return true;   // 상대가 닫으며 리셋된 경우도 '닫힘'입니다
                }
            }
        }

        @Override
        public void close() {
            closeQuietly(socket);
        }
    }

    private enum StubMode { NORMAL, FAIL, NO_DATA, ALL_BLOCKED }

    /**
     * DB 대신 쓰는 추천 조회 — 네 가지 결과 모양을 마음대로 골라 돌려줍니다.
     *
     * <p>제외 목록을 실제로 지킵니다. 리롤이 이미 본 메뉴를 정말 빼고 부르는지는
     * 그 약속을 지키는 상대가 있어야만 확인할 수 있습니다.
     */
    private static final class StubRecommend extends GroupRecommendService {

        private final AtomicInteger calls = new AtomicInteger();
        volatile StubMode mode = StubMode.NORMAL;
        volatile Set<Integer> excluded = Set.of();
        volatile List<GroupCandidate> candidates = List.of(
                new GroupCandidate(1, "김치찌개", "할머니손맛", 100, "시험용"),
                new GroupCandidate(2, "비빔밥", "전주집", 90, "시험용"),
                new GroupCandidate(3, "라면", "분식집", 80, "시험용"));

        @Override
        public GroupResult recommend(GroupCondition condition, int limit,
                                     Set<Integer> excludedMenuIds) {
            calls.incrementAndGet();
            excluded = Set.copyOf(excludedMenuIds);
            return switch (mode) {
                case FAIL -> throw new RuntimeException("시험용 조회 실패");
                case NO_DATA -> new GroupResult(List.of(), 0, 0, false);
                case ALL_BLOCKED -> new GroupResult(List.of(), 7, 7, false);
                case NORMAL -> {
                    List<GroupCandidate> left = new ArrayList<>();
                    int remaining = 0;
                    for (GroupCandidate candidate : candidates) {
                        if (excludedMenuIds.contains(candidate.menuId())) {
                            continue;
                        }
                        remaining++;
                        if (left.size() < limit) {
                            left.add(candidate);
                        }
                    }
                    yield new GroupResult(List.copyOf(left), remaining, 0, false);
                }
            };
        }

        int callCount() {
            return calls.get();
        }

        Set<Integer> lastExcluded() {
            return excluded;
        }
    }
}
