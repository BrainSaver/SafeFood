package com.safefood.service;

import com.safefood.dao.GroupMenuDao;
import com.safefood.dao.GroupMenuDao.AllergySource;
import com.safefood.dao.GroupMenuDao.CandidateMenu;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class GroupRecommendService {

    private static final int BASE_SCORE = 100;
    private static final int UNKNOWN_PENALTY = 40;
    private static final int OVER_LIMIT_RANK = 1;

    private final GroupMenuDao groupMenuDao;

    public GroupRecommendService() {
        this.groupMenuDao = new GroupMenuDao();
    }

    public record GroupCondition(Set<String> allergies, int spicyMax, int budgetMax) {

        public GroupCondition {
            allergies = allergies == null ? Set.of() : Set.copyOf(allergies);
            spicyMax = Math.max(0, Math.min(5, spicyMax));
            budgetMax = Math.max(0, budgetMax);
        }
    }

    public record GroupCandidate(int menuId, String menu, String restaurant, int score, String reason) {

        @Override
        public String toString() {
            return String.format("[%s (%s)] 점수: %d점 | 사유: %s", menu, restaurant, score, reason);
        }
    }

    public record GroupResult(List<GroupCandidate> candidates, int scannedMenus, int blockedByAllergy,
                              boolean failed) {

        public static GroupResult empty() {
            return new GroupResult(List.of(), 0, 0, false);
        }

        /**
         * 메뉴를 <b>불러오지 못한</b> 경우 — 메뉴가 하나도 없는 경우와 구분해야 합니다.
         * 둘을 뭉뚱그리면 조회 실패일 때 검증 안 된 대체 후보가 나갑니다.
         */
        public static GroupResult failure() {
            return new GroupResult(List.of(), 0, 0, true);
        }

        public boolean hasMenuData() {
            return scannedMenus > 0;
        }

        public boolean allBlockedByAllergy() {
            return scannedMenus > 0 && candidates.isEmpty();
        }
    }

    public GroupResult recommend(GroupCondition condition, int limit) {
        if (condition == null || limit <= 0) {
            return GroupResult.empty();
        }

        List<CandidateMenu> menus;
        try {
            menus = groupMenuDao.findAllWithAllergies();
        } catch (SQLException e) {
            System.err.println("[group] 추천 후보 조회 실패: " + e.getMessage());
            return GroupResult.failure();
        }
        List<Scored> scored = new ArrayList<>();
        int blocked = 0;
        for (CandidateMenu menu : menus) {
            Scored candidate = score(menu, condition);
            if (candidate == null) {
                blocked++;
            } else {
                scored.add(candidate);
            }
        }

        scored.sort(Comparator.comparingInt(Scored::rankPenalty)
                .thenComparing(Comparator.comparingInt(Scored::score).reversed())
                .thenComparing(Comparator.comparingDouble((Scored s) -> s.menu().rating()).reversed())
                .thenComparingInt(s -> s.menu().price())
                .thenComparingInt(s -> s.menu().menuId()));

        return new GroupResult(pickDiverse(scored, limit), menus.size(), blocked, false);
    }

    private Scored score(CandidateMenu menu, GroupCondition condition) {
        if (!intersect(menu.containsAllergies(), condition.allergies()).isEmpty()
                || !intersect(menu.possibleAllergies(), condition.allergies()).isEmpty()) {
            return null;
        }

        boolean tooSpicy = menu.spicyLevel() > condition.spicyMax();
        boolean overBudget = condition.budgetMax() > 0 && menu.price() > condition.budgetMax();
        boolean unknownRisk = menu.allergySource() == AllergySource.NONE
                && !condition.allergies().isEmpty();

        int score = BASE_SCORE + (int) Math.round(menu.rating() * 4);
        if (!overBudget && condition.budgetMax() > 0 && menu.price() <= condition.budgetMax() * 0.8) {
            score += 5;
        }
        if (unknownRisk) {
            score -= UNKNOWN_PENALTY;
        }

        int rankPenalty = (tooSpicy ? OVER_LIMIT_RANK : 0)
                + (overBudget ? OVER_LIMIT_RANK : 0)
                + (unknownRisk ? OVER_LIMIT_RANK : 0);

        return new Scored(menu, Math.max(0, score), rankPenalty,
                buildReason(menu, condition, tooSpicy, overBudget, unknownRisk));
    }

    private static List<String> intersect(List<String> menuAllergies, Set<String> groupAllergies) {
        if (menuAllergies.isEmpty() || groupAllergies.isEmpty()) {
            return List.of();
        }
        List<String> hits = new ArrayList<>();
        for (String name : menuAllergies) {
            if (groupAllergies.contains(name)) {
                hits.add(name);
            }
        }
        return hits;
    }

    private static String buildReason(CandidateMenu menu, GroupCondition condition,
                                      boolean tooSpicy, boolean overBudget, boolean unknownRisk) {
        List<String> parts = new ArrayList<>();

        if (unknownRisk) {
            parts.add("알레르기 정보 없음 — 주문 전 직접 확인 필요");
        } else if (condition.allergies().isEmpty()) {
            parts.add("등록된 알레르기 없음");
        } else if (menu.allergySource() == AllergySource.DERIVED) {
            parts.add("전원 알레르기 안전 (재료 기반 추정)");
        } else {
            parts.add("전원 알레르기 안전");
        }

        parts.add(tooSpicy
                ? "매운맛 " + menu.spicyLevel() + "단계 — 병합 조건(" + condition.spicyMax() + "단계) 초과"
                : "매운맛 " + menu.spicyLevel() + "단계");

        parts.add(overBudget
                ? String.format("%,d원 — 예산(%,d원) 초과", menu.price(), condition.budgetMax())
                : String.format("%,d원", menu.price()));

        if (tooSpicy || overBudget) {
            parts.add("조건에 맞는 후보가 모자라 포함했어요");
        }
        return String.join(" · ", parts);
    }

    private static List<GroupCandidate> pickDiverse(List<Scored> scored, int limit) {
        List<GroupCandidate> picked = new ArrayList<>();
        Set<String> usedRestaurants = new LinkedHashSet<>();
        Set<Integer> usedMenuIds = new LinkedHashSet<>();

        for (Scored candidate : scored) {
            if (picked.size() >= limit) {
                break;
            }
            if (usedRestaurants.add(candidate.menu().restaurantName())) {
                usedMenuIds.add(candidate.menu().menuId());
                picked.add(candidate.toCandidate());
            }
        }
        for (Scored candidate : scored) {
            if (picked.size() >= limit) {
                break;
            }
            if (usedMenuIds.add(candidate.menu().menuId())) {
                picked.add(candidate.toCandidate());
            }
        }
        return List.copyOf(picked);
    }

    private record Scored(CandidateMenu menu, int score, int rankPenalty, String reason) {

        GroupCandidate toCandidate() {
            return new GroupCandidate(menu.menuId(), menu.menuName(), menu.restaurantName(),
                    score, reason);
        }
    }

    public static void main(String[] args) {
        GroupRecommendService service = new GroupRecommendService();

        print("조건 없음", service.recommend(new GroupCondition(Set.of(), 5, 0), 3));
        print("새우·땅콩 제외 / 맵기 1 / 8,000원",
                service.recommend(new GroupCondition(Set.of("새우", "땅콩"), 1, 8000), 3));
        print("대두·밀·돼지고기·계란·새우·토마토 제외",
                service.recommend(new GroupCondition(
                        Set.of("대두", "밀", "돼지고기", "계란", "새우", "토마토"), 5, 0), 3));
    }

    private static void print(String title, GroupResult result) {
        System.out.println("=== " + title + " ===");
        if (result.failed()) {
            System.out.println("  메뉴를 불러오지 못했습니다.");
            System.out.println();
            return;
        }
        System.out.printf("  훑은 메뉴 %d개 · 알레르기로 제외 %d개 · 후보 %d개%n",
                result.scannedMenus(), result.blockedByAllergy(), result.candidates().size());
        result.candidates().forEach(candidate -> System.out.println("  " + candidate));
        System.out.println();
    }
}
