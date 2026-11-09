package com.safefood.view.controller;

import com.safefood.dao.FavoriteDao;
import com.safefood.AppConfig;
import com.safefood.dto.LocationDto;
import com.safefood.dto.RecommendationCard;
import com.safefood.dto.Session;
import com.safefood.service.RecommendService;
import com.safefood.view.AppNav;
import com.safefood.view.AsyncLoader;
import javafx.concurrent.Worker;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import netscape.javascript.JSObject;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
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

    /** 카카오맵 링크: 별도 키가 필요 없고, PC·모바일에 맞춰 알아서 열립니다. */
    private static final String KAKAO_MAP_LINK = "https://map.kakao.com/link";

    private RecommendationCard item;

    private final FavoriteDao favoriteDao = new FavoriteDao();
    private final AsyncLoader loader = new AsyncLoader();

    /** {@link #bindFavorite} 로 받기 전에는 0: 저장하지 않고 안내만 합니다. */
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
            AppNav.info("게스트는 찜을 저장할 수 없어요: 회원으로 로그인하면 남길 수 있습니다.");
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
        // 조회는 AsyncLoader 로 보냅니다. 예전에는 CompletableFuture.runAsync 로 돌렸는데,
        // 그 공용 풀은 AsyncLoader 가 세워 둔 줄을 서지 않아 다른 조회와 커넥션이 겹쳤습니다
        // (커넥션 풀이 없어 조회마다 새로 연결하므로 줄 세우기가 필요합니다).
        // 게다가 runAsync 는 예외를 삼켜서, 조회가 깨지면 지도가 아무 말 없이 비어 있었습니다.
        loader.load(
                // '내 위치'를 읽는 자리는 RecommendService.myLocation() 하나로 모읍니다 —
                // 세션 사본 → 없으면 DB 라는 같은 규칙을 여기 따로 두면 둘이 갈라집니다
                RecommendService::myLocation,
                myLoc -> drawMap(engine, card, myLoc),
                error -> {
                    // 내 위치를 못 읽어도 식당 위치는 띄웁니다 — 지도를 통째로 비우지 않습니다
                    System.err.println("[Map] 내 위치를 읽지 못했습니다: " + error.getMessage());
                    drawMap(engine, card, null);
                });
    }

    /** FX 스레드 전용: 좌표가 없으면 null 을 넘겨 {@code displayMap} 이 "표시 안 함"으로 처리합니다. */
    private static void drawMap(WebEngine engine, RecommendationCard card, LocationDto myLoc) {
        boolean hasMine = myLoc != null && myLoc.hasCoordinates();
        callDisplayMap(engine,
                card.latitude(), card.longitude(),
                card.restaurant() != null ? card.restaurant() : "식당",
                hasMine ? myLoc.getLatitude() : null,
                hasMine ? myLoc.getLongitude() : null,
                "현재 위치");
    }

    /**
     * 지도 함수 {@code displayMap(restLat, restLng, restName, myLat, myLng, myLabel)} 를 호출합니다.
     *
     * <p><b>값을 스크립트 문자열에 끼워 넣지 않습니다.</b> 예전에는
     * {@code executeScript("displayMap(..., '" + 식당이름 + "', ...)")} 처럼 문자열로 조립했는데,
     * 식당 이름에 담긴 따옴표·역슬래시가 문자열을 깨고 그 뒤를 <b>코드로 실행</b>시킬 수 있었습니다
     * (스크립트 인젝션). 좌표는 숫자, 이름은 문자열 <b>인자</b>로 그대로 건네면
     * WebView 가 값으로만 취급하므로 그 통로 자체가 사라집니다. ({@code PostcodeController} 와 같은 방식)
     *
     * <p>좌표는 {@code Double} 이라 없으면 {@code null} 이 그대로 JS {@code null} 로 넘어가,
     * {@code displayMap} 이 "표시 안 함"으로 처리합니다.
     */
    @SuppressWarnings("removal")   // JSObject 는 JavaFX 21 에서 WebView 로 값을 넘기는 표준 통로입니다
    private static void callDisplayMap(WebEngine engine, Double restLat, Double restLng, String restName,
                                       Double myLat, Double myLng, String myLabel) {
        try {
            JSObject window = (JSObject) engine.executeScript("window");
            window.call("displayMap", restLat, restLng, restName, myLat, myLng, myLabel);
        } catch (RuntimeException ex) {
            System.err.println("[Map] 지도 스크립트 실행 오류: " + ex.getMessage());
        }
    }

    /**
     * 카카오맵 길찾기를 기본 브라우저로 엽니다.
     *
     * <p>내 위치를 알면 <b>출발지까지 채워서</b>, 모르면 도착지만 지정해 엽니다 — 출발지를
     * 0,0 같은 값으로 채우면 엉뚱한 경로가 나오므로 아예 넘기지 않습니다.
     *
     * <p>내 위치 조회는 DB 를 볼 수 있어 백그라운드로 보냅니다. 실패해도 도착지만으로 엽니다 —
     * 길찾기를 통째로 막는 것보다 낫습니다.
     */
    @FXML
    private void handleRoute() {
        if (item == null || item.latitude() == null || item.longitude() == null) {
            AppNav.info("이 가게는 좌표가 등록돼 있지 않아 길찾기를 열 수 없어요.");
            return;
        }
        RecommendationCard target = item;
        loader.submit(RecommendService::myLocation,
                myLoc -> openRoute(target, myLoc),
                error -> {
                    System.err.println("[Map] 내 위치를 읽지 못해 도착지만 엽니다: " + error.getMessage());
                    openRoute(target, null);
                });
    }

    private static void openRoute(RecommendationCard card, LocationDto myLoc) {
        String name = (card.restaurant() != null && !card.restaurant().isBlank())
                ? card.restaurant() : "목적지";
        String to = kakaoPoint(name, card.latitude(), card.longitude());

        String url = (myLoc != null && myLoc.hasCoordinates())
                ? KAKAO_MAP_LINK + "/from/"
                        + kakaoPoint("내 위치", myLoc.getLatitude(), myLoc.getLongitude())
                        + "/to/" + to
                : KAKAO_MAP_LINK + "/to/" + to;

        AppNav.openExternal(url);
    }

    /**
     * 카카오맵 링크의 한 지점 — {@code 이름,위도,경도} 입니다.
     *
     * <p>이름은 반드시 인코딩합니다. 쉼표가 좌표 구분자이고 슬래시가 경로 구분자라, 가게 이름에
     * 그런 글자가 있으면 링크가 통째로 어긋납니다. 좌표는 {@code Locale.US} 로 찍어 소수점이
     * 쉼표로 바뀌는 것을 막습니다 — 그러면 칸 수가 달라져 카카오맵이 읽지 못합니다.
     */
    private static String kakaoPoint(String name, double latitude, double longitude) {
        return URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20")
                + "," + String.format(Locale.US, "%.7f", latitude)
                + "," + String.format(Locale.US, "%.7f", longitude);
    }

    @FXML
    private void handleEat() {
        if (!Session.isMember()) {
            AppNav.info("게스트는 식사 기록을 남길 수 없어요: 회원으로 로그인하면 저장됩니다.");
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
                    AppNav.success(menu + "(으)로 정했어요: 기록에 남기고 다음 추천에서 제외합니다");
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
