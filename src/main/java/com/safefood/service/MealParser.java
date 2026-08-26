package com.safefood.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 나이스 급식 식단 문자열({@code DDISH_NM})을 메뉴 한 줄씩으로 쪼갭니다.
 *
 * <p>DB 도 네트워크도 건드리지 않는 순수 함수라, 실제 식단 원문을 픽스처로 두고
 * 그대로 시험할 수 있습니다 ({@code MealParserTest}).
 *
 * <p><b>핵심 규칙</b> — 괄호를 찾아 숫자만 뽑으면 안 됩니다. 실제 식단에는 알레르기 번호가
 * 아닌 괄호가 섞여 있고 그 안에도 숫자가 있습니다:
 * <ul>
 *   <li>{@code 혼합잡곡밥(15곡) (5)} — '15곡'을 15번(닭고기)으로 읽으면 안 됩니다</li>
 *   <li>{@code 뽀득핫도그(50g) (1.2.5.6.10.12.16)} — '50g'</li>
 *   <li>{@code @드링킹요구르트(딸기,180ml) (2)} — 비번호 괄호 안의 쉼표</li>
 *   <li>{@code 노각무침(0) (5.6.13)} — 괄호가 둘, 앞의 0은 범위 밖</li>
 * </ul>
 * 그래서 <b>괄호 안이 통째로 숫자와 구분자로만</b> 이루어졌을 때만 알레르기 번호로 봅니다.
 *
 * <p><b>표기 없음 ≠ 알레르겐 없음</b> — 표본의 18.6%가 번호 없이 옵니다. 대부분 쌀밥·수박처럼
 * 정말 없는 것이지만, 학교가 빠뜨린 경우와 구분할 방법이 없습니다. 그래서 번호가 하나도 없었다는
 * 사실을 {@link Dish#labeled()} 로 남겨, 화면이 '안전'이라고 말하지 않게 합니다.
 */
public final class MealParser {

    /** {@code <br/>} · {@code <br>} · 줄바꿈이 섞여 옵니다. */
    private static final Pattern LINE_SEPARATOR = Pattern.compile("<br\\s*/?>|[\\r\\n]+", Pattern.CASE_INSENSITIVE);

    private static final Pattern PAREN = Pattern.compile("\\(([^)]*)\\)");

    /** 괄호 안이 숫자·점·쉼표·공백으로만 되어 있어야 알레르기 번호입니다. */
    private static final Pattern CODES_ONLY = Pattern.compile("[0-9\\s.,]+");

    private static final Pattern NUMBER = Pattern.compile("\\d+");

    /** 학교마다 붙이는 장식 기호 — 메뉴 이름의 일부가 아닙니다. */
    private static final Pattern LEADING_MARK = Pattern.compile("^[#@*\\s]+");
    private static final Pattern TRAILING_MARK = Pattern.compile("[\\-\\s]+$");

    private MealParser() {
    }

    /**
     * 메뉴 한 줄.
     *
     * @param seq       식단에 적힌 순서 (0부터)
     * @param name      장식 기호와 알레르기 괄호를 뗀 이름. {@code (15곡)} 처럼 뜻이 있는 괄호는 남깁니다
     * @param rawText   원문 한 줄 그대로
     * @param neisCodes 학교가 표기한 NEIS 번호. 범위 밖 숫자는 빠집니다
     * @param labeled   알레르기 번호 괄호가 <b>있었는지</b>. false 면 '표기 없음'이지 '안전'이 아닙니다
     */
    public record Dish(int seq, String name, String rawText, List<Integer> neisCodes, boolean labeled) {

        public Dish {
            neisCodes = List.copyOf(neisCodes);
        }
    }

    /** 식단 문자열 하나를 메뉴 목록으로. null 이나 빈 문자열이면 빈 목록입니다. */
    public static List<Dish> parse(String ddishNm) {
        List<Dish> dishes = new ArrayList<>();
        if (ddishNm == null || ddishNm.isBlank()) {
            return dishes;
        }

        int seq = 0;
        for (String line : LINE_SEPARATOR.split(ddishNm)) {
            String raw = line.trim();
            if (raw.isEmpty()) {
                continue;
            }
            dishes.add(parseLine(seq++, raw));
        }
        return dishes;
    }

    private static Dish parseLine(int seq, String raw) {
        Set<Integer> codes = new LinkedHashSet<>();
        boolean labeled = false;
        StringBuilder name = new StringBuilder();

        int cursor = 0;
        Matcher paren = PAREN.matcher(raw);
        while (paren.find()) {
            String inside = paren.group(1);
            if (!CODES_ONLY.matcher(inside).matches() || inside.isBlank()) {
                continue;   // (자율) (친환경) (15곡) (50g) — 이름의 일부라 그대로 둡니다
            }
            // 여기까지 왔으면 알레르기 번호 표기입니다 — 이름에서 떼어 냅니다
            labeled = true;
            name.append(raw, cursor, paren.start());
            cursor = paren.end();

            Matcher number = NUMBER.matcher(inside);
            while (number.find()) {
                int code = Integer.parseInt(number.group());
                if (code >= NeisAllergyCodes.MIN_CODE && code <= NeisAllergyCodes.MAX_CODE) {
                    codes.add(code);
                }
                // 범위 밖(0 등)은 버리되 labeled 는 유지 — 표기 자체는 있었으니까요
            }
        }
        name.append(raw, cursor, raw.length());

        return new Dish(seq, clean(name.toString()), raw, new ArrayList<>(codes), labeled);
    }

    private static String clean(String name) {
        String cleaned = LEADING_MARK.matcher(name).replaceAll("");
        cleaned = TRAILING_MARK.matcher(cleaned).replaceAll("");
        return cleaned.replaceAll("\\s{2,}", " ").trim();
    }
}
