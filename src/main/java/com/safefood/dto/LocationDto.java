package com.safefood.dto;

/**
 * 등록해 둔 내 위치 한 곳 — 회원당 1개입니다 (user_location 테이블 1행).
 *
 * <p>주소는 카카오(다음) 우편번호 서비스에서 골라 받고, 좌표는 그 주소를 카카오 지도 API로
 * 다시 물어봐서 채웁니다. <b>좌표가 비어 있을 수 있습니다</b> — {@code map.api.key}를 아직
 * 넣지 않았거나 주소 검색이 좌표를 못 찾은 경우입니다. 그래서 두 값은 {@code Double}이고,
 * 거리 계산 쪽에서는 {@link #hasCoordinates()}로 먼저 걸러야 합니다.
 */
public class LocationDto {

    /** 사람이 알아보는 이름 — 집 / 회사처럼. */
    private String label;

    /** 우편번호 (5자리). 주소를 직접 친 경우 비어 있을 수 있습니다. */
    private String zonecode;

    /** 도로명 주소 + 상세 주소. */
    private String address;

    /** 거리·중간 지점 계산에 쓰이는 값. 아직 못 찾았으면 null 입니다. */
    private Double latitude;
    private Double longitude;

    public LocationDto() {
    }

    public LocationDto(String label, String zonecode, String address,
                       Double latitude, Double longitude) {
        this.label = label;
        this.zonecode = zonecode;
        this.address = address;
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public String getZonecode() { return zonecode; }
    public void setZonecode(String zonecode) { this.zonecode = zonecode; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }

    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }

    /** 거리 계산에 쓸 수 있는 위치인지 — 좌표가 둘 다 있어야 합니다. */
    public boolean hasCoordinates() {
        return latitude != null && longitude != null;
    }

    /** 화면 한 줄로 요약 — "집 · 서울 강남구 테헤란로 12길 5" 처럼. */
    public String describe() {
        boolean hasLabel = label != null && !label.isBlank();
        boolean hasAddress = address != null && !address.isBlank();
        if (hasLabel && hasAddress) {
            return label + " · " + address;
        }
        if (hasLabel) {
            return label;
        }
        if (hasAddress) {
            return address;
        }
        return hasCoordinates()
                ? String.format("위도 %.5f, 경도 %.5f", latitude, longitude)
                : "이름 없는 위치";
    }
}
