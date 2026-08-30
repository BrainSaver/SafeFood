package com.safefood.network;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("JoinThrottle — 초대 코드 무차별 대입 차단")
class JoinThrottleTest {

    /** 시험용 시계 — 원하는 만큼 시간을 앞으로 돌립니다. */
    private static final class FakeClock implements JoinThrottle.Clock {
        private long now = 1_000_000L;

        @Override
        public long now() {
            return now;
        }

        void advance(long millis) {
            now += millis;
        }
    }

    private final FakeClock clock = new FakeClock();
    private final JoinThrottle throttle = new JoinThrottle(clock);

    private static InetAddress ip(String literal) throws UnknownHostException {
        return InetAddress.getByName(literal);
    }

    private void fail(InetAddress address, int times) {
        for (int i = 0; i < times; i++) {
            throttle.record(address, false);
        }
    }

    @Test
    @DisplayName("처음 온 주소는 받아 준다")
    void allowsUnknownAddress() throws Exception {
        assertTrue(throttle.allows(ip("192.168.0.10")));
    }

    @Test
    @DisplayName("상한 직전까지 틀려도 아직 받아 준다 — 손으로 옮겨 적다 틀리는 경우")
    void allowsUntilLimit() throws Exception {
        InetAddress address = ip("192.168.0.10");
        fail(address, JoinThrottle.MAX_FAILURES - 1);
        assertTrue(throttle.allows(address));
    }

    @Test
    @DisplayName("상한만큼 틀리면 막는다")
    void blocksAtLimit() throws Exception {
        InetAddress address = ip("192.168.0.10");
        fail(address, JoinThrottle.MAX_FAILURES);
        assertFalse(throttle.allows(address));
    }

    @Test
    @DisplayName("막힌 시간이 지나면 다시 받아 준다")
    void unblocksAfterCooldown() throws Exception {
        InetAddress address = ip("192.168.0.10");
        fail(address, JoinThrottle.MAX_FAILURES);
        assertFalse(throttle.allows(address));

        clock.advance(JoinThrottle.BLOCK_MS);
        assertTrue(throttle.allows(address));
    }

    @Test
    @DisplayName("실패가 창을 넘겨 띄엄띄엄이면 잠기지 않는다")
    void failuresOutsideWindowDoNotAccumulate() throws Exception {
        InetAddress address = ip("192.168.0.10");
        for (int i = 0; i < JoinThrottle.MAX_FAILURES * 3; i++) {
            throttle.record(address, false);
            clock.advance(JoinThrottle.WINDOW_MS + 1);
        }
        assertTrue(throttle.allows(address), "창 밖의 오래된 실패까지 더해 막으면 안 된다");
    }

    @Test
    @DisplayName("들어오는 데 성공하면 실패 기록이 지워진다 — 같은 공유기의 다른 사람을 막지 않도록")
    void successClearsFailures() throws Exception {
        InetAddress address = ip("192.168.0.10");
        fail(address, JoinThrottle.MAX_FAILURES - 1);

        throttle.record(address, true);

        fail(address, JoinThrottle.MAX_FAILURES - 1);
        assertTrue(throttle.allows(address), "성공 뒤에는 처음부터 다시 세야 한다");
    }

    @Test
    @DisplayName("한 주소를 막아도 다른 주소는 그대로 들어온다")
    void blockIsPerAddress() throws Exception {
        InetAddress attacker = ip("192.168.0.10");
        InetAddress friend = ip("192.168.0.11");

        fail(attacker, JoinThrottle.MAX_FAILURES);

        assertFalse(throttle.allows(attacker));
        assertTrue(throttle.allows(friend));
    }
}
