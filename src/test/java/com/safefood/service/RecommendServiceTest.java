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
    @DisplayName("기분·컨디션 가산점(+25점) 적용 검증 (R-05)")
    void testCalculateMenuScoreWithMood() {
        int baseScore = 100;

        // 선호 O (+20), 기분 O (+25) -> 100 + 20 + 25 = 145점
        int score = recommendService.calculateMenuScore(baseScore, true, true);
        assertEquals(145, score);

        // 선호 X (+0), 기분 O (+25) -> 100 + 25 = 125점
        int scoreMoodOnly = recommendService.calculateMenuScore(baseScore, false, true);
        assertEquals(125, scoreMoodOnly);
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

    @Test
    @DisplayName("동적 크롤링 대응: 메뉴명 기반 알레르기 유발물질 자동 추출 검증")
    void testDeducedAllergiesFromText() {
        // 1. "불고기 두부 포케" -> 쇠고기(17), 대두(5) 자동 감지
        com.safefood.dao.MenuDao.MenuDto poke = new com.safefood.dao.MenuDao.MenuDto(
                101, 1, "불고기 두부 포케", 12000, "아시안", 0
        );
        java.util.Set<Integer> pokeAllergies = recommendService.deduceAllergiesFromText(poke);
        assertTrue(pokeAllergies.contains(17) || pokeAllergies.contains(16), "불고기는 쇠고기/돼지고기 알레르기 감지");
        assertTrue(pokeAllergies.contains(5), "두부는 대두 알레르기 감지");

        // 2. "치즈 돈까스" -> 돼지고기(16) + 복합룰(밀 6, 계란 2, 우유 1) 자동 감지
        com.safefood.dao.MenuDao.MenuDto tonkatsu = new com.safefood.dao.MenuDao.MenuDto(
                102, 1, "치즈 돈까스", 11000, "일식", 0
        );
        java.util.Set<Integer> tonkatsuAllergies = recommendService.deduceAllergiesFromText(tonkatsu);
        assertTrue(tonkatsuAllergies.contains(16), "돈까스는 돼지고기 감지");
        assertTrue(tonkatsuAllergies.contains(6), "돈까스 튀김옷 밀 감지");
        assertTrue(tonkatsuAllergies.contains(2), "돈까스 반죽 계란 감지");
        assertTrue(tonkatsuAllergies.contains(1), "치즈는 우유 감지");
    }

    @Test
    @DisplayName("가게만의 특색 메뉴명(성분 추정 불가) 정보 미흡 판정 검증")
    void testInformationInsufficient() {
        // DB 알레르기도 없고, 이름에서도 재료를 알 수 없는 특색 메뉴
        com.safefood.dao.MenuDao.MenuDto signature = new com.safefood.dao.MenuDao.MenuDto(
                103, 1, "시그니처 비밀 플래터", 25000, "양식", 0
        );
        java.util.Set<Integer> deduced = recommendService.deduceAllergiesFromText(signature);
        boolean isInsufficient = recommendService.isInformationInsufficient(
                signature,
                com.safefood.dao.AllergyDao.MenuAllergies.EMPTY,
                deduced
        );
        assertTrue(isInsufficient, "재료 및 알레르기 정보가 없는 특색 메뉴는 정보 미흡으로 판정");
    }
}
