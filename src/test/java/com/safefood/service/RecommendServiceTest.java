package com.safefood.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RecommendServiceTest {

    private final RecommendService recommendService = new RecommendService();

    @Test
    @DisplayName("추천 점수 계산 (기본 100점 + 선호 카테고리 +20점)")
    void testCalculateMenuScore() {
        int baseScore = 100;

        // 선호 카테고리 O (+20) -> 100 + 20 = 120점
        int scorePreferred = recommendService.calculateMenuScore(baseScore, true);
        assertEquals(120, scorePreferred);

        // 선호 카테고리 X (+0) -> 100점
        int scoreNonPreferred = recommendService.calculateMenuScore(baseScore, false);
        assertEquals(100, scoreNonPreferred);
    }

    @Test
    @DisplayName("4단계 맞춤 사유 문구 생성 검증")
    void testGenerateRecommendReason() {
        // 1. 선호 O, 감점 O
        String r1 = recommendService.generateRecommendReason("한식", true, true);
        assertTrue(r1.contains("선호") && r1.contains("혼입"), "선호하지만 교차오염 주의 문구");

        // 2. 선호 O, 감점 X
        String r2 = recommendService.generateRecommendReason("일식", true, false);
        assertTrue(r2.contains("선호") && r2.contains("안심"), "선호하며 안심 문구");

        // 3. 선호 X, 감점 O
        String r3 = recommendService.generateRecommendReason("중식", false, true);
        assertTrue(r3.contains("주의가 필요"), "교차오염 주의 문구");

        // 4. 선호 X, 감점 X
        String r4 = recommendService.generateRecommendReason("양식", false, false);
        assertTrue(r4.contains("안전한"), "안전한 메뉴 문구");

        // 5. 기분 태그 반영 사유 (R-05)
        String r5 = recommendService.generateRecommendReason("일식", true, false, "속풀이·해장");
        assertTrue(r5.contains("속풀이·해장"), "기분 태그 사유 포함");
    }

    @Test
    @DisplayName("기분·컨디션 가산점(+15점) 적용 검증 (R-05)")
    void testCalculateMenuScoreWithMood() {
        int baseScore = 100;

        // 선호 O (+20), 기분 O (+15) -> 100 + 20 + 15 = 135점
        int score = recommendService.calculateMenuScore(baseScore, true, true);
        assertEquals(135, score);

        // 선호 X (+0), 기분 O (+15) -> 100 + 15 = 115점
        int scoreMoodOnly = recommendService.calculateMenuScore(baseScore, false, true);
        assertEquals(115, scoreMoodOnly);
    }

    @Test
    @DisplayName("룰렛 무작위 추첨 검증 (R-08)")
    void testPickRouletteMenu() {
        List<RecommendService.RecommendResult> list = List.of(
                new RecommendService.RecommendResult(1, 1, "메뉴A", "가게A", "한식", 8000, 1, 120, false, "사유A", "", "", 4.5, 10, false, null),
                new RecommendService.RecommendResult(2, 2, "메뉴B", "가게B", "일식", 9000, 2, 110, false, "사유B", "", "", 4.2, 5, false, null),
                new RecommendService.RecommendResult(3, 3, "메뉴C", "가게C", "중식", 10000, 3, 0, false, "사유C", "", "", 4.0, 1, true, "대체C")
        );

        RecommendService.RecommendResult pick = recommendService.pickRouletteMenu(list, 2);
        assertNotNull(pick);
        assertFalse(pick.isBlocked(), "차단된 메뉴는 룰렛 당첨에서 제외");
        assertTrue(pick.getMenuName().equals("메뉴A") || pick.getMenuName().equals("메뉴B"), "상위 2개 안전 메뉴 중 1개 선택");
    }
}
