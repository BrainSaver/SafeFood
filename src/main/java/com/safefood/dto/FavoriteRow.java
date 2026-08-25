package com.safefood.dto;

/**
 * 찜 목록 UI 테이블 표시용 레코드
 */
public record FavoriteRow(String restaurant, String menu, double rating, int reviewCount) {
}
