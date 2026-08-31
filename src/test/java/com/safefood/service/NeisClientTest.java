package com.safefood.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 나이스 응답 읽기 — 실제로 받은 XML 세 가지를 픽스처로 씁니다 (정상 · 데이터 없음 · 키 오류).
 */
class NeisClientTest {

    private static String fixture(String name) throws IOException {
        try (InputStream in = NeisClientTest.class.getResourceAsStream("/neis/" + name)) {
            assertNotNull(in, "픽스처 /neis/" + name + " 이 있어야 합니다");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("정상 응답에서 급식일과 식단 원문을 읽는다")
    void readsRows() throws IOException {
        List<NeisClient.MealRow> rows = NeisClient.readRows(fixture("meal-ok.xml"));

        assertEquals(2, rows.size());
        assertEquals(LocalDate.of(2026, 6, 1), rows.get(0).date());
        assertEquals("중식", rows.get(0).mealType());
        assertTrue(rows.get(0).rawDish().contains("우유 (2)"), "CDATA 안의 식단이 그대로 읽혀야 합니다");
        assertTrue(rows.get(0).rawDish().contains("<br/>"), "줄 구분자도 글자 그대로입니다");
        assertTrue(rows.get(0).date().isBefore(rows.get(1).date()), "날짜순으로 정렬됩니다");
    }

    @Test
    @DisplayName("읽어 낸 식단이 파서로 그대로 이어진다")
    void rowsFeedTheParser() throws IOException {
        NeisClient.MealRow row = NeisClient.readRows(fixture("meal-ok.xml")).get(0);
        List<MealParser.Dish> dishes = MealParser.parse(row.rawDish());

        assertFalse(dishes.isEmpty());
        MealParser.Dish milk = dishes.stream()
                .filter(d -> d.name().equals("우유")).findFirst().orElseThrow();
        assertEquals(List.of(2), milk.neisCodes(), "NEIS 에서 우유는 2번입니다");
    }

    @Test
    @DisplayName("데이터 없음(INFO-200)은 빈 목록이지 오류가 아니다")
    void noDataIsNotAnError() throws IOException {
        assertTrue(NeisClient.readRows(fixture("meal-nodata.xml")).isEmpty());
    }

    @Test
    @DisplayName("깨진 원문은 빈 목록 — 캐시가 상했을 뿐이라 다시 받으면 된다")
    void brokenBodyIsEmptyNotThrown() {
        assertTrue(NeisClient.readRows("이건 XML 이 아닙니다").isEmpty());
        assertTrue(NeisClient.readRows("<mealServiceDietInfo><row>").isEmpty());
        assertTrue(NeisClient.readRows(null).isEmpty());
        assertTrue(NeisClient.readRows("").isEmpty());
    }

    @Test
    @DisplayName("키 오류 응답도 행이 없는 것으로 읽힌다")
    void badKeyResponseHasNoRows() throws IOException {
        assertTrue(NeisClient.readRows(fixture("meal-badkey.xml")).isEmpty());
    }
}
