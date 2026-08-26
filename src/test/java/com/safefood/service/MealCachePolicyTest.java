package com.safefood.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 캐시 갱신 정책 — 날짜 계산뿐이라 파일도 네트워크도 없이 시험합니다.
 */
class MealCachePolicyTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 26);
    private static final YearMonth THIS_MONTH = YearMonth.of(2026, 8);
    private static final YearMonth LAST_MONTH = YearMonth.of(2026, 7);
    private static final YearMonth NEXT_MONTH = YearMonth.of(2026, 9);

    @Test
    @DisplayName("지난 달은 다시 받지 않는다 — 지난 급식은 바뀌지 않는다")
    void pastMonthNeverStale() {
        assertFalse(MealCache.isStale(LAST_MONTH, LocalDate.of(2026, 7, 3), TODAY));
        assertFalse(MealCache.isStale(YearMonth.of(2025, 3), LocalDate.of(2025, 3, 9), TODAY));
    }

    @Test
    @DisplayName("이번 달은 달력일이 바뀌면 다시 받는다")
    void currentMonthRefreshesDaily() {
        assertFalse(MealCache.isStale(THIS_MONTH, TODAY, TODAY), "오늘 받았으면 그대로 씁니다");
        assertTrue(MealCache.isStale(THIS_MONTH, TODAY.minusDays(1), TODAY), "어제 받았으면 다시");
    }

    @Test
    @DisplayName("TTL 24시간이 아니라 달력일 기준 — 어젯밤에 받았어도 오늘 아침엔 다시 받는다")
    void calendarDayNotTwentyFourHours() {
        // 부모가 급식을 확인하는 시간이 아침이라, TTL 24시간이면 오늘 아침 내내 어제 정보를 봅니다
        assertTrue(MealCache.isStale(THIS_MONTH, TODAY.minusDays(1), TODAY));
    }

    @Test
    @DisplayName("다음 달도 하루 한 번 — 새로 올라오는 식단을 받아야 한다")
    void futureMonthRefreshesDaily() {
        assertTrue(MealCache.isStale(NEXT_MONTH, TODAY.minusDays(1), TODAY));
        assertFalse(MealCache.isStale(NEXT_MONTH, TODAY, TODAY));
    }

    @Test
    @DisplayName("저장해 둔 것이 없으면 받아야 한다")
    void missingCacheIsStale() {
        assertTrue(MealCache.isStale(THIS_MONTH, null, TODAY));
        assertTrue(MealCache.isStale(null, TODAY, TODAY));
    }

    @Test
    @DisplayName("빈 응답은 이번 달·다음 달이면 저장하지 않는다")
    void emptyFutureResponseIsNotCached() {
        // 학교 절반이 5일치만 올려 둡니다 — '아직 없음'을 하루 캐시하면 새 식단을 못 봅니다
        assertFalse(MealCache.cacheable(THIS_MONTH, false, TODAY));
        assertFalse(MealCache.cacheable(NEXT_MONTH, false, TODAY));
    }

    @Test
    @DisplayName("지난 달의 빈 응답은 저장해도 된다 — 그날은 진짜 급식이 없었다")
    void emptyPastResponseIsCacheable() {
        assertTrue(MealCache.cacheable(LAST_MONTH, false, TODAY));
    }

    @Test
    @DisplayName("내용이 있으면 언제든 저장한다")
    void responseWithDataIsCacheable() {
        assertTrue(MealCache.cacheable(THIS_MONTH, true, TODAY));
        assertTrue(MealCache.cacheable(NEXT_MONTH, true, TODAY));
        assertTrue(MealCache.cacheable(LAST_MONTH, true, TODAY));
    }
}
