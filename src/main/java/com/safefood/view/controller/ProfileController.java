package com.safefood.view.controller;

import com.safefood.dao.AllergyCatalog;
import com.safefood.dto.LocationDto;
import com.safefood.dto.PreferenceDto;
import com.safefood.dto.Session;
import com.safefood.dto.UserDto;
import com.safefood.service.LocationService;
import com.safefood.service.ProfileService;
import com.safefood.view.AppNav;
import com.safefood.view.DemoData;
import com.safefood.view.Theme;
import com.safefood.view.Widgets;
import javafx.animation.PauseTransition;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 내 프로필 — 2열 배치, 심각도는 칩 안에, 저장 버튼은 없앴습니다 (리디자인 1h).
 *
 * <p>바뀐 값은 곧바로 DB로 갑니다. 슬라이더처럼 값이 연달아 바뀌는 입력은
 * {@link PauseTransition} 으로 잠깐 멎기를 기다렸다가 한 번만 저장합니다 — 안 그러면
 * 손잡이를 끄는 동안 수십 번 쓰게 됩니다.
 */
public class ProfileController {

    /** 값이 멎었다고 볼 시간 — 이만큼 조용하면 저장합니다. */
    private static final Duration SETTLE = Duration.millis(500);

    @FXML private FlowPane ownedPane;
    @FXML private FlowPane catalogPane;
    @FXML private FlowPane categoryPane;
    @FXML private FlowPane themePane;
    @FXML private Label themeTag;
    @FXML private TextField searchField;
    @FXML private HBox distanceSeg;
    @FXML private Slider priceMax;
    @FXML private Label priceLabel;
    @FXML private Slider spicySlider;
    @FXML private Label spicyLabel;
    @FXML private Label userLabel;
    @FXML private Label autoSaveTag;
    @FXML private Label allergyHint;
    @FXML private Label locationLabel;
    @FXML private Button locationButton;

    private final List<ToggleButton> categoryChips = new ArrayList<>();
    private final ToggleGroup distanceGroup = new ToggleGroup();

    /** 화면에 붙어 있는 알레르기 → 심각도. 저장할 때 이걸 그대로 넘깁니다. */
    private final Map<String, Integer> owned = new LinkedHashMap<>();

    private final PauseTransition preferenceSettle = new PauseTransition(SETTLE);

    /** 초기 로딩 중에는 저장하지 않습니다 — 값을 채우는 것도 '변경'으로 잡히기 때문입니다. */
    private boolean loading = true;

    @FXML
    private void initialize() {
        fillDistanceSeg();
        fillThemes();
        Widgets.fillChips(categoryPane, DemoData.CATEGORIES, categoryChips);
        preferenceSettle.setOnFinished(event -> savePreferences());

        // 입력칸은 검색도 겸합니다 — 치는 동안 아래 목록이 걸러집니다
        searchField.textProperty().addListener((observable, before, after) -> renderCatalog());

        // 게스트도 Session 에 UserDto 가 들어 있으므로(id = Session.GUEST_ID) null 검사로는 못 거릅니다
        if (!Session.isMember()) {
            // 게스트는 저장할 계정이 없습니다 — 화면은 보여 주되 자동 저장은 끕니다
            userLabel.setText(Session.displayName("게스트") + "님");
            autoSaveTag.setText("게스트 — 저장되지 않아요");
            autoSaveTag.getStyleClass().remove("accent2");
            autoSaveTag.getStyleClass().add("neutral");
            renderPriceLabel();
            renderSpicyLabel();
            renderOwned();
            renderCatalog();
            renderLocation();
            wireAutoSave();
            loading = false;
            return;
        }

        UserDto me = Session.getCurrentUser();
        userLabel.setText(Session.displayName("사용자") + "님");
        ProfileService profileService = new ProfileService();

        Map<String, Integer> myAllergies = profileService.getMyAllergies(me.getId());
        if (myAllergies != null) {
            owned.putAll(myAllergies);
        }

        List<String> myCategories = profileService.getMyCategories(me.getId());
        if (myCategories != null) {
            Widgets.preselect(categoryChips, myCategories);
        }

        PreferenceDto pref = profileService.getMyPreference(me.getId());
        if (pref != null) {
            priceMax.setValue(pref.getPriceMax());
            spicySlider.setValue(pref.getSpicyLevel());
            selectDistance(pref.getMaxDistance());
        }

        renderPriceLabel();
        renderSpicyLabel();
        renderOwned();
        renderCatalog();
        renderLocation();
        wireAutoSave();
        loading = false;
    }

    /** 값이 바뀌면 저장으로 이어지도록 묶습니다 — 로딩이 끝난 뒤에 붙입니다. */
    private void wireAutoSave() {
        priceMax.valueProperty().addListener((observable, before, after) -> {
            // 1000원 단위로 스냅 — 라벨과 저장값이 같은 눈금을 쓰게
            int rounded = (int) Math.round(after.doubleValue() / 1000.0) * 1000;
            if ((int) priceMax.getValue() != rounded) {
                priceMax.setValue(rounded);
                return;   // 되돌아온 변경에서 이어서 처리됩니다
            }
            renderPriceLabel();
            schedulePreferenceSave();
        });

        spicySlider.valueProperty().addListener((observable, before, after) -> {
            renderSpicyLabel();
            schedulePreferenceSave();
        });

        for (ToggleButton chip : categoryChips) {
            chip.selectedProperty().addListener((observable, before, on) -> schedulePreferenceSave());
        }

        distanceGroup.selectedToggleProperty().addListener((observable, before, after) -> {
            if (after == null && before != null) {
                distanceGroup.selectToggle(before);
                return;
            }
            schedulePreferenceSave();
        });
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
    }

    private void selectDistance(int meters) {
        String label = switch (meters) {
            case 500 -> "500m";
            case 1000 -> "1km";
            case 2000 -> "2km";
            case 3000 -> "3km";
            default -> "제한없음";
        };
        for (javafx.scene.Node node : distanceSeg.getChildren()) {
            ToggleButton option = (ToggleButton) node;
            option.setSelected(option.getText().equals(label));
        }
    }

    // ── 알레르기 칩 ─────────────────────────────────────────────

    /** 칩 하나가 이름 + 심각도 선택 + 삭제를 다 갖습니다 — 따로 심각도 칸을 두지 않습니다. */
    private void renderOwned() {
        ownedPane.getChildren().clear();
        if (owned.isEmpty()) {
            ownedPane.getChildren().add(Widgets.sub("등록한 알레르기가 없습니다."));
            return;
        }
        for (Map.Entry<String, Integer> entry : owned.entrySet()) {
            ownedPane.getChildren().add(allergyChip(entry.getKey(), entry.getValue()));
        }
    }

    private HBox allergyChip(String name, int severity) {
        Label label = new Label(name + " · 심각");

        ChoiceBox<Integer> level = new ChoiceBox<>();
        level.getItems().setAll(1, 2, 3, 4, 5);
        level.setValue(severity);
        level.valueProperty().addListener((observable, before, after) -> {
            if (after == null) {
                return;
            }
            owned.put(name, after);
            saveAllergies();
        });

        Button remove = new Button("✕");
        remove.getStyleClass().addAll("ghost-quiet");
        remove.setOnAction(event -> {
            owned.remove(name);
            renderOwned();
            renderCatalog();
            saveAllergies();
        });

        HBox chip = new HBox(6, label, level, remove);
        chip.setAlignment(Pos.CENTER_LEFT);
        chip.getStyleClass().addAll("tag", "accent");
        return chip;
    }

    // ── 알레르기 고르기 ─────────────────────────────────────────

    /*
     * 예전에는 이름을 정확히 쳐 넣어야만 추가할 수 있었습니다. 무엇을 적을 수 있는지 모르면
     * 아무것도 못 넣는 화면이라, 고를 수 있는 목록을 그대로 펼쳐 놓고 눌러서 넣게 했습니다.
     * 입력칸은 없애지 않고 '검색'으로 바꿨습니다 — 19종을 눈으로 훑는 것보다 두 글자 치는
     * 편이 빠른 사람도 있으니까요.
     *
     * 목록이 마스터 19종으로 묶여 있는 것은 화면 사정이 아니라 DB 사정입니다:
     * user_allergy 는 allergy_id(마스터의 번호)로 저장되므로, 목록 밖의 이름은 넣을 번호가
     * 없습니다 (ProfileService.updateAllergiesOnly 참고).
     */

    /** 아직 등록하지 않은 것 중, 검색어에 걸리는 항목. */
    private List<String> candidates() {
        String keyword = searchField.getText() == null ? "" : searchField.getText().trim();
        List<String> found = new ArrayList<>();
        for (String name : AllergyCatalog.names()) {
            if (!owned.containsKey(name) && name.contains(keyword)) {
                found.add(name);
            }
        }
        return found;
    }

    private void renderCatalog() {
        catalogPane.getChildren().clear();
        List<String> found = candidates();
        if (found.isEmpty()) {
            catalogPane.getChildren().add(Widgets.sub(owned.size() == AllergyCatalog.names().size()
                    ? "등록할 수 있는 알레르기를 모두 등록했습니다."
                    : "검색어와 맞는 항목이 없습니다. 식약처 고시 19종 안에서 찾아 주세요."));
            return;
        }
        for (String name : found) {
            catalogPane.getChildren().add(catalogChip(name));
        }
    }

    /** 눌러서 바로 추가되는 칩. 고르는 순간이 곧 추가라 따로 확인 버튼을 두지 않습니다. */
    private Button catalogChip(String name) {
        Button chip = new Button(name);
        chip.getStyleClass().add("chip");
        chip.setOnAction(event -> addAllergy(name));
        return chip;
    }

    /** 입력칸으로 추가 — 검색 결과가 하나로 좁혀졌으면 그것을, 아니면 어떻게 좁힐지 알려 줍니다. */
    @FXML
    private void handleAddAllergy() {
        String keyword = searchField.getText().trim();
        if (keyword.isEmpty()) {
            hint("아래 목록에서 고르거나, 알레르기 이름을 입력해 주세요.");
            return;
        }
        if (AllergyCatalog.names().contains(keyword)) {
            addAllergy(keyword);   // 정확히 친 이름은 후보가 여럿이어도 그대로 받습니다
            return;
        }

        List<String> found = candidates();
        if (found.size() == 1) {
            addAllergy(found.get(0));
        } else if (found.isEmpty()) {
            hint("'" + keyword + "'와(과) 맞는 알레르기가 없습니다. 식약처 고시 19종 중에서 골라 주세요.");
        } else {
            hint("'" + keyword + "'에 걸리는 항목이 " + found.size() + "개입니다 — 아래에서 눌러 골라 주세요.");
        }
    }

    private void addAllergy(String name) {
        if (owned.containsKey(name)) {
            hint("이미 등록한 알레르기입니다.");
            return;
        }

        owned.put(name, 3);   // 기본 '보통' — 칩에서 바로 바꿀 수 있습니다
        searchField.clear();  // 검색어를 비워 목록을 전체로 되돌립니다
        Widgets.hideError(allergyHint);
        renderOwned();
        renderCatalog();
        saveAllergies();
    }

    private void hint(String message) {
        Widgets.showError(allergyHint, message);
    }

    // ── 저장 ────────────────────────────────────────────────────

    private void schedulePreferenceSave() {
        if (loading) {
            return;
        }
        preferenceSettle.playFromStart();
    }

    private void saveAllergies() {
        if (loading) {
            return;
        }
        if (!Session.isMember()) {
            return;   // 게스트 — 저장할 계정이 없습니다
        }
        // 실패를 조용히 넘기면 '자동 저장' 배지만 보고 저장된 줄 압니다 — 알레르기는 특히 위험합니다
        if (!new ProfileService().updateAllergiesOnly(
                Session.currentUserId(), new java.util.LinkedHashMap<>(owned))) {
            AppNav.error("알레르기를 저장하지 못했습니다. "
                    + "이전에 등록한 목록은 그대로 남아 있습니다 — 연결을 확인한 뒤 다시 시도해 주세요.");
        }
    }

    private void savePreferences() {
        if (!Session.isMember()) {
            return;
        }
        if (!new ProfileService().updatePreferencesOnly(Session.currentUserId(),
                (int) spicySlider.getValue(),
                (int) priceMax.getValue(),
                parseDistance(selectedDistance()),
                Widgets.selected(categoryChips))) {
            AppNav.error("취향을 저장하지 못했습니다. 데이터베이스 연결을 확인해 주세요.");
        }
    }

    private String selectedDistance() {
        ToggleButton picked = (ToggleButton) distanceGroup.getSelectedToggle();
        return picked == null ? "1km" : picked.getText();
    }

    private static int parseDistance(String label) {
        return switch (label) {
            case "500m" -> 500;
            case "1km" -> 1000;
            case "2km" -> 2000;
            case "3km" -> 3000;
            default -> 0;
        };
    }

    // ── 라벨 ────────────────────────────────────────────────────

    private void renderPriceLabel() {
        priceLabel.setText(String.format("한 끼 예산 상한 — %,d원", (int) priceMax.getValue()));
    }

    private void renderSpicyLabel() {
        spicyLabel.setText("선호 맵기 — " + (int) spicySlider.getValue() + "단계");
    }

    // ── 내 위치 ─────────────────────────────────────────────────

    /*
     * 거리 조건('최대 거리')은 기준점이 있어야 쓸모가 있습니다. 그 기준점을 등록하는 자리가
     * 이 카드이고, 실제 입력은 창(location.fxml)에서 받습니다 — 이름·주소에 좌표까지 받아야
     * 해서 프로필 카드 한 줄에 넣기에는 칸이 모자랍니다.
     */

    /** 등록해 둔 위치를 한 줄로. 세션에 사본이 없으면 DB에서 한 번 읽어 옵니다. */
    private void renderLocation() {
        LocationDto mine = Session.getMyLocation();
        if (mine == null && Session.isMember()) {
            mine = new LocationService().getMyLocation(Session.currentUserId());
            Session.setMyLocation(mine);
        }
        boolean registered = mine != null;
        // 좌표가 없으면 거리 계산에서 빠지므로, 등록돼 있어도 그 사실을 같이 알려 줍니다
        locationLabel.setText(registered
                ? mine.describe() + (mine.hasCoordinates() ? "" : " · 좌표 없음")
                : "아직 등록한 위치가 없어요");
        locationButton.setText(registered ? "위치 변경" : "위치 등록");
    }

    /**
     * 위치 등록 창을 엽니다.
     *
     * <p>창이 닫히기를 기다렸다가 라벨을 다시 그리지 <b>않습니다</b> — {@code AppNav.dialog}는
     * 실제로 여는 일을 한 박자 미루므로, 이 메서드가 끝난 뒤에야 창이 열립니다. 대신 저장된
     * 순간 알려 달라고 걸어 둡니다.
     */
    @FXML
    private void handleRegisterLocation() {
        AppNav.dialog("위치 등록", "location.fxml",
                (LocationController controller) -> controller.setOnSaved(this::renderLocation));
    }

    // ── 앱 스타일 ───────────────────────────────────────────────

    /*
     * 테마는 색 토큰만 갈아 끼우는 css 한 장입니다 (Theme 참고). 고르는 즉시 열려 있는
     * 창 전부가 다시 칠해지므로, '적용' 버튼 없이 누르는 것으로 끝냅니다.
     */

    private void fillThemes() {
        themePane.getChildren().clear();
        for (Theme option : Theme.values()) {
            themePane.getChildren().add(themeOption(option));
        }
        renderThemeTag();
    }

    /** 테마 한 칸 — 축소판 미리보기 + 이름 + 한 줄 설명. */
    private VBox themeOption(Theme option) {
        VBox card = new VBox(8,
                themePreview(option),
                Widgets.label(option.label(), "name"),
                Widgets.micro(option.description()));
        card.getStyleClass().add("theme-option");
        card.setMaxWidth(158);   // 설명이 줄바꿈되도록 폭을 묶습니다
        card.setUserData(option);
        card.setOnMouseClicked(event -> pickTheme(option));
        markSelected(card, option == AppNav.theme());
        return card;
    }

    /**
     * 축소판 화면 — 바탕 위에 표면 두 줄과 강조색 알약.
     *
     * <p>여기 색만은 인라인 스타일로 칠합니다. 이 칸이 보여 줘야 하는 것은 <b>지금 테마가
     * 아니라 저 테마의 색</b>이라, 화면 전체가 쓰는 토큰(-bg, -accent)으로는 그릴 수 없습니다.
     */
    private static VBox themePreview(Theme option) {
        VBox preview = new VBox(6,
                bar(option.surface(), 74, 9),
                bar(option.surface(), 50, 9),
                bar(option.accent(), 34, 13));
        preview.getStyleClass().add("theme-preview");
        preview.setStyle("-fx-background-color: " + option.bg() + ";");
        return preview;
    }

    private static Region bar(String color, double width, double height) {
        Region bar = new Region();
        bar.getStyleClass().add("bar");
        bar.setMinSize(width, height);
        bar.setPrefSize(width, height);
        bar.setMaxSize(width, height);
        bar.setStyle("-fx-background-color: " + color + ";");
        return bar;
    }

    private void pickTheme(Theme option) {
        AppNav.theme(option);
        for (Node card : themePane.getChildren()) {
            markSelected(card, card.getUserData() == option);
        }
        renderThemeTag();
        AppNav.success("앱 스타일을 '" + option.label() + "'(으)로 바꿨어요");
    }

    private static void markSelected(Node card, boolean selected) {
        card.getStyleClass().remove("selected");
        if (selected) {
            card.getStyleClass().add("selected");
        }
    }

    private void renderThemeTag() {
        themeTag.setText("지금 · " + AppNav.theme().label());
    }

    // ── 내비게이션 ──────────────────────────────────────────────

    /**
     * 정보 변경 창을 엽니다.
     *
     * <p>{@code AppNav.dialog} 는 여는 일을 한 박자 미루므로 이 메서드는 <b>창이 뜨기 전에</b>
     * 끝납니다. 그래서 여기서 곧바로 라벨을 다시 그려도 바뀐 닉네임이 반영되지 않았습니다 —
     * 저장된 순간 알려 달라고 걸어 둡니다 (위치 등록과 같은 방식).
     */
    @FXML
    private void handleEditInfo() {
        AppNav.dialog("정보 변경", "edit-info.fxml",
                (EditInfoController controller) -> controller.setOnSaved(
                        () -> userLabel.setText(Session.displayName("사용자") + "님")));
    }

    @FXML
    private void handleMain() {
        AppNav.show("SafeFood — 맞춤 맛집 추천", "main.fxml");
    }

    @FXML
    private void handleHistory() {
        AppNav.show("식사 기록 및 즐겨찾기", "history.fxml");
    }

    @FXML
    private void handleGroup() {
        AppNav.dialog("같이 먹기", "group-option.fxml");
    }

    @FXML
    private void handleLogout() {
        AppNav.logout();
    }
}
