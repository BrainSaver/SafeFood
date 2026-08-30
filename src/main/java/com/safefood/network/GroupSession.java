package com.safefood.network;

import java.io.IOException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class GroupSession implements GroupClient.Listener {
    public record MemberView(String name, boolean owner, boolean ready) {
    }

    public record CandidateView(int no, String menu, String restaurant) {
    }

    public record Event(EventKind kind, String sender, String text) {
    }

    public enum EventKind {
        SYSTEM,
        SYSTEM_GOOD,
        MERGED,
        SYSTEM_BAD,
        CHAT
    }

    private static final GroupSession INSTANCE = new GroupSession();
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    public static GroupSession get() {
        return INSTANCE;
    }

    private String displayName = "게스트";
    private boolean guest = true;

    private GroupServer server;
    private GroupClient client;
    private volatile GroupClient.Listener uiListener;

    private final List<String> memberOrder = new ArrayList<>();
    private final Set<String> readyNames = new HashSet<>();
    private final List<String> logs = new ArrayList<>();
    private final List<Event> events = new ArrayList<>();
    private List<String> mergedLines = List.of();
    private List<CandidateView> candidates = List.of();
    private Map<Integer, Integer> voteCounts = Map.of();
    private String resultText;
    private String inviteCode = "";
    private boolean remoteClosed;
    private boolean disconnected;
    private boolean candidatesReceived;

    private GroupSession() {
    }

    public synchronized void hostRoom(int port, String inviteCode) throws IOException {
        shutdown();
        server = GroupServer.open(port, inviteCode);
        try {
            client = GroupClient.connect("localhost", port);
        } catch (IOException e) {
            server.stop();
            server = null;
            throw e;
        }
        client.setListener(this);
        this.inviteCode = inviteCode;
        appendLog("서버 시작 (:" + port + ") — 초대 코드 " + inviteCode);
        events.add(good("방이 열렸어요 — 초대 코드를 일행에게 보내세요"));
    }

    public synchronized void joinRoom(String host, int port) throws IOException {
        shutdown();
        client = GroupClient.connect(host, port);
        client.setListener(this);
    }

    public synchronized void shutdown() {
        uiListener = null;
        if (server != null) {
            server.stop();
            server = null;
        }
        if (client != null) {
            client.close();
            client = null;
        }
        memberOrder.clear();
        readyNames.clear();
        logs.clear();
        events.clear();
        mergedLines = List.of();
        candidates = List.of();
        voteCounts = Map.of();
        resultText = null;
        inviteCode = "";
        remoteClosed = false;
        disconnected = false;
        candidatesReceived = false;
    }

    public synchronized void setInviteCode(String code) {
        inviteCode = code == null ? "" : code;
    }

    public synchronized String inviteCode() {
        return inviteCode;
    }

    @Override
    public void onMessage(Message message) {
        synchronized (this) {
            appendLog(message.serialize());
            switch (message.type()) {
                case JOINED -> {
                    String name = message.part(0);
                    if (!memberOrder.contains(name)) {
                        memberOrder.add(name);
                    }
                }
                case LEFT -> {
                    memberOrder.remove(message.part(0));
                    readyNames.remove(message.part(0));
                }
                case READY -> readyNames.add(message.part(0));
                case MERGED -> mergedLines = List.copyOf(message.body());
                case CANDIDATES -> {
                    candidates = parseCandidates(message.part(0));
                    candidatesReceived = true;
                }
                case VOTE_STATUS -> voteCounts = parseVoteStatus(message.part(0));
                case RESULT -> resultText = message.part(0)
                        + (message.part(1).isEmpty() ? "" : " (" + message.part(1) + ")");
                case CLOSED -> remoteClosed = true;
                default -> { }
            }
            recordEvent(message);
        }
        GroupClient.Listener current = uiListener;
        if (current != null) {
            current.onMessage(message);
        }
    }

    @Override
    public void onDisconnected() {
        boolean notify;
        synchronized (this) {
            disconnected = true;
            notify = !remoteClosed;
            if (notify) {
                appendLog("서버와의 연결이 끊겼습니다.");
                events.add(bad("서버와의 연결이 끊겼어요"));
            }
        }
        if (!notify) {
            return;
        }
        GroupClient.Listener current = uiListener;
        if (current != null) {
            current.onDisconnected();
        }
    }

    public synchronized List<MemberView> members() {
        List<MemberView> list = new ArrayList<>();
        for (int i = 0; i < memberOrder.size(); i++) {
            String name = memberOrder.get(i);
            list.add(new MemberView(name, i == 0, readyNames.contains(name)));
        }
        return list;
    }

    public synchronized List<String> logLines() {
        return List.copyOf(logs);
    }

    public synchronized List<Event> events() {
        return List.copyOf(events);
    }

    public synchronized List<String> mergedLines() {
        return mergedLines;
    }

    public synchronized List<CandidateView> candidates() {
        return candidates;
    }

    public synchronized boolean candidatesReceived() {
        return candidatesReceived;
    }

    public synchronized int votesFor(int candidateNo) {
        return voteCounts.getOrDefault(candidateNo, 0);
    }

    public synchronized int totalVotes() {
        int total = 0;
        for (int count : voteCounts.values()) {
            total += count;
        }
        return total;
    }

    public synchronized String result() {
        return resultText;
    }

    public synchronized GroupClient client() {
        return client;
    }

    public synchronized GroupServer server() {
        return server;
    }

    public synchronized boolean isOwner() {
        return server != null;
    }

    public synchronized boolean isConnected() {
        return client != null;
    }

    public synchronized boolean isRemoteClosed() {
        return remoteClosed;
    }

    public synchronized boolean isDead() {
        return remoteClosed || disconnected || (client != null && !client.isAlive());
    }

    public synchronized String displayName() {
        return displayName;
    }

    public synchronized void setDisplayName(String name) {
        if (name != null && !name.isBlank()) {
            displayName = name.trim();
        }
    }

    public synchronized boolean isGuest() {
        return guest;
    }

    public synchronized void setGuest(boolean guest) {
        this.guest = guest;
    }

    public void setUiListener(GroupClient.Listener listener) {
        uiListener = listener;
    }

    private void appendLog(String text) {
        logs.add("[" + LocalTime.now().format(LOG_TIME) + "] " + text);
    }

    private void recordEvent(Message message) {
        switch (message.type()) {
            case CHAT -> events.add(
                    new Event(EventKind.CHAT, message.part(0), message.rest(1)));
            case JOINED -> events.add(system(
                    message.part(0) + "님이 참여했어요 (" + memberOrder.size() + "명)"));
            case LEFT -> events.add(system(message.part(0) + "님이 나갔어요"));
            case READY -> events.add(system(
                    message.part(0) + "님이 준비를 마쳤어요 ("
                            + readyNames.size() + "/" + memberOrder.size() + ")"));
            case MERGED -> events.add(new Event(
                    EventKind.MERGED, "", "✓ 전원 준비 완료 — 조건을 합쳐 후보를 뽑았어요"));
            case CANDIDATES -> events.add(candidates.isEmpty()
                    ? bad("조건에 맞는 추천 후보를 찾지 못했어요")
                    : good("추천 후보 " + candidates.size() + "개가 도착했어요"));
            case VOTE_STATUS -> {
                int total = 0;
                for (int count : voteCounts.values()) {
                    total += count;
                }
                if (total > 0) {
                    events.add(system("투표가 접수되었어요 (" + total + "/" + memberOrder.size() + ")"));
                }
            }
            case RESULT -> events.add(good("최종 메뉴 확정 — " + resultText));
            case ERROR -> events.add(bad(message.part(0)));
            case CLOSED -> events.add(bad(message.part(0).isEmpty()
                    ? "방장이 방을 종료했어요" : message.part(0)));
            default -> { }
        }
    }

    private static Event system(String text) {
        return new Event(EventKind.SYSTEM, "", text);
    }

    private static Event good(String text) {
        return new Event(EventKind.SYSTEM_GOOD, "", text);
    }

    private static Event bad(String text) {
        return new Event(EventKind.SYSTEM_BAD, "", text);
    }

    private static List<CandidateView> parseCandidates(String csv) {
        List<CandidateView> list = new ArrayList<>();
        for (String token : csv.split(",")) {
            token = token.trim();
            int dot = token.indexOf('.');
            if (dot < 1) {
                continue;
            }
            int no;
            try {
                no = Integer.parseInt(token.substring(0, dot));
            } catch (NumberFormatException e) {
                continue;
            }
            String rest = token.substring(dot + 1);
            String menu = rest;
            String restaurant = "";
            int open = rest.lastIndexOf('(');
            if (open > 0 && rest.endsWith(")")) {
                menu = rest.substring(0, open);
                restaurant = rest.substring(open + 1, rest.length() - 1);
            }
            list.add(new CandidateView(no, menu, restaurant));
        }
        return List.copyOf(list);
    }

    private static Map<Integer, Integer> parseVoteStatus(String csv) {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (String token : csv.split(",")) {
            String[] pair = token.trim().split(":");
            if (pair.length != 2) {
                continue;
            }
            try {
                counts.put(Integer.parseInt(pair[0].trim()), Integer.parseInt(pair[1].trim()));
            } catch (NumberFormatException e) {
            }
        }
        return counts;
    }
}
