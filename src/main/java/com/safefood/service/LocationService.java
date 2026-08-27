package com.safefood.service;

import com.safefood.dao.LocationDao;
import com.safefood.dto.LocationDto;

/**
 * 내 위치 등록 — 값이 말이 되는지 보고 저장까지 맡습니다.
 *
 * <p>주소는 카카오(다음) 우편번호 서비스에서 고르고, 좌표는 {@link MapService}가 그 주소로
 * 찾아 줍니다. 좌표는 <b>없을 수도 있습니다</b> — {@code map.api.key} 가 비어 있거나 카카오가
 * 그 주소의 좌표를 못 찾은 경우입니다. 그래서 저장 조건은 "주소나 좌표 중 하나는 있을 것"이고,
 * 좌표를 넣는다면 둘 다 제대로 된 값이어야 합니다.
 */
public class LocationService {

    /** 국내 좌표 대략 범위 — 마라도·독도까지 넉넉히 잡은 값입니다. */
    private static final double KOREA_MIN_LAT = 33.0;
    private static final double KOREA_MAX_LAT = 39.0;
    private static final double KOREA_MIN_LNG = 124.0;
    private static final double KOREA_MAX_LNG = 132.0;

    private final LocationDao locationDao;

    public LocationService() {
        this.locationDao = new LocationDao();
    }

    /** 등록해 둔 위치. 아직 없으면 null. */
    public LocationDto getMyLocation(int userId) {
        return locationDao.getLocation(userId);
    }

    /** 등록·수정. 값이 모자라거나 좌표가 범위를 벗어나면 저장하지 않고 false 를 돌려줍니다. */
    public boolean saveMyLocation(int userId, LocationDto location) {
        if (!isSavable(location)) {
            return false;
        }
        return locationDao.saveLocation(userId, location);
    }

    /** 등록 해제. */
    public boolean clearMyLocation(int userId) {
        return locationDao.deleteLocation(userId);
    }

    // ── 값 검사 ─────────────────────────────────────────────────

    /** 저장해도 되는 위치인지 — 주소나 좌표 중 하나는 있어야 하고, 좌표는 짝이 맞아야 합니다. */
    public static boolean isSavable(LocationDto location) {
        if (location == null) {
            return false;
        }
        boolean hasAddress = location.getAddress() != null && !location.getAddress().isBlank();
        if (location.getLatitude() == null && location.getLongitude() == null) {
            return hasAddress;
        }
        if (!location.hasCoordinates()) {
            return false;   // 한쪽만 채운 좌표는 쓸 수 없습니다
        }
        return isValidLatitude(location.getLatitude())
                && isValidLongitude(location.getLongitude());
    }

    public static boolean isValidLatitude(double latitude) {
        return latitude >= -90 && latitude <= 90;
    }

    public static boolean isValidLongitude(double longitude) {
        return longitude >= -180 && longitude <= 180;
    }

    /** 국내 좌표처럼 보이는지 — 아니라고 저장을 막지는 않고, 안내만 합니다. */
    public static boolean isInKorea(double latitude, double longitude) {
        return latitude >= KOREA_MIN_LAT && latitude <= KOREA_MAX_LAT
                && longitude >= KOREA_MIN_LNG && longitude <= KOREA_MAX_LNG;
    }

    /** 입력칸의 글자를 좌표로. 숫자가 아니면 null 입니다 (빈칸 포함). */
    public static Double parseCoordinate(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Double.valueOf(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 두 위경도 좌표 사이의 실제 직선 거리 (미터 단위, 하버사인 공식)
     */
    public static double calculateDistanceMeter(double lat1, double lng1, double lat2, double lng2) {
        final double R = 6371000.0; // 지구 반지름 (m)
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2.0) * Math.sin(dLat / 2.0)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2.0) * Math.sin(dLng / 2.0);
        double c = 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
        return R * c;
    }

    /**
     * 거리를 보기 좋은 문자열로 변환 (예: 450m, 1.2km)
     */
    public static String formatDistance(double meters) {
        if (meters < 1000.0) {
            return String.format(java.util.Locale.US, "%dm", (int) Math.round(meters));
        } else {
            return String.format(java.util.Locale.US, "%.1fkm", meters / 1000.0);
        }
    }
}
