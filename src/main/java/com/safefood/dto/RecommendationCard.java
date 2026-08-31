package com.safefood.dto;

/**
 * UI 추천 카드 데이터 레코드
 *
 * <p><b>{@code price} 는 0 일 수 있습니다</b> — 메뉴에 가격이 등록돼 있지 않다는 뜻이고,
 * "공짜"가 아닙니다. 화면에 찍을 때는 {@link #priceText()} 를 쓰세요.
 */
public record RecommendationCard(int rank, int score, String menu, String restaurant,
                                  Safety safety, int spicyLevel, int price, String reason,
                                  String address, String hours, double rating,
                                  String distance, boolean blocked, String alternative,
                                  Double latitude, Double longitude) {

    /** 화면에 그대로 쓰는 가격 문구. */
    public String priceText() {
        return priceText(price);
    }

    /**
     * 가격을 사람이 읽는 문구로. 등록되지 않았으면(0 이하) 숫자 대신 그렇다고 알립니다 —
     * {@code 0원} 으로 찍으면 공짜로 읽힙니다.
     */
    public static String priceText(int price) {
        return price > 0 ? String.format("%,d원", price) : "가격 정보 없음";
    }
}
