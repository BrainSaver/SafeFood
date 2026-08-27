package com.safefood.service;

import com.safefood.dao.AllergyCatalog;
import com.safefood.setup.core.Schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 나이스(NEIS) 급식 식단의 알레르기 표기 번호를 우리 {@code allergy.id} 로 옮깁니다.
 *
 * <p><b>왜 표가 필요한가</b> — 같은 식약처 고시 19종이지만 <b>번호 순서가 다릅니다</b>.
 * 실제 식단으로 확인했습니다: {@code 우유 (2)}, {@code 고등어구이 (7)}, {@code 돈육불고기 (10)},
 * {@code 오징어무침 (17)}. 우리 마스터는 우유가 1번, 계란이 2번입니다.
 * 19개 중 <b>5개만 우연히 맞고 14개가 어긋납니다</b> — 번호를 그대로 쓰면 우유 알레르기 아이에게
 * 계란이 걸리고 우유는 안 걸립니다.
 *
 * <p>그래서 번호 → <b>이름</b> → id 순으로만 옮깁니다. 이름은 {@link Schema#NEIS_ALLERGY_NAMES},
 * id 는 {@link AllergyCatalog} 가 DB 에서 읽어 줍니다. 한 번이라도 못 옮기면 <b>표 전체를 버립니다</b> —
 * 절반만 맞는 표는 없는 것보다 위험합니다.
 */
public final class NeisAllergyCodes {

    /** NEIS 표기 번호의 유효 범위. 이 밖의 숫자는 알레르기 번호가 아닙니다. */
    public static final int MIN_CODE = 1;
    public static final int MAX_CODE = Schema.NEIS_ALLERGY_NAMES.size();

    private static volatile Map<Integer, Integer> cache;

    private NeisAllergyCodes() {
    }

    /**
     * NEIS 번호에 해당하는 우리 {@code allergy.id}.
     *
     * @return 표를 만들지 못했거나 범위 밖 번호면 {@code null} — 이 값으로는 판정하지 않습니다
     */
    public static Integer allergyIdOf(int neisCode) {
        Map<Integer, Integer> table = table();
        return table.isEmpty() ? null : table.get(neisCode);
    }

    /** 번호를 사람이 읽는 이름으로. 범위 밖이면 {@code null}. */
    public static String nameOf(int neisCode) {
        if (neisCode < MIN_CODE || neisCode > MAX_CODE) {
            return null;
        }
        return Schema.NEIS_ALLERGY_NAMES.get(neisCode - 1);
    }

    /**
     * 번호 목록을 우리 id 목록으로. 옮기지 못한 번호는 <b>조용히 버리지 않고</b>
     * {@code unmapped} 에 담아 돌려줍니다 — 화면이 "확인 불가"로 알릴 수 있도록.
     */
    public static Mapped map(List<Integer> neisCodes) {
        List<Integer> ids = new ArrayList<>();
        List<Integer> unmapped = new ArrayList<>();
        for (Integer code : neisCodes) {
            Integer id = code == null ? null : allergyIdOf(code);
            if (id == null) {
                if (code != null) {
                    unmapped.add(code);
                }
            } else if (!ids.contains(id)) {
                ids.add(id);
            }
        }
        return new Mapped(List.copyOf(ids), List.copyOf(unmapped));
    }

    /** 옮긴 결과. {@code unmapped} 가 비어 있지 않으면 화면에서 그 사실을 알려야 합니다. */
    public record Mapped(List<Integer> allergyIds, List<Integer> unmappedCodes) {
    }

    /** 표를 다시 만듭니다 (마법사로 알레르기 마스터를 다시 심은 뒤 등). */
    public static void reload() {
        cache = null;
        AllergyCatalog.reload();
    }

    private static Map<Integer, Integer> table() {
        Map<Integer, Integer> local = cache;
        if (local != null) {
            return local;
        }
        synchronized (NeisAllergyCodes.class) {
            if (cache == null) {
                cache = build();
            }
            return cache;
        }
    }

    /**
     * 번호 → id 표를 만듭니다. 이름 하나라도 마스터에서 못 찾으면 <b>빈 표</b>를 돌려줍니다.
     *
     * <p>빈 표도 캐시합니다 — DB 가 죽어 있을 때 화면이 다시 그려질 때마다 새 커넥션을
     * 시도하지 않도록 ({@link AllergyCatalog} 와 같은 이유).
     */
    private static Map<Integer, Integer> build() {
        Map<Integer, Integer> table = new LinkedHashMap<>();
        List<String> names = Schema.NEIS_ALLERGY_NAMES;
        for (int code = MIN_CODE; code <= names.size(); code++) {
            String name = names.get(code - 1);
            Integer allergyId = AllergyCatalog.idOf(name);
            if (allergyId == null) {
                System.err.println("[neis] 알레르기 마스터에서 " + name + " 을(를) 찾지 못해"
                        + " 번호 표를 만들지 않았습니다. 급식 알레르기 판정을 하지 않습니다.");
                return Map.of();
            }
            table.put(code, allergyId);
        }
        return Map.copyOf(table);
    }
}
