package com.safefood.service;

import com.safefood.dao.AllergyCatalog;
import com.safefood.dao.AllergyDao;
import com.safefood.dao.AllergyDao.MenuAllergies;
import com.safefood.dao.HistoryDao;
import com.safefood.dao.MenuDao;
import com.safefood.dao.MenuDao.MenuDto;
import com.safefood.dao.ProfileDao;
import com.safefood.dto.PreferenceDto;
import com.safefood.dto.RecommendationCard;
import com.safefood.dto.Safety;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * [ SafeFood - 개인 맞춤 및 그룹 추천 엔진 서비스 ]
 * 담당자: 조영준
 * 
 * 구현 기능:
 * - R-01 (개인 추천 점수 계산 및 랭킹 정렬)
 * - R-02 (가격/카테고리/맵기 제약 필터링)
 * - R-03 (선호도·알레르기·기분 기반 추천 사유 문구 생성)
 * - R-04 (재추천 / 다른 추천 보기 시 직전 결과 제외)
 * - R-05 (기분·컨디션 태그 기반 가중치 +15점 부여)
 * - R-06 (히스토리 EATEN 기록 기반 전날/최근 메뉴 제외)
 * - R-07 (알레르기 차단 메뉴 감지 및 안전 대체 메뉴 추천)
 * - R-08 (상위 3~5개 추천 후보 무작위 룰렛 추첨)
 */
public class RecommendService {

    private final AllergyService allergyService;
    private final AllergyDao allergyDao;
    private final MenuDao menuDao;
    private final HistoryDao historyDao;
    private final ProfileDao profileDao;

    public RecommendService() {
        this.allergyService = new AllergyService();
        this.allergyDao = new AllergyDao();
        this.menuDao = new MenuDao();
        this.historyDao = new HistoryDao();
        this.profileDao = new ProfileDao();
    }

    /**
     * [추천 결과 DTO] UI 카드(SC-04) 및 비즈니스 연동용 데이터 객체
     */
    public static class RecommendResult {
        private final int menuId;
        private final int restaurantId;
        private final String menuName;
        private final String restaurantName;
        private final String category;
        private final int price;
        private final int spicyLevel;
        private final int finalScore;
        private final boolean hasPenalty;
        private final String reason;
        private final String address;
        private final String hours;
        private final double rating;
        private final int reviewCount;
        private final boolean blocked;
        private final String alternative;

        public RecommendResult(int menuId, int restaurantId, String menuName, String restaurantName,
                               String category, int price, int spicyLevel, int finalScore,
                               boolean hasPenalty, String reason, String address, String hours,
                               double rating, int reviewCount, boolean blocked, String alternative) {
            this.menuId = menuId;
            this.restaurantId = restaurantId;
            this.menuName = menuName;
            this.restaurantName = restaurantName;
            this.category = category;
            this.price = price;
            this.spicyLevel = spicyLevel;
            this.finalScore = finalScore;
            this.hasPenalty = hasPenalty;
            this.reason = reason;
            this.address = address;
            this.hours = hours;
            this.rating = rating;
            this.reviewCount = reviewCount;
            this.blocked = blocked;
            this.alternative = alternative;
        }

        public int getMenuId() { return menuId; }
        public int getRestaurantId() { return restaurantId; }
        public String getMenuName() { return menuName; }
        public String getRestaurantName() { return restaurantName; }
        public String getCategory() { return category; }
        public int getPrice() { return price; }
        public int getSpicyLevel() { return spicyLevel; }
        public int getFinalScore() { return finalScore; }
        public boolean isHasPenalty() { return hasPenalty; }
        public String getReason() { return reason; }
        public String getAddress() { return address; }
        public String getHours() { return hours; }
        public double getRating() { return rating; }
        public int getReviewCount() { return reviewCount; }
        public boolean isBlocked() { return blocked; }
        public String getAlternative() { return alternative; }

        /**
         * UI RecommendationCard 레코드로 변환
         */
        public RecommendationCard toRecommendation(int rank) {
            Safety safety = blocked ? Safety.CONTAINS
                    : (hasPenalty ? Safety.POSSIBLE : Safety.SAFE);
            return new RecommendationCard(
                    rank,
                    finalScore,
                    menuName,
                    (restaurantName != null && !restaurantName.isEmpty()) ? restaurantName : "안심 식당",
                    safety,
                    spicyLevel,
                    reason,
                    (address != null && !address.isEmpty()) ? address : "서울시 강남구 테헤란로",
                    (hours != null && !hours.isEmpty()) ? hours : "11:00 ~ 21:00",
                    rating > 0 ? rating : 4.5,
                    "450m",
                    blocked,
                    alternative
            );
        }

        @Override
        public String toString() {
            return String.format("[%s (%s - %s)] 점수: %d점 | 사유: %s | 가격: %,d원",
                    menuName, category, restaurantName, finalScore, reason, price);
        }
    }

    // =========================================================================
    // [R-05] 기분·컨디션 연관 시맨틱 키워드 사전 (DB 매핑 누락 방지용)
    // =========================================================================
    private static final Map<String, List<String>> MOOD_KEYWORDS = Map.of(
        "속풀이·해장", List.of(
            "국", "탕", "찌개", "짬뽕", "라멘", "라면", "해장", "우동", "쌀국수", "칼국수", "수제비", "국밥", "순두부", "북어", "콩나물", "얼큰", "시원"
        ),
        "가벼운 식사", List.of(
            "샐러드", "샌드위치", "김밥", "포케", "초밥", "롤", "토스트", "죽", "소바", "냉면", "우동", "베이글", "만두", "브런치"
        ),
        "든든한 한끼", List.of(
            "고기", "돈까스", "스테이크", "갈비", "삼겹살", "보쌈", "족발", "덮밥", "정식", "백반", "국밥", "제육", "불고기", "카레", "치킨", "피자", "버거", "구이", "찜"
        )
    );

    // =========================================================================
    // [R-01 & R-05] 점수 연산 로직 (기본 100점 + 선호 카테고리 +20점 + 기분 태그 +25점)
    // =========================================================================
    /**
     * [R-01 & R-05] 메뉴별 추천 점수 계산
     * - 기본점수 100점 시작
     * - 선호 카테고리 일치 시 +20점 가산 (R-01)
     * - 선택한 기분 태그 일치 시 +25점 가산 (R-05)
     * - 알레르기(CONTAINS / POSSIBLE) 메뉴는 앞단에서 100% 완전 차단되므로 감점 없이 안심 점수 산출
     */
    public int calculateMenuScore(int baseScore, boolean isPreferredCategory, boolean isMatchedMood) {
        int score = baseScore;

        // [R-01 가산점] 선호 카테고리 일치 시 +20점
        if (isPreferredCategory) {
            score += 20;
        }

        // [R-05 가산점] 선택한 기분 태그 매칭 시 +25점
        if (isMatchedMood) {
            score += 25;
        }

        return score;
    }

    /**
     * [R-01] 메뉴별 추천 점수 계산 (기분 태그 미적용 기본형)
     */
    public int calculateMenuScore(int baseScore, boolean isPreferredCategory) {
        return calculateMenuScore(baseScore, isPreferredCategory, false);
    }

    // =========================================================================
    // [R-03 & R-05] UI 카드 노출용 추천 사유 문구 세분화
    // =========================================================================
    /**
     * [R-03 & R-05] 추천 점수 산출 근거를 친절한 문장으로 생성
     * - 선호 카테고리 일치 여부 (O / X)
     * - 알레르기 교차오염 혼입 감점 여부 (O / X)
     * - 기분·컨디션 태그 매칭 여부 (O / X)
     */
    public String generateRecommendReason(String categoryName, boolean isPreferred, boolean hasPenalty, String matchedMood) {
        String moodPhrase = (matchedMood != null && !matchedMood.isBlank()) ? " '" + matchedMood + "' 태그와 어울리는" : "";
        if (isPreferred) {
            if (hasPenalty) {
                return "선호하시는 [" + categoryName + "] 메뉴이며" + moodPhrase + " 훌륭한 선택이지만, 조리 과정에서 알레르기 성분이 혼입되었을 수 있으니 주의하세요.";
            } else {
                return "회원님이 선호하시는 [" + categoryName + "] 메뉴이며" + moodPhrase + ", 알레르기 걱정 없이 안심하고 드실 수 있습니다!";
            }
        } else {
            if (hasPenalty) {
                return moodPhrase.isEmpty()
                        ? "알레르기 유발 성분의 교차 오염 가능성이 있으니 주의가 필요합니다."
                        : moodPhrase.trim() + " 메뉴이지만, 알레르기 교차 오염 가능성이 있으니 주의가 필요합니다.";
            } else {
                return moodPhrase.isEmpty()
                        ? "알레르기 유발 물질로부터 안전한 메뉴입니다."
                        : moodPhrase.trim() + " 알레르기 안심 메뉴입니다!";
            }
        }
    }

    /**
     * [R-03] 4단계 맞춤 사유 문구 생성 (기분 태그 미적용 하위 호환)
     */
    public String generateRecommendReason(String categoryName, boolean isPreferred, boolean hasPenalty) {
        return generateRecommendReason(categoryName, isPreferred, hasPenalty, null);
    }

    // =========================================================================
    // [R-05] 기분·컨디션 태그 매칭 판별 헬퍼
    // =========================================================================
    /**
     * [R-05] 사용자가 선택한 기분 태그와 메뉴 간의 일치 여부 판별
     * - 1차: DB menu_mood 테이블의 태그 매칭
     * - 2차: 연관 키워드 사전(MOOD_KEYWORDS) 기반 메뉴명 및 설명 매칭
     */
    private String findMatchedMood(MenuDto menu, List<String> selectedMoods) {
        if (selectedMoods == null || selectedMoods.isEmpty() || menu == null) {
            return null;
        }
        String menuName = menu.getName() != null ? menu.getName() : "";
        String desc = menu.getDescription() != null ? menu.getDescription() : "";

        for (String mood : selectedMoods) {
            if (mood == null || mood.isBlank()) continue;

            // 1. menu_mood DB 직접 일치
            if (menu.getMoods() != null && menu.getMoods().contains(mood)) {
                return mood;
            }

            // 2. 연관 키워드 사전 매칭 (예: "콩나물국밥" -> "국밥" 감지 시 '속풀이·해장' 매칭)
            List<String> keywords = MOOD_KEYWORDS.get(mood);
            if (keywords != null) {
                for (String kw : keywords) {
                    if (menuName.contains(kw) || desc.contains(kw)) {
                        return mood;
                    }
                }
            }

            // 3. 키워드 기반 일치 정제 ("속풀이·해장" -> "해장")
            String clean = mood.replace(" 해소", "").replace("속풀이·", "").replace(" 한끼", "").replace(" 식사", "").trim();
            if (menuName.contains(clean) || desc.contains(clean)) {
                return mood;
            }
            if (menu.getMoods() != null) {
                for (String m : menu.getMoods()) {
                    if (m.contains(clean) || mood.contains(m)) {
                        return mood;
                    }
                }
            }
        }
        return null;
    }

    // =========================================================================
    // [R-01 & R-07] 실시간 크롤링 대응 식약처 19종 알레르기 연관 키워드 사전
    // =========================================================================
    private static final Map<String, List<String>> ALLERGY_KEYWORDS = Map.ofEntries(
        Map.entry("쇠고기", List.of("소고기", "쇠고기", "불고기", "스테이크", "비프", "한우", "차돌", "양지", "우삼겹", "갈비탕", "육회", "도가니", "비프버거", "함박", "규동", "와규")),
        Map.entry("돼지고기", List.of("돼지", "돈까스", "돈가스", "돈육", "제육", "삼겹살", "목살", "보쌈", "족발", "베이컨", "햄", "소시지", "순대", "카츠", "포크", "차슈", "부타동", "탕수육", "동파육")),
        Map.entry("닭고기", List.of("닭", "치킨", "닭갈비", "삼계탕", "백숙", "찜닭", "치킨까스", "가라아게", "닭발", "오리", "야키토리", "오야코동", "닭볶음탕")),
        Map.entry("대두", List.of("두부", "된장", "청국장", "간장", "콩", "순두부", "유부", "비지", "미소", "포케", "나또", "낫또", "인절미")),
        Map.entry("새우", List.of("새우", "쉬림프", "새우튀김", "간장새우", "감바스", "멘보샤", "새우까스", "에비")),
        Map.entry("게", List.of("게장", "간장게장", "양념게장", "꽃게", "크랩", "게살")),
        Map.entry("오징어", List.of("오징어", "한치", "문어", "낙지", "쭈꾸미", "타코야끼", "타코")),
        Map.entry("조개류", List.of("조개", "바지락", "홍합", "굴", "전복", "꼬막", "가리비", "소라", "봉골레", "백합", "재첩")),
        Map.entry("고등어", List.of("고등어", "삼치", "꽁치")),
        Map.entry("우유", List.of("치즈", "우유", "버터", "크림", "라떼", "요거트", "연유", "리코타", "모짜렐라", "파마산", "치즈카츠", "치즈돈까스")),
        Map.entry("계란", List.of("계란", "달걀", "에그", "오믈렛", "에그타르트", "지단", "마요네즈", "카스테라")),
        Map.entry("밀", List.of("라면", "우동", "파스타", "스파게티", "피자", "빵", "버거", "만두", "칼국수", "짜장면", "짬뽕", "튀김", "토스트", "와플", "소면", "당면", "국수", "수제비", "뇨끼")),
        Map.entry("메밀", List.of("메밀", "소바", "막국수", "평양냉면")),
        Map.entry("땅콩", List.of("땅콩", "피넛")),
        Map.entry("호두", List.of("호두", "월넛")),
        Map.entry("잣", List.of("잣")),
        Map.entry("토마토", List.of("토마토", "케첩", "살사")),
        Map.entry("복숭아", List.of("복숭아")),
        Map.entry("아황산류", List.of("아황산", "와인"))
    );

    /**
     * [R-01 & R-07] 메뉴명 및 설명 텍스트를 분석하여 알레르기 유발물질 ID 목록 자동 추출 (실시간 크롤링 대응)
     */
    public Set<Integer> deduceAllergiesFromText(MenuDto menu) {
        if (menu == null) return Set.of();
        Set<Integer> deduced = new HashSet<>();
        String name = menu.getName() != null ? menu.getName() : "";
        String desc = menu.getDescription() != null ? menu.getDescription() : "";
        String text = name + " " + desc;

        for (Map.Entry<String, List<String>> entry : ALLERGY_KEYWORDS.entrySet()) {
            String allergyName = entry.getKey();
            for (String keyword : entry.getValue()) {
                if (text.contains(keyword)) {
                    Integer id = AllergyCatalog.idOf(allergyName);
                    if (id != null) {
                        deduced.add(id);
                    }
                    break;
                }
            }
        }

        // 복합 조리법(Compound Recipe) 룰 확장
        // 1. 튀김/까스류 -> 밀, 계란 기본 포함
        if (text.contains("돈까스") || text.contains("돈가스") || text.contains("카츠")
                || text.contains("치킨") || text.contains("튀김") || text.contains("전") || text.contains("부침")) {
            addAllergyIfAvailable(deduced, "밀");
            addAllergyIfAvailable(deduced, "계란");
        }
        // 2. 양식/베이커리류 -> 밀, 우유 기본 포함
        if (text.contains("파스타") || text.contains("피자") || text.contains("버거")
                || text.contains("샌드위치") || text.contains("토스트") || text.contains("라자냐") || text.contains("그라탕")) {
            addAllergyIfAvailable(deduced, "밀");
            addAllergyIfAvailable(deduced, "우유");
        }
        // 3. 찌개/탕/국밥류 -> 대두 기본 포함
        if (text.contains("찌개") || text.contains("탕") || text.contains("국밥")) {
            addAllergyIfAvailable(deduced, "대두");
        }

        return deduced;
    }

    private void addAllergyIfAvailable(Set<Integer> set, String allergyName) {
        Integer id = AllergyCatalog.idOf(allergyName);
        if (id != null) {
            set.add(id);
        }
    }

    /**
     * 가게만의 특색 메뉴명으로 작성되어 재료/알레르기 추정이 불가능한지 검사
     */
    public boolean isInformationInsufficient(MenuDto menu, MenuAllergies dbAllergies, Set<Integer> deducedAllergies) {
        boolean hasDbInfo = (dbAllergies != null) && (!dbAllergies.contains().isEmpty() || !dbAllergies.possible().isEmpty());
        boolean hasDeducedInfo = (deducedAllergies != null && !deducedAllergies.isEmpty());
        return !hasDbInfo && !hasDeducedInfo;
    }

    // =========================================================================
    // [R-07] 알레르기 차단 메뉴 감지 및 대체 메뉴(Alternative) 추천
    // =========================================================================
    /**
     * [R-07] CONTAINS 또는 POSSIBLE 알레르기로 차단된 메뉴에 대해 동일 식당/카테고리의 안전한 대안 메뉴 제안
     */
    private RecommendResult createBlockedRecommendationWithAlternative(MenuDto blockedMenu,
                                                                        List<Integer> userAllergies,
                                                                        List<MenuDto> allMenus,
                                                                        Map<Integer, MenuAllergies> allergiesByMenu) {
        MenuAllergies blockedAllergies =
                allergiesByMenu.getOrDefault(blockedMenu.getId(), MenuAllergies.EMPTY);
        Set<Integer> menuContains = new HashSet<>(blockedAllergies.contains());
        menuContains.addAll(deduceAllergiesFromText(blockedMenu));
        List<Integer> menuPossible = blockedAllergies.possible();

        List<String> blockedAllergyNames = new ArrayList<>();
        boolean isPossibleOnly = true;

        for (Integer allergyId : userAllergies) {
            // 이름은 allergy 테이블에서 찾습니다 — 목록 순서로 추측하면 다른 알레르기 이름이 나옵니다
            String allergyName = AllergyCatalog.nameOf(allergyId);
            if (allergyName == null) {
                continue;
            }
            if (menuContains.contains(allergyId)) {
                blockedAllergyNames.add(allergyName);
                isPossibleOnly = false;
            } else if (menuPossible.contains(allergyId)) {
                blockedAllergyNames.add(allergyName + "(혼입우려)");
            }
        }
        String blockedReason;
        if (blockedAllergyNames.isEmpty()) {
            blockedReason = "알레르기 안전 기준에 부합하지 않아 추천에서 제외되었습니다.";
        } else if (isPossibleOnly) {
            blockedReason = "알레르기 유발물질(" + String.join(", ", blockedAllergyNames) + ")의 조리 과정 교차오염 및 혼입 가능성이 있어 추천에서 제외되었습니다.";
        } else {
            blockedReason = allergyService.createBlockedReason(blockedAllergyNames);
        }

        // 같은 식당 또는 동일 카테고리의 대체 메뉴 탐색 (CONTAINS와 POSSIBLE 모두 겹치지 않는 메뉴)
        //
        // 알레르기 정보가 <b>하나도 없는</b> 메뉴는 여기서 제외합니다. isSafe 는 아는 것이
        // 없어도 true 를 돌려주므로, 거르지 않으면 재료를 전혀 모르는 메뉴를 '안심 메뉴'라고
        // 이름 붙여 권하게 됩니다 — 차단당한 사람에게 가장 하면 안 되는 안내입니다.
        String alternative = null;
        for (MenuDto other : allMenus) {
            if (other.getId() == blockedMenu.getId()) continue;
            MenuAllergies otherAllergies =
                    allergiesByMenu.getOrDefault(other.getId(), MenuAllergies.EMPTY);
            Set<Integer> otherDeduced = deduceAllergiesFromText(other);
            if (isInformationInsufficient(other, otherAllergies, otherDeduced)) {
                continue;
            }
            Set<Integer> otherContains = new HashSet<>(otherAllergies.contains());
            otherContains.addAll(otherDeduced);
            if (allergyService.isSafe(userAllergies, List.copyOf(otherContains))
                    && allergyService.isSafe(userAllergies, otherAllergies.possible())) {
                if (other.getRestaurantId() == blockedMenu.getRestaurantId()) {
                    alternative = "같은 식당의 안심 메뉴인 '" + other.getName() + "'(을)를 대신 드실 수 있습니다.";
                    break;
                } else if (other.getCategory().equals(blockedMenu.getCategory()) && alternative == null) {
                    alternative = "동일 카테고리의 알레르기 안심 메뉴 '" + other.getName() + "'(을)를 추천합니다.";
                }
            }
        }
        if (alternative == null) {
            alternative = "조리 시 해당 알레르기 유발 성분 제외를 요청하시거나 다른 안전 메뉴를 선택해 주세요.";
        }

        return new RecommendResult(
                blockedMenu.getId(),
                blockedMenu.getRestaurantId(),
                blockedMenu.getName(),
                blockedMenu.getRestaurantName(),
                blockedMenu.getCategory(),
                blockedMenu.getPrice(),
                blockedMenu.getSpicyLevel(),
                0,
                false,
                blockedReason,
                blockedMenu.getRestaurantAddress(),
                blockedMenu.getHours(),
                blockedMenu.getRating(),
                blockedMenu.getReviewCount(),
                true,
                alternative
        );
    }

    // =========================================================================
    // [R-01 ~ R-07] 고도화된 개인 맞춤 추천 메인 엔진
    // =========================================================================
    /**
     * [R-01 ~ R-07 통합 실행]
     * @param userId 회원 PK
     * @param preferredCategories 선호 카테고리 (+20점 가산)
     * @param selectedCategory 상단 카테고리 선택 필터 (R-02)
     * @param selectedMoods 선택한 기분 태그 목록 (+15점 가산, R-05)
     * @param maxSpicyLevel 맵기 상한선 필터 (R-02)
     * @param maxBudget 예산 상한선 필터 (R-02)
     * @param excludeEaten 히스토리 먹은 메뉴(EATEN) 제외 여부 (R-06)
     * @param excludeMenuIds 재추천(Reroll) 시 직전 노출 메뉴 ID 제외 (R-04)
     * @param includeBlockedCard 알레르기 차단 및 대체 메뉴 카드 포함 여부 (R-07)
     */
    public List<RecommendResult> getPersonalizedRecommendations(int userId,
                                                               List<String> preferredCategories,
                                                               String selectedCategory,
                                                               List<String> selectedMoods,
                                                               int maxSpicyLevel,
                                                               int maxBudget,
                                                               boolean excludeEaten,
                                                               Set<Integer> excludeMenuIds,
                                                               boolean includeBlockedCard) {
        List<RecommendResult> results = new ArrayList<>();
        List<MenuDto> blockedMenus = new ArrayList<>();

        List<Integer> userAllergies = (userId > 0) ? allergyDao.getUserAllergyIds(userId) : List.of();
        // [R-06] 전날/최근 먹은 메뉴(EATEN) 목록 조회 (전날/최근 식사 1일치만 자동 제외)
        List<Integer> eatenMenuIds = (excludeEaten && userId > 0)
                ? historyDao.getRecentEatenMenuIds(userId, 1)
                : List.of();
        List<String> effectivePreferredCategories = (preferredCategories != null)
                ? preferredCategories
                : ((userId > 0) ? profileDao.getCategories(userId) : List.of());

        List<MenuDto> candidateMenus = menuDao.getAllCandidateMenus();

        // 메뉴마다 묻지 않고 한 번에 받아 둡니다 — 예전에는 후보 1개당 커넥션 2개를 새로 맺어
        // (풀이 없습니다) 메뉴가 조금만 늘어도 화면이 몇 초씩 멎었습니다
        Map<Integer, MenuAllergies> allergiesByMenu = allergyDao.getAllMenuAllergies();

        for (MenuDto menu : candidateMenus) {
            // [R-04] 재추천 직전 노출 제외
            if (excludeMenuIds != null && excludeMenuIds.contains(menu.getId())) {
                continue;
            }

            // [R-06] 전날/최근 먹은 메뉴(EATEN) 제외
            if (excludeEaten && eatenMenuIds.contains(menu.getId())) {
                continue;
            }

            // [R-02] 카테고리 필터링
            if (selectedCategory != null && !selectedCategory.isBlank() && !"전체".equals(selectedCategory)) {
                if (!selectedCategory.equals(menu.getCategory())) {
                    continue;
                }
            }

            // [R-02] 맵기 상한선 필터링
            if (maxSpicyLevel > 0 && menu.getSpicyLevel() > maxSpicyLevel) {
                continue;
            }

            // [R-02] 예산 상한선 필터링
            if (maxBudget > 0 && menu.getPrice() > maxBudget) {
                continue;
            }

            MenuAllergies menuAllergies = allergiesByMenu.getOrDefault(menu.getId(), MenuAllergies.EMPTY);
            List<Integer> containsAllergies = menuAllergies.contains();
            List<Integer> possibleAllergies = menuAllergies.possible();

            // [동적 크롤링 대응] 메뉴명/설명 기반 알레르기 유발물질 자동 추출 및 정보 미흡 여부 판정
            Set<Integer> deducedAllergies = deduceAllergiesFromText(menu);
            boolean isInsufficient = isInformationInsufficient(menu, menuAllergies, deducedAllergies);

            // DB 알레르기 + 실시간 분석 알레르기 결합
            Set<Integer> combinedContains = new HashSet<>(containsAllergies);
            combinedContains.addAll(deducedAllergies);

            // [R-01 & R-07] CONTAINS(원재료 포함) 또는 POSSIBLE(혼입 가능성) 알레르기 성분 겹침 시 100% 완전 차단 (안전 제일주의)
            boolean isContainsUnsafe = !allergyService.isSafe(userAllergies, List.copyOf(combinedContains));
            boolean isPossibleUnsafe = !allergyService.isSafe(userAllergies, possibleAllergies);

            if (isContainsUnsafe || isPossibleUnsafe) {
                blockedMenus.add(menu);
                continue;
            }

            // [R-01] 선호 카테고리 매칭
            String category = (menu.getCategory() != null) ? menu.getCategory() : "";
            boolean isPreferred = (!category.isEmpty() && effectivePreferredCategories.contains(category));

            // [R-05] 기분 태그 매칭
            String matchedMood = findMatchedMood(menu, selectedMoods);
            boolean isMoodMatched = (matchedMood != null);

            // [R-01 & R-05] 추천 점수 연산 (100점 기본 + 선호 +20점 + 기분 +25점)
            //
            // 여기까지 온 메뉴는 '아는 알레르겐이 겹치지 않는' 메뉴이지, 확인된 안전 메뉴가
            // 아닙니다. 알레르기가 있는 사람에게는 <b>재료를 아는 메뉴</b>가 먼저 보여야 하므로,
            // 정보 미흡 메뉴는 확인된 메뉴 아래로 내립니다 (선호 +20 · 기분 +25 를 합친 것보다
            // 크게 깎아, 가산점이 붙어도 뒤집히지 않게 합니다).
            int baseScore = 100;
            int unknownPenalty = (isInsufficient && !userAllergies.isEmpty()) ? 50 : 0;
            int finalScore = baseScore + (isPreferred ? 20 : 0) + (isMoodMatched ? 25 : 0) - unknownPenalty;

            // [R-03 & R-05] 사유 문구 생성 (정보 미흡 특색 메뉴인 경우 명확히 경고/안내)
            String reason;
            if (isInsufficient) {
                reason = (!userAllergies.isEmpty())
                        ? "!![정보 미흡] 상세 식재료 및 알레르기 유발물질 정보가 확인되지 않은 특색 메뉴입니다. (주의 필요)"
                        : "!![정보 미흡] 상세 재료 정보가 등록되지 않은 특색 메뉴입니다.";
            } else {
                reason = generateRecommendReason(category.isEmpty() ? "추천" : category, isPreferred, false, matchedMood);
            }

            results.add(new RecommendResult(
                    menu.getId(),
                    menu.getRestaurantId(),
                    menu.getName(),
                    menu.getRestaurantName(),
                    menu.getCategory(),
                    menu.getPrice(),
                    menu.getSpicyLevel(),
                    finalScore,
                    false,
                    reason,
                    menu.getRestaurantAddress(),
                    menu.getHours(),
                    menu.getRating(),
                    menu.getReviewCount(),
                    false,
                    null
            ));
        }

        // [R-01] 점수 내림차순 정렬 (동점 시 별점 높은 순)
        results.sort(Comparator.comparingInt(RecommendResult::getFinalScore).reversed()
                .thenComparing(Comparator.comparingDouble(RecommendResult::getRating).reversed()));

        // [R-07] 대체 메뉴 제안 카드 추가 (최대 1건)
        if (includeBlockedCard && !blockedMenus.isEmpty()) {
            MenuDto representativeBlocked = blockedMenus.get(0);
            for (MenuDto b : blockedMenus) {
                if (preferredCategories != null && preferredCategories.contains(b.getCategory())) {
                    representativeBlocked = b;
                    break;
                }
            }
            RecommendResult blockedCard = createBlockedRecommendationWithAlternative(
                    representativeBlocked, userAllergies, candidateMenus, allergiesByMenu);
            results.add(blockedCard);
        }

        return results;
    }

    /**
     * [R-01 & R-02 & R-06] 기본 맞춤 추천 편의 오버로딩
     */
    public List<RecommendResult> getPersonalizedRecommendations(int userId,
                                                               List<String> preferredCategories,
                                                               String selectedCategory,
                                                               int maxSpicyLevel,
                                                               int maxBudget,
                                                               boolean excludeEaten) {
        return getPersonalizedRecommendations(userId, preferredCategories, selectedCategory, null, maxSpicyLevel, maxBudget, excludeEaten, null, true);
    }

    /**
     * [R-01] 최소 파라미터 맞춤 추천 편의 오버로딩
     */
    public List<RecommendResult> getPersonalizedRecommendations(int userId, List<String> preferredCategories) {
        return getPersonalizedRecommendations(userId, preferredCategories, null, null, 5, 0, false, null, true);
    }

    /**
     * [R-01 ~ R-06] 세션 사용자 맞춤 추천 통합 메서드 (MainController 호출용)
     */
    public List<RecommendResult> getRecommendationsForUser(int userId, String selectedCategory, List<String> selectedMoods,
                                                          boolean excludeEaten, Set<Integer> excludeMenuIds) {
        PreferenceDto pref = profileDao.getPreference(userId);
        List<String> categories = profileDao.getCategories(userId);

        int maxSpicy = (pref != null && pref.getSpicyLevel() > 0) ? pref.getSpicyLevel() : 5;
        int maxBudget = (pref != null && pref.getPriceMax() > 0) ? pref.getPriceMax() : 0;

        return getPersonalizedRecommendations(userId, categories, selectedCategory, selectedMoods, maxSpicy, maxBudget, excludeEaten, excludeMenuIds, true);
    }

    /**
     * [R-01 ~ R-06] 세션 사용자 맞춤 추천 기본형
     */
    public List<RecommendResult> getRecommendationsForUser(int userId, String selectedCategory, boolean excludeEaten) {
        return getRecommendationsForUser(userId, selectedCategory, null, excludeEaten, null);
    }

    // =========================================================================
    // [R-08] 룰렛 추천 (상위 안전 후보 중 무작위 1개 추첨)
    // =========================================================================
    /**
     * [R-08] 상위 안전 추천 후보 N개 중 무작위 1개 추첨
     * - 알레르기로 차단된 메뉴는 추첨에서 제외
     */
    public RecommendResult pickRouletteMenu(List<RecommendResult> recommendations, int topN) {
        if (recommendations == null || recommendations.isEmpty()) {
            return null;
        }
        List<RecommendResult> safeCandidates = new ArrayList<>();
        for (RecommendResult r : recommendations) {
            if (!r.isBlocked()) {
                safeCandidates.add(r);
            }
        }
        if (safeCandidates.isEmpty()) {
            return null;
        }
        int limit = (topN <= 0 || topN > safeCandidates.size()) ? safeCandidates.size() : topN;
        int randomIndex = ThreadLocalRandom.current().nextInt(limit);
        return safeCandidates.get(randomIndex);
    }

    public RecommendResult pickRouletteMenu(List<RecommendResult> recommendations) {
        return pickRouletteMenu(recommendations, 0);
    }

    // =========================================================================
    // [실행 및 테스트] main 메서드
    // =========================================================================
    public static void main(String[] args) {
        RecommendService service = new RecommendService();
        List<String> myFavoriteCategories = List.of("한식", "일식");
        List<String> myMoods = List.of("스트레스 해소");

        System.out.println("=== 1. 개인 맞춤 추천 결과 (R-01, R-03, R-05, R-07) ===");
        List<RecommendResult> recommendations = service.getPersonalizedRecommendations(
                100, myFavoriteCategories, null, myMoods, 5, 0, false, null, true);
        for (RecommendResult result : recommendations) {
            System.out.println(result + (result.isBlocked() ? " [BLOCKED] 대체: " + result.getAlternative() : ""));
        }

        System.out.println("\n=== 2. 룰렛 추천 추첨 결과 (R-08) ===");
        RecommendResult roulettePick = service.pickRouletteMenu(recommendations, 5);
        System.out.println("당첨 메뉴: " + roulettePick);
    }
}