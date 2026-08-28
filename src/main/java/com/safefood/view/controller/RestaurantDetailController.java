package com.safefood.view.controller;

import com.safefood.dao.FavoriteDao;
import com.safefood.AppConfig;
import com.safefood.dao.LocationDao;
import com.safefood.dto.LocationDto;
import com.safefood.dto.RecommendationCard;
import com.safefood.dto.Session;
import com.safefood.view.AppNav;
import com.safefood.view.AsyncLoader;
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

import java.util.function.Consumer;

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
    @FXML private javafx.scene.control.Button eatButton;

    private RecommendationCard item;

    private final FavoriteDao favoriteDao = new FavoriteDao();
    private final AsyncLoader loader = new AsyncLoader();

    /** {@link #bindFavorite} 로 받기 전에는 0 — 저장하지 않고 안내만 합니다. */
    private int menuId;
    private int restaurantId;

    /** 여기서 바꾼 찜을 띄운 쪽(메인 카드 하트)에도 알려 줍니다. */
    private Consumer<Boolean> onFavoriteChanged = state -> { };

    /** '이걸로 먹을래요' 클릭 후 메인 카드 새로고침 콜백 */
    private Runnable onEatAction = () -> { };

    @FXML
    private void initialize() {
        favoriteButton.selectedProperty().addListener((observable, before, on) ->
                favoriteButton.setText(on ? "♥ 찜함" : "♡ 찜하기"));
        // 사용자가 직접 누를 때만 저장합니다 — setSelected 로 되돌릴 때는 돌지 않습니다
        favoriteButton.setOnAction(event -> toggleFavorite());
    }

    public void setOnEatAction(Runnable onEatAction) {
        this.onEatAction = (onEatAction == null) ? () -> { } : onEatAction;
    }

    public void setItem(RecommendationCard item, int menuId, int restaurantId) {
        this.menuId = menuId;
        this.restaurantId = restaurantId;
        setItem(item);
    }

    /**
     * 찜 버튼을 실제 DB에 연결합니다. <b>부르지 않으면 버튼이 저장되지 않습니다</b> —
     * 이 창을 여는 쪽이 메뉴·식당 id를 쥐고 있어서 여기서 넘겨받습니다.
     *
     * @param favorite  지금 찜해 둔 상태인지 (하트를 채운 채로 열지)
     * @param onChanged 저장이 끝난 뒤 바뀐 상태를 받습니다. null 이면 아무 데도 알리지 않습니다
     */
    public void bindFavorite(int menuId, int restaurantId, boolean favorite, Consumer<Boolean> onChanged) {
        this.menuId = menuId;
        this.restaurantId = restaurantId;
        this.onFavoriteChanged = onChanged == null ? state -> { } : onChanged;
        favoriteButton.setSelected(favorite);
    }

    private void toggleFavorite() {
        if (!Session.isMember() || menuId <= 0) {
            favoriteButton.setSelected(false);
            AppNav.info("게스트는 찜을 저장할 수 없어요 — 회원으로 로그인하면 남길 수 있습니다.");
            return;
        }
        // 저장도 왕복이라 화면 스레드에서 하면 누르는 순간 창이 멎습니다
        boolean wantFavorite = favoriteButton.isSelected();
        favoriteButton.setDisable(true);
        int userId = Session.currentUserId();
        String menu = menuName.getText();
        loader.submit(() -> wantFavorite
                        ? favoriteDao.addFavorite(userId, restaurantId, menuId)
                        : favoriteDao.removeFavorite(userId, menuId),
                ok -> {
                    favoriteButton.setDisable(false);
                    if (!ok) {
                        // 저장하지 못했으면 하트도 되돌립니다 — 저장된 것처럼 보이면 안 됩니다
                        favoriteButton.setSelected(!wantFavorite);
                        AppNav.error("찜을 저장하지 못했습니다. 데이터베이스 연결을 확인해 주세요.");
                        return;
                    }
                    onFavoriteChanged.accept(wantFavorite);
                    if (wantFavorite) {
                        AppNav.success(menu + "을(를) 찜 목록에 추가했습니다.");
                    } else {
                        AppNav.info(menu + "을(를) 찜 목록에서 제외했습니다.");
                    }
                },
                error -> {
                    favoriteButton.setDisable(false);
                    favoriteButton.setSelected(!wantFavorite);
                    AppNav.error("찜을 저장하지 못했습니다: " + error.getMessage());
                });
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

        // DB 식당 주소 · 영업시간 · 가격 · 평점 · 거리를 메타 정보로 표시
        metaLabel.setText("📍 " + addr + " · 영업 " + hours
                + " · 💰 " + item.priceText() + " · ★ " + item.rating() + dist);

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
                com.safefood.dto.Safety.SAFE, item.getSpicyLevel(), 0, item.getReason(),
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
    private void handleEat() {
        if (!Session.isMember()) {
            AppNav.info("게스트는 식사 기록을 남길 수 없어요 — 회원으로 로그인하면 저장됩니다.");
            return;
        }
        if (menuId <= 0) {
            AppNav.warn("이 메뉴는 기록할 수 없어요.");
            return;
        }
        if (eatButton != null) {
            eatButton.setDisable(true);
        }
        int userId = Session.currentUserId();
        String menu = (item != null && item.menu() != null) ? item.menu() : menuName.getText();
        loader.submit(() -> new com.safefood.service.HistoryService().saveEatenHistory(userId, menuId),
                saved -> {
                    if (eatButton != null) {
                        eatButton.setDisable(false);
                    }
                    if (!saved) {
                        AppNav.error("식사 기록을 저장하지 못했습니다. 데이터베이스 연결을 확인해 주세요.");
                        return;
                    }
                    AppNav.success(menu + "(으)로 정했어요 — 기록에 남기고 다음 추천에서 제외합니다");
                    onEatAction.run();
                    AppNav.close(root);
                },
                error -> {
                    if (eatButton != null) {
                        eatButton.setDisable(false);
                    }
                    AppNav.error("식사 기록을 저장하지 못했습니다: " + error.getMessage());
                });
    }

    @FXML
    private void handleClose() {
        AppNav.close(root);
    }
}
