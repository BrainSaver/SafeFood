package com.safefood.tool;

import com.safefood.dao.DatabaseUtil;
import com.safefood.setup.core.Schema;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SeedImporter {

    private static final Path PUBLIC_DIR = Path.of("data", "public");
    private static final List<String> TABLES =
            List.of("category", "restaurant", "menu", "menu_allergy");

    public static void main(String[] args) {
        try {
            new SeedImporter().run();
        } catch (Exception e) {
            System.err.println("[실패] " + e.getMessage());
            System.exit(1);
        }
    }

    private void run() throws IOException, SQLException {
        List<String[]> restaurants = read("restaurant.txt", 11);
        List<String[]> menus = read("menu.txt", 8);
        List<String[]> menuAllergies = read("menu_allergy.txt", 4);
        List<String[]> foods = read("food.txt", 4);
        List<String> allergyNames = readNames("allergy.txt");

        if (restaurants.isEmpty() || menus.isEmpty()) {
            throw new IOException("data/public/restaurant.txt 또는 menu.txt 가 비어 있습니다.");
        }

        Map<String, String> foodCdByFileId = new LinkedHashMap<>();
        for (String[] food : foods) {
            foodCdByFileId.put(food[0], food[1]);
        }

        try (Connection conn = DatabaseUtil.getConnection()) {
            ensureTables(conn);
            ensureCategories(conn);

            Map<String, Integer> allergyIds = selectIdMap(conn, "SELECT name, id FROM allergy");
            if (allergyIds.isEmpty() && !menuAllergies.isEmpty()) {
                throw new SQLException("allergy 테이블이 비어 있습니다. Setup Wizard 를 먼저 실행하세요.");
            }

            conn.setAutoCommit(false);
            try {
                Map<String, Integer> categoryIds = selectIdMap(conn, "SELECT name, id FROM category");
                Map<String, Integer> foodIds = selectIdMap(conn, "SELECT food_cd, id FROM food");

                Map<String, Integer> restaurantIdByFileId = upsertRestaurants(conn, restaurants);
                Map<String, Integer> menuIdByFileId = upsertMenus(conn, menus, restaurantIdByFileId,
                        categoryIds, foodCdByFileId, foodIds);
                int allergyRows = replaceMenuAllergies(conn, menuAllergies, menuIdByFileId,
                        allergyNames, allergyIds);
                conn.commit();

                System.out.printf("[DB] 시드 반영 완료 — 식당 %d곳, 메뉴 %d개, 알레르기 표시 %d건%n",
                        restaurantIdByFileId.size(), menuIdByFileId.size(), allergyRows);
                if (foodIds.isEmpty()) {
                    System.out.println("[안내] food 테이블이 비어 있어 메뉴의 food_id 는 비워 두었습니다. "
                            + "MenuGenImporter 를 먼저 돌리면 재료 정보까지 연결됩니다.");
                }
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    private static List<String[]> read(String fileName, int fields) throws IOException {
        Path file = PUBLIC_DIR.resolve(fileName);
        if (!Files.exists(file)) {
            throw new IOException(file + " 가 없습니다. 먼저 Setup Wizard 를 실행하세요.");
        }
        List<String[]> rows = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\\|", -1);
            if (parts.length < fields) {
                throw new IOException(fileName + " 의 칸 수가 모자랍니다: " + line);
            }
            rows.add(parts);
        }
        return rows;
    }

    private static List<String> readNames(String fileName) throws IOException {
        Path file = PUBLIC_DIR.resolve(fileName);
        if (!Files.exists(file)) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                names.add(line.trim());
            }
        }
        return names;
    }

    private static void ensureTables(Connection conn) throws SQLException {
        try (Statement statement = conn.createStatement()) {
            for (String table : TABLES) {
                statement.execute(Schema.ddlOf(table));
            }
        }
    }

    private static void ensureCategories(Connection conn) throws SQLException {
        try (PreparedStatement ps =
                     conn.prepareStatement("INSERT IGNORE INTO category (name) VALUES (?)")) {
            for (String name : Schema.CATEGORIES) {
                ps.setString(1, name);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static Map<String, Integer> upsertRestaurants(Connection conn, List<String[]> rows)
            throws SQLException {
        String update = "UPDATE restaurant SET category = ?, address = ?, phone = ?, open_time = ?, "
                + "close_time = ?, latitude = ?, longitude = ?, rating = ?, review_count = ? "
                + "WHERE id = ?";
        String insert = "INSERT INTO restaurant (category, address, phone, open_time, close_time, "
                + "latitude, longitude, rating, review_count, name) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        Map<String, Integer> existing = selectIdMap(conn, "SELECT name, id FROM restaurant");
        Map<String, Integer> byFileId = new LinkedHashMap<>();

        for (String[] row : rows) {
            Integer id = existing.get(row[1]);
            if (id != null) {
                try (PreparedStatement ps = conn.prepareStatement(update)) {
                    bindRestaurant(ps, row);
                    ps.setInt(10, id);
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps =
                             conn.prepareStatement(insert, Statement.RETURN_GENERATED_KEYS)) {
                    bindRestaurant(ps, row);
                    ps.setString(10, row[1]);
                    ps.executeUpdate();
                    id = generatedKey(ps);
                }
            }
            byFileId.put(row[0], id);
        }
        return byFileId;
    }

    private static void bindRestaurant(PreparedStatement ps, String[] row) throws SQLException {
        ps.setString(1, row[2]);
        ps.setString(2, row[3]);
        ps.setString(3, row[4]);
        ps.setString(4, row[5]);
        ps.setString(5, row[6]);
        setDouble(ps, 6, row[7]);
        setDouble(ps, 7, row[8]);
        setDouble(ps, 8, row[9]);
        ps.setInt(9, Integer.parseInt(row[10].trim()));
    }

    private static Map<String, Integer> upsertMenus(Connection conn, List<String[]> rows,
                                                    Map<String, Integer> restaurantIds,
                                                    Map<String, Integer> categoryIds,
                                                    Map<String, String> foodCdByFileId,
                                                    Map<String, Integer> foodIds)
            throws SQLException {
        String find = "SELECT id FROM menu WHERE restaurant_id = ? AND name = ?";
        String update = "UPDATE menu SET price = ?, category_id = ?, spicy_level = ?, "
                + "description = ?, food_id = ? WHERE id = ?";
        String insert = "INSERT INTO menu (price, category_id, spicy_level, description, food_id, "
                + "restaurant_id, name) VALUES (?, ?, ?, ?, ?, ?, ?)";

        Map<String, Integer> byFileId = new LinkedHashMap<>();

        for (String[] row : rows) {
            Integer restaurantId = restaurantIds.get(row[1]);
            if (restaurantId == null) {
                throw new SQLException("menu.txt 의 restaurant_id 를 찾지 못했습니다: " + row[1]);
            }
            Integer categoryId = resolveCategory(categoryIds, row[4]);
            Integer foodId = resolveFood(foodCdByFileId, foodIds, row[7]);

            Integer id = null;
            try (PreparedStatement ps = conn.prepareStatement(find)) {
                ps.setInt(1, restaurantId);
                ps.setString(2, row[2]);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        id = rs.getInt(1);
                    }
                }
            }

            if (id != null) {
                try (PreparedStatement ps = conn.prepareStatement(update)) {
                    bindMenu(ps, row, categoryId, foodId);
                    ps.setInt(6, id);
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps =
                             conn.prepareStatement(insert, Statement.RETURN_GENERATED_KEYS)) {
                    bindMenu(ps, row, categoryId, foodId);
                    ps.setInt(6, restaurantId);
                    ps.setString(7, row[2]);
                    ps.executeUpdate();
                    id = generatedKey(ps);
                }
            }
            byFileId.put(row[0], id);
        }
        return byFileId;
    }

    private static void bindMenu(PreparedStatement ps, String[] row, Integer categoryId, Integer foodId)
            throws SQLException {
        ps.setInt(1, Integer.parseInt(row[3].trim()));
        setNullableInt(ps, 2, categoryId);
        ps.setInt(3, Integer.parseInt(row[5].trim()));
        ps.setString(4, row[6].isBlank() ? null : row[6]);
        setNullableInt(ps, 5, foodId);
    }

    private static Integer resolveCategory(Map<String, Integer> categoryIds, String rawIndex) {
        if (rawIndex.isBlank()) {
            return null;
        }
        int index = Integer.parseInt(rawIndex.trim());
        if (index < 1 || index > Schema.CATEGORIES.size()) {
            return null;
        }
        return categoryIds.get(Schema.CATEGORIES.get(index - 1));
    }

    private static Integer resolveFood(Map<String, String> foodCdByFileId,
                                       Map<String, Integer> foodIds, String rawFileId) {
        if (rawFileId.isBlank()) {
            return null;
        }
        String foodCd = foodCdByFileId.get(rawFileId.trim());
        return foodCd == null ? null : foodIds.get(foodCd);
    }

    private static int replaceMenuAllergies(Connection conn, List<String[]> rows,
                                            Map<String, Integer> menuIds,
                                            List<String> allergyNames,
                                            Map<String, Integer> allergyIds) throws SQLException {
        if (menuIds.isEmpty()) {
            return 0;
        }
        try (PreparedStatement delete =
                     conn.prepareStatement("DELETE FROM menu_allergy WHERE menu_id = ?")) {
            for (int menuId : menuIds.values()) {
                delete.setInt(1, menuId);
                delete.addBatch();
            }
            delete.executeBatch();
        }

        String insert = "INSERT IGNORE INTO menu_allergy (menu_id, allergy_id, risk_level, ingredient) "
                + "VALUES (?, ?, ?, ?)";
        int count = 0;
        try (PreparedStatement ps = conn.prepareStatement(insert)) {
            for (String[] row : rows) {
                Integer menuId = menuIds.get(row[0]);
                Integer allergyId = resolveAllergy(allergyNames, allergyIds, row[1]);
                if (menuId == null || allergyId == null) {
                    System.err.println("[안내] 알레르기 표시를 건너뜁니다: " + String.join("|", row));
                    continue;
                }
                ps.setInt(1, menuId);
                ps.setInt(2, allergyId);
                ps.setString(3, row[2]);
                ps.setString(4, row[3].isBlank() ? null : row[3]);
                ps.addBatch();
                count++;
            }
            ps.executeBatch();
        }
        return count;
    }

    private static Integer resolveAllergy(List<String> allergyNames, Map<String, Integer> allergyIds,
                                          String rawIndex) {
        if (rawIndex.isBlank()) {
            return null;
        }
        int index = Integer.parseInt(rawIndex.trim());
        if (index < 1 || index > allergyNames.size()) {
            return null;
        }
        return allergyIds.get(allergyNames.get(index - 1));
    }

    private static Map<String, Integer> selectIdMap(Connection conn, String sql) throws SQLException {
        Map<String, Integer> result = new LinkedHashMap<>();
        try (Statement statement = conn.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                result.put(rs.getString(1), rs.getInt(2));
            }
        } catch (SQLException e) {
            return result;
        }
        return result;
    }

    private static int generatedKey(PreparedStatement ps) throws SQLException {
        try (ResultSet keys = ps.getGeneratedKeys()) {
            if (!keys.next()) {
                throw new SQLException("생성된 기본키를 받지 못했습니다.");
            }
            return keys.getInt(1);
        }
    }

    private static void setDouble(PreparedStatement ps, int index, String raw) throws SQLException {
        if (raw == null || raw.isBlank()) {
            ps.setNull(index, Types.DECIMAL);
        } else {
            ps.setDouble(index, Double.parseDouble(raw.trim()));
        }
    }

    private static void setNullableInt(PreparedStatement ps, int index, Integer value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }
}
