package com.safefood.dao;

import com.safefood.dto.FavoriteDto;
import com.safefood.dto.FavoriteRow;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * [ SafeFood - 즐겨찾기(Favorite / 찜하기) DAO ]
 */
public class FavoriteDao {

    // 1. 찜 목록 꺼내오기 (FavoriteDto 리스트)
    public List<FavoriteDto> findFavoritesByUserId(int userId) {
        String sql = "SELECT f.id, r.name as restaurant_name, m.name as menu_name, " +
                "r.rating, r.review_count " +
                "FROM favorite f " +
                "LEFT JOIN restaurant r ON f.restaurant_id = r.id " +
                "LEFT JOIN menu m ON f.menu_id = m.id " +
                "WHERE f.user_id = ? " +
                "ORDER BY f.created_at DESC";

        List<FavoriteDto> list = new ArrayList<>();
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, userId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    list.add(new FavoriteDto(
                            rs.getInt("id"),
                            rs.getString("restaurant_name"),
                            rs.getString("menu_name"),
                            rs.getDouble("rating"),
                            rs.getInt("review_count")
                    ));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return list;
    }

    // 2. 찜 목록 꺼내오기 (UI FavoriteRow 리스트)
    public List<FavoriteRow> getUserFavorites(int userId) {
        List<FavoriteRow> list = new ArrayList<>();
        String sql = "SELECT r.name AS restaurant_name, m.name AS menu_name, r.rating, r.review_count " +
                     "FROM favorite f " +
                     "JOIN menu m ON f.menu_id = m.id " +
                     "LEFT JOIN restaurant r ON m.restaurant_id = r.id " +
                     "WHERE f.user_id = ? " +
                     "ORDER BY f.created_at DESC";
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, userId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String rest = rs.getString("restaurant_name") != null ? rs.getString("restaurant_name") : "";
                    String menu = rs.getString("menu_name");
                    double rating = rs.getDouble("rating");
                    int reviewCount = rs.getInt("review_count");
                    list.add(new FavoriteRow(rest, menu, rating, reviewCount));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    // 3. 찜 추가하기 (중복 방지 포함)
    public boolean addFavorite(int userId, Integer restaurantId, Integer menuId) {
        if (isFavorite(userId, menuId)) {
            return true;
        }
        return insertFavorite(userId, restaurantId, menuId);
    }

    public boolean insertFavorite(int userId, Integer restaurantId, Integer menuId) {
        String sql = "INSERT INTO favorite (user_id, restaurant_id, menu_id) VALUES (?, ?, ?)";
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, userId);

            if (restaurantId != null && restaurantId > 0) pstmt.setInt(2, restaurantId);
            else pstmt.setNull(2, Types.INTEGER);

            if (menuId != null && menuId > 0) pstmt.setInt(3, menuId);
            else pstmt.setNull(3, Types.INTEGER);

            return pstmt.executeUpdate() > 0;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    // 4. 찜 여부 확인
    public boolean isFavorite(int userId, Integer menuId) {
        if (userId <= 0 || menuId == null || menuId <= 0) return false;
        String sql = "SELECT id FROM favorite WHERE user_id = ? AND menu_id = ?";
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, userId);
            pstmt.setInt(2, menuId);
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    // 5. 찜 취소하기 (메뉴 ID 기준)
    public boolean removeFavorite(int userId, Integer menuId) {
        String sql = "DELETE FROM favorite WHERE user_id = ? AND menu_id = ?";
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

    // 6. 찜 취소하기 (PK 기준)
    public boolean deleteFavorite(int favoriteId) {
        String sql = "DELETE FROM favorite WHERE id = ?";
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, favoriteId);
            return pstmt.executeUpdate() > 0;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    // 7. 찜 취소하기 (유저, 식당, 메뉴 ID 기준)
    public boolean deleteFavoriteByMenu(int userId, Integer restaurantId, Integer menuId) {
        String sql = "DELETE FROM favorite WHERE user_id = ? AND restaurant_id = ? AND menu_id = ?";
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, userId);
            if (restaurantId != null) pstmt.setInt(2, restaurantId);
            else pstmt.setNull(2, Types.INTEGER);
            if (menuId != null) pstmt.setInt(3, menuId);
            else pstmt.setNull(3, Types.INTEGER);

            return pstmt.executeUpdate() > 0;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
}
