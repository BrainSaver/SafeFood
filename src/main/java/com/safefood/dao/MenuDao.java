package com.safefood.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * [ SafeFood - 메뉴 DAO ]
 * DB의 menu 및 restaurant 테이블 조회 담당
 */
public class MenuDao {

    /**
     * 추천 후보 메뉴 DTO (식당 정보 및 위도/경도 포함)
     */
    public static class MenuDto {
        private int id;
        private int restaurantId;
        private String name;
        private int price;
        private String category;
        private int spicyLevel;
        private String description;
        private Integer foodId;
        private Integer categoryId;
        private String restaurantName;
        private String restaurantAddress;
        private String openTime;
        private String closeTime;
        private double rating;
        private int reviewCount;
        private Double latitude;
        private Double longitude;
        private List<String> moods = new ArrayList<>();

        public MenuDto(int id, int restaurantId, String name, int price, String category, int spicyLevel) {
            this(id, restaurantId, name, price, category, spicyLevel, "", null, null, "", "", "", "", 0.0, 0, null, null);
        }

        public MenuDto(int id, int restaurantId, String name, int price, String category, int spicyLevel,
                       String description, String restaurantName, String restaurantAddress,
                       String openTime, String closeTime, double rating, int reviewCount) {
            this(id, restaurantId, name, price, category, spicyLevel, description, null, null,
                    restaurantName, restaurantAddress, openTime, closeTime, rating, reviewCount, null, null);
        }

        public MenuDto(int id, int restaurantId, String name, int price, String category, int spicyLevel,
                       String description, Integer foodId, Integer categoryId,
                       String restaurantName, String restaurantAddress,
                       String openTime, String closeTime, double rating, int reviewCount,
                       Double latitude, Double longitude) {
            this.id = id;
            this.restaurantId = restaurantId;
            this.name = name != null ? name : "";
            this.price = price;
            this.category = category != null ? category : "";
            this.spicyLevel = spicyLevel;
            this.description = description != null ? description : "";
            this.foodId = foodId;
            this.categoryId = categoryId;
            this.restaurantName = restaurantName != null ? restaurantName : "";
            this.restaurantAddress = restaurantAddress != null ? restaurantAddress : "";
            this.openTime = openTime != null ? openTime : "";
            this.closeTime = closeTime != null ? closeTime : "";
            this.rating = rating;
            this.reviewCount = reviewCount;
            this.latitude = latitude;
            this.longitude = longitude;
            this.moods = new ArrayList<>();
        }

        public int getId() { return id; }
        public int getRestaurantId() { return restaurantId; }
        public String getName() { return name; }
        public int getPrice() { return price; }
        public String getCategory() { return category; }
        public int getSpicyLevel() { return spicyLevel; }
        public String getDescription() { return description; }
        public Integer getFoodId() { return foodId; }
        public Integer getCategoryId() { return categoryId; }
        public String getRestaurantName() { return restaurantName; }
        public String getRestaurantAddress() { return restaurantAddress; }
        public String getOpenTime() { return openTime; }
        public String getCloseTime() { return closeTime; }
        public double getRating() { return rating; }
        public int getReviewCount() { return reviewCount; }
        public Double getLatitude() { return latitude; }
        public Double getLongitude() { return longitude; }
        public List<String> getMoods() { return moods; }
        public void setMoods(List<String> moods) { this.moods = moods != null ? moods : new ArrayList<>(); }

        public String getHours() {
            if (!openTime.isEmpty() && !closeTime.isEmpty()) {
                return openTime + " ~ " + closeTime;
            } else if (!openTime.isEmpty()) {
                return openTime + " ~";
            }
            return "영업시간 정보 없음";
        }
    }

    /**
     * 메뉴-기분 태그(menu_mood) 매핑 조회
     */
    public java.util.Map<Integer, List<String>> getMenuMoodsMap() {
        java.util.Map<Integer, List<String>> map = new java.util.HashMap<>();
        String sql = "SELECT mm.menu_id, m.name AS mood_name " +
                     "FROM menu_mood mm " +
                     "JOIN mood m ON mm.mood_id = m.id";

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {

            while (rs.next()) {
                int menuId = rs.getInt("menu_id");
                String moodName = rs.getString("mood_name");
                map.computeIfAbsent(menuId, k -> new ArrayList<>()).add(moodName);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return map;
    }

    /**
     * 전체 추천 후보 메뉴 목록 조회 (식당 정보 LEFT JOIN + 카테고리 JOIN + 식당 위경도 조회)
     */
    public List<MenuDto> getAllCandidateMenus() {
        List<MenuDto> menuList = new ArrayList<>();
        java.util.Map<Integer, List<String>> moodMap = getMenuMoodsMap();

        String sql = "SELECT m.id, m.restaurant_id, m.name, m.price, c.name AS category, m.spicy_level, m.description, " +
                     "       m.food_id, m.category_id, " +
                     "       r.name AS restaurant_name, r.address AS restaurant_address, " +
                     "       r.open_time, r.close_time, r.rating, r.review_count, " +
                     "       r.latitude, r.longitude " +
                     "FROM menu m " +
                     "LEFT JOIN restaurant r ON m.restaurant_id = r.id " +
                     "LEFT JOIN category c ON m.category_id = c.id";

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {

            while (rs.next()) {
                String openTime = formatTime(rs.getString("open_time"));
                String closeTime = formatTime(rs.getString("close_time"));

                Integer foodId = rs.getObject("food_id") != null ? rs.getInt("food_id") : null;
                Integer categoryId = rs.getObject("category_id") != null ? rs.getInt("category_id") : null;
                Double lat = rs.getObject("latitude") != null ? rs.getDouble("latitude") : null;
                Double lng = rs.getObject("longitude") != null ? rs.getDouble("longitude") : null;

                MenuDto dto = new MenuDto(
                        rs.getInt("id"),
                        rs.getInt("restaurant_id"),
                        rs.getString("name"),
                        rs.getInt("price"),
                        rs.getString("category"),
                        rs.getInt("spicy_level"),
                        rs.getString("description"),
                        foodId,
                        categoryId,
                        rs.getString("restaurant_name"),
                        rs.getString("restaurant_address"),
                        openTime,
                        closeTime,
                        rs.getDouble("rating"),
                        rs.getInt("review_count"),
                        lat,
                        lng
                );

                if (moodMap.containsKey(dto.getId())) {
                    dto.setMoods(moodMap.get(dto.getId()));
                }
                menuList.add(dto);
            }
        } catch (SQLException e) {
            throw new DataAccessException("추천 후보 메뉴를 불러오지 못했습니다.", e);
        }
        return menuList;
    }

    /**
     * 특정 메뉴 단건 조회
     */
    public MenuDto getMenuById(int menuId) {
        String sql = "SELECT m.id, m.restaurant_id, m.name, m.price, c.name AS category, m.spicy_level, m.description, " +
                     "       m.food_id, m.category_id, " +
                     "       r.name AS restaurant_name, r.address AS restaurant_address, " +
                     "       r.open_time, r.close_time, r.rating, r.review_count, " +
                     "       r.latitude, r.longitude " +
                     "FROM menu m " +
                     "LEFT JOIN restaurant r ON m.restaurant_id = r.id " +
                     "LEFT JOIN category c ON m.category_id = c.id " +
                     "WHERE m.id = ?";

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, menuId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    String openTime = formatTime(rs.getString("open_time"));
                    String closeTime = formatTime(rs.getString("close_time"));

                    Integer foodId = rs.getObject("food_id") != null ? rs.getInt("food_id") : null;
                    Integer categoryId = rs.getObject("category_id") != null ? rs.getInt("category_id") : null;
                    Double lat = rs.getObject("latitude") != null ? rs.getDouble("latitude") : null;
                    Double lng = rs.getObject("longitude") != null ? rs.getDouble("longitude") : null;

                    return new MenuDto(
                            rs.getInt("id"),
                            rs.getInt("restaurant_id"),
                            rs.getString("name"),
                            rs.getInt("price"),
                            rs.getString("category"),
                            rs.getInt("spicy_level"),
                            rs.getString("description"),
                            foodId,
                            categoryId,
                            rs.getString("restaurant_name"),
                            rs.getString("restaurant_address"),
                            openTime,
                            closeTime,
                            rs.getDouble("rating"),
                            rs.getInt("review_count"),
                            lat,
                            lng
                    );
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    private static String formatTime(String timeStr) {
        if (timeStr == null || timeStr.isBlank()) {
            return "";
        }
        timeStr = timeStr.trim();
        if (timeStr.length() >= 5) {
            return timeStr.substring(0, 5);
        }
        return timeStr;
    }
}