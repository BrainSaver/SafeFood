package com.safefood.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;

/**
 * 나이스 응답 원문을 파일로 두고 다시 씁니다.
 *
 * <p><b>왜 파일인가</b> — 실측으로 나이스 호출이 약 150ms, 로컬 파일 읽기+파싱이 0.3ms 입니다.
 * 500배 차이인데다 <b>앱을 껐다 켜도 남습니다</b>. 메모리 캐시는 하루 한 번 여는 사용자에게
 * 사실상 없는 것과 같습니다. 원격 MySQL 에 넣는 방식은 커넥션 풀이 없어 오히려 더 느립니다.
 *
 * <p><b>원문을 그대로 저장합니다.</b> 파싱 결과가 아니라 응답 본문이라, {@link MealParser} 를
 * 고치면 다시 받지 않고도 기존 캐시에 바로 적용됩니다.
 *
 * <h3>언제 다시 받는가</h3>
 * <ul>
 *   <li><b>완전히 지난 달</b> — 다시 받지 않습니다. 지난 급식은 바뀌지 않습니다
 *       (실제 데이터에서 과거 레코드의 적재일이 급식일 +7일에서 굳는 것을 확인했습니다).</li>
 *   <li><b>이번 달·다음 달</b> — <b>달력일</b> 기준 하루 한 번. TTL 24시간이 아닙니다 —
 *       어젯밤에 본 사람이 오늘 아침 내내 어제 정보를 보게 되는데, 부모가 급식을 확인하는
 *       시간이 바로 그 아침입니다.</li>
 *   <li><b>결과가 비어 있던 조회</b> — 저장하지 않습니다. 학교 절반이 5일치만 올려 두므로,
 *       "아직 없음"을 하루 캐시하면 새로 올라온 식단을 못 보게 됩니다.</li>
 * </ul>
 *
 * <p>날짜는 시스템 기본값이 아니라 {@code Asia/Seoul} 로 셉니다. 급식일은 한국 날짜입니다.
 */
public final class MealCache {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private static final Path DIR = Path.of("data", "private", "meal-cache");

    /** 저장해 둔 응답 한 건. */
    public record Cached(String body, LocalDate fetchedOn) {
    }

    // ---- 정책 (순수 함수 — DB·파일 없이 시험합니다) ----

    /**
     * 다시 받아야 하는가.
     *
     * @param month     조회한 연월
     * @param fetchedOn 저장해 둔 날짜
     * @param today     오늘 (Asia/Seoul)
     */
    public static boolean isStale(YearMonth month, LocalDate fetchedOn, LocalDate today) {
        if (month == null || fetchedOn == null || today == null) {
            return true;
        }
        if (month.isBefore(YearMonth.from(today))) {
            return false;   // 지난 달 — 확정된 사실이라 영구 유효
        }
        return fetchedOn.isBefore(today);
    }

    /**
     * 저장해도 되는 응답인가.
     *
     * <p>빈 응답은 이번 달·다음 달일 때만 거릅니다. 지난 달의 빈 응답은 그날 정말로 급식이
     * 없었던 것(주말·방학)이라 저장해도 됩니다.
     */
    public static boolean cacheable(YearMonth month, boolean hasData, LocalDate today) {
        if (month == null || today == null) {
            return false;
        }
        return hasData || month.isBefore(YearMonth.from(today));
    }

    // ---- 읽기 · 쓰기 ----

    /** 오늘 (Asia/Seoul). */
    public static LocalDate today() {
        return LocalDate.now(KST);
    }

    /**
     * 저장해 둔 응답. 없거나 읽지 못하면 {@code null} 입니다.
     *
     * <p>깨진 파일은 <b>예외로 올리지 않고</b> 없는 셈 칩니다. 캐시는 있으면 좋은 것이지
     * 없으면 안 되는 것이 아닙니다. (나이스 조회 실패는 반대로 반드시 알려야 합니다)
     */
    public Cached read(String atptCode, String schulCode, YearMonth month) {
        Path file = fileOf(atptCode, schulCode, month);
        try {
            if (!Files.exists(file)) {
                return null;
            }
            String body = Files.readString(file, StandardCharsets.UTF_8);
            if (body.isBlank()) {
                return null;
            }
            LocalDate fetchedOn = Files.getLastModifiedTime(file).toInstant().atZone(KST).toLocalDate();
            return new Cached(body, fetchedOn);
        } catch (IOException | RuntimeException e) {
            System.err.println("[meal] 캐시를 읽지 못해 새로 받습니다: " + e.getMessage());
            return null;
        }
    }

    /**
     * 응답 원문을 저장합니다.
     *
     * <p>임시 파일에 쓴 뒤 이름을 바꿉니다 — 소켓 시험 때처럼 앱을 여러 개 띄워도
     * 반쯤 쓰인 파일이 남지 않도록.
     */
    public void write(String atptCode, String schulCode, YearMonth month, String body) {
        Path file = fileOf(atptCode, schulCode, month);
        try {
            Files.createDirectories(file.getParent());
            Path temp = Files.createTempFile(file.getParent(), "meal", ".tmp");
            Files.writeString(temp, body, StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // 저장 실패는 다음에 다시 받으면 그만입니다 — 화면을 막지 않습니다
            System.err.println("[meal] 캐시를 저장하지 못했습니다: " + e.getMessage());
        }
    }

    /** 한 학교의 캐시를 전부 지웁니다 (아이 삭제·탈퇴 때). */
    public void clear(String atptCode, String schulCode) {
        String prefix = key(atptCode, schulCode);
        try (var files = Files.list(DIR)) {
            files.filter(p -> p.getFileName().toString().startsWith(prefix))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
            // 폴더가 없으면 지울 것도 없습니다
        }
    }

    private static Path fileOf(String atptCode, String schulCode, YearMonth month) {
        return DIR.resolve(key(atptCode, schulCode) + month.getYear()
                + String.format("%02d", month.getMonthValue()) + ".xml");
    }

    /** 파일명 앞부분. 코드에 이상한 문자가 오더라도 경로를 벗어나지 않게 걸러 둡니다. */
    private static String key(String atptCode, String schulCode) {
        return safe(atptCode) + "_" + safe(schulCode) + "_";
    }

    private static String safe(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9]", "");
    }
}
