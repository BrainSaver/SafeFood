package com.safefood.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GroupMergeServiceTest {

    private final GroupMergeService mergeService = new GroupMergeService();

    @Test
    @DisplayName("참여자 알레르기 합집합 병합 검증")
    void testMergeAllergies() {
        List<List<Integer>> memberAllergies = List.of(
                List.of(1, 2),
                List.of(2, 4),
                List.of(5)
        );

        List<Integer> merged = mergeService.mergeAllergies(memberAllergies);
        assertEquals(4, merged.size(), "중복(2) 제거 후 4개여야 함");
        assertTrue(merged.containsAll(List.of(1, 2, 4, 5)), "1, 2, 4, 5 모두 포함");
    }

    @Test
    @DisplayName("참여자 매운맛 최솟값 병합 검증")
    void testMergeSpicyLevel() {
        List<Integer> spicyLevels = List.of(3, 1, 4, 2);
        int mergedSpicy = mergeService.mergeSpicyLevel(spicyLevels);
        assertEquals(1, mergedSpicy, "가장 낮은 1단계로 병합");

        assertEquals(5, mergeService.mergeSpicyLevel(List.of()), "참여자 정보 없으면 5단계 기본값");
    }

    @Test
    @DisplayName("참여자 예산 최솟값(교집합) 병합 검증")
    void testMergeBudget() {
        List<Integer> budgets = List.of(10000, 15000, 8000, 0);
        int mergedBudget = mergeService.mergeBudget(budgets);
        assertEquals(8000, mergedBudget, "0(제한없음) 제외 후 가장 낮은 8,000원으로 병합");

        assertEquals(0, mergeService.mergeBudget(List.of(0, 0)), "모두 제한없음(0)이면 0 반환");
    }
}
