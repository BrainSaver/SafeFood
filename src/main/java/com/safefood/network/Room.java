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

public final class Room {
    private static final class Member {
        final String name;
        List<String> allergies = List.of();
        int spicyLevel = 5;
        int budgetMax = 0;
        int userId;
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

    private final Map<ClientHandler, Member> members = new LinkedHashMap<>();
    private final Map<ClientHandler, Integer> votes = new LinkedHashMap<>();

    private List<String> mergedLines = List.of();
    private List<Candidate> candidates = List.of();
    private Message resultMessage;
    private boolean closed;

    private GroupCondition appliedCondition;

    private GroupCondition runningCondition;

    private record Merged(GroupCondition condition, List<String> lines) {
    }

    Room(String inviteCode) {
        this(inviteCode, new GroupRecommendService());
    }

    Room(String inviteCode, GroupRecommendService recommendService) {
        this.inviteCode = inviteCode;
        this.recommendService = recommendService;
    }

    /**
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

    private void reevaluate() {
        if (closed || resultMessage != null || runningCondition != null) {
            return;
        }
        if (members.isEmpty() || !members.values().stream().allMatch(m -> m.ready)) {
            return;
        }
        Merged merged = merge();
        if (appliedCondition != null && covers(appliedCondition, merged.condition())) {
            return;
        }
        startRecommendation(merged);
    }

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

    private static boolean covers(GroupCondition applied, GroupCondition wanted) {
        return applied.allergies().containsAll(wanted.allergies())
                && applied.spicyMax() <= wanted.spicyMax()
                && (wanted.budgetMax() == 0
                    || (applied.budgetMax() != 0 && applied.budgetMax() <= wanted.budgetMax()));
    }

    private void startRecommendation(Merged merged) {
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

    private static String protocolSafe(String name) {
        return name == null ? "" : name.replaceAll("[|,()]", " ").replaceAll("\\s+", " ").trim();
    }

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
     * @return 표가 하나도 없으면 false
     */
    public synchronized boolean finishResult() {
        if (candidates.isEmpty() || votes.isEmpty()) {
            return false;
        }
        if (resultMessage != null) {
            return true;
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
        reevaluate();
    }

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
