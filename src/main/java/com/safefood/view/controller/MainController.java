package com.safefood.view.controller;

import com.safefood.dao.FavoriteDao;
import com.safefood.dao.HistoryDao;
import com.safefood.dto.PreferenceDto;
import com.safefood.dto.Session;
import com.safefood.service.HistoryService;
import com.safefood.service.RecommendService;
import com.safefood.view.AppNav;
import com.safefood.view.DemoData;
import com.safefood.view.Widgets;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 메인 추천 화면 (리디자인 1a & 실데이터 개인 맞춤 추천 엔진 연동).
 */
public class MainController {

    @FXML private ComboBox<String> categoryBox;
    @FXML private ComboBox<String> distanceBox;
    @FXML private ComboBox<String> budgetBox;
    @FXML private FlowPane moodPane;
    @FXML private VBox cardBox;
    @FXML private VBox blockedBox;
    @FXML private Label userLabel;
    @FXML private Label greetingLabel;
    @FXML private Label excludeLabel;
    @FXML private Hyperlink excludeToggle;

    private final List<ToggleButton> moodChips = new ArrayList<>();
    private final RecommendService recommendService = new RecommendService();
    private final HistoryDao historyDao = new HistoryDao();
    private final FavoriteDao favoriteDao = new FavoriteDao();

    // R-04 재추천 시 직전 노출 메뉴 누적 제외용 Set 및 순위 오프셋
    private final Set<Integer> excludedForReroll = new HashSet<>();
    private int currentRankOffset = 0;
    private List<RecommendService.RecommendResult> currentDisplayedSafeResults = new ArrayList<>();

    /** 어제/최근 먹은 메뉴를 빼고 볼지 */
    private boolean excludeEaten = true;

    @FXML
    private void initialize() {
        String name = Session.displayName("사용자");
        userLabel.setText(name + "님");
        greetingLabel.setText("오늘 뭐 먹지, " + name + "님?");

        categoryBox.getItems().add("전체");
        categoryBox.getItems().addAll(DemoData.CATEGORIES);
        categoryBox.setValue("전체");

        distanceBox.getItems().setAll("500m", "1km", "2km", "3km", "제한없음");
        distanceBox.setValue("1km");

        budgetBox.getItems().setAll(DemoData.BUDGETS);
        budgetBox.setValue("15,000원 이하");

        fillMoodChips();

        categoryBox.valueProperty().addListener((obs, oldVal, newVal) -> {
            excludedForReroll.clear();
            currentRankOffset = 0;
            fillCards();
        });
        budgetBox.valueProperty().addListener((obs, oldVal, newVal) -> {
            excludedForReroll.clear();
            currentRankOffset = 0;
            fillCards();
        });
        distanceBox.valueProperty().addListener((obs, oldVal, newVal) -> {
            excludedForReroll.clear();
            currentRankOffset = 0;
            fillCards();
        });

        renderExcludeNotice();
        fillCards();
    }

    private void fillMoodChips() {
        moodPane.getChildren().clear();
        moodChips.clear();
        for (String mood : List.of("든든한 한끼", "속풀이·해장", "가벼운 식사")) {
            ToggleButton chip = Widgets.compactChip(mood);
            chip.selectedProperty().addListener((obs, oldVal, newVal) -> {
                excludedForReroll.clear();
                currentRankOffset = 0;
                fillCards();
            });
            moodChips.add(chip);
            moodPane.getChildren().add(chip);
        }
    }

    private void renderExcludeNotice() {
        String lastEatenMenu = "이전에 먹은 메뉴";
        if (Session.isMember()) {
            List<DemoData.HistoryRow> eatenRows =
                    historyDao.getUserHistoryRows(Session.currentUserId(), "먹음");
            if (!eatenRows.isEmpty()) {
                lastEatenMenu = eatenRows.get(0).menu();
            }
        }

        excludeLabel.setText(excludeEaten
                ? "최근 드신 " + lastEatenMenu + "은(는) 자동으로 제외했어요 ·"
                : "최근 드신 " + lastEatenMenu + "도 후보에 포함했어요 ·");
        excludeToggle.setText(excludeEaten ? "포함하기" : "다시 제외하기");
    }

    @FXML
    private void handleToggleEaten() {
        excludeEaten = !excludeEaten;
        excludedForReroll.clear();
        currentRankOffset = 0;
        renderExcludeNotice();
        fillCards();
    }

    private int parseBudget(String budgetStr) {
        if (budgetStr == null || budgetStr.contains("전체") || budgetStr.contains("제한")) {
            return 0;
        }
        try {
            return Integer.parseInt(budgetStr.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 추천 목록을 가져옵니다.
     *
     * <p>실패는 <b>감추지 않습니다</b> — 예전에는 빈 목록을 돌려주는 바람에 화면이
     * "조건에 맞는 메뉴가 없음"으로 오해하고 데모 데이터를 그렸습니다.
     *
     * @throws RuntimeException 조회 실패 (호출부가 실패 화면을 그립니다)
     */
    private List<RecommendService.RecommendResult> fetchRecommendations(Set<Integer> excludedIds) {
        String selectedCategory = categoryBox.getValue();
        int budget = parseBudget(budgetBox.getValue());
        List<String> selectedMoods = Widgets.selected(moodChips);

        if (Session.isMember()) {
            return recommendService.getPersonalizedRecommendations(
                    Session.currentUserId(),
                    null,
                    selectedCategory,
                    selectedMoods,
                    5,
                    budget,
                    excludeEaten,
                    excludedIds,
                    true
            );
        }

        // 게스트 — 저장된 계정이 없으니 온보딩에서 받아 둔 취향만 씁니다
        PreferenceDto guestPref = Session.getGuestPreferences();
        int spicy = guestPref != null && guestPref.getSpicyLevel() > 0 ? guestPref.getSpicyLevel() : 5;
        int maxBudget = budget > 0 ? budget : (guestPref != null ? guestPref.getPriceMax() : 0);
        return recommendService.getPersonalizedRecommendations(
                0, List.of(), selectedCategory, selectedMoods, spicy, maxBudget, false, excludedIds, true);
    }

    // ── 카드 렌더링 (5개 고정 슬라이스 & 잔여 카드 유연 렌더링) ─────────────────

    private void fillCards() {
        cardBox.getChildren().clear();
        if (blockedBox != null) {
            blockedBox.getChildren().clear();
        }
        currentDisplayedSafeResults = new ArrayList<>();

        List<RecommendService.RecommendResult> rawResults;
        List<RecommendService.RecommendResult> safeResults;
        List<RecommendService.RecommendResult> blockedResults;
        try {
            rawResults = fetchRecommendations(excludedForReroll);
            safeResults = rawResults.stream().filter(r -> !r.isBlocked()).toList();
            blockedResults = rawResults.stream()
                    .filter(RecommendService.RecommendResult::isBlocked).toList();

            // 안전 메뉴가 모두 소진되었을 때 자동 리셋 및 1위부터 재순환
            if (safeResults.isEmpty() && !excludedForReroll.isEmpty()) {
                excludedForReroll.clear();
                currentRankOffset = 0;
                rawResults = fetchRecommendations(excludedForReroll);
                safeResults = rawResults.stream().filter(r -> !r.isBlocked()).toList();
                blockedResults = rawResults.stream()
                        .filter(RecommendService.RecommendResult::isBlocked).toList();
                AppNav.info("모든 추천 후보를 다 확인하셨습니다.\n처음 순위(1위~5위)부터 다시 보여드립니다!");
            }
        } catch (RuntimeException e) {
            // 알레르기를 확인하지 못한 채 그럴듯한 카드를 그리느니 실패를 그대로 알립니다
            System.err.println("[MainController] 추천 조회 실패: " + e.getMessage());
            cardBox.getChildren().add(notice("추천을 불러오지 못했어요",
                    "데이터베이스 연결을 확인해 주세요. "
                            + "알레르기를 확인하지 못한 메뉴는 추천으로 보여 드리지 않습니다."));
            return;
        }

        // 최대 5개까지만 슬라이스 (남은 카드가 5개 미만이면 남은 수만큼만 표시)
        int displayCount = Math.min(5, safeResults.size());
        currentDisplayedSafeResults = new ArrayList<>(safeResults.subList(0, displayCount));

        if (currentDisplayedSafeResults.isEmpty()) {
            cardBox.getChildren().add(notice("조건에 맞는 메뉴가 없어요",
                    "카테고리·예산·기분 태그를 넓혀 보시거나, 등록된 메뉴 데이터가 있는지 확인해 주세요."));
        } else {
            int rank = currentRankOffset + 1;
            for (RecommendService.RecommendResult r : currentDisplayedSafeResults) {
                cardBox.getChildren().add(card(r.toRecommendation(rank++), r.getMenuId(), r.getRestaurantId()));
            }
        }

        if (!blockedResults.isEmpty() && blockedBox != null) {
            blockedBox.getChildren().add(blockedRow(blockedResults.get(0).toRecommendation(0)));
        }
    }

    /**
     * 카드 자리에 들어가는 안내 — 비었거나 실패했을 때 씁니다.
     *
     * <p>예전에는 이 자리에 {@code DemoData.RECOMMENDATIONS}(김치찌개·돈코츠 라멘·감바스)를
     * 그렸습니다. 그 세 건은 <b>회원의 알레르기를 전혀 반영하지 않은</b> 예시라, 빈 화면보다
     * 위험했습니다.
     */
    private VBox notice(String title, String detail) {
        Label heading = Widgets.label(title, "section-title");
        Label body = Widgets.sub(detail);
        body.setWrapText(true);

        VBox box = new VBox(6, heading, body);
        box.getStyleClass().addAll("card", "soft");
        box.setPadding(new Insets(20, 22, 20, 22));
        return box;
    }

    private HBox card(DemoData.Recommendation item, int menuId, int restId) {
        // 왼쪽 — 원형 순위 + 점수
        Label score = Widgets.micro(item.score() + "점");
        VBox rankBox = new VBox(4, Widgets.rankCircle(item.rank()), score);
        rankBox.setAlignment(Pos.CENTER);
        rankBox.setMinWidth(56);
        rankBox.setPrefWidth(56);

        // 제목 줄 — 메뉴 · 가게·거리·평점 · 안전 · 맵기
        Label menu = new Label(item.menu());
        menu.getStyleClass().add("menu-name");

        Label meta = new Label(item.restaurant() + " · " + item.distance()
                + " · ★ " + item.rating());
        meta.getStyleClass().add("restaurant-name");

        HBox titleRow = new HBox(10, menu, meta, Widgets.hSpacer(),
                Widgets.safetyBadge(item.safety()),
                Widgets.tag("맵기 " + item.spicyLevel() + "단계", "neutral"));
        titleRow.setAlignment(Pos.CENTER_LEFT);

        Label reason = new Label(item.reason());
        reason.setWrapText(true);

        // 액션 — 버튼 2개 + 찜 아이콘
        Button eat = new Button("이걸로 먹을래요");
        eat.getStyleClass().add("primary");
        eat.setOnAction(event -> {
            if (!Session.isMember()) {
                AppNav.info("게스트는 식사 기록을 남길 수 없어요 — 회원으로 로그인하면 저장됩니다.");
                return;
            }
            if (menuId <= 0) {
                AppNav.warn("이 메뉴는 기록할 수 없어요.");
                return;
            }
            if (!new HistoryService().saveEatenHistory(Session.currentUserId(), menuId)) {
                AppNav.error("식사 기록을 저장하지 못했습니다. 데이터베이스 연결을 확인해 주세요.");
                return;
            }
            AppNav.success(item.menu() + "(으)로 정했어요 — 기록에 남기고 다음 추천에서 제외합니다");
            renderExcludeNotice();
            fillCards();
        });

        Button map = new Button("가게·지도 보기");
        map.getStyleClass().add("ghost");
        map.setOnAction(event -> AppNav.dialog(
                item.menu() + " — 맛집 상세", "restaurant-detail.fxml",
                (RestaurantDetailController controller) -> controller.setItem(item)));

        boolean isFav = Session.isMember() && menuId > 0
                && favoriteDao.isFavorite(Session.currentUserId(), menuId);
        ToggleButton favorite = new ToggleButton(isFav ? "♥" : "♡");
        favorite.setSelected(isFav);
        favorite.getStyleClass().addAll("icon");
        favorite.setOnAction(event -> {
            if (!Session.isMember() || menuId <= 0) {
                favorite.setSelected(false);
                favorite.setText("♡");
                AppNav.info("게스트는 찜을 저장할 수 없어요 — 회원으로 로그인하면 남길 수 있습니다.");
                return;
            }
            boolean wantFavorite = favorite.isSelected();
            boolean ok = wantFavorite
                    ? favoriteDao.addFavorite(Session.currentUserId(), restId, menuId)
                    : favoriteDao.removeFavorite(Session.currentUserId(), menuId);
            if (!ok) {
                // 저장하지 못했으면 하트도 되돌립니다 — 저장된 것처럼 보이면 안 됩니다
                favorite.setSelected(!wantFavorite);
                favorite.setText(!wantFavorite ? "♥" : "♡");
                AppNav.error("찜을 저장하지 못했습니다. 데이터베이스 연결을 확인해 주세요.");
                return;
            }
            favorite.setText(wantFavorite ? "♥" : "♡");
            if (wantFavorite) {
                AppNav.success(item.menu() + "을(를) 찜 목록에 추가했습니다.");
            } else {
                AppNav.info(item.menu() + "을(를) 찜 목록에서 제외했습니다.");
            }
        });

        HBox actions = new HBox(8, eat, map, Widgets.hSpacer(), favorite);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox body = new VBox(7, titleRow, reason, actions);
        HBox.setHgrow(body, Priority.ALWAYS);

        HBox card = new HBox(20, rankBox, body);
        card.setAlignment(Pos.CENTER_LEFT);
        card.getStyleClass().addAll("card", "soft", "elev");
        return card;
    }

    /** 차단된 메뉴 — 왜 빠졌는지와 대체 메뉴만 한 줄로 보여 줍니다. */
    private HBox blockedRow(DemoData.Recommendation item) {
        Label struck = new Label(item.menu() + " · " + item.restaurant());
        struck.getStyleClass().add("strike");

        Hyperlink why = new Hyperlink("왜 빠졌나요?");
        why.setOnAction(event -> AppNav.info(item.reason()));

        HBox row = new HBox(12,
                Widgets.tag(item.safety().label, "danger"),
                struck,
                Widgets.sub(item.alternative() == null
                        ? "" : "대체 — " + item.alternative()),
                Widgets.hSpacer(),
                why);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("blocked-row");
        return row;
    }

    // ── 내비게이션 & 액션 ────────────────────────────────────────

    @FXML
    private void handleProfile() {
        AppNav.show("프로필 및 설정 관리", "profile.fxml");
    }

    @FXML
    private void handleHistory() {
        AppNav.show("식사 기록 및 즐겨찾기", "history.fxml");
    }

    @FXML
    private void handleLogout() {
        AppNav.logout();
    }

    // R-04 재추천 ("다른 추천 보기" 선택 시 직전 5개 결과를 제외하고 차순위 5개 로딩)
    @FXML
    private void handleReroll() {
        if (!currentDisplayedSafeResults.isEmpty()) {
            for (RecommendService.RecommendResult r : currentDisplayedSafeResults) {
                excludedForReroll.add(r.getMenuId());
            }
            currentRankOffset += currentDisplayedSafeResults.size();
        }
        fillCards();
    }

    // R-08 룰렛 추천 (전체 안전 후보 중 무작위 1개 추첨)
    @FXML
    private void handleRoulette() {
        List<RecommendService.RecommendResult> allSafeCandidates;
        try {
            allSafeCandidates = fetchRecommendations(null).stream()
                    .filter(r -> !r.isBlocked())
                    .toList();
        } catch (RuntimeException e) {
            System.err.println("[MainController] 룰렛 조회 실패: " + e.getMessage());
            AppNav.error("메뉴를 불러오지 못해 룰렛을 돌릴 수 없습니다. 데이터베이스 연결을 확인해 주세요.");
            return;
        }

        if (allSafeCandidates.isEmpty()) {
            AppNav.warn("룰렛을 돌릴 수 있는 안전한 추천 메뉴가 없습니다.");
            return;
        }

        RecommendService.RecommendResult pick = recommendService.pickRouletteMenu(allSafeCandidates, 0);
        if (pick == null) {
            AppNav.warn("안전하게 추천 가능한 메뉴가 없습니다.");
            return;
        }

        String priceStr = pick.getPrice() > 0 ? String.format("%,d원", pick.getPrice()) : "가격 정보 없음";
        AppNav.info("🎰 [오늘의 룰렛 당첨 메뉴!]\n\n"
                + "🍽 메뉴명: " + pick.getMenuName() + " (" + pick.getCategory() + ")\n"
                + "🏪 식당명: " + pick.getRestaurantName() + " (★ " + pick.getRating() + ")\n"
                + "💰 가격: " + priceStr + " | 맵기: " + pick.getSpicyLevel() + "단계\n\n"
                + "💡 추천 사유:\n" + pick.getReason() + "\n\n"
                + "오늘 식사는 이 메뉴로 결정해 보시는 건 어떨까요?");
    }

    @FXML
    private void handleGroup() {
        AppNav.dialog("같이 먹기 옵션 선택", "group-option.fxml");
    }
}
