package com.safefood.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * [ SafeFood - 카테고리 DAO ]
 * DB의 category 테이블 조회 및 관리 담당
 */
public class CategoryDao {

    public record CategoryDto(int id, String name) {}

    /**
     * DB에서 전체 카테고리 이름 목록 조회 (캐시 기반 CategoryCatalog 활용)
     */
    public List<String> getAllCategoryNames() {
        return CategoryCatalog.names();
    }

    /**
     * DB에서 전체 카테고리 (ID + 이름) 목록 조회
     */
    public List<CategoryDto> getAllCategories() {
        List<CategoryDto> list = new ArrayList<>();
        String sql = "SELECT id, name FROM category ORDER BY id";
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {
            while (rs.next()) {
                list.add(new CategoryDto(rs.getInt("id"), rs.getString("name")));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }
}
