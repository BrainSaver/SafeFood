package com.safefood.service;

import com.safefood.dto.RecommendationCard;
import com.safefood.service.RecommendService.RecommendResult;
import com.safefood.service.RecommendService.SortOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 추천 목록의 <b>차례</b>를 정하는 규칙 — DB 를 타지 않는 비교 규칙만 봅니다.
 *
 * <p>가격 정렬은 값을 견주려고 쓰는 기능이라, <b>맨 위 카드가 실제로 가장 싼 것</b>이어야
 * 의미가 있습니다. 가격이 등록되지 않은 메뉴(0)가 앞에 끼어들면 그 약속이 깨지므로
 * 여기서 못박아 둡니다.
 */
class RecommendSortTest {

    /** 이름·가격·점수·별점만 다른 결과 한 건. 나머지 칸은 정렬에 쓰이지 않습니다. */
    private static RecommendResult menu(String name, int price, int score, double rating) {
        return new RecommendResult(1, 1, name, "가게", "한식", price, 1, score,
                false, "사유", "", "", rating, 0, false, null);
    }

    private static List<String> order(SortOrder sortOrder, List<RecommendResult> menus) {
        List<RecommendResult> copy = new ArrayList<>(menus);
        copy.sort(RecommendService.comparatorFor(sortOrder));
        return copy.stream().map(RecommendResult::getMenuName).toList();
    }

    /** 가격이 뒤섞이고, 점수도 가격과 일부러 어긋나게 둔 한 벌. */
    private static List<RecommendResult> sample() {
        return List.of(
                menu("보통", 9000, 100, 4.0),
                menu("비쌈", 20000, 130, 4.9),
                menu("쌈", 6000, 90, 4.1));
    }

    @Test
    @DisplayName("추천순 — 점수 내림차순 (가격은 보지 않는다)")
    void recommendedOrdersByScore() {
        assertEquals(List.of("비쌈", "보통", "쌈"), order(SortOrder.RECOMMENDED, sample()));
    }

    @Test
    @DisplayName("추천순 — 점수가 같으면 별점 높은 쪽이 위")
    void recommendedBreaksTiesByRating() {
        List<RecommendResult> tied = List.of(
                menu("별점낮음", 8000, 100, 3.1),
                menu("별점높음", 9000, 100, 4.8));

        assertEquals(List.of("별점높음", "별점낮음"), order(SortOrder.RECOMMENDED, tied));
    }

    @Test
    @DisplayName("가격 낮은 순 — 점수가 높아도 비싸면 뒤로 간다")
    void priceLowOrdersByPrice() {
        assertEquals(List.of("쌈", "보통", "비쌈"), order(SortOrder.PRICE_LOW, sample()));
    }

    @Test
    @DisplayName("가격 높은 순")
    void priceHighOrdersByPrice() {
        assertEquals(List.of("비쌈", "보통", "쌈"), order(SortOrder.PRICE_HIGH, sample()));
    }

    @Test
    @DisplayName("가격이 등록되지 않은 메뉴는 어느 방향으로 정렬해도 맨 뒤")
    void unknownPriceAlwaysLast() {
        // 0 은 '공짜'가 아니라 '모름'입니다. 낮은 순에서 앞에 서면 맨 위 카드가 최저가가
        // 아니게 되고, 높은 순에서 앞에 서면 최고가가 아니게 됩니다.
        List<RecommendResult> withUnknown = List.of(
                menu("모름", 0, 140, 5.0),   // 점수·별점을 제일 높게 줘도 뒤로 가야 합니다
                menu("비쌈", 20000, 100, 4.0),
                menu("쌈", 6000, 100, 4.0));

        assertAll(
                () -> assertEquals(List.of("쌈", "비쌈", "모름"),
                        order(SortOrder.PRICE_LOW, withUnknown)),
                () -> assertEquals(List.of("비쌈", "쌈", "모름"),
                        order(SortOrder.PRICE_HIGH, withUnknown)));
    }

    @Test
    @DisplayName("가격이 같으면 추천 점수가 높은 쪽이 위")
    void samePriceFallsBackToScore() {
        List<RecommendResult> samePrice = List.of(
                menu("점수낮음", 9000, 100, 4.0),
                menu("점수높음", 9000, 130, 4.0));

        assertAll(
                () -> assertEquals(List.of("점수높음", "점수낮음"),
                        order(SortOrder.PRICE_LOW, samePrice)),
                () -> assertEquals(List.of("점수높음", "점수낮음"),
                        order(SortOrder.PRICE_HIGH, samePrice)));
    }

    @Test
    @DisplayName("정렬을 고르지 않았으면(null) 추천순으로 본다")
    void nullOrderFallsBackToRecommended() {
        assertEquals(order(SortOrder.RECOMMENDED, sample()), order(null, sample()));
    }

    @Test
    @DisplayName("콤보에 보이는 글자는 SortOrder 가 들고 있다")
    void sortOrderCarriesItsLabel() {
        assertAll(
                () -> assertEquals("추천순", SortOrder.RECOMMENDED.toString()),
                () -> assertEquals("가격 낮은 순", SortOrder.PRICE_LOW.toString()),
                () -> assertEquals("가격 높은 순", SortOrder.PRICE_HIGH.toString()));
    }

    @Test
    @DisplayName("가격 문구 — 등록되지 않았으면 0원이 아니라 '가격 정보 없음'")
    void priceTextTellsUnknownApart() {
        assertAll(
                () -> assertEquals("12,000원", RecommendationCard.priceText(12000)),
                () -> assertEquals("가격 정보 없음", RecommendationCard.priceText(0)),
                () -> assertEquals("가격 정보 없음", RecommendationCard.priceText(-1)));
    }
}
