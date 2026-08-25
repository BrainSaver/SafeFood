package com.safefood.view;

import java.util.List;

/**
 * UI 선택지 및 랜드마크 옵션 상수.
 */
public final class UiConstants {

    private UiConstants() {
    }

    public static final List<String> DISTANCES = List.of(
            "500m", "1km", "2km", "3km", "제한없음"
    );

    public static final List<String> BUDGETS = List.of(
            "6,000원 이하", "8,000원 이하", "10,000원 이하", "12,000원 이하",
            "15,000원 이하", "20,000원 이하", "제한없음"
    );

    public static final List<String> SEVERITIES = List.of(
            "Class 1 거의 없음", "Class 2 낮음", "Class 3 보통", "Class 4 높음", "Class 5 극도로 높음"
    );

    /**
     * 위치 등록에서 선택하는 대표 랜드마크 지점 좌표
     */
    public record Place(String name, String address, double latitude, double longitude) {
    }

    public static final List<Place> PLACES = List.of(
            new Place("강남역", "서울 강남구 강남대로 396", 37.4979, 127.0276),
            new Place("홍대입구역", "서울 마포구 양화로 160", 37.5572, 126.9245),
            new Place("신촌역", "서울 서대문구 신촌로 90", 37.5551, 126.9368),
            new Place("서울시청", "서울 중구 세종대로 110", 37.5663, 126.9779),
            new Place("건대입구역", "서울 광진구 아차산로 216", 37.5403, 127.0703),
            new Place("잠실역", "서울 송파구 올림픽로 265", 37.5133, 127.1001),
            new Place("판교역", "경기 성남시 분당구 판교역로 160", 37.3947, 127.1112),
            new Place("수원역", "경기 수원시 팔달구 덕영대로 924", 37.2659, 127.0001),
            new Place("부평역", "인천 부평구 부평문화로 8", 37.4894, 126.7241),
            new Place("대전시청", "대전 서구 둔산로 100", 36.3504, 127.3845),
            new Place("대구 반월당역", "대구 중구 달구벌대로 지하 2100", 35.8656, 128.5934),
            new Place("광주 충장로", "광주 동구 충장로 안길", 35.1495, 126.9190),
            new Place("부산 서면역", "부산 부산진구 중앙대로 지하 730", 35.1578, 129.0594),
            new Place("제주시청", "제주 제주시 광양9길 10", 33.4996, 126.5312)
    );
}
