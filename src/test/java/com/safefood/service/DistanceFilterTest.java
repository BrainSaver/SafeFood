package com.safefood.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 거리 계산과 눈금 — DB 없이 확인할 수 있는 부분만 봅니다.
 *
 * <p>거리 제한은 오래 "화면에만 있고 실제로는 걸리지 않는" 상태였습니다. 콤보에서 고른 값이
 * 조회로 넘어가지 않았기 때문인데, 그렇게 어긋나면 사용자는 500m 를 고르고 3km 가게를 봅니다.
 * 계산 자체가 맞는지 여기서 못박아 둡니다.
 */
class DistanceFilterTest {

    /** 부산 서면역 */
    private static final double SEOMYEON_LAT = 35.1578;
    private static final double SEOMYEON_LNG = 129.0594;

    /** 부산 동의대 가야캠퍼스 일대 — 실제 DB 의 식당들이 있는 곳 */
    private static final double GAYA_LAT = 35.1450;
    private static final double GAYA_LNG = 129.0300;

    @Test
    @DisplayName("같은 지점끼리는 0m")
    void samePointIsZero() {
        double meters = LocationService.calculateDistanceMeter(
                SEOMYEON_LAT, SEOMYEON_LNG, SEOMYEON_LAT, SEOMYEON_LNG);

        assertEquals(0.0, meters, 0.001);
    }

    @Test
    @DisplayName("서면역 → 가야동은 약 3km (화면에 뜬 2.9km 와 같은 값)")
    void knownDistanceIsAboutThreeKilometers() {
        double meters = LocationService.calculateDistanceMeter(
                SEOMYEON_LAT, SEOMYEON_LNG, GAYA_LAT, GAYA_LNG);

        // 실제 직선거리 약 2.9km — 계산이 어긋나면 거리 제한도 함께 어긋납니다
        assertTrue(meters > 2500 && meters < 3500,
                "서면역~가야동은 2.5~3.5km 사이여야 합니다 (계산값 " + Math.round(meters) + "m)");
    }

    @Test
    @DisplayName("거리는 방향과 무관하다")
    void distanceIsSymmetric() {
        double there = LocationService.calculateDistanceMeter(
                SEOMYEON_LAT, SEOMYEON_LNG, GAYA_LAT, GAYA_LNG);
        double back = LocationService.calculateDistanceMeter(
                GAYA_LAT, GAYA_LNG, SEOMYEON_LAT, SEOMYEON_LNG);

        assertEquals(there, back, 0.001);
    }

    @Test
    @DisplayName("500m 를 고르면 2.9km 가게는 상한을 넘는다")
    void gayaIsOutsideFiveHundredMeters() {
        double meters = LocationService.calculateDistanceMeter(
                SEOMYEON_LAT, SEOMYEON_LNG, GAYA_LAT, GAYA_LNG);

        assertAll(
                () -> assertTrue(meters > 500, "500m 상한을 넘어야 합니다"),
                () -> assertTrue(meters > 1000, "1km 상한도 넘어야 합니다"),
                () -> assertTrue(meters < 5000, "5km 로 늘리면 들어와야 합니다"));
    }

    @Test
    @DisplayName("거리 문구 — 1km 미만은 m, 넘으면 km")
    void formatSwitchesUnitAtOneKilometer() {
        assertAll(
                () -> assertEquals("450m", LocationService.formatDistance(450)),
                () -> assertEquals("999m", LocationService.formatDistance(999)),
                () -> assertEquals("1.0km", LocationService.formatDistance(1000)),
                () -> assertEquals("2.9km", LocationService.formatDistance(2900)));
    }
}
