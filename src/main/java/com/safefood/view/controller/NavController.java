package com.safefood.view.controller;

import com.safefood.dto.Session;
import com.safefood.view.AppNav;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;

/**
 * 상단 내비게이션 하나 — {@code nav.fxml} 의 컨트롤러.
 *
 * <p>이동·표시 이름·로그아웃을 여기서 다 하므로, 화면 컨트롤러는 자기 화면 일만 하면 됩니다.
 * 예전에는 네 화면이 {@code handleMain}·{@code handleProfile}·{@code handleHistory}·
 * {@code handleLogout} 을 각자 갖고 있었습니다.
 */
public class NavController {

    /** 지금 보고 있는 화면: 그 버튼만 강조합니다. */
    public enum Tab {
        MAIN, PROFILE, HISTORY, MEAL, NONE
    }

    private static final String ACTIVE = "active";

    @FXML private Button mainButton;
    @FXML private Button profileButton;
    @FXML private Button historyButton;
    @FXML private Button mealButton;
    @FXML private Button groupButton;
    @FXML private Label userLabel;

    @FXML
    private void initialize() {
        userLabel.setText(Session.displayName("사용자") + "님");
    }

    /**
     * 지금 화면을 알려 주면 그 버튼을 강조하고 눌리지 않게 합니다.
     * 화면 컨트롤러가 {@code initialize()} 에서 한 번 부릅니다.
     */
    public void setActive(Tab tab) {
        mark(mainButton, tab == Tab.MAIN);
        mark(profileButton, tab == Tab.PROFILE);
        mark(historyButton, tab == Tab.HISTORY);
        mark(mealButton, tab == Tab.MEAL);
    }

    /** 이름이 바뀌었을 때 다시 그립니다 (정보 변경 등). */
    public void refreshUserLabel() {
        userLabel.setText(Session.displayName("사용자") + "님");
    }

    private static void mark(Button button, boolean active) {
        button.getStyleClass().remove(ACTIVE);
        if (active) {
            button.getStyleClass().add(ACTIVE);
        }
        // 지금 보고 있는 화면을 다시 여는 것은 조회만 한 번 더 하는 낭비입니다
        button.setDisable(active);
    }

    // ---- 이동 ----

    @FXML
    private void handleMain() {
        AppNav.show("SafeFood: 맞춤 맛집 추천", "main.fxml");
    }

    @FXML
    private void handleProfile() {
        AppNav.show("프로필 및 설정 관리", "profile.fxml");
    }

    @FXML
    private void handleHistory() {
        AppNav.show("식사 기록 및 즐겨찾기", "history.fxml");
    }

    @FXML
    private void handleMeal() {
        AppNav.show("우리 아이 급식", "meal.fxml");
    }

    @FXML
    private void handleGroup() {
        AppNav.dialog("같이 먹기 옵션 선택", "group-option.fxml");
    }

    @FXML
    private void handleLogout() {
        AppNav.logout();
    }
}
