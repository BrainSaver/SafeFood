package com.safefood.service;

import com.safefood.dao.AllergyCatalog;
import com.safefood.dao.ChildDao;
import com.safefood.dto.Child;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 아이의 알레르기와 학교 급식을 맞춰 봅니다.
 *
 * <p>급식은 DB 에 넣지 않습니다. 캐시({@link MealCache}) → 나이스({@link NeisClient}) 순으로
 * 원문을 얻어 {@link MealParser} 로 읽고, {@link NeisAllergyCodes} 로 번호를 우리 알레르기 id 로
 * 옮긴 뒤 아이의 목록과 대조합니다.
 *
 * <p><b>이 화면은 '안전합니다'라고 말하지 않습니다.</b> 학교가 표기한 것이 없다는 사실까지만
 * 말하고, 표기 자체가 없으면 그것을 따로 알립니다 ({@link DishSafety#UNLABELED}).
 */
public class MealService {

    private final ChildDao childDao;
    private final NeisClient neis;
    private final MealCache cache;

    public MealService() {
        this(new ChildDao(), new NeisClient(), new MealCache());
    }

    public MealService(ChildDao childDao, NeisClient neis, MealCache cache) {
        this.childDao = childDao;
        this.neis = neis;
        this.cache = cache;
    }

    /** 메뉴 한 줄의 상태. '안전(SAFE)'이라는 값이 없는 것이 의도입니다. */
    public enum DishSafety {
        /** 아이 알레르기가 표기되어 있음 */
        RISK,
        /** 알레르기 표기가 있고 그중 아이 것이 없음 — '안전'이 아니라 '표기상 없음' */
        CLEAR,
        /** 이 메뉴에 알레르기 표기 자체가 없음 */
        UNLABELED
    }

    /**
     * @param hitAllergens 아이에게 걸리는 알레르겐 이름
     * @param allAllergens 학교가 표기한 알레르겐 전부
     * @param maxSeverity  걸리는 것 중 가장 높은 심각도 (1~5). 걸리는 게 없으면 0
     * @param unmappedCodes 우리 마스터로 옮기지 못한 번호 — 있으면 화면이 '확인 불가'로 알려야 합니다
     */
    public record DishView(int seq, String name, DishSafety safety,
                           List<String> hitAllergens, List<String> allAllergens,
                           int maxSeverity, List<Integer> unmappedCodes) {
    }

    /** 급식 하루치. */
    public record DayView(LocalDate date, String mealType, List<DishView> dishes,
                          boolean labeled, boolean risky) {
    }

    /**
     * 조회 결과 한 벌.
     *
     * @param fetchedOn 원문을 받은 날. 화면에 '○월 ○일 기준'으로 보여 줍니다
     * @param stale     나이스를 못 불러 예전 캐시를 대신 보여 주는 중인지
     * @param failure   아무것도 보여 줄 수 없을 때의 사유. null 이면 정상
     */
    public record MealPeriod(List<DayView> days, LocalDate fetchedOn,
                             boolean stale, String failure) {

        public boolean failed() {
            return failure != null;
        }

        /** 조건에 걸리는 아이가 있는 날들. 화면 위쪽 요약 배너에 씁니다. */
        public List<DayView> riskyDays() {
            return days.stream().filter(DayView::risky).toList();
        }
    }

    // ---- 조회 ----

    /**
     * 기간의 급식을 가져옵니다. 걸치는 달이 여럿이면 달마다 한 번씩 부릅니다.
     *
     * @param forceRefresh 새로고침 버튼 — 캐시가 아직 유효해도 다시 받습니다
     */
    public MealPeriod load(Child child, LocalDate from, LocalDate to, boolean forceRefresh) {
        if (child == null || !child.hasSchool()) {
            return new MealPeriod(List.of(), null, false, "학교를 먼저 등록해 주세요.");
        }

        Map<Integer, Integer> severityById = severityByAllergyId(child.id());

        List<DayView> days = new ArrayList<>();
        LocalDate fetchedOn = null;
        boolean stale = false;
        String failure = null;

        for (YearMonth month : monthsBetween(from, to)) {
            Loaded loaded = loadMonth(child, month, forceRefresh);
            if (loaded.failure() != null) {
                failure = loaded.failure();
                continue;
            }
            stale |= loaded.stale();
            if (fetchedOn == null || (loaded.fetchedOn() != null && loaded.fetchedOn().isBefore(fetchedOn))) {
                fetchedOn = loaded.fetchedOn();
            }
            for (NeisClient.MealRow row : NeisClient.readRows(loaded.body())) {
                if (row.date().isBefore(from) || row.date().isAfter(to)) {
                    continue;
                }
                days.add(toDayView(row, severityById));
            }
        }

        days.sort((a, b) -> a.date().compareTo(b.date()));
        // 보여 줄 것이 하나라도 있으면 실패로 취급하지 않습니다 — 한 달만 못 받았을 수 있습니다
        return new MealPeriod(List.copyOf(days), fetchedOn, stale, days.isEmpty() ? failure : null);
    }

    /** 아이의 알레르기 id → 심각도. */
    private Map<Integer, Integer> severityByAllergyId(int childId) {
        Map<Integer, Integer> byId = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : childDao.getAllergies(childId).entrySet()) {
            Integer allergyId = AllergyCatalog.idOf(entry.getKey());
            if (allergyId != null) {
                byId.put(allergyId, entry.getValue());
            }
        }
        return byId;
    }

    private record Loaded(String body, LocalDate fetchedOn, boolean stale, String failure) {
    }

    /**
     * 한 달치 원문을 얻습니다 — 캐시가 유효하면 그대로, 아니면 나이스에서.
     *
     * <p>나이스가 응답하지 않는데 예전 캐시가 있으면 <b>그것을 씁니다.</b> 대신 {@code stale} 을
     * 세워 화면이 '○일 전 정보'라고 밝히게 합니다. 조용히 옛 데이터를 최신인 척 내보내면
     * 캐시를 안 쓴 것만 못합니다.
     */
    private Loaded loadMonth(Child child, YearMonth month, boolean forceRefresh) {
        LocalDate today = MealCache.today();
        MealCache.Cached cached = cache.read(child.atptCode(), child.schulCode(), month);

        boolean usable = cached != null
                && !forceRefresh
                && !MealCache.isStale(month, cached.fetchedOn(), today);
        if (usable) {
            return new Loaded(cached.body(), cached.fetchedOn(), false, null);
        }

        try {
            NeisClient.MealResponse fresh = neis.fetchMonth(child.atptCode(), child.schulCode(), month);
            if (MealCache.cacheable(month, fresh.hasData(), today)) {
                cache.write(child.atptCode(), child.schulCode(), month, fresh.body());
            }
            return new Loaded(fresh.body(), today, false, null);
        } catch (NeisClient.NeisException e) {
            System.err.println("[meal] " + month + " 급식을 받지 못했습니다: " + e.getMessage());
            if (cached != null) {
                return new Loaded(cached.body(), cached.fetchedOn(), true, null);
            }
            return new Loaded(null, null, false, e.getMessage());
        }
    }

    // ---- 판정 ----

    private DayView toDayView(NeisClient.MealRow row, Map<Integer, Integer> severityById) {
        List<DishView> dishes = new ArrayList<>();
        boolean anyLabeled = false;
        boolean anyRisk = false;

        for (MealParser.Dish dish : MealParser.parse(row.rawDish())) {
            NeisAllergyCodes.Mapped mapped = NeisAllergyCodes.map(dish.neisCodes());

            Set<String> all = new LinkedHashSet<>();
            Set<String> hit = new LinkedHashSet<>();
            int maxSeverity = 0;
            for (Integer allergyId : mapped.allergyIds()) {
                String name = AllergyCatalog.nameOf(allergyId);
                all.add(name == null ? ("알레르겐 " + allergyId) : name);
                Integer severity = severityById.get(allergyId);
                if (severity != null) {
                    hit.add(name == null ? ("알레르겐 " + allergyId) : name);
                    maxSeverity = Math.max(maxSeverity, severity);
                }
            }

            DishSafety safety;
            if (!hit.isEmpty()) {
                safety = DishSafety.RISK;
            } else if (dish.labeled()) {
                safety = DishSafety.CLEAR;
            } else {
                safety = DishSafety.UNLABELED;
            }

            anyLabeled |= dish.labeled();
            anyRisk |= safety == DishSafety.RISK;

            dishes.add(new DishView(dish.seq(), dish.name(), safety,
                    List.copyOf(hit), List.copyOf(all), maxSeverity, mapped.unmappedCodes()));
        }

        return new DayView(row.date(), row.mealType() == null ? "급식" : row.mealType(),
                List.copyOf(dishes), anyLabeled, anyRisk);
    }

    private static List<YearMonth> monthsBetween(LocalDate from, LocalDate to) {
        List<YearMonth> months = new ArrayList<>();
        YearMonth cursor = YearMonth.from(from);
        YearMonth last = YearMonth.from(to);
        while (!cursor.isAfter(last)) {
            months.add(cursor);
            cursor = cursor.plusMonths(1);
        }
        return months;
    }
}
