package com.safefood.dao;

import com.safefood.dto.Child;
import com.safefood.setup.core.Schema;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 아이와 아이의 알레르기.
 *
 * <p>급식 자체는 저장하지 않습니다 — 나이스에서 그때그때 받아 파일로만 캐시합니다.
 * DB 에 남는 것은 <b>우리가 만든 데이터</b>인 아이와 그 알레르기뿐입니다.
 *
 * <p>{@code child}·{@code child_allergy} 는 뒤늦게 추가된 테이블이라, 설정 마법사를 다시 돌리지 않은
 * 데이터베이스에는 없습니다. {@link LocationDao} 와 같은 방식으로 처음 쓸 때 한 번 만듭니다.
 */
public class ChildDao {

    private static volatile boolean tablesReady;

    // ---- 조회 ----

    public List<Child> findByUser(int userId) {
        String sql = "SELECT id, user_id, name, atpt_code, schul_code, schul_nm "
                + "FROM child WHERE user_id = ? ORDER BY id";
        List<Child> children = new ArrayList<>();
        try (Connection conn = DatabaseUtil.getConnection()) {
            ensureTables(conn);
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setInt(1, userId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        children.add(map(rs));
                    }
                }
            }
        } catch (SQLException e) {
            throw new DataAccessException("등록한 아이를 불러오지 못했습니다.", e);
        }
        return children;
    }

    public Child findById(int childId) {
        String sql = "SELECT id, user_id, name, atpt_code, schul_code, schul_nm FROM child WHERE id = ?";
        try (Connection conn = DatabaseUtil.getConnection()) {
            ensureTables(conn);
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setInt(1, childId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    return rs.next() ? map(rs) : null;
                }
            }
        } catch (SQLException e) {
            throw new DataAccessException("아이 정보를 불러오지 못했습니다.", e);
        }
    }

    private static Child map(ResultSet rs) throws SQLException {
        return new Child(rs.getInt("id"), rs.getInt("user_id"), rs.getString("name"),
                rs.getString("atpt_code"), rs.getString("schul_code"), rs.getString("schul_nm"));
    }

    // ---- 저장 ----

    /** @return 새로 만든 아이의 id. 실패하면 -1 */
    public int insert(int userId, String name, String atptCode, String schulCode, String schulNm) {
        String sql = "INSERT INTO child (user_id, name, atpt_code, schul_code, schul_nm) VALUES (?, ?, ?, ?, ?)";
        try (Connection conn = DatabaseUtil.getConnection()) {
            ensureTables(conn);
            try (PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                pstmt.setInt(1, userId);
                pstmt.setString(2, name);
                pstmt.setString(3, atptCode);
                pstmt.setString(4, schulCode);
                pstmt.setString(5, schulNm);
                if (pstmt.executeUpdate() == 0) {
                    return -1;
                }
                try (ResultSet keys = pstmt.getGeneratedKeys()) {
                    return keys.next() ? keys.getInt(1) : -1;
                }
            }
        } catch (SQLException e) {
            System.err.println("[child] 아이를 저장하지 못했습니다: " + e.getMessage());
            return -1;
        }
    }

    public boolean update(int childId, String name, String atptCode, String schulCode, String schulNm) {
        String sql = "UPDATE child SET name = ?, atpt_code = ?, schul_code = ?, schul_nm = ? WHERE id = ?";
        try (Connection conn = DatabaseUtil.getConnection()) {
            ensureTables(conn);
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, name);
                pstmt.setString(2, atptCode);
                pstmt.setString(3, schulCode);
                pstmt.setString(4, schulNm);
                pstmt.setInt(5, childId);
                return pstmt.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            System.err.println("[child] 아이 정보를 고치지 못했습니다: " + e.getMessage());
            return false;
        }
    }

    /** 알레르기까지 한 트랜잭션으로 지웁니다. */
    public boolean delete(int childId) {
        try (Connection conn = DatabaseUtil.getConnection()) {
            ensureTables(conn);
            boolean previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement pstmt =
                             conn.prepareStatement("DELETE FROM child_allergy WHERE child_id = ?")) {
                    pstmt.setInt(1, childId);
                    pstmt.executeUpdate();
                }
                try (PreparedStatement pstmt = conn.prepareStatement("DELETE FROM child WHERE id = ?")) {
                    pstmt.setInt(1, childId);
                    if (pstmt.executeUpdate() == 0) {
                        conn.rollback();
                        return false;
                    }
                }
                conn.commit();
                return true;
            } catch (SQLException e) {
                conn.rollback();
                System.err.println("[child] 아이를 지우지 못해 되돌렸습니다: " + e.getMessage());
                return false;
            } finally {
                conn.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException e) {
            System.err.println("[child] 아이 삭제 중 연결 오류: " + e.getMessage());
            return false;
        }
    }

    // ---- 알레르기 ----

    /** 아이의 알레르기 → 심각도. 이름은 {@code allergy} 테이블에서 조인해 읽습니다. */
    public Map<String, Integer> getAllergies(int childId) {
        String sql = "SELECT a.name AS allergy_name, ca.severity "
                + "FROM child_allergy ca JOIN allergy a ON a.id = ca.allergy_id "
                + "WHERE ca.child_id = ? ORDER BY a.id";
        Map<String, Integer> map = new LinkedHashMap<>();
        try (Connection conn = DatabaseUtil.getConnection()) {
            ensureTables(conn);
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setInt(1, childId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        map.put(rs.getString("allergy_name"), rs.getInt("severity"));
                    }
                }
            }
        } catch (SQLException e) {
            // 삼키면 '알레르기 없는 아이'로 보여 모든 급식이 안전해 보입니다
            throw new DataAccessException("아이의 알레르기를 불러오지 못했습니다.", e);
        }
        return map;
    }

    /**
     * 알레르기 목록을 통째로 갈아 끼웁니다 — {@code ProfileDao.replaceAllergies} 와 같은 규칙입니다.
     * 이름을 번호로 바꾸는 일을 <b>DB 를 건드리기 전에</b> 끝내고, 하나라도 못 바꾸면 지우지 않습니다.
     */
    public boolean replaceAllergies(int childId, Map<String, Integer> severityByName) {
        Map<String, Integer> wanted = severityByName == null ? Map.of() : severityByName;

        Map<Integer, Integer> severityById = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : wanted.entrySet()) {
            Integer allergyId = AllergyCatalog.idOf(entry.getKey());
            if (allergyId == null) {
                System.err.println("[child] 알레르기 마스터에서 " + entry.getKey()
                        + " 을(를) 찾지 못해 저장하지 않았습니다.");
                return false;
            }
            severityById.put(allergyId, clampSeverity(entry.getValue()));
        }

        try (Connection conn = DatabaseUtil.getConnection()) {
            ensureTables(conn);
            boolean previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement delete =
                             conn.prepareStatement("DELETE FROM child_allergy WHERE child_id = ?")) {
                    delete.setInt(1, childId);
                    delete.executeUpdate();
                }
                if (!severityById.isEmpty()) {
                    String insert = "INSERT INTO child_allergy (child_id, allergy_id, severity) VALUES (?, ?, ?)";
                    try (PreparedStatement pstmt = conn.prepareStatement(insert)) {
                        for (Map.Entry<Integer, Integer> entry : severityById.entrySet()) {
                            pstmt.setInt(1, childId);
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
                System.err.println("[child] 알레르기를 저장하지 못해 이전 상태로 되돌렸습니다: " + e.getMessage());
                return false;
            } finally {
                conn.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException e) {
            System.err.println("[child] 알레르기 저장 중 연결 오류: " + e.getMessage());
            return false;
        }
    }

    private static int clampSeverity(Integer severity) {
        return severity == null ? 3 : Math.max(1, Math.min(5, severity));
    }

    // ---- 테이블 준비 ----

    private static void ensureTables(Connection conn) throws SQLException {
        if (tablesReady) {
            return;
        }
        synchronized (ChildDao.class) {
            if (tablesReady) {
                return;
            }
            try (Statement statement = conn.createStatement()) {
                statement.executeUpdate(Schema.ddlOf("child"));
                statement.executeUpdate(Schema.ddlOf("child_allergy"));
            }
            tablesReady = true;
        }
    }
}
