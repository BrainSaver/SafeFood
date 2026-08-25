package com.safefood.dto;

/**
 * 식사 기록 UI 테이블 표시용 레코드
 */
public record HistoryRow(String date, String type, String menu, String restaurant, String note) {
}
