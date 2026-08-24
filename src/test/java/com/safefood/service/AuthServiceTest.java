package com.safefood.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 비밀번호 해시 검증 — DB 를 타지 않는 순수 계산만 확인합니다.
 * ({@code login}/{@code register} 는 DAO 를 거치므로 여기서 다루지 않습니다.)
 */
class AuthServiceTest {

    private final AuthService authService = new AuthService();

    /** 예전 방식 재현 — 이미 가입한 회원의 DB 값을 흉내 냅니다. */
    private static String legacySha256(String plain) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(plain.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @Test
    @DisplayName("해시한 비밀번호는 그 비밀번호로만 검증된다")
    void hashRoundTrip() {
        String stored = authService.hashPassword("safefood!2026");

        assertAll(
                () -> assertTrue(authService.verifyPassword("safefood!2026", stored)),
                () -> assertFalse(authService.verifyPassword("safefood!2025", stored)),
                () -> assertFalse(authService.verifyPassword("", stored))
        );
    }

    @Test
    @DisplayName("같은 비밀번호라도 계정마다 해시가 다르다 (솔트)")
    void sameSecretDifferentHash() {
        String first = authService.hashPassword("동일한비밀번호");
        String second = authService.hashPassword("동일한비밀번호");

        // 예전 SHA-256 은 이 둘이 똑같아서, 한 명이 뚫리면 같이 뚫렸습니다
        assertNotEquals(first, second);
        assertAll(
                () -> assertTrue(authService.verifyPassword("동일한비밀번호", first)),
                () -> assertTrue(authService.verifyPassword("동일한비밀번호", second))
        );
    }

    @Test
    @DisplayName("저장 형식은 알고리즘·반복수·솔트·해시 네 조각을 담는다")
    void storedFormat() {
        String stored = authService.hashPassword("형식확인");
        String[] parts = stored.split("\\$");

        assertAll(
                () -> assertEquals(5, parts.length, stored),
                () -> assertEquals("pbkdf2", parts[0]),
                () -> assertEquals("sha256", parts[1]),
                () -> assertEquals(600_000, Integer.parseInt(parts[2])),
                () -> assertTrue(stored.length() <= 255, "user.password VARCHAR(255) 안에 들어가야 합니다")
        );
    }

    @Test
    @DisplayName("이미 가입한 회원의 예전 해시로도 로그인할 수 있다")
    void legacyHashStillVerifies() throws Exception {
        String legacy = legacySha256("예전비밀번호");

        assertAll(
                () -> assertTrue(authService.verifyPassword("예전비밀번호", legacy)),
                () -> assertFalse(authService.verifyPassword("다른비밀번호", legacy)),
                () -> assertTrue(authService.needsRehash(legacy), "예전 형식은 이관 대상으로 표시돼야 합니다")
        );
    }

    @Test
    @DisplayName("새 형식은 이관 대상이 아니다")
    void newHashNeedsNoRehash() {
        assertFalse(authService.needsRehash(authService.hashPassword("새비밀번호")));
    }

    @Test
    @DisplayName("확인할 표가 없으면 로그인을 유지한다 (DB 를 보지 않고 통과)")
    void sessionCheckIsSkippedWithoutToken() {
        // 판단이 서지 않을 때 쫓아내면, DB 가 잠깐 흔들릴 때마다 멀쩡한 사용자가 로그아웃됩니다.
        // 아래 세 경우는 DB 를 아예 보지 않고 통과해야 합니다.
        assertAll(
                () -> assertTrue(authService.isSessionCurrent(1, null)),
                () -> assertTrue(authService.isSessionCurrent(1, "   ")),
                () -> assertTrue(authService.isSessionCurrent(0, "표가-있어도-게스트면-확인하지-않음")),
                () -> assertTrue(authService.isSessionCurrent(-1, "게스트"))
        );
    }

    @Test
    @DisplayName("알아볼 수 없거나 손상된 해시는 통과시키지 않는다")
    void brokenHashIsRejected() {
        assertAll(
                () -> assertFalse(authService.verifyPassword("무엇이든", null)),
                () -> assertFalse(authService.verifyPassword("무엇이든", "")),
                () -> assertFalse(authService.verifyPassword("무엇이든", "평문그대로저장된값")),
                () -> assertFalse(authService.verifyPassword("무엇이든", "pbkdf2$sha256$600000$짧음")),
                () -> assertFalse(authService.verifyPassword("무엇이든", "pbkdf2$sha256$abc$c2FsdA$aGFzaA"))
        );
    }
}
