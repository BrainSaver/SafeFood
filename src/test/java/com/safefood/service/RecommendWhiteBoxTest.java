package com.safefood.service;

import com.safefood.dao.AllergyDao.MenuAllergies;
import com.safefood.dao.MenuDao.MenuDto;
import com.safefood.dto.LocationDto;
import com.safefood.dto.RecommendationCard;
import com.safefood.dto.Safety;
import com.safefood.service.RecommendService.RecommendResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 추천 엔진에서 DB 없이 닿는 나머지 분기들 — 기존 {@code RecommendServiceTest} ·
 * {@code RecommendSortTest} 가 지나간 길을 빼고, 사유 문구의 기분 태그 조합,
 * 룰렛의 가장자리, 카드 변환의 대체 문구, 비공개 기분 매칭 헬퍼까지 마저 봅니다.
 *
 * <p>{@code findMatchedMood} 는 비공개지만 3단계 매칭(DB 태그 → 연관 키워드 → 접미어
 * 정제)이 다 여기 있어, 리플렉션으로 직접 두드립니다.
 */
class RecommendWhiteBoxTest {

    private final RecommendService service = new RecommendService();

    // ── 점수 계산의 남은 조합 ──────────────────────────────────

    @Test
    @DisplayName("점수 조합 — (선호O·기분X)=120, (선호X·기분X)=100")
    void remainingScoreCombinations() {
        assertAll(
                () -> assertEquals(120, service.calculateMenuScore(100, true, false)),
                () -> assertEquals(100, service.calculateMenuScore(100, false, false)));
    }

    // ── 사유 문구의 기분 태그 분기 ─────────────────────────────

    @Test
    @DisplayName("사유 문구 — 기분 태그가 있는 세 갈래(선호X안전·선호X주의·선호O주의)")
    void moodPhraseReachesEveryBranch() {
        String tag = "속풀이·해장";

        String plainSafe = service.generateRecommendReason("한식", false, false, tag);
        String plainRisky = service.generateRecommendReason("한식", false, true, tag);
        String preferredRisky = service.generateRecommendReason("한식", true, true, tag);

        assertAll(
                () -> assertTrue(plainSafe.contains(tag) && plainSafe.contains("안심"),
                        plainSafe),
                () -> assertTrue(plainRisky.contains(tag) && plainRisky.contains("주의가 필요"),
                        plainRisky),
                () -> assertTrue(preferredRisky.contains(tag) && preferredRisky.contains("혼입"),
                        preferredRisky));
    }

    @Test
    @DisplayName("빈 기분 태그는 없는 것과 같다 — '태그' 문구가 끼어들지 않는다")
    void blankMoodBehavesLikeNull() {
        String reason = service.generateRecommendReason("한식", false, false, "   ");

        assertAll(
                () -> assertEquals(service.generateRecommendReason("한식", false, false), reason),
                () -> assertFalse(reason.contains("태그")));
    }

    // ── 룰렛의 가장자리 ────────────────────────────────────────

    @Test
    @DisplayName("룰렛 — null·빈 목록·전원 차단이면 추첨하지 않는다")
    void rouletteRefusesWhenNothingSafe() {
        assertAll(
                () -> assertNull(service.pickRouletteMenu(null)),
                () -> assertNull(service.pickRouletteMenu(List.of())),
                () -> assertNull(service.pickRouletteMenu(List.of(
                        result("차단1", true, false), result("차단2", true, false)))));
    }

    @Test
    @DisplayName("룰렛 — 차단 메뉴는 상위 N 을 세는 데도 끼지 못한다")
    void rouletteCountsTopNAmongSafeOnly() {
        // 차단 메뉴가 맨 앞이어도, topN=1 은 '안전한 것 중 1등'이어야 합니다
        List<RecommendResult> list = List.of(
                result("차단", true, false), result("안전1등", false, false),
                result("안전2등", false, false));

        assertEquals("안전1등", service.pickRouletteMenu(list, 1).getMenuName());
    }

    @Test
    @DisplayName("룰렛 — topN 이 0·음수·정원 초과면 안전 후보 전체에서 뽑는다")
    void rouletteWidensToAllSafeCandidates() {
        List<RecommendResult> list = List.of(
                result("안전1", false, false), result("안전2", false, false));
        Set<String> safeNames = Set.of("안전1", "안전2");

        for (int topN : new int[]{0, -3, 99}) {
            RecommendResult pick = service.pickRouletteMenu(list, topN);
            assertTrue(safeNames.contains(pick.getMenuName()), "topN=" + topN);
        }
    }

    // ── 정보 미흡 판정의 조합 ──────────────────────────────────

    @Test
    @DisplayName("정보 미흡 — DB 정보도 추정 정보도 없을 때만 참")
    void insufficiencyNeedsBothSourcesEmpty() {
        MenuDto menu = new MenuDto(1, 1, "시그니처 플래터", 25000, "양식", 0);

        assertAll(
                () -> assertTrue(service.isInformationInsufficient(menu, null, Set.of()),
                        "DB 조회값 자체가 없고 추정도 없으면 미흡"),
                () -> assertTrue(service.isInformationInsufficient(menu, MenuAllergies.EMPTY, null),
                        "추정 결과가 null 이어도 미흡"),
                () -> assertFalse(service.isInformationInsufficient(menu,
                        new MenuAllergies(List.of(1), List.of()), Set.of()), "원료 정보가 있으면 충분"),
                () -> assertFalse(service.isInformationInsufficient(menu,
                        new MenuAllergies(List.of(), List.of(2)), Set.of()), "혼입 정보만 있어도 충분"),
                () -> assertFalse(service.isInformationInsufficient(menu,
                        MenuAllergies.EMPTY, Set.of(3)), "이름에서 추정됐으면 충분"));
    }

    @Test
    @DisplayName("알레르기 추정 — null 메뉴와 아무 단서 없는 이름은 빈 집합")
    void deduceHandlesNullAndCluelessNames() {
        assertAll(
                () -> assertTrue(service.deduceAllergiesFromText(null).isEmpty()),
                () -> assertTrue(service.deduceAllergiesFromText(
                        new MenuDto(1, 1, "브리또", 9000, "멕시칸", 0)).isEmpty()));
    }

    // ── UI 카드 변환 ───────────────────────────────────────────

    @Test
    @DisplayName("카드 변환 — 안전 등급은 차단 > 혼입 > 안전 순서로 정해진다")
    void cardSafetyRanksBlockedFirst() {
        assertAll(
                () -> assertEquals(Safety.CONTAINS,
                        card(result("차단", true, true)).safety(), "차단이면 감점 여부와 무관"),
                () -> assertEquals(Safety.POSSIBLE, card(result("혼입", false, true)).safety()),
                () -> assertEquals(Safety.SAFE, card(result("안전", false, false)).safety()));
    }

    @Test
    @DisplayName("카드 변환 — 빈 값들은 대체 문구로 채워진다")
    void cardFillsFallbackTexts() {
        RecommendationCard card = card(new RecommendResult(
                1, 1, "메뉴", "", "한식", 0, 1, 100, false, "사유",
                "", "", 0.0, 0, false, null, null, null));

        assertAll(
                () -> assertEquals("안심 식당", card.restaurant()),
                () -> assertEquals("주소 정보 없음", card.address()),
                () -> assertEquals("영업시간 정보 없음", card.hours()),
                () -> assertEquals(4.5, card.rating(), "별점 미등록은 4.5로 보여 준다"),
                () -> assertEquals("위치 미등록", card.distance()));
    }

    @Test
    @DisplayName("카드 변환 — 실제 값이 있으면 대체 문구가 끼어들지 않는다")
    void cardKeepsRealValues() {
        RecommendationCard card = new RecommendResult(
                1, 1, "메뉴", "전주집", "한식", 9000, 1, 100, false, "사유",
                "전주시 완산구", "09:00 ~ 21:00", 3.8, 12, false, null, null, null)
                .toRecommendation(2, null);

        assertAll(
                () -> assertEquals(2, card.rank()),
                () -> assertEquals("전주집", card.restaurant()),
                () -> assertEquals("전주시 완산구", card.address()),
                () -> assertEquals(3.8, card.rating()));
    }

    @Test
    @DisplayName("카드 거리 — 내 위치와 가게 좌표가 다 있을 때만 계산한다")
    void cardDistanceNeedsBothCoordinates() {
        LocationDto here = new LocationDto("집", "12345", "부산", 35.1578, 129.0594);
        LocationDto noCoords = new LocationDto("집", "12345", "부산", null, null);

        RecommendResult located = new RecommendResult(
                1, 1, "메뉴", "가게", "한식", 9000, 1, 100, false, "사유",
                "주소", "10:00", 4.0, 1, false, null, 35.1578, 129.0594);
        RecommendResult unlocated = new RecommendResult(
                1, 1, "메뉴", "가게", "한식", 9000, 1, 100, false, "사유",
                "주소", "10:00", 4.0, 1, false, null, null, null);

        assertAll(
                () -> assertEquals("0m", located.toRecommendation(1, here).distance(),
                        "같은 지점이면 0m"),
                () -> assertEquals("위치 미등록", located.toRecommendation(1, noCoords).distance(),
                        "내 좌표가 없으면 계산하지 않는다"),
                () -> assertEquals("위치 미등록", unlocated.toRecommendation(1, here).distance(),
                        "가게 좌표가 없으면 계산하지 않는다"));
    }

    // ── 내 위치·거리 필터 보조 판단 ─────────────────────────────

    @Test
    @DisplayName("거리 상한을 걸 수 있는지 — 좌표 있는 내 위치가 기준점")
    void distanceFilterNeedsAnchoredLocation() {
        assertAll(
                () -> assertFalse(RecommendService.canFilterByDistance(null)),
                () -> assertFalse(RecommendService.canFilterByDistance(
                        new LocationDto("집", "", "부산", null, null))),
                () -> assertTrue(RecommendService.canFilterByDistance(
                        new LocationDto("집", "", "부산", 35.1, 129.0))));
    }

    // ── 비공개 기분 매칭 헬퍼 (리플렉션) ────────────────────────

    private String findMatchedMood(MenuDto menu, List<String> moods) {
        try {
            Method method = RecommendService.class.getDeclaredMethod(
                    "findMatchedMood", MenuDto.class, List.class);
            method.setAccessible(true);
            return (String) method.invoke(service, menu, moods);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("findMatchedMood 호출 실패", e);
        }
    }

    @Test
    @DisplayName("기분 매칭 — 고른 태그가 없거나 메뉴가 없으면 null")
    void moodMatchingNeedsInputs() {
        MenuDto menu = new MenuDto(1, 1, "콩나물국밥", 8000, "한식", 1);

        assertAll(
                () -> assertNull(findMatchedMood(menu, null)),
                () -> assertNull(findMatchedMood(menu, List.of())),
                () -> assertNull(findMatchedMood(null, List.of("속풀이·해장"))));
    }

    @Test
    @DisplayName("기분 매칭 — null·공백 태그는 건너뛰고 다음 태그로 넘어간다")
    void moodMatchingSkipsBlankTags() {
        MenuDto menu = new MenuDto(1, 1, "콩나물국밥", 8000, "한식", 1);

        assertEquals("속풀이·해장",
                findMatchedMood(menu, Arrays.asList(null, "   ", "속풀이·해장")));
    }

    @Test
    @DisplayName("기분 매칭 1단계 — 메뉴에 달린 태그가 그대로 맞으면 사전 없이 끝")
    void moodMatchesDbTagFirst() {
        MenuDto menu = new MenuDto(1, 1, "아무거나", 9000, "기타", 0);
        menu.setMoods(List.of("가벼운 식사"));

        assertEquals("가벼운 식사", findMatchedMood(menu, List.of("가벼운 식사")));
    }

    @Test
    @DisplayName("기분 매칭 2단계 — 연관 키워드 사전이 이름과 설명 양쪽을 본다")
    void moodMatchesKeywordDictionary() {
        MenuDto byName = new MenuDto(1, 1, "얼큰 짬뽕", 9000, "중식", 3);
        MenuDto byDescription = new MenuDto(2, 1, "오늘의 스페셜", 9000, "기타", 0,
                "시원한 국물이 일품", "가게", "주소", "", "", 0.0, 0);

        assertAll(
                () -> assertEquals("속풀이·해장", findMatchedMood(byName, List.of("속풀이·해장"))),
                () -> assertEquals("속풀이·해장",
                        findMatchedMood(byDescription, List.of("속풀이·해장")),
                        "설명 글에서도 단서를 찾아야 합니다"));
    }

    @Test
    @DisplayName("기분 매칭 3단계 — 사전에 없는 태그는 접미어를 떼고 이름과 견준다")
    void moodMatchesByStrippedSuffix() {
        MenuDto menu = new MenuDto(1, 1, "달달버거", 7000, "양식", 0);

        assertEquals("달달 한끼", findMatchedMood(menu, List.of("달달 한끼")));
    }

    @Test
    @DisplayName("기분 매칭 4단계 — 메뉴 태그와 정제된 태그가 서로를 품어도 인정")
    void moodMatchesCrossContainedTags() {
        MenuDto menu = new MenuDto(1, 1, "브리또", 9000, "멕시칸", 0);
        menu.setMoods(List.of("해장"));

        assertEquals("속풀이·해장", findMatchedMood(menu, List.of("속풀이·해장")),
                "'속풀이·해장'을 정제한 '해장'이 메뉴 태그와 만나야 합니다");
    }

    @Test
    @DisplayName("기분 매칭 — 아무 단계에도 걸리지 않으면 null")
    void moodMatchingFallsThroughToNull() {
        MenuDto menu = new MenuDto(1, 1, "브리또", 9000, "멕시칸", 0);

        assertNull(findMatchedMood(menu, List.of("든든한 한끼")));
    }

    // ── 거들이 ─────────────────────────────────────────────────

    private static RecommendResult result(String name, boolean blocked, boolean hasPenalty) {
        return new RecommendResult(1, 1, name, "가게", "한식", 9000, 1,
                blocked ? 0 : 100, hasPenalty, "사유", "주소", "10:00",
                4.0, 1, blocked, blocked ? "대체" : null);
    }

    private static RecommendationCard card(RecommendResult result) {
        return result.toRecommendation(1, null);
    }
}
