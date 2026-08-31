package com.safefood.view.controller;

import com.safefood.dao.CategoryCatalog;
import com.safefood.dao.FavoriteDao;
import com.safefood.dao.HistoryDao;
import com.safefood.dto.HistoryRow;
import com.safefood.dto.LocationDto;
import com.safefood.dto.PreferenceDto;
import com.safefood.dto.RecommendationCard;
import com.safefood.dto.Session;
import com.safefood.dto.UserDto;
import com.safefood.service.AuthService;
import com.safefood.service.HistoryService;
import com.safefood.service.ProfileService;
import com.safefood.service.RecommendService;
import com.safefood.view.AppNav;
import com.safefood.view.AsyncLoader;
import com.safefood.view.UiConstants;
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
    /**
     * 정렬만 {@code ComboBox<String>} 이 아닙니다 — 다른 콤보는 "15,000원 이하" 같은 글자에서
     * 숫자를 도로 뽑아 쓰지만, 정렬은 고른 값을 그대로 서비스에 넘길 수 있어 글자로 되돌릴
     * 이유가 없습니다. 콤보에 보이는 글자는 {@code SortOrder.toString()} 입니다.
     */
    @FXML private ComboBox<RecommendService.SortOrder> sortBox;
    @FXML private FlowPane moodPane;
    @FXML private VBox cardBox;
    @FXML private VBox blockedBox;
    @FXML private NavController navController;
    @FXML private Label greetingLabel;
    @FXML private Label excludeLabel;
    @FXML private Hyperlink excludeToggle;
    @FXML private Label distanceHint;
    @FXML private Hyperlink distanceHintLink;

    private final List<ToggleButton> moodChips = new ArrayList<>();
    private final RecommendService recommendService = new RecommendService();
    private final HistoryDao historyDao = new HistoryDao();
    private final FavoriteDao favoriteDao = new FavoriteDao();
    private final AuthService authService = new AuthService();
    private final AsyncLoader loader = new AsyncLoader();

    // R-04 재추천 시 직전 노출 메뉴 누적 제외용 Set 및 순위 오프셋
    private final Set<Integer> excludedForReroll = new HashSet<>();
    private int currentRankOffset = 0;
    private List<RecommendService.RecommendResult> currentDisplayedSafeResults = new ArrayList<>();

    /** 어제/최근 먹은 메뉴를 빼고 볼지 */
    private boolean excludeEaten = true;

    /** 내정보의 음식 취향 — 화면이 열릴 때 읽어 옵션 초기값과 조회 조건에 씁니다. 없으면 null. */
    private PreferenceDto myPreference;

    /** 내정보의 선호 카테고리 — 단일 선택 콤보에 못 담으므로 추천 가산점(+20점)으로 반영합니다. */
    private List<String> myCategories = List.of();

    /** 취향을 옵션에 옮겨 담는 동안에는 리스너가 조회를 띄우지 않게 막습니다. */
    private boolean loading = true;



    /**
     * 백그라운드에 넘길 조회 조건 한 벌.
     *
     * <p>콤보·칩은 FX 스레드에서만 읽을 수 있으므로, 조회를 띄우기 <b>전에</b> 값을 여기 떠 둡니다.
     */
    private record Query(boolean member, int userId, String sessionToken, String category,
                         int budget, int spicy, int maxDistanceMeters,
                         List<String> preferredCategories,
                         List<String> moods, boolean excludeEaten, Set<Integer> excluded,
                         RecommendService.SortOrder sortOrder) {
    }

    /** 내정보에서 읽어 온 취향 한 벌. */
    private record Preferences(PreferenceDto preference, List<String> categories) {
    }

    /** 백그라운드가 만들어 온 화면 한 판. 여기까지는 화면 요소가 하나도 없습니다. */
    private record Loaded(List<RecommendService.RecommendResult> safe,
                          List<RecommendService.RecommendResult> blocked,
                          Set<Integer> favorites,
                          String lastEatenMenu,
                          boolean recycled,
                          boolean failed,
                          boolean evicted,
                          /** 거리 상한을 골랐는데 잴 기준(내 위치)이 없어 그 조건만 빠졌는지. */
                          boolean distanceIgnored,
                          /**
                           * 카드에 적을 거리를 재는 기준점. 백그라운드에서 <b>한 번만</b> 읽어
                           * 여기 담아 둡니다 — {@link #apply} 가 카드마다 DB 를 치지 않도록.
                           */
                          LocationDto myLocation) {
    }

    @FXML
    private void initialize() {
        navController.setActive(NavController.Tab.MAIN);
        String name = Session.displayName("사용자");
        greetingLabel.setText("오늘 뭐 먹지, " + name + "님?");

        categoryBox.getItems().add("전체");
        categoryBox.getItems().addAll(CategoryCatalog.names());
        categoryBox.setValue("전체");

        distanceBox.getItems().setAll("500m", "1km", "2km", "3km", "제한없음");
        distanceBox.setValue("1km");

        budgetBox.getItems().setAll(UiConstants.BUDGETS);
        budgetBox.setValue("15,000원 이하");

        sortBox.getItems().setAll(RecommendService.SortOrder.values());
        sortBox.setValue(RecommendService.SortOrder.RECOMMENDED);

        fillMoodChips();

        categoryBox.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (loading) {
                return;
            }
            excludedForReroll.clear();
            currentRankOffset = 0;
            fillCards();
        });
        budgetBox.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (loading) {
                return;
            }
            excludedForReroll.clear();
            currentRankOffset = 0;
            fillCards();
        });
        sortBox.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (loading) {
                return;
            }
            // 다시 뽑기로 치워 둔 메뉴도 함께 되돌립니다 — 그대로 두면 '가격 낮은 순'을 골라도
            // 정작 제일 싼 메뉴가 빠진 채 줄이 서서, 맨 위 카드가 최저가가 아니게 됩니다.
            excludedForReroll.clear();
            currentRankOffset = 0;
            fillCards();
        });
        distanceBox.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (loading) {
                return;
            }
            excludedForReroll.clear();
            currentRankOffset = 0;
            fillCards();
        });

        renderExcludeNotice(null);
        renderDistanceHint(false);   // 조회가 끝나기 전에는 알 수 없으니 숨겨 둡니다
        cardBox.getChildren().add(notice("추천을 고르는 중이에요",
                "알레르기와 조건을 맞춰 보고 있습니다. 잠시만 기다려 주세요."));
        loadPreferencesThenFill();
    }

    /**
     * 내정보의 음식 취향을 옵션에 옮겨 담은 뒤 첫 추천을 띄웁니다 — 화면이 열릴 때 한 번.
     *
     * <p>회원 취향은 DB 에 있어 {@link AsyncLoader} 로 다녀오고, 게스트는 온보딩에서
     * 받아 둔 값이 세션에 남아 있어 바로 적용합니다. 적용이 끝나기 전에는 {@link #loading}
     * 이 리스너를 막고 있어, 값을 채우는 것이 '변경'으로 잡혀 조회가 겹치는 일은 없습니다.
     */
    private void loadPreferencesThenFill() {
        if (!Session.isMember()) {
            applyPreferences(Session.getGuestPreferences(), List.of());
            return;
        }
        int userId = Session.currentUserId();
        loader.submit(() -> {
                    ProfileService profileService = new ProfileService();
                    return new Preferences(profileService.getMyPreference(userId),
                            profileService.getMyCategories(userId));
                },
                p -> applyPreferences(p.preference(), p.categories()),
                error -> applyPreferences(null, List.of()));
    }

    /**
     * 읽어 온 취향을 옵션 초기값으로 반영합니다. <b>FX 스레드 전용</b>.
     *
     * <p>취향이 없으면(아직 설정 전이거나 못 읽음) 기본값 그대로 시작합니다.
     */
    private void applyPreferences(PreferenceDto pref, List<String> categories) {
        myPreference = pref;
        myCategories = categories == null ? List.of() : List.copyOf(categories);
        if (pref != null) {
            budgetBox.setValue(budgetLabelFor(pref.getPriceMax()));
            distanceBox.setValue(distanceLabelFor(pref.getMaxDistance()));
        }
        loading = false;
        fillCards();
    }

    /** 취향의 예산 상한을 넘지 않는 가장 넓은 예산 옵션. 0(제한 안 함)이면 제한없음. */
    private String budgetLabelFor(int priceMax) {
        if (priceMax <= 0) {
            return "제한없음";
        }
        String widest = null;
        for (String option : UiConstants.BUDGETS) {
            int value = parseBudget(option);
            if (value > 0 && value <= priceMax) {
                widest = option;   // 목록이 오름차순이라 마지막에 걸린 것이 가장 넓습니다
            }
        }
        // 취향이 가장 작은 옵션보다도 낮으면 그 옵션으로 — 넘는 쪽보다 좁은 쪽이 안전합니다
        return widest == null ? UiConstants.BUDGETS.get(0) : widest;
    }

    /** 거리 옵션 라벨을 미터로. {@link #distanceLabelFor} 의 반대이고, 눈금이 같아야 합니다. */
    private static int parseDistance(String label) {
        return switch (label == null ? "" : label) {
            case "500m" -> 500;
            case "1km" -> 1000;
            case "2km" -> 2000;
            case "3km" -> 3000;
            default -> 0;   // "제한없음" 과 알 수 없는 값
        };
    }

    /** 취향의 최대 거리를 거리 옵션 라벨로 — 프로필 화면과 같은 눈금입니다. */
    private static String distanceLabelFor(int meters) {
        return switch (meters) {
            case 500 -> "500m";
            case 1000 -> "1km";
            case 2000 -> "2km";
            case 3000 -> "3km";
            default -> "제한없음";
        };
    }

    private void fillMoodChips() {
        moodPane.getChildren().clear();
        moodChips.clear();
        for (String mood : List.of("든든한 한끼", "속풀이·해장", "가벼운 식사")) {
            ToggleButton chip = Widgets.compactChip(mood);
            chip.selectedProperty().addListener((obs, oldVal, newVal) -> {
                if (loading) {
                    return;
                }
                excludedForReroll.clear();
                currentRankOffset = 0;
                fillCards();
            });
            moodChips.add(chip);
            moodPane.getChildren().add(chip);
        }
    }

    /** 안내 문구 갱신. FX 스레드 전용이라 DB 를 만지지 않습니다 — 메뉴 이름은 load()가 넘겨 줍니다. */
    private void renderExcludeNotice(String lastEatenMenu) {
        String name = lastEatenMenu == null ? "이전에 먹은 메뉴" : lastEatenMenu;
        excludeLabel.setText(excludeEaten
                ? "최근 드신 " + name + "은(는) 자동으로 제외했어요 ·"
                : "최근 드신 " + name + "도 후보에 포함했어요 ·");
        excludeToggle.setText(excludeEaten ? "포함하기" : "다시 제외하기");
    }

    /**
     * 거리 제한을 골랐는데 <b>잴 기준이 없을 때</b>만 알립니다.
     *
     * <p>이 안내가 없으면 사용자는 "500m 이내"를 골라 놓고 3km 짜리 가게를 보게 되는데,
     * 화면에는 아무 설명이 없어 고장으로 읽힙니다. 조건이 무시된 이유를 그 자리에서 말해 줍니다.
     */
    private void renderDistanceHint(boolean cannotApply) {
        distanceHint.setText(cannotApply ? "· 내 위치가 없어 거리 조건은 빼고 골랐어요 ·" : "");
        distanceHint.setVisible(cannotApply);
        distanceHint.setManaged(cannotApply);
        distanceHintLink.setVisible(cannotApply);
        distanceHintLink.setManaged(cannotApply);
    }

    /** 안내에서 바로 위치를 등록하고, 닫히면 그 조건으로 다시 뽑습니다. */
    @FXML
    private void handleRegisterLocation() {
        AppNav.dialog("위치 등록", "location.fxml",
                (LocationController controller) -> controller.setOnSaved(this::fillCards));
    }

    @FXML
    private void handleToggleEaten() {
        excludeEaten = !excludeEaten;
        excludedForReroll.clear();
        currentRankOffset = 0;
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

    /** 지금 화면의 조회 조건을 한 벌 떠 둡니다. <b>FX 스레드에서만</b> 부릅니다. */
    private Query snapshot(Set<Integer> excluded) {
        String selectedCategory = categoryBox.getValue();
        int budget = parseBudget(budgetBox.getValue());
        int maxDistance = parseDistance(distanceBox.getValue());
        List<String> selectedMoods = List.copyOf(Widgets.selected(moodChips));
        Set<Integer> frozen = Set.copyOf(excluded);
        RecommendService.SortOrder sortOrder = sortBox.getValue() != null
                ? sortBox.getValue() : RecommendService.SortOrder.RECOMMENDED;

        if (Session.isMember()) {
            UserDto me = Session.getCurrentUser();
            // 맵기 상한은 내정보 취향의 값 — 아직 설정 전이면 전체(5단계)를 봅니다
            int spicy = myPreference != null && myPreference.getSpicyLevel() > 0
                    ? myPreference.getSpicyLevel() : 5;
            return new Query(true, Session.currentUserId(),
                    me == null ? null : me.getSessionToken(),
                    selectedCategory, budget, spicy, maxDistance, myCategories, selectedMoods,
                    excludeEaten, frozen, sortOrder);
        }

        // 게스트 — 저장된 계정이 없으니 온보딩에서 받아 둔 취향만 씁니다
        PreferenceDto guestPref = Session.getGuestPreferences();
        int spicy = guestPref != null && guestPref.getSpicyLevel() > 0 ? guestPref.getSpicyLevel() : 5;
        int maxBudget = budget > 0 ? budget : (guestPref != null ? guestPref.getPriceMax() : 0);
        return new Query(false, 0, null, selectedCategory, maxBudget, spicy, maxDistance, List.of(),
                selectedMoods, false, frozen, sortOrder);
    }

    /**
     * 추천 목록을 가져옵니다. <b>백그라운드 스레드 전용</b> — 화면 요소는 건드리지 않습니다.
     *
     * <p>실패는 <b>감추지 않습니다</b> — 예전에는 빈 목록을 돌려주는 바람에 화면이
     * "조건에 맞는 메뉴가 없음"으로 오해하고 데모 데이터를 그렸습니다.
     *
     * @throws RuntimeException 조회 실패 ({@link #load} 가 실패 화면으로 옮깁니다)
     */
    private List<RecommendService.RecommendResult> fetch(Query query, Set<Integer> excludedIds) {
        return recommendService.getPersonalizedRecommendations(
                query.userId(),
                query.preferredCategories(),
                query.category(),
                query.moods(),
                query.spicy(),
                query.budget(),
                query.maxDistanceMeters(),
                query.excludeEaten(),
                excludedIds,
                true,
                query.sortOrder());
    }

    /**
     * 화면 한 판을 통째로 만들어 옵니다. <b>백그라운드 스레드 전용</b>.
     *
     * <p>추천·찜 목록·안내 문구를 <b>한 번의 왕복 묶음</b>으로 처리합니다 — 예전에는
     * 안내 문구와 카드별 찜 여부를 따로 다녀오느라 커넥션을 여섯 개 더 열었습니다.
     */
    private Loaded load(Query query) {
        // 밀려난 세션이면 나머지 조회는 할 필요가 없습니다 — 커넥션 하나로 끝냅니다
        if (query.member() && !authService.isSessionCurrent(query.userId(), query.sessionToken())) {
            return new Loaded(List.of(), List.of(), Set.of(), null, false, false, true, false, null);
        }
        try {
            // 여기(백그라운드)에서 봐야 합니다 — 회원이 프로필을 아직 안 들렀으면 세션에 위치
            // 사본이 없어, 화면에서 판단하면 있는 위치를 없다고 잘못 알립니다.
            // 한 번만 읽어 안내 문구와 카드 거리 표시가 같은 값을 보게 합니다.
            LocationDto myLocation = RecommendService.myLocation();
            boolean distanceIgnored = query.maxDistanceMeters() > 0
                    && !RecommendService.canFilterByDistance(myLocation);

            List<RecommendService.RecommendResult> raw = fetch(query, query.excluded());
            boolean recycled = false;

            // 안전 메뉴가 모두 소진되었을 때 자동 리셋 및 1위부터 재순환
            if (raw.stream().noneMatch(r -> !r.isBlocked()) && !query.excluded().isEmpty()) {
                raw = fetch(query, Set.of());
                recycled = true;
            }

            Set<Integer> favorites = query.member()
                    ? favoriteDao.getFavoriteMenuIds(query.userId())
                    : Set.of();

            return new Loaded(
                    raw.stream().filter(r -> !r.isBlocked()).toList(),
                    raw.stream().filter(RecommendService.RecommendResult::isBlocked).toList(),
                    favorites,
                    lastEatenMenu(query),
                    recycled,
                    false,
                    false,
                    distanceIgnored,
                    myLocation);
        } catch (RuntimeException e) {
            // 알레르기를 확인하지 못한 채 그럴듯한 카드를 그리느니 실패를 그대로 알립니다
            System.err.println("[MainController] 추천 조회 실패: " + e.getMessage());
            return new Loaded(List.of(), List.of(), Set.of(), null, false, true, false, false, null);
        }
    }

    /** 안내 문구에 쓸 '최근 드신' 메뉴 이름. 문구일 뿐이라 못 읽어도 추천은 그대로 보여 줍니다. */
    private String lastEatenMenu(Query query) {
        if (!query.member()) {
            return null;
        }
        try {
            List<HistoryRow> eatenRows = historyDao.getUserHistoryRows(query.userId(), "먹음");
            return eatenRows.isEmpty() ? null : eatenRows.get(0).menu();
        } catch (RuntimeException e) {
            System.err.println("[MainController] 최근 식사 기록을 읽지 못했습니다: " + e.getMessage());
            return null;
        }
    }

    // ── 카드 렌더링 (5개 고정 슬라이스 & 잔여 카드 유연 렌더링) ─────────────────

    /**
     * 추천을 새로 받아 카드를 다시 그립니다.
     *
     * <p>조회는 {@link AsyncLoader} 에서 돌고 그리기만 FX 스레드로 돌아옵니다 — 예전에는
     * 이 메서드가 통째로 화면 스레드에서 돌아, 필터를 건드릴 때마다 조회가 끝날 때까지
     * 창이 멎었습니다.
     */
    private void fillCards() {
        Query query = snapshot(excludedForReroll);
        cardBox.getChildren().clear();
        if (blockedBox != null) {
            blockedBox.getChildren().clear();
        }
        currentDisplayedSafeResults = new ArrayList<>();
        cardBox.getChildren().add(notice("추천을 고르는 중이에요",
                "알레르기와 조건을 맞춰 보고 있습니다. 잠시만 기다려 주세요."));

        // 기다리는 사이 조건이 또 바뀌면 늦게 온 결과는 AsyncLoader 가 버립니다
        loader.load(() -> load(query), this::apply);
    }

    /** 받아 온 한 판을 화면에 옮깁니다. <b>FX 스레드 전용</b> — 여기서는 DB 를 만지지 않습니다. */
    private void apply(Loaded loaded) {
        cardBox.getChildren().clear();
        if (blockedBox != null) {
            blockedBox.getChildren().clear();
        }
        currentDisplayedSafeResults = new ArrayList<>();

        if (loaded.evicted()) {
            // 카드를 그리지 않고 바로 나갑니다 — 남의 세션으로 화면을 채우면 안 됩니다
            AppNav.logout();
            AppNav.info("다른 기기에서 로그인하여 로그아웃되었습니다.");
            return;
        }

        renderExcludeNotice(loaded.lastEatenMenu());
        renderDistanceHint(loaded.distanceIgnored());

        if (loaded.failed()) {
            cardBox.getChildren().add(notice("추천을 불러오지 못했어요",
                    "데이터베이스 연결을 확인해 주세요. "
                            + "알레르기를 확인하지 못한 메뉴는 추천으로 보여 드리지 않습니다."));
            return;
        }

        // 안전 메뉴가 모두 소진되어 1위부터 재순환한 경우
        if (loaded.recycled()) {
            excludedForReroll.clear();
            currentRankOffset = 0;
            AppNav.info("모든 추천 후보를 다 확인하셨습니다.\n처음 순위(1위~5위)부터 다시 보여드립니다!");
        }

        // 최대 5개까지만 슬라이스 (남은 카드가 5개 미만이면 남은 수만큼만 표시)
        int displayCount = Math.min(5, loaded.safe().size());
        currentDisplayedSafeResults = new ArrayList<>(loaded.safe().subList(0, displayCount));

        if (currentDisplayedSafeResults.isEmpty()) {
            // 거리를 먼저 짚습니다 — 상한이 좁으면 다른 조건이 아무리 넉넉해도 후보가 통째로 빕니다
            cardBox.getChildren().add(notice("조건에 맞는 메뉴가 없어요",
                    "거리·카테고리·예산·기분 태그를 넓혀 보시거나, 등록된 메뉴 데이터가 있는지 확인해 주세요."));
        } else {
            int rank = currentRankOffset + 1;
            for (RecommendService.RecommendResult r : currentDisplayedSafeResults) {
                cardBox.getChildren().add(card(r.toRecommendation(rank++, loaded.myLocation()),
                        r.getMenuId(),
                        r.getRestaurantId(), loaded.favorites().contains(r.getMenuId())));
            }
        }

        if (!loaded.blocked().isEmpty() && blockedBox != null) {
            blockedBox.getChildren().add(
                    blockedRow(loaded.blocked().get(0).toRecommendation(0, loaded.myLocation())));
        }
    }

    /**
     * 카드 자리에 들어가는 안내 — 비었거나 실패했을 때 씁니다.
     *
     * <p>예전에는 이 자리에 더미 예시 데이터를 그렸습니다.
     * 회원의 알레르기를 전혀 반영하지 않은 예시라, 빈 화면보다 위험했습니다.
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

    private HBox card(RecommendationCard item, int menuId, int restId, boolean isFav) {
        // 왼쪽 — 원형 순위 + 점수
        Label score = Widgets.micro(item.score() + "점");
        VBox rankBox = new VBox(4, Widgets.rankCircle(item.rank()), score);
        rankBox.setAlignment(Pos.CENTER);
        rankBox.setMinWidth(56);
        rankBox.setPrefWidth(56);

        // 제목 줄 — 메뉴 · 가게·가격·거리·평점 · 안전 · 맵기
        Label menu = new Label(item.menu());
        menu.getStyleClass().add("menu-name");

        // 가격을 가게 바로 뒤에 둡니다 — 카드끼리 견주는 값이라 눈이 같은 자리에서 훑게
        Label meta = new Label(item.restaurant() + " · " + item.priceText()
                + " · " + item.distance() + " · ★ " + item.rating());
        meta.getStyleClass().add("restaurant-name");

        HBox titleRow = new HBox(10, menu, meta, Widgets.hSpacer(),
                Widgets.safetyBadge(item.safety()),
                Widgets.tag("맵기 " + item.spicyLevel() + "단계", Widgets.Variant.NEUTRAL));
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
            // 저장도 왕복이라 화면 스레드에서 하면 누르는 순간 창이 멎습니다
            eat.setDisable(true);
            int userId = Session.currentUserId();
            loader.submit(() -> new HistoryService().saveEatenHistory(userId, menuId),
                    saved -> {
                        eat.setDisable(false);
                        if (!saved) {
                            AppNav.error("식사 기록을 저장하지 못했습니다. 데이터베이스 연결을 확인해 주세요.");
                            return;
                        }
                        AppNav.success(item.menu() + "(으)로 정했어요 — 기록에 남기고 다음 추천에서 제외합니다");
                        fillCards();   // 안내 문구도 여기서 같이 새로 받아 옵니다
                    },
                    error -> {
                        eat.setDisable(false);
                        AppNav.error("식사 기록을 저장하지 못했습니다: " + error.getMessage());
                    });
        });

        // 찜 여부는 카드마다 묻지 않고 load() 가 한 번에 받아 온 것을 씁니다
        // (상세 창에도 이 버튼을 넘겨야 해서 '가게·지도 보기'보다 먼저 만듭니다)
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
            favorite.setText(wantFavorite ? "♥" : "♡");
            favorite.setDisable(true);
            int userId = Session.currentUserId();
            loader.submit(() -> wantFavorite
                            ? favoriteDao.addFavorite(userId, restId, menuId)
                            : favoriteDao.removeFavorite(userId, menuId),
                    ok -> {
                        favorite.setDisable(false);
                        if (!ok) {
                            // 저장하지 못했으면 하트도 되돌립니다 — 저장된 것처럼 보이면 안 됩니다
                            favorite.setSelected(!wantFavorite);
                            favorite.setText(!wantFavorite ? "♥" : "♡");
                            AppNav.error("찜을 저장하지 못했습니다. 데이터베이스 연결을 확인해 주세요.");
                            return;
                        }
                        if (wantFavorite) {
                            AppNav.success(item.menu() + "을(를) 찜 목록에 추가했습니다.");
                        } else {
                            AppNav.info(item.menu() + "을(를) 찜 목록에서 제외했습니다.");
                        }
                    },
                    error -> {
                        favorite.setDisable(false);
                        favorite.setSelected(!wantFavorite);
                        favorite.setText(!wantFavorite ? "♥" : "♡");
                        AppNav.error("찜을 저장하지 못했습니다: " + error.getMessage());
                    });
        });

        Button map = new Button("가게·지도 보기");
        map.getStyleClass().add("ghost");
        map.setOnAction(event -> AppNav.dialog(
                item.menu() + " — 맛집 상세", "restaurant-detail.fxml",
                (RestaurantDetailController controller) -> {
                    controller.setItem(item, menuId, restId);
                    // 상세 창의 찜 버튼도 같은 행을 만집니다 — 거기서 바꾸면 카드 하트도 따라갑니다
                    controller.bindFavorite(menuId, restId, favorite.isSelected(), state -> {
                        favorite.setSelected(state);
                        favorite.setText(state ? "♥" : "♡");
                    });
                    // 상세 창에서 '이걸로 먹을래요'를 누르면 메인 화면 새로고침
                    controller.setOnEatAction(this::fillCards);
                }));

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
    private HBox blockedRow(RecommendationCard item) {
        Label struck = new Label(item.menu() + " · " + item.restaurant());
        struck.getStyleClass().add("strike");

        Hyperlink why = new Hyperlink("왜 빠졌나요?");
        why.setOnAction(event -> AppNav.info(item.reason()));

        HBox row = new HBox(12,
                Widgets.tag(item.safety().label, Widgets.Variant.DANGER),
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
        // 룰렛은 재추천 제외를 무시하고 전체 안전 후보에서 뽑습니다
        Query query = snapshot(Set.of());

        loader.submit(() -> fetch(query, Set.of()).stream()
                        .filter(r -> !r.isBlocked())
                        .toList(),
                this::announceRoulette,
                error -> {
                    System.err.println("[MainController] 룰렛 조회 실패: " + error.getMessage());
                    AppNav.error("메뉴를 불러오지 못해 룰렛을 돌릴 수 없습니다. 데이터베이스 연결을 확인해 주세요.");
                });
    }

    /** 뽑기와 안내는 FX 스레드에서. */
    private void announceRoulette(List<RecommendService.RecommendResult> allSafeCandidates) {
        if (allSafeCandidates.isEmpty()) {
            AppNav.warn("룰렛을 돌릴 수 있는 안전한 추천 메뉴가 없습니다.");
            return;
        }

        RecommendService.RecommendResult pick = recommendService.pickRouletteMenu(allSafeCandidates, 0);
        if (pick == null) {
            AppNav.warn("안전하게 추천 가능한 메뉴가 없습니다.");
            return;
        }

        String priceStr = RecommendationCard.priceText(pick.getPrice());
        AppNav.info("🎰 [오늘의 룰렛 당첨 메뉴!]\n\n"
                + "🍽 메뉴명: " + pick.getMenuName() + " (" + pick.getCategory() + ")\n"
                + "🏪 식당명: " + pick.getRestaurantName() + " (★ " + pick.getRating() + ")\n"
                + "💰 가격: " + priceStr + " | 맵기: " + pick.getSpicyLevel() + "단계\n\n"
                + "💡 추천 사유:\n" + pick.getReason() + "\n\n"
                + "오늘 식사는 이 메뉴로 결정해 보시는 건 어떨까요?");
    }

}
