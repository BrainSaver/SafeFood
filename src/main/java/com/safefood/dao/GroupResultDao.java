package com.safefood.dao;

import com.safefood.setup.core.Schema;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

public class GroupResultDao {

    public record MemberRow(String name, int userId, List<String> allergies,
                            int votedCandidateIndex) {
    }

    public record CandidateRow(int menuId, int score, String reason) {
    }

    public record GroupRecord(String inviteCode, int ownerUserId, List<MemberRow> members,
                              List<CandidateRow> candidates, int winningIndex) {
    }

    private static final List<String> TABLES = List.of(
            "dining_group", "group_member", "group_member_allergy", "group_candidate", "group_vote");

    private static final int INVITE_CODE_WIDTH = 20;
    private static final int REASON_WIDTH = 255;

    private static volatile boolean tablesReady = false;

    public boolean save(GroupRecord record) {
        if (record == null || record.ownerUserId() <= 0 || record.candidates().isEmpty()
                || record.winningIndex() < 0
                || record.winningIndex() >= record.candidates().size()) {
            return false;
        }

        List<List<Integer>> allergyIds = resolveAllergyIds(record.members());

        try (Connection conn = DatabaseUtil.getConnection()) {
            ensureTables(conn);
            boolean previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                int groupId = insertGroup(conn, record);
                List<Integer> memberIds = insertMembers(conn, groupId, record.members());
                insertAllergies(conn, memberIds, allergyIds);
                List<Integer> candidateIds = insertCandidates(conn, groupId, record.candidates());
                insertVotes(conn, memberIds, candidateIds, record.members());
                insertHistory(conn, groupId, record);
                conn.commit();
                return true;
            } catch (SQLException e) {
                conn.rollback();
                System.err.println("[group] 그룹 결과를 저장하지 못해 되돌렸습니다: " + e.getMessage());
                return false;
            } finally {
                conn.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException e) {
            System.err.println("[group] 그룹 결과 저장 중 연결 오류: " + e.getMessage());
            return false;
        }
    }

    private static List<List<Integer>> resolveAllergyIds(List<MemberRow> members) {
        List<List<Integer>> resolved = new ArrayList<>();
        for (MemberRow member : members) {
            List<Integer> ids = new ArrayList<>();
            for (String name : member.allergies()) {
                Integer allergyId = AllergyCatalog.idOf(name);
                if (allergyId != null) {
                    ids.add(allergyId);
                } else {
                    System.err.println("[group] 알레르기 마스터에 없는 이름이라 기록에서 빠집니다: " + name);
                }
            }
            resolved.add(ids);
        }
        return resolved;
    }

    private static int insertGroup(Connection conn, GroupRecord record) throws SQLException {
        String sql = "INSERT INTO dining_group (owner_id, invite_code, status) "
                   + "VALUES (?, ?, 'CLOSED')";
        try (PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            pstmt.setInt(1, record.ownerUserId());
            pstmt.setString(2, record.inviteCode());
            pstmt.executeUpdate();
            return generatedKey(pstmt);
        }
    }

    private static List<Integer> insertMembers(Connection conn, int groupId, List<MemberRow> members)
            throws SQLException {
        String sql = "INSERT INTO group_member (group_id, user_id, guest_name) VALUES (?, ?, ?)";
        List<Integer> ids = new ArrayList<>();
        try (PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            for (MemberRow member : members) {
                pstmt.setInt(1, groupId);
                if (member.userId() > 0) {
                    pstmt.setInt(2, member.userId());
                    pstmt.setNull(3, Types.VARCHAR);
                } else {
                    pstmt.setNull(2, Types.INTEGER);
                    pstmt.setString(3, member.name());
                }
                pstmt.executeUpdate();
                ids.add(generatedKey(pstmt));
            }
        }
        return ids;
    }

    private static void insertAllergies(Connection conn, List<Integer> memberIds,
                                        List<List<Integer>> allergyIds) throws SQLException {
        String sql = "INSERT IGNORE INTO group_member_allergy (member_id, allergy_id) VALUES (?, ?)";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            for (int i = 0; i < memberIds.size() && i < allergyIds.size(); i++) {
                for (int allergyId : allergyIds.get(i)) {
                    pstmt.setInt(1, memberIds.get(i));
                    pstmt.setInt(2, allergyId);
                    pstmt.addBatch();
                }
            }
            pstmt.executeBatch();
        }
    }

    private static List<Integer> insertCandidates(Connection conn, int groupId,
                                                  List<CandidateRow> candidates) throws SQLException {
        String sql = "INSERT INTO group_candidate (group_id, menu_id, score, reason) "
                   + "VALUES (?, ?, ?, ?)";
        List<Integer> ids = new ArrayList<>();
        try (PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            for (CandidateRow candidate : candidates) {
                pstmt.setInt(1, groupId);
                pstmt.setInt(2, candidate.menuId());
                pstmt.setInt(3, candidate.score());
                pstmt.setString(4, clip(candidate.reason()));
                pstmt.executeUpdate();
                ids.add(generatedKey(pstmt));
            }
        }
        return ids;
    }

    private static void insertVotes(Connection conn, List<Integer> memberIds,
                                    List<Integer> candidateIds, List<MemberRow> members)
            throws SQLException {
        String sql = "INSERT INTO group_vote (candidate_id, member_id) VALUES (?, ?)";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            for (int i = 0; i < members.size() && i < memberIds.size(); i++) {
                int voted = members.get(i).votedCandidateIndex();
                if (voted < 0 || voted >= candidateIds.size()) {
                    continue;
                }
                pstmt.setInt(1, candidateIds.get(voted));
                pstmt.setInt(2, memberIds.get(i));
                pstmt.addBatch();
            }
            pstmt.executeBatch();
        }
    }

    private static void insertHistory(Connection conn, int groupId, GroupRecord record)
            throws SQLException {
        String sql = "INSERT INTO history (user_id, menu_id, group_id, type) VALUES (?, ?, ?, 'EATEN')";
        int menuId = record.candidates().get(record.winningIndex()).menuId();
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            for (MemberRow member : record.members()) {
                if (member.userId() <= 0) {
                    continue;
                }
                pstmt.setInt(1, member.userId());
                pstmt.setInt(2, menuId);
                pstmt.setInt(3, groupId);
                pstmt.addBatch();
            }
            pstmt.executeBatch();
        }
    }

    private static int generatedKey(PreparedStatement pstmt) throws SQLException {
        try (ResultSet keys = pstmt.getGeneratedKeys()) {
            if (!keys.next()) {
                throw new SQLException("생성된 기본키를 받지 못했습니다.");
            }
            return keys.getInt(1);
        }
    }

    private static String clip(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= REASON_WIDTH ? reason : reason.substring(0, REASON_WIDTH);
    }

    private static void ensureTables(Connection conn) throws SQLException {
        if (tablesReady) {
            return;
        }
        synchronized (GroupResultDao.class) {
            if (tablesReady) {
                return;
            }
            for (String table : TABLES) {
                if (!exists(conn, table)) {
                    run(conn, Schema.ddlOf(table));
                    System.out.println("[db] " + table + " 테이블을 새로 만들었습니다.");
                }
            }
            widenInviteCode(conn);
            tablesReady = true;
        }
    }

    private static void widenInviteCode(Connection conn) throws SQLException {
        try (ResultSet columns = conn.getMetaData()
                .getColumns(conn.getCatalog(), null, "dining_group", "invite_code")) {
            if (columns.next() && columns.getInt("COLUMN_SIZE") < INVITE_CODE_WIDTH) {
                run(conn, "ALTER TABLE dining_group "
                        + "MODIFY invite_code VARCHAR(" + INVITE_CODE_WIDTH + ") NOT NULL");
                System.out.println("[db] dining_group.invite_code 를 "
                        + INVITE_CODE_WIDTH + "자로 넓혔습니다.");
            }
        }
    }

    private static boolean exists(Connection conn, String table) throws SQLException {
        try (ResultSet tables = conn.getMetaData()
                .getTables(conn.getCatalog(), null, table, new String[]{"TABLE"})) {
            return tables.next();
        }
    }

    private static void run(Connection conn, String sql) throws SQLException {
        try (Statement statement = conn.createStatement()) {
            statement.executeUpdate(sql);
        }
    }
}
