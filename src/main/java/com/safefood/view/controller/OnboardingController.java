package com.safefood.view.controller;

import com.safefood.dao.AllergyCatalog;
import com.safefood.dao.CategoryCatalog;
import com.safefood.dto.UserDto;
import com.safefood.view.AppNav;
import com.safefood.view.DemoData;
import com.safefood.view.Widgets;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 온보딩 3단계 스텝 (리디자인 1d).
 */
public class OnboardingController {

    /**
     * 회원가입 화면이 넘겨 주는 방금 만든 계정의 아이디 — 온보딩이 저장할 대상입니다.
     *
     * <p>정적 값이라 다 쓰고 비우지 않으면 앱이 도는 동안 남습니다.
     * 신원에 매인 값을 남겨 두면 다음 사람 차례에 잘못 쓰이므로 {@link #save()} 끝에서 비웁니다.
     */
    public static String newUserId;

    private static final int LAST_STEP = 3;

    private static final String[][] HEADINGS = {
            {"무슨 음식을 좋아하세요?", "고른 종류가 추천 상위에서 먼저 올라와요"},
            {"못 드시는 재료가 있나요?",
                    "식약처 고시 19종 · 한 명이라도 못 먹는 메뉴는 추천에서 빠져요"},
            {"예산과 거리는요?", "여기까지만 정하면 바로 추천을 시작할 수 있어요"}
    };

    @FXML private Label dot1;
    @FXML private Label dot2;
    @FXML private Label dot3;
    @FXML private Region line1;
    @FXML private Region line2;
    @FXML private Label name1;
    @FXML private Label name2;
    @FXML private Label name3;

    @FXML private Label stepTitle;
    @FXML private Label stepSubtitle;

    @FXML private VBox step1;
    @FXML private VBox step2;
    @FXML private VBox step3;

    @FXML private Slider spicySlider;
    @FXML private Label spicyValue;
    @FXML private FlowPane categoryPane;

    @FXML private FlowPane allergyPane;
    @FXML private VBox severityBox;

    @FXML private ComboBox<String> budgetBox;
    @FXML private HBox distanceSeg;

    @FXML private Button prevButton;
    @FXML private Button nextButton;

    private final List<ToggleButton> categoryChips = new ArrayList<>();
    private final List<ToggleButton> allergyChips = new ArrayList<>();
    private final ToggleGroup distanceGroup = new ToggleGroup();

    /** 고른 알레르기 별 심각도 (1~5). 칩을 껐다 켜도 값이 남아 있게 유지 */
    private final Map<String, Integer> severityByAllergy = new LinkedHashMap<>();

    private int step = 1;

    @FXML
    private void initialize() {
        spicySlider.valueProperty().addListener((observable, before, after) ->
                spicyValue.setText((int) spicySlider.getValue() + "단계"));

        budgetBox.getItems().setAll(DemoData.BUDGETS);
        budgetBox.setValue("12,000원 이하");

        fillDistanceSeg();

        Widgets.fillChips(categoryPane, CategoryCatalog.names(), categoryChips);
        // 고를 목록은 allergy 테이블 기준입니다 (DB를 못 읽으면 고시 19종으로 대신합니다)
        Widgets.fillChips(allergyPane, AllergyCatalog.names(), allergyChips);

        for (ToggleButton chip : allergyChips) {
            chip.selectedProperty().addListener((observable, before, on) -> {
                if (!on) {
                    severityByAllergy.remove(chip.getText());
                } else {
                    severityByAllergy.putIfAbsent(chip.getText(), 3);   // 기본 '보통'
                }
                rebuildSeverity();
            });
        }
        rebuildSeverity();

        renderStep();
    }

    private void fillDistanceSeg() {
        distanceSeg.getChildren().clear();
        List<String> options = DemoData.DISTANCES;
        for (int i = 0; i < options.size(); i++) {
            ToggleButton option =
                    Widgets.segOption(options.get(i), i == 0, i == options.size() - 1);
            option.setToggleGroup(distanceGroup);
            if ("1km".equals(options.get(i))) {
                option.setSelected(true);
            }
            distanceSeg.getChildren().add(option);
        }
        distanceGroup.selectedToggleProperty().addListener((observable, before, after) -> {
            if (after == null && before != null) {
                distanceGroup.selectToggle(before);
            }
        });
    }

    /** 고른 알레르기마다 심각도 카드 한 장 */
    private void rebuildSeverity() {
        severityBox.getChildren().clear();

        if (severityByAllergy.isEmpty()) {
            severityBox.getChildren().add(
                    Widgets.sub("해당하는 재료를 고르면 심각도를 지정할 수 있어요. 없으면 그냥 넘어가세요."));
            return;
        }

        for (Map.Entry<String, Integer> entry : severityByAllergy.entrySet()) {
            severityBox.getChildren().add(severityCard(entry.getKey(), entry.getValue()));
        }
        severityBox.getChildren().add(
                Widgets.micro("심각할수록 '혼입 가능' 메뉴의 순위를 크게 낮춰요."));
    }

    private VBox severityCard(String allergy, int current) {
        Label title = Widgets.label(allergy + " — 얼마나 심한가요?", "section-title");

        ToggleGroup group = new ToggleGroup();
        HBox seg = new HBox();
        seg.setAlignment(Pos.CENTER_LEFT);
        for (int level = 1; level <= DemoData.SEVERITIES.size(); level++) {
            String label = DemoData.SEVERITIES.get(level - 1).replace("Class ", "");
            ToggleButton option =
                    Widgets.segOption(label, level == 1, level == DemoData.SEVERITIES.size());
            option.setToggleGroup(group);
            option.setUserData(level);
            option.setSelected(level == current);
            seg.getChildren().add(option);
        }
        group.selectedToggleProperty().addListener((observable, before, after) -> {
            if (after == null) {
                group.selectToggle(before);
                return;
            }
            severityByAllergy.put(allergy, (int) after.getUserData());
        });

        VBox card = new VBox(10, title, seg);
        card.getStyleClass().addAll("card", "plain");
        return card;
    }

    // ── 단계 이동 ───────────────────────────────────────────────

    @FXML
    private void handleNext() {
        if (step < LAST_STEP) {
            step++;
            renderStep();
            return;
        }
        save();
        if (com.safefood.dto.Session.isGuest()) {
            AppNav.dialog("같이 먹기", "group-option.fxml");
        } else {
            AppNav.show("SafeFood — 맞춤 맛집 추천", "main.fxml");
        }
    }

    @FXML
    private void handlePrev() {
        if (step > 1) {
            step--;
            renderStep();
        }
    }

    /** 건너뛰기 */
    @FXML
    private void handleSkip() {
        save();
        if (com.safefood.dto.Session.isGuest()) {
            AppNav.dialog("같이 먹기", "group-option.fxml");
        } else {
            AppNav.show("SafeFood — 맞춤 맛집 추천", "main.fxml");
        }
    }

    private void renderStep() {
        show(step1, step == 1);
        show(step2, step == 2);
        show(step3, step == 3);

        stepTitle.setText(HEADINGS[step - 1][0]);
        stepSubtitle.setText(HEADINGS[step - 1][1]);

        paintDot(dot1, 1);
        paintDot(dot2, 2);
        paintDot(dot3, 3);
        paintLine(line1, step > 1);
        paintLine(line2, step > 2);
        paintName(name1, 1);
        paintName(name2, 2);
        paintName(name3, 3);

        prevButton.setDisable(step == 1);
        nextButton.setText(step == LAST_STEP ? "추천 시작하기 →" : "다음 →");
    }

    private void paintDot(Label dot, int index) {
        dot.getStyleClass().removeAll("done", "now");
        if (index < step) {
            dot.getStyleClass().add("done");
            dot.setText("✓");
        } else {
            dot.setText(String.valueOf(index));
            if (index == step) {
                dot.getStyleClass().add("now");
            }
        }
    }

    private static void paintLine(Region line, boolean done) {
        line.getStyleClass().remove("done");
        if (done) {
            line.getStyleClass().add("done");
        }
    }

    private void paintName(Label name, int index) {
        name.getStyleClass().removeAll("sub", "accent-text");
        name.getStyleClass().add(index == step ? "accent-text" : "sub");
    }

    private static void show(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    // ── 저장 ────────────────────────────────────────────────────

    private void save() {
        UserDto me = com.safefood.dto.Session.getCurrentUser();
        boolean isGuest = com.safefood.dto.Session.isGuest();

        int currentUserId = -1;
        if (!isGuest) {
            if (newUserId != null && !newUserId.isBlank()) {
                com.safefood.service.AuthService authService = new com.safefood.service.AuthService();
                UserDto user = authService.getUserInfo(newUserId);
                if (user != null) {
                    com.safefood.dto.Session.setCurrentUser(user);
                    currentUserId = user.getId();
                }
            } else if (me != null) {
                currentUserId = me.getId();
            }
        }

        int spicyLevel = (int) spicySlider.getValue();

        String budgetStr = budgetBox.getValue();
        int priceMax = 0;
        if (budgetStr != null && !"제한없음".equals(budgetStr)) {
            priceMax = Integer.parseInt(budgetStr.replaceAll("[^0-9]", ""));
        }

        int maxDistance = parseDistance(selectedDistance());
        List<String> preferredCategories = Widgets.selected(categoryChips);

        if (isGuest) {
            com.safefood.dto.PreferenceDto guestPref = new com.safefood.dto.PreferenceDto(spicyLevel, 0, priceMax, maxDistance);
            com.safefood.dto.Session.setGuestPreference(guestPref);
            return;
        }

        if (currentUserId > 0) {
            com.safefood.service.OnboardingService onboardingService =
                    new com.safefood.service.OnboardingService();

            // 알레르기는 이름 → 번호 변환과 저장을 한 트랜잭션으로 처리하는 쪽으로 보냅니다
            // (목록 순서로 번호를 짐작하면 다른 알레르기로 저장될 수 있습니다)
            if (!new com.safefood.service.ProfileService()
                    .updateAllergiesOnly(currentUserId, severityByAllergy)) {
                AppNav.error("알레르기를 저장하지 못했습니다. 프로필 화면에서 다시 등록해 주세요.");
            }

            onboardingService.savePreferences(
                    currentUserId, spicyLevel, priceMax, maxDistance, preferredCategories);
        }

        newUserId = null;   // 다 썼습니다 — 다음 사람 차례에 남아 있으면 안 됩니다
    }

    private String selectedDistance() {
        ToggleButton picked = (ToggleButton) distanceGroup.getSelectedToggle();
        return picked == null ? "1km" : picked.getText();
    }

    /** "1km" → 1000(미터), "제한없음" → 0 */
    private static int parseDistance(String label) {
        return switch (label) {
            case "500m" -> 500;
            case "1km" -> 1000;
            case "2km" -> 2000;
            case "3km" -> 3000;
            default -> 0;
        };
    }
}
