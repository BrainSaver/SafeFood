package com.safefood.network;

import com.safefood.dao.GroupResultDao;
import com.safefood.service.GroupRecommendService;
import com.safefood.service.GroupRecommendService.GroupCandidate;
import com.safefood.service.GroupRecommendService.GroupCondition;
import com.safefood.service.GroupRecommendService.GroupResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 접속자 목록 · 브로드캐스트 · 투표 집계 — 방장 앱 안에만 존재하는 방 상태.
 *
 * <p>여러 {@link ClientHandler} 스레드가 동시에 호출하므로 상태를 건드리는 메서드는
 * 모두 {@code synchronized}로 보호합니다.
 */
public final class Room {

    private static final class Member {
        final String name;
        List<String> allergies = List.of();
        int spicyLevel = 5;   // 병합이 최솟값이라 초기값은 결과에 영향 없는 최대치
        int budgetMax = 0;    // 0 = 제한없음
        int userId;           // 0 = 게스트
        boolean ready;

        Member(String name) {
            this.name = name;
        }
    }

    private record Candidate(int menuId, String menu, String restaurant, int score, String reason) {
    }

    private static final int CANDIDATE_COUNT = 3;

    private final GroupRecommendService recommendService;

    private final String inviteCode;

    // 입장 순서 유지(첫 번째 = 방장). synchronized 밖에서는 절대 만지지 않습니다.
    private final Map<ClientHandler, Member> members = new LinkedHashMap<>();
    private final Map<ClientHandler, Integer> votes = new LinkedHashMap<>();

    private List<String> mergedLines = List.of();
    private List<Candidate> candidates = List.of();   // 번호는 순서 + 1
    private Message resultMessage;   // 확정 후에는 고정 — 재확정·재투표를 막는 기준
    private boolean closed;

    /** 지금 나가 있는 후보를 뽑을 때 쓴 조건. 아직 한 번도 뽑지 않았으면 null. */
    private GroupCondition appliedCondition;

    /** 지금 돌고 있는 조회의 조건. 도는 중이 아니면 null. */
    private GroupCondition runningCondition;

    /** 병합 한 벌 — 조회에 쓸 조건과 화면에 보여 줄 설명 세 줄. */
    private record Merged(GroupCondition condition, List<String> lines) {
    }

    Room(String inviteCode) {
        this(inviteCode, new GroupRecommendService());
    }

    /**
     * 추천 조회를 갈아 끼우는 생성자 — 시험에서 DB 없이 방의 흐름만 확인할 때 씁니다.
     * 실행 중에는 {@link #Room(String)} 만 쓰입니다.
     */
    Room(String inviteCode, GroupRecommendService recommendService) {
        this.inviteCode = inviteCode;
        this.recommendService = recommendService;
    }

    /**
     * JOIN 처리 — 코드 전체를 대조하므로 주소까지 맞아야 통과합니다.
     * 늦게 들어온 참여자에게는 현재 명단·병합 결과·후보·득표를 먼저 재전송해 화면을 맞춥니다.
     *
     * @return 실패하면 ERROR를 보낸 뒤 false — 호출한 쪽에서 연결을 끊습니다.
     */
    synchronized boolean join(ClientHandler handler, String code, String name) {
        if (closed) {
            handler.send(Message.of(Message.Type.ERROR, "이미 종료된 방입니다."));
            return false;
        }
        if (!inviteCode.equals(InviteCode.normalize(code))) {
            handler.send(Message.of(Message.Type.ERROR, "존재하지 않는 초대 코드"));
            return false;
        }
        if (name.isBlank()) {
            handler.send(Message.of(Message.Type.ERROR, "이름을 입력해 주세요."));
            return false;
        }
        if (members.values().stream().anyMatch(member -> member.name.equals(name))) {
            handler.send(Message.of(Message.Type.ERROR, "이미 사용 중인 이름입니다: " + name));
            return false;
        }

        int order = 0;
        for (Member member : members.values()) {
            handler.send(Message.of(Message.Type.JOINED, member.name, String.valueOf(++order)));
            if (member.ready) {
                handler.send(Message.of(Message.Type.READY, member.name));
            }
        }
        if (!mergedLines.isEmpty()) {
            handler.send(Message.of(Message.Type.MERGED, mergedLines));
        }
        if (!candidates.isEmpty()) {
            handler.send(candidatesMessage());
            handler.send(voteStatusMessage());
        }
        if (resultMessage != null) {
            handler.send(resultMessage);
        }

        members.put(handler, new Member(name));
        broadcast(Message.of(Message.Type.JOINED, name, String.valueOf(members.size())));
        return true;
    }

    synchronized void updateInfo(ClientHandler handler, List<String> allergies, int spicyLevel,
                                 int budgetMax, int userId) {
        Member member = members.get(handler);
        if (member == null) {
            return;
        }
        member.allergies = List.copyOf(allergies);
        member.spicyLevel = Math.max(0, Math.min(5, spicyLevel));
        member.budgetMax = Math.max(0, budgetMax);
        member.userId = Math.max(0, userId);
        // 보통은 INFO 다음에 READY 가 바로 오지만, 이미 준비를 마친 사람이 조건만 고쳐 보낼 수도 있습니다
        reevaluate();
    }

    synchronized void markReady(ClientHandler handler) {
        Member member = members.get(handler);
        if (member == null || member.ready) {
            return;
        }
        member.ready = true;
        broadcast(Message.of(Message.Type.READY, member.name));
        reevaluate();
    }

    /**
     * 추천을 (다시) 시작할 때인지 판단합니다 — <b>명단·조건·준비 상태가 바뀌는 모든 자리</b>에서 부릅니다.
     *
     * <p>예전에는 이 판단이 {@link #markReady} 안에만 있었습니다. 그래서 준비 상태가 바뀌지 않는
     * 방식으로 상황이 달라지면 아무도 다시 보지 않았고, 세 가지가 한꺼번에 어긋났습니다:
     * <ul>
     *   <li>준비를 마치지 않은 사람이 <b>나가면</b> 남은 전원이 준비 상태인데도 추천이 영영 시작되지 않음</li>
     *   <li>추천이 <b>도는 사이</b> 합류해 조건을 낸 사람의 알레르기가 병합에서 빠진 채 후보가 나감</li>
     *   <li>후보가 이미 나간 방에 <b>새로 들어온 사람이 준비</b>하면 조건이 그대로인데도 다시 뽑아 투표가 초기화됨</li>
     * </ul>
     *
     * <p>판단을 여기 한 곳으로 모으고, <b>조건이 실제로 빡빡해졌을 때만</b> 다시 뽑습니다.
     */
    private void reevaluate() {
        // 도는 중이면 여기서 판단하지 않습니다 — applyCandidates 가 끝에서 다시 부릅니다
        if (closed || resultMessage != null || runningCondition != null) {
            return;
        }
        if (members.isEmpty() || !members.values().stream().allMatch(m -> m.ready)) {
            return;
        }
        Merged merged = merge();
        if (appliedCondition != null && covers(appliedCondition, merged.condition())) {
            return;   // 조건이 느슨해지기만 했습니다 — 후보도 표도 그대로 둡니다
        }
        startRecommendation(merged);
    }

    /** 병합 규칙 — 알레르기 합집합, 매운맛·예산 최솟값. */
    private Merged merge() {
        Set<String> allergyUnion = new LinkedHashSet<>();
        int spicyMin = 5;
        int budgetMin = 0;
        for (Member member : members.values()) {
            allergyUnion.addAll(member.allergies);
            spicyMin = Math.min(spicyMin, member.spicyLevel);
            if (member.budgetMax > 0) {
                budgetMin = budgetMin == 0 ? member.budgetMax : Math.min(budgetMin, member.budgetMax);
            }
        }
        List<String> lines = List.of(
                "알레르기 (합집합) — " + (allergyUnion.isEmpty() ? "없음"
                        : String.join(" · ", allergyUnion) + " 제외"),
                "매운맛 (최솟값) — " + spicyMin + "단계 이하",
                "예산 (교집합) — " + (budgetMin == 0 ? "제한없음" : String.format("%,d원 이하", budgetMin)));
        return new Merged(new GroupCondition(allergyUnion, spicyMin, budgetMin), lines);
    }

    /**
     * 이미 나간 후보를 그대로 두어도 되는지 — 조건이 <b>느슨해지기만</b> 했으면 다시 뽑지 않습니다.
     *
     * <p>알레르기가 하나라도 늘면 지금 후보가 그 사람에게 안전하다고 말할 수 없으므로 반드시 다시 뽑습니다.
     * 반대로 이것을 단순 비교(<code>equals</code>)로 두면, 알레르기가 있는 사람이 <b>나가서</b> 조건이
     * 느슨해진 경우까지 다시 뽑게 됩니다 — 투표 도중 한 명이 연결을 잃는 것만으로 모두의 표가 사라집니다.
     */
    private static boolean covers(GroupCondition applied, GroupCondition wanted) {
        return applied.allergies().containsAll(wanted.allergies())
                && applied.spicyMax() <= wanted.spicyMax()
                && (wanted.budgetMax() == 0
                    || (applied.budgetMax() != 0 && applied.budgetMax() <= wanted.budgetMax()));
    }

    private void startRecommendation(Merged merged) {
        // 표가 이미 쌓였는데 다시 뽑는 경우 — 왜 초기화되는지 알리지 않으면 오작동으로 보입니다
        boolean discardsVotes = !votes.isEmpty();

        mergedLines = merged.lines();
        broadcast(Message.of(Message.Type.MERGED, mergedLines));
        if (discardsVotes) {
            broadcast(Message.of(Message.Type.ERROR,
                    "참여자의 조건이 바뀌어 후보를 다시 뽑습니다 — 지금까지의 투표는 초기화됩니다."));
        }

        GroupCondition condition = merged.condition();
        runningCondition = condition;
        Thread worker = new Thread(() -> runRecommendation(condition), "group-recommend");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 후보를 뽑아 방에 뿌립니다.
     *
     * <p>⚠️ 못 뽑았을 때 임시 메뉴로 채우지 마세요. 알레르기를 검사하지 않은 후보를 내보내는 것은
     * 빈 화면보다 위험합니다 — 왜 못 뽑았는지만 알립니다.
     */
    private void runRecommendation(GroupCondition condition) {
        GroupResult result;
        try {
            result = recommendService.recommend(condition, CANDIDATE_COUNT);
        } catch (RuntimeException e) {
            System.err.println("[room] 후보 추천 실패: " + e);
            result = GroupResult.failure();
        }

        if (result.failed()) {
            applyCandidates(condition, List.of(), "메뉴 정보를 불러오지 못했어요 —"
                    + " 방장 쪽 데이터베이스 연결을 확인해 주세요."
                    + " 알레르기를 확인하지 못한 메뉴는 후보로 내보내지 않습니다.");
        } else if (!result.hasMenuData()) {
            applyCandidates(condition, List.of(), "등록된 메뉴가 없어 후보를 뽑지 못했어요 —"
                    + " 메뉴 데이터를 먼저 넣어 주세요.");
        } else if (result.allBlockedByAllergy()) {
            applyCandidates(condition, List.of(),
                    allergyDeadEndNotice(condition, result.scannedMenus()));
        } else {
            applyCandidates(condition, toCandidates(result.candidates()), null);
        }
    }

    private static List<Candidate> toCandidates(List<GroupCandidate> found) {
        List<Candidate> rows = new ArrayList<>();
        for (GroupCandidate candidate : found) {
            rows.add(new Candidate(candidate.menuId(),
                    protocolSafe(candidate.menu()), protocolSafe(candidate.restaurant()),
                    candidate.score(), candidate.reason()));
        }
        return List.copyOf(rows);
    }

    private static String allergyDeadEndNotice(GroupCondition condition, int scannedMenus) {
        return "전원 알레르기(" + String.join("·", condition.allergies()) + ")를 모두 피하는 메뉴가 없어요"
                + " — 등록된 메뉴 " + scannedMenus + "개가 전부 제외됐습니다."
                + " 매운맛·예산을 바꿔도 결과는 같으니, 인원을 나누거나 메뉴 데이터를 늘려 주세요.";
    }

    /**
     * @param used 이 결과를 뽑을 때 쓴 조건 — 그사이 조건이 바뀌었는지 견주는 기준입니다
     */
    private synchronized void applyCandidates(GroupCondition used, List<Candidate> found, String notice) {
        runningCondition = null;
        if (closed || resultMessage != null) {
            return;
        }

        // 도는 사이에 조건이 빡빡해졌다면(늦게 합류한 사람의 알레르기 등) 이 결과는 버리고 다시 돕니다.
        // ⚠️ 검사하지 않은 알레르기가 섞인 후보는 잠깐이라도 내보내면 안 됩니다.
        if (!covers(used, merge().condition())) {
            reevaluate();
            return;
        }

        appliedCondition = used;
        candidates = found;
        votes.clear();

        broadcast(candidatesMessage());
        broadcast(voteStatusMessage());
        if (notice != null) {
            broadcast(Message.of(Message.Type.ERROR, notice));
        }
    }

    /** 메뉴·가게 이름에서 프로토콜 구분자를 걷어 냅니다. */
    private static String protocolSafe(String name) {
        return name == null ? "" : name.replaceAll("[|,()]", " ").replaceAll("\\s+", " ").trim();
    }

    /** 참여자당 1표, 다시 투표하면 표를 옮깁니다. 확정 후에는 받지 않습니다. */
    synchronized void vote(ClientHandler handler, int candidateNo) {
        if (!members.containsKey(handler) || candidates.isEmpty() || resultMessage != null) {
            return;
        }
        if (candidateNo < 1 || candidateNo > candidates.size()) {
            handler.send(Message.of(Message.Type.ERROR, "없는 후보 번호입니다: " + candidateNo));
            return;
        }
        votes.put(handler, candidateNo);
        broadcast(voteStatusMessage());
    }

    synchronized void retry(ClientHandler handler) {
        if (!members.containsKey(handler) || closed || resultMessage != null) {
            return;
        }
        if (!candidates.isEmpty() || runningCondition != null) {
            return;
        }
        if (!members.values().stream().allMatch(m -> m.ready)) {
            handler.send(Message.of(Message.Type.ERROR,
                    "아직 준비를 마치지 않은 참여자가 있어요."));
            return;
        }
        appliedCondition = null;
        reevaluate();
    }

    synchronized void chat(ClientHandler handler, String text) {
        Member member = members.get(handler);
        if (member == null || text.isBlank()) {
            return;
        }
        broadcast(Message.of(Message.Type.CHAT, member.name, text));
    }

    /**
     * 최다 득표 후보를 확정합니다 (동표면 앞 번호 우선). 방장 화면에서 직접 호출합니다.
     *
     * @return 표가 하나도 없으면 false
     */
    public synchronized boolean finishResult() {
        if (candidates.isEmpty() || votes.isEmpty()) {
            return false;
        }
        if (resultMessage != null) {
            return true;   // 이미 확정 — 다시 브로드캐스트하지 않습니다
        }
        int[] counts = tally();
        int best = 0;
        for (int i = 1; i < counts.length; i++) {
            if (counts[i] > counts[best]) {
                best = i;
            }
        }
        Candidate pick = candidates.get(best);
        resultMessage = Message.of(Message.Type.RESULT, pick.menu(), pick.restaurant());
        broadcast(resultMessage);
        saveResult(best);
        return true;
    }

    private void saveResult(int winningIndex) {
        GroupResultDao.GroupRecord record = snapshotForSave(winningIndex);
        if (record == null) {
            return;
        }
        Thread saver = new Thread(() -> {
            if (!new GroupResultDao().save(record)) {
                System.err.println("[room] 그룹 기록을 남기지 못했습니다.");
            }
        }, "group-save");
        saver.setDaemon(true);
        saver.start();
    }

    private GroupResultDao.GroupRecord snapshotForSave(int winningIndex) {
        Member owner = members.values().stream().findFirst().orElse(null);
        if (owner == null || owner.userId <= 0) {
            System.err.println("[room] 방장이 게스트 계정이라 그룹 기록을 남기지 않습니다.");
            return null;
        }

        List<GroupResultDao.MemberRow> memberRows = new ArrayList<>();
        for (Map.Entry<ClientHandler, Member> entry : members.entrySet()) {
            Member member = entry.getValue();
            Integer voted = votes.get(entry.getKey());
            memberRows.add(new GroupResultDao.MemberRow(member.name, member.userId,
                    List.copyOf(member.allergies), voted == null ? -1 : voted - 1));
        }

        List<GroupResultDao.CandidateRow> candidateRows = new ArrayList<>();
        for (Candidate candidate : candidates) {
            candidateRows.add(new GroupResultDao.CandidateRow(
                    candidate.menuId(), candidate.score(), candidate.reason()));
        }

        return new GroupResultDao.GroupRecord(inviteCode, owner.userId,
                List.copyOf(memberRows), List.copyOf(candidateRows), winningIndex);
    }

    synchronized void leave(ClientHandler handler) {
        Member member = members.remove(handler);
        if (member == null || closed) {
            return;
        }
        boolean hadVote = votes.remove(handler) != null;
        broadcast(Message.of(Message.Type.LEFT, member.name, String.valueOf(members.size())));
        if (hadVote && resultMessage == null) {
            broadcast(voteStatusMessage());
        }
        // 준비를 마치지 않은 사람이 나갔다면 이제 남은 전원이 준비 상태일 수 있습니다
        reevaluate();
    }

    /** 전원에게 알린 뒤 모든 소켓을 정리합니다. */
    synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        broadcast(Message.of(Message.Type.CLOSED, "방장이 방을 종료했습니다."));
        for (ClientHandler handler : List.copyOf(members.keySet())) {
            handler.close();
        }
        members.clear();
        votes.clear();
    }

    private void broadcast(Message message) {
        for (ClientHandler handler : members.keySet()) {
            handler.send(message);
        }
    }

    /** 예) {@code CANDIDATES|1.김치찌개(할머니손맛),2.제육볶음(백반집)} */
    private Message candidatesMessage() {
        StringBuilder csv = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            if (i > 0) {
                csv.append(',');
            }
            Candidate candidate = candidates.get(i);
            csv.append(i + 1).append('.').append(candidate.menu())
                    .append('(').append(candidate.restaurant()).append(')');
        }
        return Message.of(Message.Type.CANDIDATES, csv.toString());
    }

    /** 예) {@code VOTE_STATUS|1:2,2:1,3:0} */
    private Message voteStatusMessage() {
        int[] counts = tally();
        StringBuilder csv = new StringBuilder();
        for (int i = 0; i < counts.length; i++) {
            if (i > 0) {
                csv.append(',');
            }
            csv.append(i + 1).append(':').append(counts[i]);
        }
        return Message.of(Message.Type.VOTE_STATUS, csv.toString());
    }

    private int[] tally() {
        int[] counts = new int[candidates.size()];
        for (int no : votes.values()) {
            counts[no - 1]++;
        }
        return counts;
    }
}
