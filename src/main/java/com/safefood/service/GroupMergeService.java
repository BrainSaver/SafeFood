package com.safefood.service;

import com.safefood.dao.GroupDao;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * [ SafeFood - 그룹 참여자 조건 병합 서비스 ]
 *
 * 담당자: 조영준
 * 관련 기능 ID: G-03 (그룹 조건 병합 알고리즘)
 */
public class GroupMergeService {

    private final GroupDao groupDao;

    public GroupMergeService() {
        this.groupDao = new GroupDao();
    }

    /**
     * [병합 결과 DTO] 그룹 조건 병합 결과를 담는 클래스
     */
    public static class MergedGroupCondition {
        private List<Integer> mergedAllergies;
        private int minSpicyLevel;
        private int maxBudget; // 0 = 제한 없음

        public MergedGroupCondition(List<Integer> mergedAllergies, int minSpicyLevel) {
            this(mergedAllergies, minSpicyLevel, 0);
        }

        public MergedGroupCondition(List<Integer> mergedAllergies, int minSpicyLevel, int maxBudget) {
            this.mergedAllergies = mergedAllergies;
            this.minSpicyLevel = minSpicyLevel;
            this.maxBudget = maxBudget;
        }

        public List<Integer> getMergedAllergies() { return mergedAllergies; }
        public int getMinSpicyLevel() { return minSpicyLevel; }
        public int getMaxBudget() { return maxBudget; }

        @Override
        public String toString() {
            String budgetStr = maxBudget > 0 ? String.format("%,d원 이하", maxBudget) : "제한없음";
            return String.format("[그룹 병합 결과] 알레르기 합집합: %s | 매운맛 최솟값: %d단계 | 예산: %s",
                    mergedAllergies, minSpicyLevel, budgetStr);
        }
    }

    // =========================================================================
    // 1. 단순 순수 자바 병합 로직
    // =========================================================================
    public List<Integer> mergeAllergies(List<List<Integer>> membersAllergies) {
        if (membersAllergies == null || membersAllergies.isEmpty()) {
            return new ArrayList<>();
        }

        Set<Integer> mergedSet = new HashSet<>();
        for (List<Integer> singleMemberAllergies : membersAllergies) {
            if (singleMemberAllergies != null) {
                mergedSet.addAll(singleMemberAllergies);
            }
        }
        return new ArrayList<>(mergedSet);
    }

    public int mergeSpicyLevel(List<Integer> memberSpicyLevels) {
        if (memberSpicyLevels == null || memberSpicyLevels.isEmpty()) {
            return 5;
        }

        int minSpicy = 5;
        for (Integer level : memberSpicyLevels) {
            if (level != null) {
                minSpicy = Math.min(minSpicy, level);
            }
        }
        return minSpicy;
    }

    /**
     * 예산(최대 가격) 목록 병합 (교집합 -> 참여자 중 가장 낮은 상한선 적용)
     * @param memberBudgets 참여자별 예산 목록 (0 또는 null은 제한 없음)
     * @return 병합된 예산 상한선 (모두 제한 없으면 0)
     */
    public int mergeBudget(List<Integer> memberBudgets) {
        if (memberBudgets == null || memberBudgets.isEmpty()) {
            return 0;
        }

        int minBudget = 0;
        for (Integer budget : memberBudgets) {
            if (budget != null && budget > 0) {
                if (minBudget == 0) {
                    minBudget = budget;
                } else {
                    minBudget = Math.min(minBudget, budget);
                }
            }
        }
        return minBudget;
    }

    // =========================================================================
    // 2. DB 데이터 기반 그룹 조건 통합 병합 실행 메서드
    // =========================================================================
    /**
     * 特定 그룹(groupId)의 참여자 조건들을 DB에서 조회하여 합집합 알레르기, 최솟값 매운맛, 예산 조건 생성
     */
    public MergedGroupCondition getMergedConditionForGroup(int groupId) {
        // 1. DB에서 참여자 알레르기 스냅샷 목록 조회 및 합집합 연산
        List<List<Integer>> membersAllergies = groupDao.getGroupMembersAllergies(groupId);
        List<Integer> mergedAllergies = mergeAllergies(membersAllergies);

        // 2. DB에서 참여자 매운맛 선호도 목록 조회 및 최솟값 연산
        List<Integer> spicyLevels = groupDao.getGroupMembersSpicyLevels(groupId);
        int minSpicyLevel = mergeSpicyLevel(spicyLevels);

        // 3. DB에서 참여자 예산 선호도 목록 조회 및 최솟값(교집합) 연산
        List<Integer> budgets = groupDao.getGroupMembersBudgets(groupId);
        int maxBudget = mergeBudget(budgets);

        return new MergedGroupCondition(mergedAllergies, minSpicyLevel, maxBudget);
    }

    // =========================================================================
    // [실행 및 테스트] main 메서드
    // =========================================================================
    public static void main(String[] args) {
        GroupMergeService service = new GroupMergeService();

        // 1. 순수 연산 테스트
        System.out.println("=== 1. 자바 병합 로직 테스트 ===");
        List<List<Integer>> memberAllergies = List.of(List.of(4), List.of(1, 2), List.of(4, 5));
        System.out.println("병합 알레르기: " + service.mergeAllergies(memberAllergies));

        List<Integer> budgets = List.of(10000, 15000, 8000, 0);
        System.out.println("병합 예산 (8000원 예상): " + service.mergeBudget(budgets) + "원");

        // 2. DB 통합 테스트 (그룹 ID 1번 기준)
        System.out.println("\n=== 2. DB 연동 그룹 조건 병합 테스트 ===");
        MergedGroupCondition result = service.getMergedConditionForGroup(1);
        System.out.println(result);
    }
}