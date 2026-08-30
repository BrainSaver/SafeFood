package com.safefood.network;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;

/**
 * 초대 코드를 찍어서 맞히려는 시도를 늦춥니다 — 접속지 주소별로 실패 횟수를 셉니다.
 *
 * <p><b>왜 필요한가</b> — 코드의 비밀 부분은 4자(32진수)라 약 105만 가지입니다. 한 번 틀리면
 * 연결이 끊기지만 <b>다시 붙는 데 제한이 없어서</b>, 같은 랜에 있는 사람이 쉬지 않고 두드리면
 * 언젠가는 맞습니다. 들어오면 참여자 이름과 합쳐진 알레르기 목록이 그대로 보입니다.
 *
 * <p>막는 대상은 <b>실패</b>뿐입니다. 제대로 된 코드로 들어온 주소는 기록을 지워, 한 집에서
 * 여러 명이 같은 공유기로 들어오는 흔한 경우에 서로를 막지 않게 합니다.
 *
 * <p>시계는 {@link System#currentTimeMillis()} 대신 생성자로 받습니다 — 시험에서 시간을
 * 앞으로 돌려 차단이 풀리는지 확인해야 하는데, 실제 시계로는 1분을 기다려야 합니다.
 */
final class JoinThrottle {

    /** 이 횟수만큼 연달아 틀리면 잠급니다. 코드를 손으로 옮겨 적다 틀리는 정도는 통과합니다. */
    static final int MAX_FAILURES = 5;

    /** 실패 기록의 유효 기간 — 이 시간이 지나면 처음부터 다시 셉니다. */
    static final long WINDOW_MS = 60_000;

    /** 잠긴 뒤 다시 받아 줄 때까지. */
    static final long BLOCK_MS = 60_000;

    /** 시각을 밀리초로 알려 주는 곳. 실제 실행에서는 시스템 시계입니다. */
    interface Clock {
        long now();
    }

    private static final class Record {
        int failures;
        long firstFailureAt;
        long blockedUntil;
    }

    private final Clock clock;
    private final Map<String, Record> records = new HashMap<>();

    JoinThrottle() {
        this(System::currentTimeMillis);
    }

    JoinThrottle(Clock clock) {
        this.clock = clock;
    }

    /**
     * 지금 이 주소를 받아 줘도 되는지. 잠겨 있으면 false —
     * 부른 쪽에서 소켓을 바로 닫으면 됩니다.
     */
    synchronized boolean allows(InetAddress address) {
        Record record = records.get(key(address));
        return record == null || clock.now() >= record.blockedUntil;
    }

    /**
     * 입장 시도 결과를 남깁니다.
     *
     * @param joined 들어왔으면 true — 그 주소의 실패 기록을 지웁니다
     */
    synchronized void record(InetAddress address, boolean joined) {
        String key = key(address);
        if (joined) {
            records.remove(key);
            return;
        }

        long now = clock.now();
        Record record = records.computeIfAbsent(key, ignored -> new Record());

        // 창이 지났으면 새로 셉니다 — 오래전 실패까지 더해 잠그지 않도록
        if (record.failures == 0 || now - record.firstFailureAt > WINDOW_MS) {
            record.failures = 0;
            record.firstFailureAt = now;
        }
        record.failures++;

        if (record.failures >= MAX_FAILURES) {
            record.blockedUntil = now + BLOCK_MS;
            record.failures = 0;
            System.err.println("[server] 초대 코드를 " + MAX_FAILURES + "번 틀려 "
                    + (BLOCK_MS / 1000) + "초 동안 막습니다: " + key);
        }
    }

    private static String key(InetAddress address) {
        return address == null ? "unknown" : address.getHostAddress();
    }
}
