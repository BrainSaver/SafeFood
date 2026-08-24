package com.safefood.view.controller;

import com.safefood.view.AppNav;
import com.safefood.view.DemoData;
import com.safefood.view.Widgets;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

/**
 * 나의 기록 — 날짜로 묶은 타임라인 (리디자인 1i).
 *
 * <p>줄마다 날짜를 반복해 적던 것을 날짜 머리글 하나로 묶고, 상태 콤보 필터를 칩으로
 * 바꿨습니다. 즐겨찾기는 날짜가 없으므로 머리글 없이 같은 모양의 줄로만 그립니다.
 */
public class HistoryController {

    /** 필터 칩 — 라벨과 DemoData 의 type 값 짝. type 이 null 이면 전체. */
    private record Filter(String label, String type) {
    }

    private static final List<Filter> FILTERS = List.of(
            new Filter("전체", null),
            new Filter("먹음", "EATEN"),
            new Filter("추천받음", "RECOMMENDED"),
            new Filter("조회", "VIEWED"),
            new Filter("차단됨", "BLOCKED")
    );

    @FXML private HBox tabSeg;
    @FXML private HBox filterRow;
    @FXML private VBox timelineBox;
    @FXML private Label userLabel;

    private final ToggleGroup tabGroup = new ToggleGroup();
    private final ToggleGroup filterGroup = new ToggleGroup();

    private boolean showingHistory = true;

    @FXML
    private void initialize() {
        if (com.safefood.dto.Session.getCurrentUser() != null) {
            userLabel.setText(com.safefood.dto.Session.getCurrentUser().getNickname() + "님");
        } else {
            userLabel.setText(com.safefood.network.GroupSession.get().displayName() + "님");
        }

        buildTabs();
        buildFilters();
        render();
    }

    private void buildTabs() {
        ToggleButton history = Widgets.segOption("히스토리", true, false);
        ToggleButton favorite = Widgets.segOption("즐겨찾기", false, true);
        history.setToggleGroup(tabGroup);
        favorite.setToggleGroup(tabGroup);
        history.setSelected(true);
        tabSeg.getChildren().setAll(history, favorite);

        tabGroup.selectedToggleProperty().addListener((observable, before, after) -> {
            if (after == null) {
                tabGroup.selectToggle(before);   // 한 쪽은 늘 켜져 있어야 합니다
                return;
            }
            showingHistory = after == history;
            filterRow.setVisible(showingHistory);
            filterRow.setManaged(showingHistory);   // 즐겨찾기에는 상태 필터가 없습니다
            render();
        });
    }

    private void buildFilters() {
        filterRow.getChildren().clear();
        for (Filter filter : FILTERS) {
            ToggleButton chip = Widgets.compactChip(filter.label());
            chip.setToggleGroup(filterGroup);
            chip.setUserData(filter);
            if (filter.type() == null) {
                chip.setSelected(true);
            }
            filterRow.getChildren().add(chip);
        }
        filterGroup.selectedToggleProperty().addListener((observable, before, after) -> {
            if (after == null) {
                filterGroup.selectToggle(before);
                return;
            }
            render();
        });
    }

    // ── 그리기 ──────────────────────────────────────────────────

    private void render() {
        timelineBox.getChildren().clear();
        if (showingHistory) {
            renderHistory();
        } else {
            renderFavorites();
        }
    }

    private final com.safefood.dao.HistoryDao historyDao = new com.safefood.dao.HistoryDao();
    private final com.safefood.dao.FavoriteDao favoriteDao = new com.safefood.dao.FavoriteDao();
    private final com.safefood.dao.MenuDao menuDao = new com.safefood.dao.MenuDao();

    private void renderHistory() {
        String wanted = selectedType();
        String lastDate = null;
        int shown = 0;

        List<DemoData.HistoryRow> historyRows = List.of();
        if (com.safefood.dto.Session.getCurrentUser() != null) {
            historyRows = historyDao.getUserHistoryRows(com.safefood.dto.Session.getCurrentUser().getId(), wanted);
        } else {
            historyRows = DemoData.HISTORY;
        }

        for (DemoData.HistoryRow row : historyRows) {
            if (wanted != null && !wanted.equalsIgnoreCase(row.type())) {
                continue;
            }
            // 날짜가 바뀌는 지점에서만 머리글을 답니다 — 같은 날은 한 번만
            if (!row.date().equals(lastDate)) {
                lastDate = row.date();
                Label header = Widgets.label(formatDate(row.date()), "timeline-date");
                VBox.setMargin(header, new javafx.geometry.Insets(shown == 0 ? 4 : 12, 0, 2, 6));
                timelineBox.getChildren().add(header);
            }
            timelineBox.getChildren().add(historyRow(row));
            shown++;
        }

        if (shown == 0) {
            timelineBox.getChildren().add(Widgets.sub("해당하는 기록이 없습니다."));
        }
    }

    private HBox historyRow(DemoData.HistoryRow row) {
        Label menu = new Label(row.menu());
        menu.getStyleClass().add("BLOCKED".equalsIgnoreCase(row.type()) ? "strike" : "section-title");

        HBox line = new HBox(12,
                Widgets.statusLabel(row.type()),
                menu,
                Widgets.sub(row.restaurant()),
                Widgets.hSpacer(),
                Widgets.sub(row.note()));
        line.setAlignment(Pos.CENTER_LEFT);
        line.getStyleClass().addAll("card", "soft");
        line.setPadding(new javafx.geometry.Insets(13, 18, 13, 18));

        if ("EATEN".equalsIgnoreCase(row.type())) {
            Button rate = new Button("평가하기");
            rate.getStyleClass().add("ghost-quiet");
            rate.setOnAction(event ->
                    AppNav.info("좋아요 / 만족도 1~5점을 입력받는 자리입니다. (H-04)"));
            line.getChildren().add(rate);
        }
        return line;
    }

    private void renderFavorites() {
        List<DemoData.FavoriteRow> favList = List.of();
        if (com.safefood.dto.Session.getCurrentUser() != null) {
            favList = favoriteDao.getUserFavorites(com.safefood.dto.Session.getCurrentUser().getId());
        } else {
            favList = DemoData.FAVORITES;
        }

        if (favList.isEmpty()) {
            timelineBox.getChildren().add(Widgets.sub("찜한 가게나 메뉴가 없습니다."));
            return;
        }
        for (DemoData.FavoriteRow row : favList) {
            timelineBox.getChildren().add(favoriteRow(row));
        }
    }

    private HBox favoriteRow(DemoData.FavoriteRow row) {
        Button remove = new Button("♥ 찜 취소");
        remove.getStyleClass().add("ghost-quiet");

        HBox line = new HBox(12,
                Widgets.tag("찜", "accent"),
                Widgets.label(row.restaurant(), "section-title"),
                Widgets.sub(row.menu()),
                Widgets.hSpacer(),
                Widgets.sub("★ " + row.rating() + " · 리뷰 " + row.reviewCount()),
                remove);
        line.setAlignment(Pos.CENTER_LEFT);
        line.getStyleClass().addAll("card", "soft");
        line.setPadding(new javafx.geometry.Insets(13, 18, 13, 18));

        remove.setOnAction(event -> {
            if (com.safefood.dto.Session.getCurrentUser() != null) {
                int menuId = 0;
                for (com.safefood.dao.MenuDao.MenuDto m : menuDao.getAllCandidateMenus()) {
                    if (m.getName().equals(row.menu())) {
                        menuId = m.getId();
                        break;
                    }
                }
                if (menuId > 0) {
                    favoriteDao.removeFavorite(com.safefood.dto.Session.getCurrentUser().getId(), menuId);
                }
            }
            timelineBox.getChildren().remove(line);
            if (timelineBox.getChildren().isEmpty()) {
                timelineBox.getChildren().add(Widgets.sub("찜한 가게나 메뉴가 없습니다."));
            }
        });
        return line;
    }

    private String selectedType() {
        ToggleButton picked = (ToggleButton) filterGroup.getSelectedToggle();
        return picked == null ? null : ((Filter) picked.getUserData()).type();
    }

    /** "2026-08-12" → "8월 12일 화요일". 형식이 다르면 원문을 그대로 씁니다. */
    private static String formatDate(String iso) {
        try {
            LocalDate date = LocalDate.parse(iso);
            return date.getMonthValue() + "월 " + date.getDayOfMonth() + "일 "
                    + date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.KOREAN);
        } catch (DateTimeParseException e) {
            return iso;
        }
    }

    // ── 내비게이션 ──────────────────────────────────────────────

    @FXML
    private void handleMain() {
        AppNav.show("SafeFood — 맞춤 맛집 추천", "main.fxml");
    }

    @FXML
    private void handleProfile() {
        AppNav.show("프로필 및 설정 관리", "profile.fxml");
    }

    @FXML
    private void handleGroup() {
        AppNav.dialog("같이 먹기", "group-option.fxml");
    }

    @FXML
    private void handleLogout() {
        AppNav.show("로그인", "login.fxml");
    }
}
