package com.safefood.dao;

import com.safefood.view.DemoData;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * [ SafeFood - 즐겨찾기(Favorite / 찜하기) DAO ]
 */
public class FavoriteDao {

    public boolean addFavorite(int userId, Integer restaurantId, Integer menuId) {
        if (isFavorite(userId, menuId)) {
            return true;
        }
        String sql = "INSERT INTO favorite (user_id, restaurant_id, menu_id) VALUES (?, ?, ?)";
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, userId);
            if (restaurantId != null && restaurantId > 0) {
                pstmt.setInt(2, restaurantId);
            } else {
                pstmt.setNull(2, java.sql.Types.INTEGER);
            }
            if (menuId != null && menuId > 0) {
                pstmt.setInt(3, menuId);
            } else {
                pstmt.setNull(3, java.sql.Types.INTEGER);
            }
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

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

    public List<DemoData.FavoriteRow> getUserFavorites(int userId) {
        List<DemoData.FavoriteRow> list = new ArrayList<>();
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
                    list.add(new DemoData.FavoriteRow(rest, menu, rating, reviewCount));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }
}
