package com.safefood.dto;

/**
 * UI 추천 카드 데이터 레코드
 */
public record RecommendationCard(int rank, int score, String menu, String restaurant,
                                  Safety safety, int spicyLevel, String reason,
                                  String address, String hours, double rating,
                                  String distance, boolean blocked, String alternative,
                                  Double latitude, Double longitude) {

    public RecommendationCard(int rank, int score, String menu, String restaurant,
                              Safety safety, int spicyLevel, String reason,
                              String address, String hours, double rating,
                              String distance, boolean blocked, String alternative) {
        this(rank, score, menu, restaurant, safety, spicyLevel, reason, address, hours, rating, distance, blocked, alternative, null, null);
    }
}
