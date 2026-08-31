package com.safefood.dao;

import com.safefood.dto.UserDto;
import java.sql.*;

public class UserDao {

    /**
     * 신규 회원 저장 (회원가입)
     * 비밀번호는 반드시 해시값으로 저장되어야 합니다.
     */
    public boolean insertUser(UserDto user) {
        String sql = "INSERT INTO `user` (login_id, password, nickname) VALUES (?, ?, ?)";

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, user.getLoginId());
            pstmt.setString(2, user.getPassword());
            pstmt.setString(3, user.getNickname());

            int result = pstmt.executeUpdate();
            return result > 0;

        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 아이디로 회원 조회 (로그인 및 아이디 중복 확인용)
     */
    public UserDto findByLoginId(String loginId) {
        String sql = "SELECT id, login_id, password, nickname, created_at FROM `user` WHERE login_id = ?";
        UserDto user = null;

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, loginId);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    user = new UserDto();
                    user.setId(rs.getInt("id"));
                    user.setLoginId(rs.getString("login_id"));
                    user.setPassword(rs.getString("password"));
                    user.setNickname(rs.getString("nickname"));
                    user.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
                    // session_token 은 일부러 읽지 않습니다 — 로그인이 이 칼럼에 매달리면,
                    // 아직 칼럼을 추가하지 않은 DB 에서는 로그인 자체가 통째로 실패합니다.
                    // 어차피 login() 이 새 표를 발급하므로 예전 값은 쓸 데도 없습니다.
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return user;
    }


    // 닉네임 중복 확인
    public UserDto findByNickname(String nickname) {
        String sql = "SELECT id FROM `user` WHERE nickname = ?";
        UserDto user = null;
        try (Connection conn = DatabaseUtil.getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, nickname);
            try (ResultSet rs = pstmt.executeQuery()) {
                if(rs.next()) {
                    user = new UserDto(); // 데이터가 하나라도 있다면 객체 만들기(중복이라는 뜻)
                }
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return user;
    }


    // 회원 정보 덮어쓰기
    /**
     * 로그인할 때 발급한 세션 표를 저장합니다. <b>나중에 로그인한 쪽이 이깁니다</b> —
     * 이전 기기가 들고 있던 표는 이 순간 낡은 값이 됩니다.
     */
    public boolean updateSessionToken(int userId, String token) {
        String sql = "UPDATE `user` SET session_token = ? WHERE id = ?";
        try (Connection conn = DatabaseUtil.getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, token);
            pstmt.setInt(2, userId);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[user] 세션 표를 저장하지 못했습니다: " + e.getMessage());
            return false;
        }
    }

    /**
     * DB 에 저장된 지금의 세션 표.
     *
     * @return 아직 발급받은 적이 없으면 {@code null}
     * @throws DataAccessException 읽기 실패 — <b>삼키지 않습니다</b>. 못 읽은 것을
     *                             '표가 바뀌었다'로 오해하면 멀쩡한 사용자를 쫓아냅니다
     */
    public String findSessionToken(int userId) {
        String sql = "SELECT session_token FROM `user` WHERE id = ?";
        try (Connection conn = DatabaseUtil.getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, userId);
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next() ? rs.getString("session_token") : null;
            }
        } catch (SQLException e) {
            throw new DataAccessException("세션 표를 확인하지 못했습니다.", e);
        }
    }

    /**
     * 비밀번호만 갈아 끼웁니다 — 예전 해시 형식을 로그인하는 김에 새 형식으로 바꿀 때 씁니다.
     * (닉네임까지 함께 넘겨야 하는 {@link #updateUserInfo} 와 달리 이 자리는 닉네임을 모릅니다.)
     */
    public boolean updatePassword(int userId, String newPassword) {
        String sql = "UPDATE `user` SET password = ? WHERE id = ?";
        try (Connection conn = DatabaseUtil.getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, newPassword);
            pstmt.setInt(2, userId);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[user] 비밀번호를 저장하지 못했습니다: " + e.getMessage());
            return false;
        }
    }

    public boolean updateUserInfo(int userId, String newPassword, String newNickname) {
        String sql = "UPDATE `user` SET password = ?, nickname = ? WHERE id = ?";
        try (Connection conn = DatabaseUtil.getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, newPassword);
            pstmt.setString(2, newNickname);
            pstmt.setInt(3, userId);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    public boolean deleteUser(int userId) {
        if (userId <= 0) {
            return false;
        }
        try (Connection conn = DatabaseUtil.getConnection()) {
            boolean previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                if (hasColumn(conn, "history", "feedback_id")) {
                    run(conn, "UPDATE history SET feedback_id = NULL "
                            + "WHERE feedback_id IN (SELECT id FROM feedback WHERE user_id = ?)", userId);
                }
                run(conn, "UPDATE history SET group_id = NULL "
                        + "WHERE group_id IN (SELECT id FROM dining_group WHERE owner_id = ?)", userId);

                run(conn, "DELETE gv FROM group_vote gv "
                        + "JOIN group_member gm ON gm.id = gv.member_id "
                        + "WHERE gm.user_id = ?", userId);
                run(conn, "DELETE gv FROM group_vote gv "
                        + "JOIN group_member gm ON gm.id = gv.member_id "
                        + "JOIN dining_group dg ON dg.id = gm.group_id "
                        + "WHERE dg.owner_id = ?", userId);
                run(conn, "DELETE gv FROM group_vote gv "
                        + "JOIN group_candidate gc ON gc.id = gv.candidate_id "
                        + "JOIN dining_group dg ON dg.id = gc.group_id "
                        + "WHERE dg.owner_id = ?", userId);

                run(conn, "DELETE gma FROM group_member_allergy gma "
                        + "JOIN group_member gm ON gm.id = gma.member_id "
                        + "WHERE gm.user_id = ?", userId);
                run(conn, "DELETE gma FROM group_member_allergy gma "
                        + "JOIN group_member gm ON gm.id = gma.member_id "
                        + "JOIN dining_group dg ON dg.id = gm.group_id "
                        + "WHERE dg.owner_id = ?", userId);

                run(conn, "DELETE gc FROM group_candidate gc "
                        + "JOIN dining_group dg ON dg.id = gc.group_id "
                        + "WHERE dg.owner_id = ?", userId);

                run(conn, "DELETE FROM group_member WHERE user_id = ?", userId);
                run(conn, "DELETE gm FROM group_member gm "
                        + "JOIN dining_group dg ON dg.id = gm.group_id "
                        + "WHERE dg.owner_id = ?", userId);

                run(conn, "DELETE FROM history WHERE user_id = ?", userId);
                run(conn, "DELETE FROM dining_group WHERE owner_id = ?", userId);
                run(conn, "DELETE FROM feedback WHERE user_id = ?", userId);
                run(conn, "DELETE FROM favorite WHERE user_id = ?", userId);
                run(conn, "DELETE FROM user_allergy WHERE user_id = ?", userId);
                run(conn, "DELETE FROM user_category WHERE user_id = ?", userId);
                run(conn, "DELETE FROM user_location WHERE user_id = ?", userId);
                run(conn, "DELETE FROM user_preference WHERE user_id = ?", userId);

                // 아이는 이 계정에만 매달린 데이터라 함께 지웁니다.
                // 급식은 학교 데이터라 저장하지 않으므로 지울 것이 없습니다.
                run(conn, "DELETE ca FROM child_allergy ca "
                        + "JOIN child c ON c.id = ca.child_id WHERE c.user_id = ?", userId);
                run(conn, "DELETE FROM child WHERE user_id = ?", userId);

                if (run(conn, "DELETE FROM `user` WHERE id = ?", userId) == 0) {
                    conn.rollback();
                    return false;
                }
                conn.commit();
                return true;
            } catch (SQLException e) {
                conn.rollback();
                System.err.println("[user] 탈퇴 처리에 실패해 이전 상태로 되돌렸습니다: " + e.getMessage());
                return false;
            } finally {
                conn.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException e) {
            System.err.println("[user] 탈퇴 처리 중 연결 오류: " + e.getMessage());
            return false;
        }
    }

    private static int run(Connection conn, String sql, int userId) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, userId);
            return pstmt.executeUpdate();
        }
    }

    private static boolean hasColumn(Connection conn, String table, String column) throws SQLException {
        try (ResultSet columns = conn.getMetaData().getColumns(conn.getCatalog(), null, table, column)) {
            return columns.next();
        }
    }
}