package com.safefood.view.controller;

import com.safefood.dao.ChildDao;
import com.safefood.dto.Child;
import com.safefood.dto.Session;
import com.safefood.service.MealService;
import com.safefood.service.NeisClient;
import com.safefood.view.AppNav;
import com.safefood.view.AsyncLoader;
import com.safefood.view.Widgets;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 우리 아이 급식 화면.
 *
 * <p><b>이 화면은 '안전합니다'라고 말하지 않습니다.</b> 학교가 표기한 것 중 아이 것이 없다는
 * 사실까지만 말하고, 표기 자체가 없으면 그 사실을 따로 알립니다. 부모가 급식을 고를 수는 없으니
 * 걸러 주는 것이 아니라 <b>조심할 날을 놓치지 않게</b> 하는 것이 목적입니다.
 *
 * <p>실제 학교를 훑어 보니 절반이 <b>5일치만</b> 올려 둡니다. 그래서 앞으로의 날이 비어 있는 것이
 * 정상이고, '아직 등록 전'과 '알레르기 표기 없음'을 화면에서 반드시 갈라 보여 줍니다.
 */
public class MealController {

    @FXML private ComboBox<String> childBox;
    @FXML private Label schoolLabel;
    @FXML private NavController navController;
    @FXML private Label rangeLabel;
    @FXML private Label fetchedLabel;
    @FXML private VBox summaryBox;
    @FXML private VBox dayBox;
    @FXML private Button refreshButton;

    private final ChildDao childDao = new ChildDao();
    private final MealService mealService = new MealService();
    private final AsyncLoader loader = new AsyncLoader();


    private List<Child> children = List.of();
    private Child selected;
    private LocalDate weekStart = thisMonday();
    private boolean switching;

    @FXML
    private void initialize() {
        navController.setActive(NavController.Tab.MEAL);

        childBox.getSelectionModel().selectedIndexProperty().addListener((obs, before, after) -> {
            if (switching) {
                return;
            }
            int index = after == null ? -1 : after.intValue();
            selected = (index >= 0 && index < children.size()) ? children.get(index) : null;
            renderSchool();
            reload(false);
        });

        renderRange();
        loadChildren();
    }

    private static LocalDate thisMonday() {
        LocalDate today = LocalDate.now(com.safefood.service.MealCache.KST);
        return today.minusDays(today.getDayOfWeek().getValue() - 1L);
    }

    // ---- 아이 목록 ----

    private void loadChildren() {
        if (!Session.isMember()) {
            showNotice("회원만 쓸 수 있어요", "아이와 알레르기를 저장하려면 로그인해 주세요.");
            childBox.setDisable(true);
            return;
        }
        int userId = Session.currentUserId();
        loader.load(() -> childDao.findByUser(userId),
                this::applyChildren,
                error -> showNotice("아이 정보를 불러오지 못했어요", error.getMessage()));
    }

    private void applyChildren(List<Child> loaded) {
        children = loaded;
        switching = true;
        childBox.getItems().setAll(children.stream().map(MealController::labelOf).toList());
        childBox.getSelectionModel().clearSelection();
        switching = false;

        if (children.isEmpty()) {
            selected = null;
            renderSchool();
            showNotice("아직 등록한 아이가 없어요",
                    "'아이 관리'에서 아이 이름과 학교, 알레르기를 등록하면 급식을 확인할 수 있어요.");
            return;
        }
        int keep = 0;
        if (selected != null) {
            for (int i = 0; i < children.size(); i++) {
                if (children.get(i).id() == selected.id()) {
                    keep = i;
                    break;
                }
            }
        }
        childBox.getSelectionModel().select(keep);
    }

    private static String labelOf(Child child) {
        return child.name() + " (" + child.schoolLabel() + ")";
    }

    private void renderSchool() {
        if (selected == null) {
            schoolLabel.setText("");
        } else if (!selected.hasSchool()) {
            schoolLabel.setText("학교가 등록되지 않았어요");
        } else {
            schoolLabel.setText(selected.schulNm());
        }
    }

    // ---- 급식 조회 ----

    private void reload(boolean forceRefresh) {
        if (selected == null) {
            return;
        }
        if (!selected.hasSchool()) {
            showNotice("학교를 먼저 등록해 주세요",
                    "'아이 관리'에서 학교를 검색해 고르면 급식을 불러올 수 있어요.");
            return;
        }
        if (!NeisClient.hasApiKey()) {
            showNotice("나이스 인증키가 없어요",
                    "config.properties 의 neis.api.key 를 채우면 급식을 불러옵니다.");
            return;
        }

        refreshButton.setDisable(true);
        showNotice("급식을 불러오는 중이에요", "잠시만 기다려 주세요.");

        Child child = selected;
        LocalDate from = weekStart;
        LocalDate to = weekStart.plusDays(6);
        loader.load(() -> mealService.load(child, from, to, forceRefresh),
                period -> {
                    refreshButton.setDisable(false);
                    render(period, from, to);
                },
                error -> {
                    refreshButton.setDisable(false);
                    showNotice("급식을 불러오지 못했어요", error.getMessage());
                });
    }

    // ---- 그리기 ----

    private void render(MealService.MealPeriod period, LocalDate from, LocalDate to) {
        dayBox.getChildren().clear();
        summaryBox.getChildren().clear();
        summaryBox.setVisible(false);
        summaryBox.setManaged(false);

        if (period.failed()) {
            fetchedLabel.setText("");
            dayBox.getChildren().add(notice("급식을 불러오지 못했어요", period.failure()));
            return;
        }

        fetchedLabel.setText(period.fetchedOn() == null ? ""
                : (period.stale() ? "⚠ " : "") + describeFetched(period.fetchedOn()));

        if (period.stale()) {
            summaryBox.getChildren().add(banner(Widgets.Variant.WARN,
                    "최신 정보를 불러오지 못해 예전에 받아 둔 내용을 보여 드립니다 — "
                            + describeFetched(period.fetchedOn()) + ". 새로고침을 눌러 다시 시도해 보세요."));
        }

        List<MealService.DayView> risky = period.riskyDays();
        if (!risky.isEmpty()) {
            String days = String.join(", ", risky.stream().map(d -> shortDay(d.date())).toList());
            summaryBox.getChildren().add(banner(Widgets.Variant.DANGER,
                    "이번 주 " + risky.size() + "일 주의 — " + days));
        }
        if (!summaryBox.getChildren().isEmpty()) {
            summaryBox.setVisible(true);
            summaryBox.setManaged(true);
        }

        LocalDate today = LocalDate.now(com.safefood.service.MealCache.KST);
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            if (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
                continue;
            }
            LocalDate day = date;
            List<MealService.DayView> onDay = period.days().stream()
                    .filter(d -> d.date().equals(day)).toList();
            if (onDay.isEmpty()) {
                dayBox.getChildren().add(missingDay(day, today));
            } else {
                onDay.forEach(view -> dayBox.getChildren().add(dayCard(view)));
            }
        }
    }

    /** 급식이 없는 날 — 지난 날은 '급식 없음', 앞으로의 날은 '아직 등록 전'입니다. */
    private VBox missingDay(LocalDate date, LocalDate today) {
        boolean past = date.isBefore(today);
        VBox card = new VBox(4,
                Widgets.label(longDay(date), "section-title"),
                Widgets.sub(past ? "이 날은 등록된 급식이 없어요."
                        : "아직 학교가 식단을 올리지 않았어요. 보통 며칠 전에 올라옵니다."));
        card.getStyleClass().addAll("card", "soft");
        card.setPadding(new Insets(14, 18, 14, 18));
        return card;
    }

    private VBox dayCard(MealService.DayView view) {
        HBox header = new HBox(8, Widgets.label(longDay(view.date()), "section-title"),
                Widgets.sub(view.mealType()));
        header.setAlignment(Pos.CENTER_LEFT);
        if (view.risky()) {
            header.getChildren().add(Widgets.tag("주의", Widgets.Variant.DANGER));
        } else if (!view.labeled()) {
            header.getChildren().add(Widgets.tag("표기 없음", Widgets.Variant.NEUTRAL));
        }

        VBox dishes = new VBox(6);
        for (MealService.DishView dish : view.dishes()) {
            dishes.getChildren().add(dishRow(dish));
        }

        VBox card = new VBox(9, header, dishes);
        card.getStyleClass().addAll("card", "soft");
        if (view.risky()) {
            card.getStyleClass().add("elev");
        }
        card.setPadding(new Insets(14, 18, 14, 18));
        return card;
    }

    private HBox dishRow(MealService.DishView dish) {
        Label name = new Label(dish.name());
        name.getStyleClass().add(dish.safety() == MealService.DishSafety.RISK ? "menu-name" : "");

        FlowPane tags = new FlowPane(4, 4);
        if (dish.safety() == MealService.DishSafety.RISK) {
            for (String hit : dish.hitAllergens()) {
                tags.getChildren().add(Widgets.tag(hit, Widgets.Variant.DANGER));
            }
            if (dish.maxSeverity() >= 4) {
                tags.getChildren().add(Widgets.tag("심각도 " + dish.maxSeverity(), Widgets.Variant.DANGER));
            }
        }
        for (String all : dish.allAllergens()) {
            if (!dish.hitAllergens().contains(all)) {
                tags.getChildren().add(Widgets.tag(all, Widgets.Variant.NEUTRAL));
            }
        }
        if (dish.safety() == MealService.DishSafety.UNLABELED) {
            tags.getChildren().add(Widgets.tag("표기 없음", Widgets.Variant.NEUTRAL));
        }
        if (!dish.unmappedCodes().isEmpty()) {
            tags.getChildren().add(Widgets.tag("확인 불가", Widgets.Variant.WARN));
        }

        HBox row = new HBox(10, name, Widgets.hSpacer(), tags);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private Label banner(Widgets.Variant variant, String text) {
        Label label = Widgets.tag(text, variant);
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    private void showNotice(String title, String detail) {
        dayBox.getChildren().setAll(notice(title, detail));
        summaryBox.setVisible(false);
        summaryBox.setManaged(false);
        fetchedLabel.setText("");
    }

    private VBox notice(String title, String detail) {
        Label body = Widgets.sub(detail == null ? "" : detail);
        body.setWrapText(true);
        VBox box = new VBox(6, Widgets.label(title, "section-title"), body);
        box.getStyleClass().addAll("card", "soft");
        box.setPadding(new Insets(20, 22, 20, 22));
        return box;
    }

    private void renderRange() {
        rangeLabel.setText(shortDay(weekStart) + " ~ " + shortDay(weekStart.plusDays(4)));
    }

    private static String shortDay(LocalDate date) {
        return date.getMonthValue() + "/" + date.getDayOfMonth() + "("
                + date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.KOREAN) + ")";
    }

    private static String longDay(LocalDate date) {
        return date.getMonthValue() + "월 " + date.getDayOfMonth() + "일 "
                + date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.KOREAN);
    }

    private static String describeFetched(LocalDate fetchedOn) {
        LocalDate today = LocalDate.now(com.safefood.service.MealCache.KST);
        long days = today.toEpochDay() - fetchedOn.toEpochDay();
        if (days <= 0) {
            return "오늘 받은 정보";
        }
        if (days == 1) {
            return "어제 받은 정보";
        }
        return days + "일 전에 받은 정보";
    }

    // ---- 조작 ----

    @FXML
    private void handlePrevWeek() {
        weekStart = weekStart.minusWeeks(1);
        renderRange();
        reload(false);
    }

    @FXML
    private void handleNextWeek() {
        weekStart = weekStart.plusWeeks(1);
        renderRange();
        reload(false);
    }

    @FXML
    private void handleRefresh() {
        reload(true);
    }

    @FXML
    private void handleManageChildren() {
        AppNav.dialog("아이 관리", "child.fxml",
                (ChildController controller) -> controller.setOnChanged(this::loadChildren));
    }

    // ---- 내비게이션 ----




}
