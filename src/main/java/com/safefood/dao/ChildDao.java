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
 * <p><b>아이와 알레르기는 언제나 한 트랜잭션으로 씁니다</b> ({@link #insertWithAllergies},
 * {@link #updateWithAllergies}). 예전에는 아이를 먼저 넣고 알레르기를 따로 넣어서, 뒤엣것이
 * 실패하면 <b>알레르기가 하나도 없는 아이</b>가 남았습니다 — 급식이 전부 안전해 보이는 상태라
 * 알레르기 앱에서 가장 나쁜 결과입니다.
 *
 * <p>{@code child}·{@code child_allergy} 는 뒤늦게 추가된 테이블이라, 설정 마법사를 다시 돌리지 않은
 * 데이터베이스에는 없습니다. {@link LocationDao} 와 같은 방식으로 처음 쓸 때 한 번 만듭니다.
 */
public class ChildDao {

    private static volatile boolean tablesReady;

    private static final String SAVE_FAILED = "저장하지 못했습니다. 연결을 확인해 주세요.";

    private static final String MASTER_UNREADABLE =
            "알레르기 목록을 확인하지 못해 저장하지 않았습니다. 잠시 뒤에 다시 시도해 주세요.";

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

    public record Saved(int childId, String failure) {

        public boolean ok() {
            return failure == null;
        }

        static Saved fail(String reason) {
            return new Saved(-1, reason);
        }
    }

    public Saved insertWithAllergies(int userId, String name, String atptCode, String schulCode,
                                     String schulNm, Map<String, Integer> severityByName) {
        Map<Integer, Integer> severityById = resolveSeverity(severityByName);
        if (severityById == null) {
            return Saved.fail(MASTER_UNREADABLE);
        }
        String sql = "INSERT INTO child (user_id, name, atpt_code, schul_code, schul_nm) VALUES (?, ?, ?, ?, ?)";
        return inTransaction(conn -> {
            int childId;
            try (PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                pstmt.setInt(1, userId);
                pstmt.setString(2, name);
                pstmt.setString(3, atptCode);
                pstmt.setString(4, schulCode);
                pstmt.setString(5, schulNm);
                if (pstmt.executeUpdate() == 0) {
                    return Saved.fail(SAVE_FAILED);
                }
                try (ResultSet keys = pstmt.getGeneratedKeys()) {
                    childId = keys.next() ? keys.getInt(1) : -1;
                }
            }
            if (childId <= 0) {
                return Saved.fail(SAVE_FAILED);
            }
            writeAllergies(conn, childId, severityById);
            return new Saved(childId, null);
        });
    }

    public Saved updateWithAllergies(int childId, String name, String atptCode, String schulCode,
                                     String schulNm, Map<String, Integer> severityByName) {
        Map<Integer, Integer> severityById = resolveSeverity(severityByName);
        if (severityById == null) {
            return Saved.fail(MASTER_UNREADABLE);
        }
        String sql = "UPDATE child SET name = ?, atpt_code = ?, schul_code = ?, schul_nm = ? WHERE id = ?";
        return inTransaction(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, name);
                pstmt.setString(2, atptCode);
                pstmt.setString(3, schulCode);
                pstmt.setString(4, schulNm);
                pstmt.setInt(5, childId);
                if (pstmt.executeUpdate() == 0) {
                    return Saved.fail("고칠 아이를 찾지 못했습니다. 목록을 새로 불러와 주세요.");
                }
            }
            writeAllergies(conn, childId, severityById);
            return new Saved(childId, null);
        });
    }

    /** 알레르기까지 한 트랜잭션으로 지웁니다. */
    public boolean delete(int childId) {
        return inTransaction(conn -> {
            try (PreparedStatement pstmt =
                         conn.prepareStatement("DELETE FROM child_allergy WHERE child_id = ?")) {
                pstmt.setInt(1, childId);
                pstmt.executeUpdate();
            }
            try (PreparedStatement pstmt = conn.prepareStatement("DELETE FROM child WHERE id = ?")) {
                pstmt.setInt(1, childId);
                if (pstmt.executeUpdate() == 0) {
                    return Saved.fail("지울 아이를 찾지 못했습니다.");
                }
            }
            return new Saved(childId, null);
        }).ok();
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

    private static Map<Integer, Integer> resolveSeverity(Map<String, Integer> severityByName) {
        Map<String, Integer> wanted = severityByName == null ? Map.of() : severityByName;
        Map<Integer, Integer> severityById = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : wanted.entrySet()) {
            Integer allergyId = AllergyCatalog.idOf(entry.getKey());
            if (allergyId == null) {
                System.err.println("[child] 알레르기 마스터에서 " + entry.getKey()
                        + " 을(를) 찾지 못해 저장하지 않았습니다.");
                return null;
            }
            severityById.put(allergyId, clampSeverity(entry.getValue()));
        }
        return severityById;
    }

    private static void writeAllergies(Connection conn, int childId,
                                       Map<Integer, Integer> severityById) throws SQLException {
        try (PreparedStatement delete =
                     conn.prepareStatement("DELETE FROM child_allergy WHERE child_id = ?")) {
            delete.setInt(1, childId);
            delete.executeUpdate();
        }
        if (severityById.isEmpty()) {
            return;
        }
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

    private static int clampSeverity(Integer severity) {
        return severity == null ? 3 : Math.max(1, Math.min(5, severity));
    }

    @FunctionalInterface
    private interface Work {
        Saved run(Connection conn) throws SQLException;
    }

    private Saved inTransaction(Work work) {
        try (Connection conn = DatabaseUtil.getConnection()) {
            ensureTables(conn);
            boolean previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                Saved saved = work.run(conn);
                if (saved.ok()) {
                    conn.commit();
                } else {
                    conn.rollback();
                }
                return saved;
            } catch (SQLException e) {
                conn.rollback();
                System.err.println("[child] 저장하지 못해 이전 상태로 되돌렸습니다: " + e.getMessage());
                return Saved.fail(explain(e));
            } finally {
                conn.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException e) {
            System.err.println("[child] 저장 중 연결 오류: " + e.getMessage());
            return Saved.fail(SAVE_FAILED);
        }
    }

    private static String explain(SQLException e) {
        return e.getErrorCode() == 1062
                ? "같은 이름의 아이가 이미 있어요. 이름을 다르게 적어 주세요."
                : SAVE_FAILED;
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
            addUniqueName(conn);
            tablesReady = true;
        }
    }

    private static void addUniqueName(Connection conn) throws SQLException {
        try (Statement statement = conn.createStatement()) {
            statement.executeUpdate(Schema.indexDdlOf("child", "uq_child_user_name"));
        } catch (SQLException e) {
            if (e.getErrorCode() == 1061) {
                return;
            }
            if (e.getErrorCode() == 1062) {
                System.err.println("[child] 같은 회원에 같은 이름의 아이가 이미 있어"
                        + " uq_child_user_name 을 붙이지 못했습니다. 이름을 고쳐 주세요.");
                return;
            }
            throw e;
        }
    }
}
