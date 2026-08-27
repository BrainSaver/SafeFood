package com.safefood.dao;

import com.safefood.setup.core.Schema;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 알레르기 마스터({@code allergy} 테이블)의 이름 ↔ id 대응표.
 *
 * <p><b>왜 필요한가</b> — 예전에는 하드코딩된 목록의 <b>순서</b>를 id 로 썼습니다
 * ({@code indexOf(name) + 1}, {@code get(id - 1)}). 화면 목록과 DB 의 AUTO_INCREMENT 가 언제나
 * 같은 순서라는 보장이 없으므로, 목록 중간에 항목을 넣거나 마스터를 다시 심으면
 * <b>회원의 '새우'가 '게'로 저장·판정</b>될 수 있었습니다. 알레르기 앱에서 조용히 어긋나면
 * 안 되는 값이라, id 는 반드시 DB 에서 읽습니다.
 *
 * <p>마스터는 설정 마법사가 한 번 심고 앱이 도는 동안 바뀌지 않으므로 한 번 읽어 캐시합니다.
 * 마법사를 다시 돌린 뒤 등 새로 읽어야 하면 {@link #reload()} 를 부르세요.
 *
 * <p><b>못 읽었을 때</b> — {@link #names()} 는 화면에 고를 목록이라도 띄우려고
 * {@link Schema#ALLERGIES} 로 물러섭니다. 반면 {@link #idOf(String)} 는 <b>절대 물러서지 않고</b>
 * null 을 돌려줍니다. 저장은 실패하는 편이, 틀린 번호로 저장되는 것보다 낫습니다.
 */
public final class AllergyCatalog {

    /** 한 번 읽어 둔 대응표. null 이면 아직 읽기 전입니다 (읽기 실패는 빈 대응표로 캐시됩니다). */
    private static volatile Snapshot cache;

    private AllergyCatalog() {
    }

    private record Snapshot(Map<String, Integer> idByName, Map<Integer, String> nameById) {

        List<String> names() {
            return List.copyOf(idByName.keySet());
        }
    }

    /**
     * 저장용 — 이름에 해당하는 {@code allergy.id}.
     *
     * @return 마스터에 없거나 DB 를 읽지 못했으면 {@code null} (이 값으로는 저장하면 안 됩니다)
     */
    public static Integer idOf(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Snapshot snapshot = snapshot();
        return snapshot == null ? null : snapshot.idByName().get(name.trim());
    }

    /**
     * 표시용 — id 에 해당하는 알레르기 이름.
     *
     * @return 마스터에 없거나 DB 를 읽지 못했으면 {@code null}
     */
    public static String nameOf(int id) {
        Snapshot snapshot = snapshot();
        return snapshot == null ? null : snapshot.nameById().get(id);
    }

    /**
     * 화면에서 고를 목록 — DB 의 id 순서입니다.
     * DB 를 읽지 못하면 {@link Schema#ALLERGIES}(식약처 고시 19종)로 대신합니다.
     */
    public static List<String> names() {
        Snapshot snapshot = snapshot();
        if (snapshot == null || snapshot.idByName().isEmpty()) {
            return Schema.ALLERGIES;
        }
        return snapshot.names();
    }

    /** 다음 호출 때 DB 에서 다시 읽습니다 (설정 마법사로 마스터를 다시 심은 뒤 등). */
    public static void reload() {
        cache = null;
    }

    public static List<String> verify() {
        List<String> problems = new ArrayList<>();
        Snapshot snapshot = snapshot();
        if (snapshot == null || snapshot.idByName().isEmpty()) {
            problems.add("allergy 테이블을 읽지 못했거나 비어 있습니다."
                    + " 설정 마법사로 기본 데이터를 먼저 심어 주세요.");
            return problems;
        }

        List<String> missing = Schema.ALLERGIES.stream()
                .filter(name -> !snapshot.idByName().containsKey(name)).toList();
        if (!missing.isEmpty()) {
            problems.add("마스터에 없는 알레르겐: " + String.join(", ", missing)
                    + " — 이 알레르기는 저장도 급식 판정도 되지 않습니다.");
        }

        int count = snapshot.idByName().size();
        int maxId = snapshot.nameById().keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
        if (maxId != count) {
            problems.add("allergy.id 가 1~" + count + " 로 이어지지 않습니다 (가장 큰 id " + maxId + ")."
                    + " 마스터를 지웠다 다시 심은 흔적입니다 — user_allergy · child_allergy ·"
                    + " menu_allergy · ingredient_allergy 가 가리키는 알레르겐이 그대로인지 확인해 주세요.");
        }

        int nextId = nextAutoIncrement();
        if (nextId > count + 1) {
            problems.add("다음에 심을 allergy.id 가 " + nextId + " 부터입니다 (지금 " + count + "종)."
                    + " 마스터를 지우고 다시 심으면 번호가 " + nextId + " 부터 매겨져"
                    + " 기존 알레르기 기록이 전부 다른 알레르겐을 가리키게 됩니다."
                    + " 다시 심어야 한다면 id 를 명시해 넣어 주세요.");
        }
        return problems;
    }

    private static int nextAutoIncrement() {
        String sql = "SELECT AUTO_INCREMENT FROM information_schema.tables"
                + " WHERE table_schema = DATABASE() AND table_name = 'allergy'";
        try (Connection conn = DatabaseUtil.getConnection();
             Statement statement = conn.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            return 0;
        }
    }

    private static Snapshot snapshot() {
        Snapshot local = cache;
        if (local != null) {
            return local;
        }
        synchronized (AllergyCatalog.class) {
            if (cache == null) {
                cache = load();
            }
            return cache;
        }
    }

    /**
     * 못 읽었을 때도 <b>빈 대응표를 캐시합니다</b> — null 을 돌려주고 캐시를 비워 두면
     * 화면이 다시 그려질 때마다(프로필 검색창은 한 글자마다) 죽은 DB 에 새 커넥션을
     * 시도해 창이 멎습니다. 빈 대응표라도 동작은 그대로입니다 — {@link #idOf}는 null 을
     * 돌려주어 저장을 막고, {@link #names()}는 고시 19종으로 화면만 채웁니다.
     * 마법사로 마스터를 심은 뒤에는 {@link #reload()} 로 다시 읽습니다.
     */
    private static Snapshot load() {
        Map<String, Integer> idByName = new LinkedHashMap<>();
        Map<Integer, String> nameById = new LinkedHashMap<>();
        String sql = "SELECT id, name FROM allergy ORDER BY id";

        try (Connection conn = DatabaseUtil.getConnection();
             Statement statement = conn.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {

            while (rs.next()) {
                int id = rs.getInt("id");
                String name = rs.getString("name");
                if (name == null || name.isBlank()) {
                    continue;
                }
                idByName.put(name.trim(), id);
                nameById.put(id, name.trim());
            }
        } catch (SQLException e) {
            System.err.println("[allergy] 알레르기 마스터를 읽지 못했습니다: " + e.getMessage());
            return new Snapshot(Map.of(), Map.of());
        }
        if (idByName.isEmpty()) {
            System.err.println("[allergy] allergy 테이블이 비어 있습니다."
                    + " 설정 마법사로 기본 데이터를 먼저 심어 주세요.");
        }
        return new Snapshot(idByName, nameById);
    }
}
