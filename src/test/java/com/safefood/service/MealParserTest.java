package com.safefood.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 식단 파서 — 나이스에서 실제로 받은 원문을 픽스처로 씁니다
 * ({@code src/test/resources/neis/ddish-samples.txt}, 22개 학교 393 급식일).
 *
 * <p>여기 적힌 함정은 지어낸 것이 아니라 그 표본에서 나온 것들입니다.
 */
class MealParserTest {

    private static MealParser.Dish only(String line) {
        List<MealParser.Dish> dishes = MealParser.parse(line);
        assertEquals(1, dishes.size(), "한 줄이어야 합니다: " + line);
        return dishes.get(0);
    }

    @Test
    @DisplayName("기본 — 메뉴명과 번호를 갈라 읽는다")
    void basic() {
        MealParser.Dish dish = only("통밀밥 (6)");
        assertEquals("통밀밥", dish.name());
        assertEquals(List.of(6), dish.neisCodes());
        assertTrue(dish.labeled());
    }

    @Test
    @DisplayName("괄호 안이 통째로 숫자일 때만 알레르기 번호로 본다")
    void onlyNumericParenthesesAreCodes() {
        // (15곡) 을 15번(닭고기)으로 읽으면 안 됩니다
        MealParser.Dish grain = only("혼합잡곡밥(15곡) (5)");
        assertEquals(List.of(5), grain.neisCodes());
        assertEquals("혼합잡곡밥(15곡)", grain.name(), "뜻이 있는 괄호는 이름에 남깁니다");

        MealParser.Dish hotdog = only("뽀득핫도그(50g) (1.2.5.6.10.12.16)");
        assertEquals(List.of(1, 2, 5, 6, 10, 12, 16), hotdog.neisCodes());
        assertFalse(hotdog.neisCodes().contains(50));

        MealParser.Dish yogurt = only("@드링킹요구르트(딸기,180ml) (2)");
        assertEquals(List.of(2), yogurt.neisCodes(), "비번호 괄호 안의 쉼표에 속으면 안 됩니다");
    }

    @Test
    @DisplayName("괄호가 둘이고 앞의 것이 범위 밖 숫자여도 뒤의 번호를 읽는다")
    void multipleParentheses() {
        MealParser.Dish dish = only("노각무침(0) (5.6.13)");
        assertEquals(List.of(5, 6, 13), dish.neisCodes());
        assertTrue(dish.labeled(), "0 은 버리지만 표기 자체는 있었습니다");
        assertFalse(dish.neisCodes().contains(0));
    }

    @Test
    @DisplayName("번호 구분자는 점과 쉼표 둘 다 쓰인다")
    void bothSeparators() {
        assertEquals(List.of(1, 2, 5), only("무언가 (1.2.5)").neisCodes());
        assertEquals(List.of(1, 2, 5), only("무언가 (1,2,5)").neisCodes());
    }

    @Test
    @DisplayName("표기가 없는 메뉴는 '알레르겐 없음'이 아니라 '표기 없음'")
    void unlabeledIsNotSafe() {
        MealParser.Dish rice = only("#쌀밥");
        assertTrue(rice.neisCodes().isEmpty());
        assertFalse(rice.labeled(), "이 값으로 화면이 '안전'이라 말하지 않게 막습니다");

        assertFalse(only("@매실주스(100ml)").labeled(), "숫자가 있어도 번호 표기가 아닙니다");
    }

    @Test
    @DisplayName("학교마다 붙이는 장식 기호는 이름에서 뗀다")
    void stripsDecorations() {
        assertEquals("쌀밥", only("#쌀밥").name());
        assertEquals("망고", only("* 망고").name());
        assertEquals("크런키요거트아이스크림", only("크런키요거트아이스크림-").name());
        assertEquals("김치말이국수(냉)", only("#김치말이국수(냉) (2.5.6)").name());
    }

    @Test
    @DisplayName("줄 구분자는 <br/> · <br> · 줄바꿈이 섞여 온다")
    void splitsLines() {
        List<MealParser.Dish> dishes = MealParser.parse("쌀보리밥 <br/>콩나물국 (5)<br>우유 (2)\n김구이 ");
        assertEquals(4, dishes.size());
        assertEquals("쌀보리밥", dishes.get(0).name());
        assertEquals("우유", dishes.get(2).name());
        assertEquals(List.of(2), dishes.get(2).neisCodes());
        assertEquals(3, dishes.get(3).seq());
    }

    @Test
    @DisplayName("빈 입력에도 터지지 않는다")
    void emptyInput() {
        assertTrue(MealParser.parse(null).isEmpty());
        assertTrue(MealParser.parse("").isEmpty());
        assertTrue(MealParser.parse("<br/><br/>").isEmpty());
    }

    // ── 실제 수집 표본 전체 ────────────────────────────────────

    private static List<String> samples() throws IOException {
        try (InputStream in = MealParserTest.class.getResourceAsStream("/neis/ddish-samples.txt")) {
            assertNotNull(in, "픽스처 /neis/ddish-samples.txt 가 있어야 합니다");
            List<String> lines = new ArrayList<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (!line.isBlank()) {
                    lines.add(line);
                }
            }
            return lines;
        }
    }

    @Test
    @DisplayName("실제 393 급식일 전체 — 범위 밖 번호가 새어 나오지 않는다")
    void realCorpusStaysInRange() throws IOException {
        int dishCount = 0;
        int labeled = 0;
        for (String line : samples()) {
            for (MealParser.Dish dish : MealParser.parse(line)) {
                dishCount++;
                if (dish.labeled()) {
                    labeled++;
                }
                assertFalse(dish.name().isBlank(), "메뉴 이름이 비면 안 됩니다: " + dish.rawText());
                for (int code : dish.neisCodes()) {
                    assertTrue(code >= NeisAllergyCodes.MIN_CODE && code <= NeisAllergyCodes.MAX_CODE,
                            "범위 밖 번호 " + code + " : " + dish.rawText());
                }
            }
        }
        assertEquals(2485, dishCount, "표본의 메뉴 줄 수");
        assertTrue(labeled > 0 && labeled < dishCount, "표기가 있는 줄과 없는 줄이 섞여 있어야 합니다");
        // 표기 없는 줄이 상당수 나옵니다 — 화면이 이것을 '안전'으로 보이면 안 됩니다
        assertTrue(dishCount - labeled >= 463, "표기 없는 줄이 최소 463건은 나와야 합니다");
    }
}
