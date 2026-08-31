package com.safefood.dao;

import com.safefood.dto.HistoryDto;
import com.safefood.dto.HistoryRow;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * [ SafeFood - 히스토리 DAO ]
 * DB의 history 테이블 (RECOMMENDED, EATEN, VIEWED, BLOCKED) 조회 및 저장 담당
 */
public class HistoryDao {

    private static final String TABLE = "history";

    /**
     * {@code history.feedback_id} 를 이미 확인했는지 — JVM 당 한 번만 봅니다.
     */
    private static volatile boolean feedbackColumnReady;

    /**
     * 이미 만들어져 있는 history 테이블에 {@code feedback_id} 를 채워 넣습니다.
     *
     * <p>{@code CREATE TABLE IF NOT EXISTS} 는 기존 테이블의 구조를 건드리지 않으므로
     * ({@link com.safefood.setup.core.Schema} 클래스 주석 참고), 마법사를 이미 돌린
     * 데이터베이스에는 이 컬럼이 없습니다. 없으면 여기서 한 번 붙입니다 — LocationDao 와 같은 방식입니다.
     *
     * <p>외래 키는 붙이지 못해도 그냥 넘어갑니다. 컬럼만 있으면 평가 저장·조회는 동작하고,
     * 제약이 없다고 화면을 막을 이유는 없기 때문입니다.
     */
    private static void ensureFeedbackColumn(Connection conn) throws SQLException {
        if (feedbackColumnReady) {
            return;
        }
        synchronized (HistoryDao.class) {
            if (feedbackColumnReady) {
                return;
            }
            if (!hasColumn(conn, "feedback_id")) {
                run(conn, "ALTER TABLE history ADD COLUMN feedback_id INT AFTER created_at");
                System.out.println("[db] " + TABLE + ".feedback_id 컬럼을 추가했습니다.");
                try {
                    run(conn, "ALTER TABLE history "
                            + "ADD CONSTRAINT fk_history_feedback "
                            + "FOREIGN KEY (feedback_id) REFERENCES feedback(id)");
                } catch (SQLException e) {
                    System.err.println("[db] " + TABLE + ".feedback_id 외래 키는 걸지 못했습니다"
                            + " (컬럼은 추가됐습니다): " + e.getMessage());
                }
            }
            feedbackColumnReady = true;
        }
    }

    private static boolean hasColumn(Connection conn, String column) throws SQLException {
        try (ResultSet columns = conn.getMetaData()
                .getColumns(conn.getCatalog(), null, TABLE, column)) {
            return columns.next();
        }
    }

    private static void run(Connection conn, String sql) throws SQLException {
        try (Statement statement = conn.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

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
     * 특정 회원의 최근 N일 이내 또는 가장 최근 식사 일자의 '먹음(EATEN)' 메뉴 ID 목록 조회 (전날/최근 먹은 메뉴 제외용)
     */
    public List<Integer> getRecentEatenMenuIds(int userId, int days) {
        List<Integer> eatenMenuIds = new ArrayList<>();
        String sql = "SELECT DISTINCT menu_id FROM history " +
                     "WHERE user_id = ? AND type = 'EATEN' " +
                     "AND DATE(created_at) >= DATE_SUB(CURDATE(), INTERVAL ? DAY)";

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
     * 특정 회원의 히스토리 목록 조회 (메뉴명, 식당명 포함 - UI HistoryRow)
     */
    public List<HistoryRow> getUserHistoryRows(int userId, String typeFilter) {
        List<HistoryRow> rows = new ArrayList<>();
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

                    rows.add(new HistoryRow(date, type, menu, restaurant, note));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return rows;
    }

    /**
     * 피드백 ID 업데이트
     */
    public boolean updateFeedbackId(int historyId, int feedbackId) {
        String sql = "UPDATE history SET feedback_id = ? WHERE id = ?";
        try (Connection conn = DatabaseUtil.getConnection()) {
            ensureFeedbackColumn(conn);
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setInt(1, feedbackId);
                pstmt.setInt(2, historyId);
                return pstmt.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 특정 회원의 히스토리 목록 조회 (HistoryDto)
     */
    public List<HistoryDto> findHistoriesByUserId(int userId) {
        String sql = "SELECT DATE_FORMAT(h.created_at, '%Y-%m-%d') as date, " +
                "h.type, m.name as menu_name, r.name as restaurant_name, " +
                "m.id as menu_id, r.id as restaurant_id, h.id as history_id, h.feedback_id " +
                "FROM history h " +
                "JOIN menu m ON h.menu_id = m.id " +
                "LEFT JOIN restaurant r ON m.restaurant_id = r.id " +
                "WHERE h.user_id = ? " +
                "ORDER BY h.created_at DESC";

        List<HistoryDto> list = new ArrayList<>();
        try (Connection conn = DatabaseUtil.getConnection()) {
            ensureFeedbackColumn(conn);
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setInt(1, userId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        String date = rs.getString("date");
                        String type = rs.getString("type");
                        String menu = rs.getString("menu_name");
                        String restaurant = rs.getString("restaurant_name");
                        int menuId = rs.getInt("menu_id");
                        int restaurantId = rs.getInt("restaurant_id");
                        int historyId = rs.getInt("history_id");
                        int feedbackId = rs.getInt("feedback_id");
                        list.add(new HistoryDto(date, type, menu, restaurant, "",
                                menuId, restaurantId, historyId, feedbackId));
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return list;
    }

    /**
     * 특정 회원의 '차단됨(BLOCKED)' 메뉴 ID 목록 조회
     *
     * @throws DataAccessException 조회 실패 — 삼키고 빈 목록을 돌려주면 '차단한 메뉴가 없음'과
     *                             구분이 되지 않아, 사용자가 직접 차단해 둔 메뉴가 추천에 다시
     *                             올라옵니다. 차단 사유는 '알레르기 포함'이라 안전 판정입니다
     *                             ({@link AllergyDao#getUserAllergyIds} 와 같은 규칙)
     */
    public List<Integer> getBlockedMenuIds(int userId) {
        List<Integer> blockedMenuIds = new ArrayList<>();
        String sql = "SELECT DISTINCT menu_id FROM history WHERE user_id = ? AND type = 'BLOCKED'";

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, userId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    blockedMenuIds.add(rs.getInt("menu_id"));
                }
            }
        } catch (SQLException e) {
            throw new DataAccessException("차단한 메뉴 목록을 불러오지 못했습니다.", e);
        }
        return blockedMenuIds;
    }

    /**
     * 히스토리 타입 변경 (예: EATEN -> BLOCKED)
     */
    public boolean updateHistoryType(int historyId, String type) {
        String sql = "UPDATE history SET type = ? WHERE id = ?";
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, type);
            pstmt.setInt(2, historyId);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 히스토리 기록 삭제 (차단 해제 등)
     */
    public boolean deleteHistory(int historyId) {
        String sql = "DELETE FROM history WHERE id = ?";
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, historyId);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 메뉴 차단 (history 테이블에 BLOCKED로 추가)
     */
    public boolean blockMenu(int userId, int menuId) {
        String checkSql = "SELECT id FROM history WHERE user_id = ? AND menu_id = ? AND type = 'BLOCKED'";
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement checkStmt = conn.prepareStatement(checkSql)) {
            checkStmt.setInt(1, userId);
            checkStmt.setInt(2, menuId);
            try (ResultSet rs = checkStmt.executeQuery()) {
                if (rs.next()) {
                    return true;
                }
            }
            return insertHistory(userId, menuId, null, "BLOCKED");
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 메뉴 차단 해제 (BLOCKED 행을 EATEN으로 변경)
     */
    public boolean unblockMenuToEaten(int userId, int menuId) {
        String sql = "UPDATE history SET type = 'EATEN' WHERE user_id = ? AND menu_id = ? AND type = 'BLOCKED'";
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, userId);
            pstmt.setInt(2, menuId);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 메뉴 차단 해제 (BLOCKED 행 삭제)
     */
    public boolean unblockMenu(int userId, int menuId) {
        String sql = "DELETE FROM history WHERE user_id = ? AND menu_id = ? AND type = 'BLOCKED'";
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, userId);
            pstmt.setInt(2, menuId);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }
}
