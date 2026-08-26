package com.safefood.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * [ SafeFood - 알레르기 DAO ]
 * DB의 user_allergy, menu_allergy 테이블 조회 담당
 */
public class AllergyDao {

    private static final String DERIVED_CONTAINS = """
            SELECT dm.id AS menu_id, ia.allergy_id
              FROM menu dm
              JOIN food_ingredient fi    ON fi.food_id = dm.food_id
              JOIN ingredient_allergy ia ON ia.ingredient_id = fi.ingredient_id
             WHERE dm.food_id IS NOT NULL
               AND NOT EXISTS (SELECT 1 FROM menu_allergy x WHERE x.menu_id = dm.id)
            """;

    /**
     * 특정 회원의 알레르기 ID 목록 조회
     */
    public List<Integer> getUserAllergyIds(int userId) {
        List<Integer> allergyIds = new ArrayList<>();
        String sql = "SELECT allergy_id FROM user_allergy WHERE user_id = ?";

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, userId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    allergyIds.add(rs.getInt("allergy_id"));
                }
            }
        } catch (SQLException e) {
            // 삼키면 '알레르기가 없는 회원'으로 보여 모든 메뉴가 안전해집니다
            throw new DataAccessException("회원 알레르기를 불러오지 못했습니다.", e);
        }
        return allergyIds;
    }

    /**
     * 특정 회원의 알레르기 ID 및 심각도(severity) Map 조회
     */
    public Map<Integer, Integer> getUserSeverityMap(int userId) {
        Map<Integer, Integer> severityMap = new HashMap<>();
        String sql = "SELECT allergy_id, severity FROM user_allergy WHERE user_id = ?";

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, userId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    severityMap.put(rs.getInt("allergy_id"), rs.getInt("severity"));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return severityMap;
    }

    /**
     * 특정 메뉴의 특정 위험도(risk_level: CONTAINS / POSSIBLE) 알레르기 ID 목록 조회
     */
    public List<Integer> getMenuAllergiesByRiskLevel(int menuId, String riskLevel) {
        List<Integer> allergyIds = new ArrayList<>();
        String sql = "SELECT allergy_id FROM menu_allergy WHERE menu_id = ? AND risk_level = ? "
                   + "UNION "
                   + "SELECT allergy_id FROM (" + DERIVED_CONTAINS + ") derived "
                   + "WHERE derived.menu_id = ? AND ? = 'CONTAINS'";

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, menuId);
            pstmt.setString(2, riskLevel);
            pstmt.setInt(3, menuId);
            pstmt.setString(4, riskLevel);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    allergyIds.add(rs.getInt("allergy_id"));
                }
            }
        } catch (SQLException e) {
            // 삼키면 '알레르기 성분이 없는 메뉴'로 보여 위험한 메뉴가 추천에 오릅니다
            throw new DataAccessException(
                    "메뉴 " + menuId + " 의 알레르기 정보를 불러오지 못했습니다.", e);
        }
        return allergyIds;
    }

    /**
     * 메뉴 하나의 위험도별 알레르기 — {@link AllergyDao#getAllMenuAllergies()} 의 값.
     *
     * <p>{@link #EMPTY} 는 <b>알레르기 정보가 없는 메뉴</b>를 뜻합니다. 조회에 실패한 경우가
     * 아닙니다 — 실패는 {@link DataAccessException} 으로 올라갑니다.
     */
    public record MenuAllergies(List<Integer> contains, List<Integer> possible) {

        public static final MenuAllergies EMPTY = new MenuAllergies(List.of(), List.of());
    }

    /**
     * 전체 메뉴의 알레르기 정보를 <b>쿼리 한 번</b>으로 조회합니다.
     *
     * <p>메뉴마다 {@link #getMenuAllergiesByRiskLevel} 을 부르면 커넥션이 메뉴 수 × 2 만큼
     * 열립니다 — 커넥션 풀이 없어 매번 새로 맺으므로(DatabaseUtil 참고) 후보가 조금만 늘어도
     * 화면이 몇 초씩 멎습니다. 추천처럼 후보 전체를 훑는 자리에서는 이 메서드를 쓰세요.
     *
     * <p>{@code UNKNOWN} 등급은 <b>어느 쪽에도 담지 않습니다</b> — 메뉴 단건 조회가 CONTAINS /
     * POSSIBLE 만 골라 오던 것과 결과를 똑같이 맞추기 위해서입니다.
     *
     * @return 메뉴 id → 위험도별 알레르기 id. 해당 등급 기록이 없는 메뉴는 키가 없으므로
     *         {@code getOrDefault(menuId, MenuAllergies.EMPTY)} 로 꺼내세요.
     * @throws DataAccessException 조회 실패 — 삼키고 빈 값을 돌려주면 모든 메뉴가
     *                             '알레르기 없음'으로 보여 위험한 메뉴가 추천에 오릅니다
     */
    public Map<Integer, MenuAllergies> getAllMenuAllergies() {
        Map<Integer, List<Integer>> containsByMenu = new HashMap<>();
        Map<Integer, List<Integer>> possibleByMenu = new HashMap<>();
        String sql = "SELECT menu_id, allergy_id, risk_level FROM menu_allergy "
                   + "UNION ALL "
                   + "SELECT menu_id, allergy_id, 'CONTAINS' AS risk_level "
                   + "FROM (" + DERIVED_CONTAINS + ") derived";

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {

            while (rs.next()) {
                int menuId = rs.getInt("menu_id");
                int allergyId = rs.getInt("allergy_id");
                String riskLevel = rs.getString("risk_level");

                Map<Integer, List<Integer>> bucket;
                if ("CONTAINS".equals(riskLevel)) {
                    bucket = containsByMenu;
                } else if ("POSSIBLE".equals(riskLevel)) {
                    bucket = possibleByMenu;
                } else {
                    continue;
                }
                bucket.computeIfAbsent(menuId, key -> new ArrayList<>()).add(allergyId);
            }
        } catch (SQLException e) {
            throw new DataAccessException("메뉴 알레르기 정보를 불러오지 못했습니다.", e);
        }

        Map<Integer, MenuAllergies> byMenu = new HashMap<>();
        Set<Integer> menuIds = new HashSet<>(containsByMenu.keySet());
        menuIds.addAll(possibleByMenu.keySet());
        for (Integer menuId : menuIds) {
            byMenu.put(menuId, new MenuAllergies(
                    containsByMenu.getOrDefault(menuId, List.of()),
                    possibleByMenu.getOrDefault(menuId, List.of())));
        }
        return byMenu;
    }

    /**
     * 특정 메뉴의 알레르기 성분 목록을 menu_allergy 테이블에 저장 (크롤러 및 동기화 파이프라인용)
     */
    public boolean saveMenuAllergies(int menuId, Set<Integer> allergyIds) {
        if (menuId <= 0 || allergyIds == null || allergyIds.isEmpty()) {
            return false;
        }
        String sql = "INSERT INTO menu_allergy (menu_id, allergy_id, risk_level, ingredient) " +
                     "VALUES (?, ?, 'CONTAINS', '') " +
                     "ON DUPLICATE KEY UPDATE risk_level = VALUES(risk_level)";
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            for (Integer allergyId : allergyIds) {
                if (allergyId == null || allergyId <= 0) continue;
                pstmt.setInt(1, menuId);
                pstmt.setInt(2, allergyId);
                pstmt.addBatch();
            }
            pstmt.executeBatch();
            return true;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 여러 메뉴의 알레르기 성분을 대량 일괄 저장 (Batch)
     */
    public int saveMenuAllergiesBatch(Map<Integer, Set<Integer>> allergiesByMenuId) {
        if (allergiesByMenuId == null || allergiesByMenuId.isEmpty()) {
            return 0;
        }
        String sql = "INSERT INTO menu_allergy (menu_id, allergy_id, risk_level, ingredient) " +
                     "VALUES (?, ?, 'CONTAINS', '') " +
                     "ON DUPLICATE KEY UPDATE risk_level = VALUES(risk_level)";
        int totalInserted = 0;
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            conn.setAutoCommit(false);
            for (Map.Entry<Integer, Set<Integer>> entry : allergiesByMenuId.entrySet()) {
                int menuId = entry.getKey();
                Set<Integer> allergies = entry.getValue();
                if (allergies == null || allergies.isEmpty()) continue;
                for (Integer allergyId : allergies) {
                    if (allergyId == null || allergyId <= 0) continue;
                    pstmt.setInt(1, menuId);
                    pstmt.setInt(2, allergyId);
                    pstmt.addBatch();
                    totalInserted++;
                }
            }
            pstmt.executeBatch();
            conn.commit();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return totalInserted;
    }
}
