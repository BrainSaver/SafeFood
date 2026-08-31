package com.safefood.network;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 초대 코드의 부호화 규칙 — 내부 구조(5비트 문자 × 10 + 검증 2비트 + 비밀 4자)를 알고
 * 각 분기에 정확히 걸리는 입력을 만들어 확인합니다.
 *
 * <p>검증 비트 불일치·포트 0·선두 옥텟 0 같은 분기는 밖에서 우연히 만들 수 없어,
 * 발급 로직과 같은 부호화를 시험 안에 거울로 두고({@link #encode}) 입력을 직접 조립합니다.
 * 거울이 실물과 어긋나면 {@link #handMadeCodeMatchesIssueFormat()} 이 먼저 깨집니다.
 */
class InviteCodeTest {

    /** 실물({@code InviteCode.ALPHABET})의 사본 — O·I·L·U 가 없는 32자입니다. */
    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    private static InetAddress ip(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (UnknownHostException e) {
            throw new AssertionError(literal, e);
        }
    }

    /**
     * 발급 로직의 거울 — 주소 10자를 같은 규칙으로 조립하되, 검증 비트를 일부러
     * {@code checksumDelta} 만큼 비틀 수 있습니다. 비밀 4자는 아무 값이나 됩니다.
     */
    private static String encode(int[] octets, int port, int checksumDelta) {
        long payload = 0;
        for (int octet : octets) {
            payload = (payload << 8) | (octet & 0xFF);
        }
        payload = (payload << 16) | port;

        long sum = 0;
        for (int shift = 0; shift < 48; shift += 8) {
            sum += (payload >>> shift) & 0xFF;
        }
        long value = (((sum + checksumDelta) & 0b11) << 48) | payload;

        char[] chars = new char[10];
        for (int i = 9; i >= 0; i--) {
            chars[i] = ALPHABET.charAt((int) (value & 0x1F));
            value >>>= 5;
        }
        return new String(chars) + "AAAA";
    }

    // ── 발급 ↔ 해석 왕복 ────────────────────────────────────────

    @Test
    @DisplayName("발급한 코드를 해석하면 주소와 포트가 그대로 나온다")
    void issueParseRoundTrip() {
        String code = InviteCode.issue(ip("192.168.0.10"), 5000);
        InviteCode parsed = InviteCode.parse(code);

        assertAll(
                () -> assertEquals(InviteCode.LENGTH, code.length()),
                () -> assertEquals("192.168.0.10", parsed.host()),
                () -> assertEquals(5000, parsed.port()),
                () -> assertEquals("192.168.0.10:5000", parsed.address()),
                () -> assertEquals(code, parsed.code()),
                () -> assertEquals(code.substring(0, 10), parsed.addressPart()));
    }

    @Test
    @DisplayName("포트 경계값 1과 65535도 왕복이 된다")
    void portBoundariesRoundTrip() {
        assertAll(
                () -> assertEquals(1, InviteCode.parse(InviteCode.issue(ip("10.0.0.1"), 1)).port()),
                () -> assertEquals(0xFFFF,
                        InviteCode.parse(InviteCode.issue(ip("10.0.0.1"), 0xFFFF)).port()));
    }

    @Test
    @DisplayName("사람이 흘려 적은 코드도 읽힌다 — 소문자·붙임표·O/I/L 혼동까지")
    void parseForgivesHumanTypos() {
        String code = InviteCode.issue(ip("172.16.31.7"), 40000);

        // 화면의 5-5-4 표기 그대로, 소문자로, 0→O·1→L 로 잘못 적은 상황
        String sloppy = InviteCode.format(code).toLowerCase()
                .replace('0', 'o').replace('1', 'l');
        InviteCode parsed = InviteCode.parse(sloppy);

        assertAll(
                () -> assertEquals("172.16.31.7", parsed.host()),
                () -> assertEquals(40000, parsed.port()),
                () -> assertEquals(code, parsed.code(), "code()는 표준형으로 돌아와야 합니다"));
    }

    // ── 발급 거부 분기 ──────────────────────────────────────────

    @Test
    @DisplayName("IPv6 주소는 발급을 거부한다 — 코드에는 32비트만 담긴다")
    void issueRejectsIpv6() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> InviteCode.issue(ip("::1"), 5000));
        assertTrue(e.getMessage().contains("IPv4"));
    }

    @Test
    @DisplayName("포트 범위 밖(0 · 65536)은 발급을 거부한다")
    void issueRejectsPortOutOfRange() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> InviteCode.issue(ip("10.0.0.1"), 0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> InviteCode.issue(ip("10.0.0.1"), 0x1_0000)));
    }

    // ── 해석 거부 분기 ──────────────────────────────────────────

    @Test
    @DisplayName("빈 입력 — null·빈 문자열·구분자만 있는 입력은 '입력해 주세요'")
    void parseRejectsEmptyInput() {
        for (String raw : new String[]{null, "", " - _  "}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> InviteCode.parse(raw), String.valueOf(raw));
            assertTrue(e.getMessage().contains("입력해 주세요"), e.getMessage());
        }
    }

    @Test
    @DisplayName("길이가 다르면 몇 자인지 알려 주며 거부한다")
    void parseRejectsWrongLength() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> InviteCode.parse("ABC"));
        assertAll(
                () -> assertTrue(e.getMessage().contains("14자"), e.getMessage()),
                () -> assertTrue(e.getMessage().contains("3자"), e.getMessage()));
    }

    @Test
    @DisplayName("자모표에 없는 글자 — 주소부와 비밀부 각각의 검사 분기에 걸린다")
    void parseRejectsForeignCharacters() {
        assertAll(
                // 주소부(앞 10자) 검사 분기
                () -> assertTrue(assertThrows(IllegalArgumentException.class,
                                () -> InviteCode.parse("*000000000AAAA"))
                        .getMessage().contains("쓸 수 없는 글자")),
                // 비밀부(뒤 4자) 검사 분기 — U 는 자모표에서 뺀 글자입니다
                () -> assertTrue(assertThrows(IllegalArgumentException.class,
                                () -> InviteCode.parse("0000000000ABUD"))
                        .getMessage().contains("쓸 수 없는 글자")));
    }

    @Test
    @DisplayName("거울 부호화가 실물과 같다 — 아래 조작 시험들의 전제")
    void handMadeCodeMatchesIssueFormat() {
        InviteCode parsed = InviteCode.parse(encode(new int[]{192, 168, 0, 10}, 5000, 0));

        assertAll(
                () -> assertEquals("192.168.0.10", parsed.host()),
                () -> assertEquals(5000, parsed.port()));
    }

    @Test
    @DisplayName("검증 비트가 어긋난 코드 — 한 글자 잘못 친 코드가 여기서 걸린다")
    void parseRejectsChecksumMismatch() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> InviteCode.parse(encode(new int[]{192, 168, 0, 10}, 5000, 1)));
        assertTrue(e.getMessage().contains("잘못 입력된 글자"), e.getMessage());
    }

    @Test
    @DisplayName("검증은 통과해도 말이 안 되는 주소 — 포트 0, 선두 옥텟 0")
    void parseRejectsImpossibleAddress() {
        assertAll(
                () -> assertTrue(assertThrows(IllegalArgumentException.class,
                                () -> InviteCode.parse(encode(new int[]{192, 168, 0, 10}, 0, 0)))
                        .getMessage().contains("접속 주소가 올바르지")),
                () -> assertTrue(assertThrows(IllegalArgumentException.class,
                                () -> InviteCode.parse(encode(new int[]{0, 8, 0, 10}, 5000, 0)))
                        .getMessage().contains("접속 주소가 올바르지")));
    }

    // ── 보조 변환 ──────────────────────────────────────────────

    @Test
    @DisplayName("normalize — 대문자화·구분자 제거·O/I/L 치환의 모든 규칙")
    void normalizeAppliesEveryRule() {
        assertAll(
                () -> assertEquals("", InviteCode.normalize(null)),
                () -> assertEquals("011", InviteCode.normalize("oil"), "O→0, I→1, L→1"),
                () -> assertEquals("ABC2", InviteCode.normalize(" a-b_c\t2 ")),
                () -> assertEquals("XY", InviteCode.normalize("xy")));
    }

    @Test
    @DisplayName("format — 14자면 5-5-4 로 끊고, 아니면 표준형 그대로 돌려준다")
    void formatGroupsOnlyFullLengthCodes() {
        String code = InviteCode.issue(ip("10.1.2.3"), 5000);
        String formatted = InviteCode.format(code);

        assertAll(
                () -> assertEquals(code.substring(0, 5) + "-" + code.substring(5, 10)
                        + "-" + code.substring(10), formatted),
                () -> assertEquals("ABC", InviteCode.format("abc"), "짧으면 정규화만 하고 그대로"));
    }

    @Test
    @DisplayName("addressPart — 10자를 넘으면 앞 10자만, 짧으면 그대로")
    void addressPartTruncatesAtTen() {
        String code = InviteCode.issue(ip("10.1.2.3"), 5000);

        assertAll(
                () -> assertEquals(code.substring(0, 10), InviteCode.addressPart(code)),
                () -> assertEquals("AB0", InviteCode.addressPart("ab-o")));
    }
}
