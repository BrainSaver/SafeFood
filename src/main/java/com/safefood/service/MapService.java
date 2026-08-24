package com.safefood.service;

import com.safefood.AppConfig;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 지도 API 호출 — 지금은 <b>주소 → 좌표</b> 한 가지만 합니다 (카카오 로컬 API).
 *
 * <p>주소 자체는 우편번호 서비스(WebView)에서 고르고, 그 주소로 좌표를 얻는 것이 이 클래스의
 * 일입니다. 우편번호 서비스는 좌표를 주지 않기 때문입니다.
 *
 * <p><b>REST API 키</b>가 필요합니다 — {@code config.properties} 의 {@code map.api.key}.
 * (카카오 개발자 콘솔 › 내 애플리케이션 › 앱 키 › <b>REST API 키</b>. JavaScript 키가 아닙니다)
 * 키가 없으면 {@link #hasApiKey()} 가 false 이고, 화면은 좌표 없이 주소만 등록하게 둡니다.
 *
 * <p>응답에서 꺼내는 값이 좌표 두 개뿐이라 JSON 라이브러리를 새로 들이지 않고 정규식으로
 * 읽습니다. 문서 목록의 <b>첫 번째</b> 항목이 가장 잘 맞는 주소이고, 그 항목의 x·y 가
 * 응답에서 가장 먼저 나옵니다.
 */
public class MapService {

    private static final String ENDPOINT = "https://dapi.kakao.com/v2/local/search/address.json";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static final Pattern X = Pattern.compile("\"x\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern Y = Pattern.compile("\"y\"\\s*:\\s*\"([^\"]+)\"");

    /** 위도·경도 한 쌍. */
    public record Coordinates(double latitude, double longitude) {
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();

    /** 키가 채워져 있는지 — 화면이 좌표 자동 채우기를 켤지 말지 정할 때 씁니다. */
    public static boolean hasApiKey() {
        return !apiKey().isEmpty();
    }

    private static String apiKey() {
        return AppConfig.trimmed("map.api.key", "");
    }

    /**
     * 주소를 좌표로. 찾지 못하면 null 을 돌려줍니다 (오류가 아닙니다 — 없는 주소일 뿐).
     *
     * @throws IllegalStateException 키가 없을 때
     * @throws IOException           네트워크 오류이거나 카카오가 거절했을 때
     */
    public Coordinates findCoordinates(String address) throws IOException, InterruptedException {
        String key = apiKey();
        if (key.isEmpty()) {
            throw new IllegalStateException("map.api.key 가 비어 있습니다.");
        }
        if (address == null || address.isBlank()) {
            return null;
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        ENDPOINT + "?query=" + URLEncoder.encode(address.trim(), StandardCharsets.UTF_8)))
                .header("Authorization", "KakaoAK " + key)
                .timeout(TIMEOUT)
                .GET()
                .build();

        HttpResponse<String> response =
                http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (response.statusCode() == 401) {
            throw new IOException("카카오가 키를 거절했습니다. map.api.key 가 REST API 키인지 확인하세요.");
        }
        if (response.statusCode() != 200) {
            throw new IOException("카카오 지도 API 오류 (HTTP " + response.statusCode() + ")");
        }
        return firstCoordinates(response.body());
    }

    /** 응답 본문에서 첫 문서의 x(경도)·y(위도)를 꺼냅니다. 결과가 없으면 null. */
    private static Coordinates firstCoordinates(String body) {
        Matcher x = X.matcher(body);
        Matcher y = Y.matcher(body);
        if (!x.find() || !y.find()) {
            return null;   // "documents":[] — 맞는 주소가 없습니다
        }
        try {
            return new Coordinates(Double.parseDouble(y.group(1)), Double.parseDouble(x.group(1)));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
