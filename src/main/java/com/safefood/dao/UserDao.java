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
}