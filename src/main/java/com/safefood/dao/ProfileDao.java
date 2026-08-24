package com.safefood.dao;

import com.safefood.dto.PreferenceDto;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


public class ProfileDao {

    // 내 취향 정보 가져오기
    public PreferenceDto getPreference(int userId){
        // 유저 맵기/최소가격/최대가격/최대거리 가져옴
        String sql = "SELECT spicy_level, price_min, price_max, max_distance FROM user_preference WHERE user_id = ?";
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)){
            pstmt.setInt(1, userId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return new PreferenceDto(
                            rs.getInt("spicy_level"), rs.getInt("price_min"),
                            rs.getInt("price_max"), rs.getInt("max_distance")
                    );
                }
            }
        }catch (SQLException e) { e.printStackTrace(); }
        return null;
    }

    // 내 선호 음식 종류 가져오기(List 사용)
    public List<String> getCategories(int userId){
        List<String> list = new ArrayList<>();
        // 선택 카테고리 유저 카테고리에서 유저아이디에 맞는것.
        String sql = "SELECT category from user_category WHERE user_id = ?";
        try (Connection conn = DatabaseUtil.getConnection();
            PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, userId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    list.add(rs.getString("category"));
                }
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return list;
    }

    /**
     * 내 알레르기와 심각도 가져오기 (이름 → 심각도).
     *
     * <p>이름은 {@code allergy} 테이블에서 <b>조인해서</b> 읽습니다. 예전처럼 화면 목록의
     * 순서로 id 를 되돌리면 마스터 순서가 바뀌는 순간 다른 알레르기로 보입니다
     * ({@link AllergyCatalog} 참고).
     */
    public Map<String, Integer> getAllergies(int userId) {
        Map<String, Integer> map = new LinkedHashMap<>();
        String sql = "SELECT a.name AS allergy_name, ua.severity " +
                     "FROM user_allergy ua " +
                     "JOIN allergy a ON a.id = ua.allergy_id " +
                     "WHERE ua.user_id = ? " +
                     "ORDER BY a.id";
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)){
            pstmt.setInt(1, userId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    map.put(rs.getString("allergy_name"), rs.getInt("severity"));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return map;
    }


    // 단일 취향 업데이트 (데이터가 없으면 새로 넣고, 있으면 덮어쓰기)
    public void updatePreference(int userId, int spicy_level, int priceMax, int maxDistance){
        String sql = "INSERT INTO user_preference (user_id, spicy_level, price_min, price_max, max_distance) " +
                     "VALUES (?, ?, 0, ?, ?) " +
                     "ON DUPLICATE KEY UPDATE spicy_level = ?, price_max = ?, max_distance = ?";
        try (Connection conn = DatabaseUtil.getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)){
            // INSERT 용
            pstmt.setInt(1, userId);
            pstmt.setInt(2, spicy_level);
            pstmt.setInt(3, priceMax);
            pstmt.setInt(4, maxDistance);

            // UPDATE 용
            pstmt.setInt(5, spicy_level);
            pstmt.setInt(6, priceMax);
            pstmt.setInt(7, maxDistance);

            pstmt.executeUpdate();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 알레르기 목록을 통째로 갈아 끼웁니다 — <b>한 트랜잭션</b>으로.
     *
     * <p>예전에는 DELETE 와 INSERT 가 각각 별도 커넥션이라, 중간에 끊기면 알레르기 목록이
     * <b>비워진 채로</b> 남았습니다. 못 먹는 재료가 사라진 프로필로 추천이 돌아가는 상황이라
     * 반드시 전부 되거나 전부 안 되어야 합니다.
     *
     * <p>이름을 번호로 바꾸는 일도 <b>DB 를 건드리기 전에</b> 끝냅니다. 마스터에 없는 이름이
     * 하나라도 있으면 지우지 않고 그대로 실패합니다.
     *
     * @param severityByName 알레르기 이름 → 심각도(1~5). 빈 Map 이면 전부 지웁니다.
     * @return 저장 성공 여부
     */
    public boolean replaceAllergies(int userId, Map<String, Integer> severityByName) {
        Map<String, Integer> wanted = severityByName == null ? Map.of() : severityByName;

        // 1. 먼저 전부 번호로 바꿔 봅니다 — 하나라도 못 바꾸면 DB 는 손대지 않습니다
        Map<Integer, Integer> severityById = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : wanted.entrySet()) {
            Integer allergyId = AllergyCatalog.idOf(entry.getKey());
            if (allergyId == null) {
                System.err.println("[profile] 알레르기 마스터에서 " + entry.getKey()
                        + " 을(를) 찾지 못해 저장하지 않았습니다.");
                return false;
            }
            severityById.put(allergyId, clampSeverity(entry.getValue()));
        }

        // 2. 지우고 다시 넣는 일을 한 트랜잭션으로
        try (Connection conn = DatabaseUtil.getConnection()) {
            boolean previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement delete =
                             conn.prepareStatement("DELETE FROM user_allergy WHERE user_id = ?")) {
                    delete.setInt(1, userId);
                    delete.executeUpdate();
                }
                if (!severityById.isEmpty()) {
                    String insert = "INSERT INTO user_allergy (user_id, allergy_id, severity) VALUES (?, ?, ?)";
                    try (PreparedStatement pstmt = conn.prepareStatement(insert)) {
                        for (Map.Entry<Integer, Integer> entry : severityById.entrySet()) {
                            pstmt.setInt(1, userId);
                            pstmt.setInt(2, entry.getKey());
                            pstmt.setInt(3, entry.getValue());
                            pstmt.addBatch();
                        }
                        pstmt.executeBatch();
                    }
                }
                conn.commit();
                return true;
            } catch (SQLException e) {
                conn.rollback();
                System.err.println("[profile] 알레르기를 저장하지 못해 이전 상태로 되돌렸습니다: " + e.getMessage());
                return false;
            } finally {
                conn.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException e) {
            System.err.println("[profile] 알레르기 저장 중 연결 오류: " + e.getMessage());
            return false;
        }
    }

    /**
     * 선호 카테고리를 통째로 갈아 끼웁니다 — 알레르기와 같은 이유로 한 트랜잭션입니다.
     */
    public boolean replaceCategories(int userId, List<String> categories) {
        List<String> wanted = categories == null ? List.of() : categories;

        try (Connection conn = DatabaseUtil.getConnection()) {
            boolean previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement delete =
                             conn.prepareStatement("DELETE FROM user_category WHERE user_id = ?")) {
                    delete.setInt(1, userId);
                    delete.executeUpdate();
                }
                if (!wanted.isEmpty()) {
                    String insert = "INSERT INTO user_category (user_id, category) VALUES (?, ?)";
                    try (PreparedStatement pstmt = conn.prepareStatement(insert)) {
                        for (String category : wanted) {
                            pstmt.setInt(1, userId);
                            pstmt.setString(2, category);
                            pstmt.addBatch();
                        }
                        pstmt.executeBatch();
                    }
                }
                conn.commit();
                return true;
            } catch (SQLException e) {
                conn.rollback();
                System.err.println("[profile] 선호 카테고리를 저장하지 못해 이전 상태로 되돌렸습니다: "
                        + e.getMessage());
                return false;
            } finally {
                conn.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException e) {
            System.err.println("[profile] 선호 카테고리 저장 중 연결 오류: " + e.getMessage());
            return false;
        }
    }

    /** user_allergy.severity 는 TINYINT 1~5 입니다 — 범위를 벗어난 값은 보통(3)으로 봅니다. */
    private static int clampSeverity(Integer severity) {
        if (severity == null) {
            return 3;
        }
        return Math.max(1, Math.min(5, severity));
    }
}
