package com.safefood.network;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 전문(電文) 한 줄의 해석 규칙 — 프로토콜의 가장 밑단이라 모든 분기를 못박아 둡니다.
 *
 * <p>여기가 어긋나면 그 위의 방·화면 로직이 전부 같이 어긋나므로, 화이트 박스로
 * {@code parse}의 실패 분기(빈 줄·모르는 타입)와 {@code split(-1)} 이 지키는
 * 빈 꼬리 칸, {@code part}/{@code rest} 의 범위 밖 접근까지 전부 확인합니다.
 */
class MessageTest {

    // ── parse 실패 분기 ─────────────────────────────────────────

    @Test
    @DisplayName("null·빈 줄·공백 줄은 null — 연결 유지용 잡음으로 취급")
    void blankLinesParseToNull() {
        assertAll(
                () -> assertNull(Message.parse(null)),
                () -> assertNull(Message.parse("")),
                () -> assertNull(Message.parse("   \t")));
    }

    @Test
    @DisplayName("모르는 타입·소문자 타입은 null — 예외를 밖으로 던지지 않는다")
    void unknownTypeParsesToNull() {
        assertAll(
                () -> assertNull(Message.parse("NOPE|x")),
                () -> assertNull(Message.parse("join|abc|민수"), "Type.valueOf 는 대소문자를 가립니다"),
                () -> assertNull(Message.parse("JOIN |x"), "타입 뒤에 공백이 붙으면 다른 이름입니다"));
    }

    // ── parse 성공 경로 ─────────────────────────────────────────

    @Test
    @DisplayName("타입만 있는 줄 — 본문 없는 READY 같은 전문")
    void typeOnlyLineHasEmptyBody() {
        Message message = Message.parse("READY");

        assertAll(
                () -> assertEquals(Message.Type.READY, message.type()),
                () -> assertTrue(message.body().isEmpty()),
                () -> assertEquals("READY", message.serialize()));
    }

    @Test
    @DisplayName("줄 양끝 공백은 잘라 내고 해석한다")
    void surroundingWhitespaceIsTrimmed() {
        Message message = Message.parse("  JOIN|CODE|민수  ");

        assertEquals(Message.Type.JOIN, message.type());
        assertEquals(List.of("CODE", "민수"), message.body());
    }

    @Test
    @DisplayName("빈 꼬리 칸이 사라지지 않는다 — split 의 -1 리밋이 지키는 약속")
    void trailingEmptyFieldSurvives() {
        // JOIN|코드| 처럼 이름 칸이 빈 채로 와도 '칸이 없음'이 아니라 '빈 칸'이어야
        // 방이 "이름을 입력해 주세요"를 제대로 돌려줄 수 있습니다.
        Message message = Message.parse("JOIN|CODE|");

        assertAll(
                () -> assertEquals(2, message.body().size()),
                () -> assertEquals("", message.part(1)),
                () -> assertEquals("JOIN|CODE|", message.serialize(), "직렬화 왕복에도 그대로"));
    }

    // ── part / rest ────────────────────────────────────────────

    @Test
    @DisplayName("part — 범위 밖 번호는 예외 대신 빈 문자열")
    void partOutOfRangeIsEmpty() {
        Message message = Message.parse("JOIN|CODE|민수");

        assertAll(
                () -> assertEquals("CODE", message.part(0)),
                () -> assertEquals("민수", message.part(1)),
                () -> assertEquals("", message.part(2)),
                () -> assertEquals("", message.part(99)));
    }

    @Test
    @DisplayName("rest — 구분자가 든 채팅 본문을 다시 이어 붙인다")
    void restRejoinsWithPipes() {
        Message message = Message.parse("CHAT|민수|안녕|하세요");

        assertAll(
                () -> assertEquals("민수|안녕|하세요", message.rest(0)),
                () -> assertEquals("안녕|하세요", message.rest(1), "채팅 본문의 | 가 살아남아야 합니다"),
                () -> assertEquals("하세요", message.rest(2)),
                () -> assertEquals("", message.rest(3), "범위 밖은 빈 문자열"));
    }

    // ── of / serialize ─────────────────────────────────────────

    @Test
    @DisplayName("of(가변 인자)와 of(목록)는 같은 전문을 만든다")
    void bothFactoriesAgree() {
        Message varargs = Message.of(Message.Type.JOINED, "민수", "2");
        Message list = Message.of(Message.Type.JOINED, List.of("민수", "2"));

        assertEquals(varargs.serialize(), list.serialize());
        assertEquals("JOINED|민수|2", varargs.serialize());
    }

    @Test
    @DisplayName("serialize ↔ parse 왕복이 손실 없다 · toString 은 serialize 와 같다")
    void serializeParseRoundTrip() {
        Message original = Message.of(Message.Type.ERROR, "이미 사용 중인 이름입니다: 민수");
        Message reparsed = Message.parse(original.serialize());

        assertAll(
                () -> assertEquals(original.type(), reparsed.type()),
                () -> assertEquals(original.body(), reparsed.body()),
                () -> assertEquals(original.serialize(), original.toString()));
    }

    @Test
    @DisplayName("body 는 바꿀 수 없는 사본 — 받은 전문을 어디서도 고칠 수 없다")
    void bodyIsImmutable() {
        Message message = Message.of(Message.Type.MERGED, "줄1", "줄2");

        assertThrows(UnsupportedOperationException.class, () -> message.body().add("줄3"));
    }
}
