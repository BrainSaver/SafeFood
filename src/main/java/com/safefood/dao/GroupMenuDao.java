package com.safefood.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class GroupMenuDao {

    public enum AllergySource {
        CURATED,
        DERIVED,
        NONE
    }

    public record CandidateMenu(int menuId, String menuName, String restaurantName, String category,
                                int price, int spicyLevel, double rating, AllergySource allergySource,
                                List<String> containsAllergies, List<String> possibleAllergies) {
    }

    private static final String FIND_ALL = """
            SELECT m.id,
                   m.name AS menu_name,
                   r.name AS restaurant_name,
                   COALESCE(c.name, r.category, '') AS category,
                   m.price,
                   COALESCE(m.spicy_level, 0) AS spicy_level,
                   COALESCE(r.rating, 0)      AS rating,
                   MAX(src.origin)            AS origin,
                   GROUP_CONCAT(DISTINCT CASE WHEN src.risk_level = 'CONTAINS' THEN a.name END) AS contains_names,
                   GROUP_CONCAT(DISTINCT CASE WHEN src.risk_level = 'POSSIBLE' THEN a.name END) AS possible_names
              FROM menu m
              JOIN restaurant r ON r.id = m.restaurant_id
              LEFT JOIN category c ON c.id = m.category_id
              LEFT JOIN (
                    SELECT ma.menu_id, ma.allergy_id, ma.risk_level, 'CURATED' AS origin
                      FROM menu_allergy ma
                    UNION ALL
                    SELECT dm.id, ia.allergy_id, 'CONTAINS', 'DERIVED'
                      FROM menu dm
                      JOIN food_ingredient fi    ON fi.food_id = dm.food_id
                      JOIN ingredient_allergy ia ON ia.ingredient_id = fi.ingredient_id
                     WHERE dm.food_id IS NOT NULL
                       AND NOT EXISTS (SELECT 1 FROM menu_allergy x WHERE x.menu_id = dm.id)
                   ) src ON src.menu_id = m.id
              LEFT JOIN allergy a ON a.id = src.allergy_id
             GROUP BY m.id, m.name, r.name, c.name, r.category, m.price, m.spicy_level, r.rating
            """;

    /**
     * @throws SQLException 조회 실패 — 호출부가 '메뉴 없음'과 구분할 수 있게 그대로 올립니다.
     *                      삼키고 빈 목록을 돌려주면 알레르기 검증을 못 한 대체 후보가 나갑니다.
     */
    public List<CandidateMenu> findAllWithAllergies() throws SQLException {
        List<CandidateMenu> menus = new ArrayList<>();
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(FIND_ALL);
             ResultSet rs = pstmt.executeQuery()) {

            while (rs.next()) {
                List<String> contains = splitNames(rs.getString("contains_names"));
                List<String> possible = splitNames(rs.getString("possible_names"));
                menus.add(new CandidateMenu(
                        rs.getInt("id"),
                        rs.getString("menu_name"),
                        rs.getString("restaurant_name"),
                        rs.getString("category"),
                        rs.getInt("price"),
                        rs.getInt("spicy_level"),
                        rs.getDouble("rating"),
                        sourceOf(rs.getString("origin"), contains, possible),
                        contains,
                        possible));
            }
        }
        return menus;
    }

    private static AllergySource sourceOf(String origin, List<String> contains, List<String> possible) {
        if ("DERIVED".equals(origin)) {
            return AllergySource.DERIVED;
        }
        if ("CURATED".equals(origin) && !(contains.isEmpty() && possible.isEmpty())) {
            return AllergySource.CURATED;
        }
        return AllergySource.NONE;
    }

    private static List<String> splitNames(String concatenated) {
        if (concatenated == null || concatenated.isBlank()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (String name : concatenated.split(",")) {
            String trimmed = name.trim();
            if (!trimmed.isEmpty()) {
                names.add(trimmed);
            }
        }
        return List.copyOf(names);
    }
}
