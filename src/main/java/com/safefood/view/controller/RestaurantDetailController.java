package com.safefood.view.controller;

import com.safefood.AppConfig;
import com.safefood.dao.LocationDao;
import com.safefood.dto.LocationDto;
import com.safefood.dto.RecommendationCard;
import com.safefood.dto.Session;
import com.safefood.view.AppNav;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * 맛집 상세 — 카카오 실시간 지도(식당 위치 + 로그인 유저의 현재 위치)를 상단에 표시하고, DB 식당 정보 및 주소를 보여줍니다.
 */
public class RestaurantDetailController {

    @FXML private VBox root;
    @FXML private WebView mapWebView;
    @FXML private Label restaurantName;
    @FXML private Label menuName;
    @FXML private Label safetyTag;
    @FXML private Label metaLabel;
    @FXML private Label reasonLabel;
    @FXML private ToggleButton favoriteButton;

    private RecommendationCard item;

    @FXML
    private void initialize() {
        favoriteButton.selectedProperty().addListener((observable, before, on) ->
                favoriteButton.setText(on ? "♥ 찜함" : "♡ 찜"));
    }

    public void setItem(RecommendationCard item) {
        this.item = item;
        restaurantName.setText(item.restaurant());
        menuName.setText(item.menu());
        reasonLabel.setText(item.reason());

        safetyTag.setText(item.safety().shortLabel);
        safetyTag.getStyleClass().removeAll("safe", "possible", "contains");
        safetyTag.getStyleClass().add(item.safety().styleClass);

        String addr = (item.address() != null && !item.address().isBlank()) ? item.address() : "주소 정보 없음";
        String hours = (item.hours() != null && !item.hours().isBlank()) ? item.hours() : "영업시간 정보 없음";
        String dist = (item.distance() != null && !item.distance().isBlank()) ? " (" + item.distance() + ")" : "";

        // DB 식당 주소 · 영업시간 · 평점 · 거리를 메타 정보로 표시
        metaLabel.setText("📍 " + addr + " · 영업 " + hours + " · ★ " + item.rating() + dist);

        loadMap(item);
    }

    public void setItem(com.safefood.dto.RecommendationDto item) {
        restaurantName.setText(item.getRestaurant());
        menuName.setText(item.getMenu());
        reasonLabel.setText(item.getReason());

        if (item.getSafety() != null) {
            safetyTag.setText(item.getSafety().label);
            safetyTag.getStyleClass().removeAll("safe", "possible", "contains");
            safetyTag.getStyleClass().add(item.getSafety().styleClass);
        }

        String hours = item.getHours() != null ? item.getHours() : "영업시간 정보 없음";
        String address = item.getAddress() != null ? item.getAddress() : "주소 정보 없음";
        metaLabel.setText("📍 " + address + " · 영업 " + hours + " · ★ " + item.getRating());

        RecommendationCard card = new RecommendationCard(
                item.getRank(), item.getScore(), item.getMenu(), item.getRestaurant(),
                com.safefood.dto.Safety.SAFE, item.getSpicyLevel(), item.getReason(),
                address, hours, item.getRating(), "", item.isBlocked(), item.getAlternative(), null, null
        );
        loadMap(card);
    }

    /**
     * kakao-map.html 템플릿을 로드하고 식당(restaurant 테이블) 및 로그인 유저 위치(user_location 테이블)를 지도에 띄웁니다.
     */
    private void loadMap(RecommendationCard targetItem) {
        if (mapWebView == null) {
            return;
        }
        WebEngine engine = mapWebView.getEngine();
        engine.setJavaScriptEnabled(true);

        try (InputStream is = getClass().getResourceAsStream("/com/safefood/view/kakao-map.html")) {
            if (is == null) {
                System.err.println("[Map] kakao-map.html 파일을 찾을 수 없습니다.");
                return;
            }
            String rawHtml = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            String jsKey = AppConfig.kakaoJsKey();
            String finalHtml = rawHtml.replace("__KAKAO_JS_KEY__", jsKey != null ? jsKey : "");

            engine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
                if (newState == Worker.State.SUCCEEDED) {
                    renderLocationsOnMap(engine, targetItem);
                }
            });

            engine.loadContent(finalHtml);
        } catch (Exception e) {
            System.err.println("[Map] 지도 템플릿 로드 실패: " + e.getMessage());
        }
    }

    /**
     * 1. 식당 위치 (restaurant 테이블의 latitude, longitude)
     * 2. 현재 위치 (로그인 한 유저의 user_location 테이블의 latitude, longitude)
     * 두 지점을 웹뷰 지도에 마커 및 영역으로 렌더링합니다.
     */
    private void renderLocationsOnMap(WebEngine engine, RecommendationCard card) {
        CompletableFuture.runAsync(() -> {
            // 1. 식당 위치 (restaurant 테이블)
            Double restLat = card.latitude();
            Double restLng = card.longitude();

            // 2. 현재 위치 (로그인 한 유저의 user_location 테이블)
            LocationDto myLoc = Session.getMyLocation();
            if (myLoc == null && Session.isMember()) {
                myLoc = new LocationDao().getLocation(Session.currentUserId());
            }

            Double myLat = (myLoc != null && myLoc.hasCoordinates()) ? myLoc.getLatitude() : null;
            Double myLng = (myLoc != null && myLoc.hasCoordinates()) ? myLoc.getLongitude() : null;
            String myLabel = "현재 위치";

            final String restLatStr = restLat != null ? String.format(Locale.US, "%.7f", restLat) : "null";
            final String restLngStr = restLng != null ? String.format(Locale.US, "%.7f", restLng) : "null";
            final String safeRestName = card.restaurant() != null ? card.restaurant().replace("'", "\\'") : "식당";

            final String myLatStr = myLat != null ? String.format(Locale.US, "%.7f", myLat) : "null";
            final String myLngStr = myLng != null ? String.format(Locale.US, "%.7f", myLng) : "null";
            final String safeMyLabel = myLabel.replace("'", "\\'");

            Platform.runLater(() -> {
                try {
                    String script = String.format(
                            Locale.US,
                            "displayMap(%s, %s, '%s', %s, %s, '%s');",
                            restLatStr, restLngStr, safeRestName, myLatStr, myLngStr, safeMyLabel
                    );
                    engine.executeScript(script);
                } catch (Exception ex) {
                    System.err.println("[Map] 지도 스크립트 실행 오류: " + ex.getMessage());
                }
            });
        });
    }

    @FXML
    private void handleRoute() {
        AppNav.info("외부 지도 앱으로 연결하는 기능입니다. (3차 구현 예정)");
    }

    @FXML
    private void handleRecord() {
        String menu = item == null ? "이 메뉴" : item.menu();
        AppNav.success(menu + "을(를) 먹은 기록으로 남겼어요");
    }

    @FXML
    private void handleClose() {
        AppNav.close(root);
    }
}
