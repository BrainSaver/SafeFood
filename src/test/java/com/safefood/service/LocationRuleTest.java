package com.safefood.service;

import com.safefood.dto.LocationDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 위치 값 검사의 모든 분기 — DB 를 타지 않는 정적 규칙만 봅니다.
 * ({@code DistanceFilterTest} 가 거리 계산을 보므로, 여기서는 저장 가능 판정과
 * 좌표 해석, 거리 표기 눈금을 봅니다.)
 *
 * <p>저장 판정이 어긋나면 한쪽 좌표만 남은 위치가 저장돼, 거리 계산이 조용히
 * 빠지거나 엉뚱한 값이 나옵니다.
 */
class LocationRuleTest {

    private static LocationDto loc(String address, Double lat, Double lng) {
        return new LocationDto("집", "12345", address, lat, lng);
    }

    // ── 저장 가능 판정 ─────────────────────────────────────────

    @Test
    @DisplayName("저장 판정 — null 과 '주소도 좌표도 없음'은 거절")
    void savableRejectsEmptyLocation() {
        assertAll(
                () -> assertFalse(LocationService.isSavable(null)),
                () -> assertFalse(LocationService.isSavable(loc(null, null, null))),
                () -> assertFalse(LocationService.isSavable(loc("   ", null, null)),
                        "공백뿐인 주소는 주소가 아닙니다"));
    }

    @Test
    @DisplayName("저장 판정 — 주소만 있어도, 좌표만 있어도 된다")
    void savableAcceptsAddressOrCoordinates() {
        assertAll(
                () -> assertTrue(LocationService.isSavable(loc("부산 진구", null, null))),
                () -> assertTrue(LocationService.isSavable(loc(null, 35.15, 129.05))));
    }

    @Test
    @DisplayName("저장 판정 — 한쪽만 채운 좌표는 주소가 있어도 거절")
    void savableRejectsHalfCoordinates() {
        assertAll(
                () -> assertFalse(LocationService.isSavable(loc("부산 진구", 35.15, null))),
                () -> assertFalse(LocationService.isSavable(loc("부산 진구", null, 129.05))));
    }

    @Test
    @DisplayName("저장 판정 — 범위 밖 좌표는 거절, 경계값(±90·±180)은 허용")
    void savableChecksCoordinateRange() {
        assertAll(
                () -> assertFalse(LocationService.isSavable(loc(null, 90.01, 129.0))),
                () -> assertFalse(LocationService.isSavable(loc(null, -90.01, 129.0))),
                () -> assertFalse(LocationService.isSavable(loc(null, 35.0, 180.01))),
                () -> assertFalse(LocationService.isSavable(loc(null, 35.0, -180.01))),
                () -> assertTrue(LocationService.isSavable(loc(null, 90.0, 180.0))),
                () -> assertTrue(LocationService.isSavable(loc(null, -90.0, -180.0))));
    }

    // ── 국내 좌표 안내 ─────────────────────────────────────────

    @Test
    @DisplayName("국내 판정 — 네 경계가 모두 포함이고, 하나만 벗어나도 거짓")
    void koreaBoundsAreInclusive() {
        assertAll(
                () -> assertTrue(LocationService.isInKorea(33.0, 124.0)),
                () -> assertTrue(LocationService.isInKorea(39.0, 132.0)),
                () -> assertTrue(LocationService.isInKorea(35.15, 129.05)),
                () -> assertFalse(LocationService.isInKorea(32.99, 129.0)),
                () -> assertFalse(LocationService.isInKorea(39.01, 129.0)),
                () -> assertFalse(LocationService.isInKorea(35.0, 123.99)),
                () -> assertFalse(LocationService.isInKorea(35.0, 132.01)));
    }

    // ── 좌표 입력 해석 ─────────────────────────────────────────

    @Test
    @DisplayName("좌표 해석 — 빈칸·숫자 아님은 null, 양끝 공백은 눈감아 준다")
    void parseCoordinateForgivesWhitespaceOnly() {
        assertAll(
                () -> assertNull(LocationService.parseCoordinate(null)),
                () -> assertNull(LocationService.parseCoordinate("   ")),
                () -> assertNull(LocationService.parseCoordinate("삼십오도")),
                () -> assertEquals(35.5, LocationService.parseCoordinate(" 35.5 ")),
                () -> assertEquals(-129.05, LocationService.parseCoordinate("-129.05")));
    }

    // ── 거리 표기 눈금 ─────────────────────────────────────────

    @Test
    @DisplayName("거리 표기 — 1km 미만은 m 반올림, 그 위는 km 소수 한 자리")
    void distanceScaleSwitchesAtOneKilometer() {
        assertAll(
                () -> assertEquals("0m", LocationService.formatDistance(0)),
                () -> assertEquals("450m", LocationService.formatDistance(449.6)),
                () -> assertEquals("999m", LocationService.formatDistance(999.4)),
                () -> assertEquals("1.0km", LocationService.formatDistance(1000)),
                () -> assertEquals("1.2km", LocationService.formatDistance(1240)),
                () -> assertEquals("15.4km", LocationService.formatDistance(15_440)));
    }

    // ── 화면 한 줄 요약 ────────────────────────────────────────

    @Test
    @DisplayName("위치 요약 — 이름·주소·좌표 순으로 있는 것부터 보여 준다")
    void describePicksBestAvailableText() {
        assertAll(
                () -> assertEquals("집 · 부산 진구",
                        new LocationDto("집", "", "부산 진구", null, null).describe()),
                () -> assertEquals("집",
                        new LocationDto("집", "", "  ", null, null).describe()),
                () -> assertEquals("부산 진구",
                        new LocationDto(" ", "", "부산 진구", null, null).describe()),
                () -> assertEquals("위도 35.15780, 경도 129.05940",
                        new LocationDto(null, null, null, 35.1578, 129.0594).describe()),
                () -> assertEquals("이름 없는 위치",
                        new LocationDto(null, null, null, null, null).describe()));
    }
}
