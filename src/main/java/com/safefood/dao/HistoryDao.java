package com.safefood.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * [ SafeFood - 히스토리 DAO ]
 * DB의 history 테이블 (RECOMMENDED, EATEN, VIEWED, BLOCKED) 조회 및 저장 담당
 */
public class HistoryDao {

    /**
     * 특정 회원의 '먹음(EATEN)' 이력에 있는 메뉴 ID 목록 조회 (재추천 방지용)
     */
    public List<Integer> getEatenMenuIds(int userId) {
        List<Integer> eatenMenuIds = new ArrayList<>();
        String sql = "SELECT DISTINCT menu_id FROM history WHERE user_id = ? AND type = 'EATEN'";

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, userId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    eatenMenuIds.add(rs.getInt("menu_id"));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return eatenMenuIds;
    }

    /**
     * 특정 회원의 최근 N일 이내 '먹음(EATEN)' 메뉴 ID 목록 조회 (전날/최근 먹은 메뉴 제외용)
     */
    public List<Integer> getRecentEatenMenuIds(int userId, int days) {
        List<Integer> eatenMenuIds = new ArrayList<>();
        String sql = "SELECT DISTINCT menu_id FROM history " +
                     "WHERE user_id = ? AND type = 'EATEN' AND created_at >= DATE_SUB(NOW(), INTERVAL ? DAY)";

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, userId);
            pstmt.setInt(2, days);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    eatenMenuIds.add(rs.getInt("menu_id"));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return eatenMenuIds;
    }

    /**
     * 히스토리 기록 추가
     */
    public boolean insertHistory(int userId, int menuId, Integer groupId, String type) {
        String sql = "INSERT INTO history (user_id, menu_id, group_id, type) VALUES (?, ?, ?, ?)";

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, userId);
            pstmt.setInt(2, menuId);
            if (groupId != null) {
                pstmt.setInt(3, groupId);
            } else {
                pstmt.setNull(3, Types.INTEGER);
            }
            pstmt.setString(4, type);

            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 특정 회원의 히스토리 목록 조회 (메뉴명, 식당명 포함)
     */
    public List<com.safefood.view.DemoData.HistoryRow> getUserHistoryRows(int userId, String typeFilter) {
        List<com.safefood.view.DemoData.HistoryRow> rows = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT h.created_at, h.type, m.name AS menu_name, r.name AS restaurant_name " +
                "FROM history h " +
                "JOIN menu m ON h.menu_id = m.id " +
                "LEFT JOIN restaurant r ON m.restaurant_id = r.id " +
                "WHERE h.user_id = ? "
        );

        String typeEnum = null;
        if ("추천받음".equals(typeFilter)) typeEnum = "RECOMMENDED";
        else if ("먹음".equals(typeFilter)) typeEnum = "EATEN";
        else if ("조회함".equals(typeFilter)) typeEnum = "VIEWED";
        else if ("차단됨".equals(typeFilter)) typeEnum = "BLOCKED";

        if (typeEnum != null) {
            sql.append("AND h.type = ? ");
        }
        sql.append("ORDER BY h.created_at DESC");

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql.toString())) {

            pstmt.setInt(1, userId);
            if (typeEnum != null) {
                pstmt.setString(2, typeEnum);
            }

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String date = rs.getTimestamp("created_at") != null
                            ? rs.getTimestamp("created_at").toLocalDateTime().toLocalDate().toString()
                            : "";
                    String type = rs.getString("type");
                    String menu = rs.getString("menu_name");
                    String restaurant = rs.getString("restaurant_name") != null ? rs.getString("restaurant_name") : "";
                    String note = "EATEN".equals(type) ? "식사 완료"
                            : ("BLOCKED".equals(type) ? "알레르기 포함으로 차단" : "알레르기 안전");

                    rows.add(new com.safefood.view.DemoData.HistoryRow(date, type, menu, restaurant, note));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return rows;
    }
}
