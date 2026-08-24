package com.safefood.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AllergyServiceTest {

    private final AllergyService allergyService = new AllergyService();

    @Test
    @DisplayName("CONTAINS 알레르기 포함 여부 안전성 검증")
    void testIsSafe() {
        List<Integer> userAllergies = List.of(4); // 땅콩(4)
        List<Integer> dangerousMenu = List.of(4, 2); // 땅콩, 계란 포함
        List<Integer> safeMenu = List.of(1, 2); // 우유, 계란 포함

        assertFalse(allergyService.isSafe(userAllergies, dangerousMenu), "땅콩 포함 메뉴는 false 반환");
        assertTrue(allergyService.isSafe(userAllergies, safeMenu), "땅콩 미포함 메뉴는 true 반환");
        assertTrue(allergyService.isSafe(List.of(), dangerousMenu), "사용자 알레르기 없으면 true 반환");
        assertTrue(allergyService.isSafe(userAllergies, List.of()), "메뉴에 알레르기 성분 없으면 true 반환");
    }

    @Test
    @DisplayName("POSSIBLE 혼입 가능성 심각도 비례 감점 계산")
    void testCalculatePenalty() {
        Map<Integer, Integer> userSeverityMap = Map.of(4, 3, 2, 5); // 땅콩 심각도 3, 계란 심각도 5
        List<Integer> possibleAllergies = List.of(4); // 땅콩 혼입 가능

        int penalty = allergyService.calculatePenalty(userSeverityMap, possibleAllergies);
        assertEquals(30, penalty, "땅콩 심각도 3 * 10 = 30점 감점");

        int noPenalty = allergyService.calculatePenalty(userSeverityMap, List.of(1)); // 우유(1)는 사용자 알레르기 아님
        assertEquals(0, noPenalty, "사용자가 해당하지 않는 알레르기는 0점 감점");
    }

    @Test
    @DisplayName("알레르기 차단 사유 문구 생성")
    void testCreateBlockedReason() {
        String reason = allergyService.createBlockedReason(List.of("땅콩", "대두"));
        assertTrue(reason.contains("땅콩"), "땅콩 포함 확인");
        assertTrue(reason.contains("대두"), "대두 포함 확인");
    }
}
