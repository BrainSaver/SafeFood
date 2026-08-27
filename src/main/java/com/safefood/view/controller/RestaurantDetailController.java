package com.safefood.view.controller;

import com.safefood.dao.FavoriteDao;
import com.safefood.dto.RecommendationCard;
import com.safefood.dto.Session;
import com.safefood.view.AppNav;
import com.safefood.view.AsyncLoader;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

/**
 * 맛집 상세 (리디자인 1j) — 지도가 맨 위 전체 폭을 차지하고, 나머지 정보는 한 줄 메타로 접힙니다.
 */
public class RestaurantDetailController {

    @FXML private VBox root;
    @FXML private Label restaurantName;
    @FXML private Label menuName;
    @FXML private Label safetyTag;
    @FXML private Label metaLabel;
    @FXML private Label reasonLabel;
    @FXML private ToggleButton favoriteButton;

    private RecommendationCard item;

    private final FavoriteDao favoriteDao = new FavoriteDao();
    private final AsyncLoader loader = new AsyncLoader();

    /** {@link #bindFavorite} 로 받기 전에는 0 — 저장하지 않고 안내만 합니다. */
    private int menuId;
    private int restaurantId;

    /** 여기서 바꾼 찜을 띄운 쪽(메인 카드 하트)에도 알려 줍니다. */
    private Consumer<Boolean> onFavoriteChanged = state -> { };

    @FXML
    private void initialize() {
        favoriteButton.selectedProperty().addListener((observable, before, on) ->
                favoriteButton.setText(on ? "♥ 찜함" : "♡ 찜"));
        // 사용자가 직접 누를 때만 저장합니다 — setSelected 로 되돌릴 때는 돌지 않습니다
        favoriteButton.setOnAction(event -> toggleFavorite());
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

        // 주소 · 영업시간 · 평점 · 거리를 한 줄로
        metaLabel.setText(item.address()
                + " · 영업 " + item.hours()
                + " · ★ " + item.rating() + " (312)"
                + " · " + item.distance());
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

        String hours = item.getHours() != null ? item.getHours() : "";
        String address = item.getAddress() != null ? item.getAddress() : "";
        metaLabel.setText(address + " · 영업 " + hours + " · ★ " + item.getRating());
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
