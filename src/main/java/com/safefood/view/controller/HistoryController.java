package com.safefood.view.controller;

import com.safefood.dto.FavoriteDto;
import com.safefood.dto.HistoryDto;
import com.safefood.dto.UserDto;
import com.safefood.service.FavoriteService;
import com.safefood.service.FeedbackService;
import com.safefood.service.HistoryService;
import com.safefood.view.AppNav;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 나의 기록 — 날짜로 묶은 타임라인 (전체, 먹음, 차단됨 분류).
 */
public class HistoryController {

    private record Filter(String label, String type) {
    }

    // 나의 기록 분류: 전체, 먹음, 차단됨
    private static final List<Filter> FILTERS = List.of(
            new Filter("전체", null),
            new Filter("먹음", "EATEN"),
            new Filter("차단됨", "BLOCKED")
    );

    @FXML private HBox tabSeg;
    @FXML private HBox filterRow;
    @FXML private VBox timelineBox;
    @FXML private NavController navController;

    private final ToggleGroup tabGroup = new ToggleGroup();
    private final ToggleGroup filterGroup = new ToggleGroup();

    private boolean showingHistory = true;

    @FXML
    private void initialize() {
        navController.setActive(NavController.Tab.HISTORY);

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
                tabGroup.selectToggle(before);
                return;
            }
            showingHistory = (after == history);
            filterRow.setVisible(showingHistory);
            filterRow.setManaged(showingHistory);
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

    private void renderHistory() {
        String wanted = selectedType();
        UserDto me = com.safefood.dto.Session.getCurrentUser();

        List<HistoryDto> allHistories = com.safefood.dto.Session.isMember()
                ? new HistoryService().getHistories(me.getId())
                : new ArrayList<>();

        String lastDate = null;
        int shown = 0;

        for (HistoryDto row : allHistories) {
            // "전체" 선택 시: EATEN(먹음)과 BLOCKED(차단됨)만 표시
            if (wanted == null) {
                if (!"EATEN".equals(row.getType()) && !"BLOCKED".equals(row.getType())) {
                    continue;
                }
            } else if (!wanted.equals(row.getType())) {
                continue;
            }

            if (row.getDate() != null && !row.getDate().equals(lastDate)) {
                lastDate = row.getDate();
                Label header = Widgets.label(formatDate(row.getDate()), "timeline-date");
                VBox.setMargin(header, new javafx.geometry.Insets(shown == 0 ? 4 : 12, 0, 2, 6));
                timelineBox.getChildren().add(header);
            }
            timelineBox.getChildren().add(historyRow(row, me, wanted));
            shown++;
        }

        if (shown == 0) {
            String emptyMsg = "BLOCKED".equals(wanted) ? "차단된 메뉴가 없습니다."
                    : ("EATEN".equals(wanted) ? "먹은 기록이 없습니다." : "해당하는 기록이 없습니다.");
            timelineBox.getChildren().add(Widgets.sub(emptyMsg));
        }
    }

    private HBox historyRow(HistoryDto row, UserDto me, String currentFilterType) {
        boolean isBlocked = "BLOCKED".equals(row.getType());
        Label menu = new Label(row.getMenu());
        menu.getStyleClass().add(isBlocked ? "strike" : "section-title");

        HBox line = new HBox(12,
                Widgets.statusLabel(row.getType()),
                menu,
                Widgets.sub(row.getRestaurant()),
                Widgets.hSpacer(),
                Widgets.sub(row.getNote() != null ? row.getNote() : ""));
        line.setAlignment(Pos.CENTER_LEFT);
        line.getStyleClass().addAll("card", "soft");
        line.setPadding(new javafx.geometry.Insets(13, 18, 13, 18));

        if ("EATEN".equals(row.getType())) {
            // 1. 차단하기 버튼 (평가 버튼 왼쪽)
            Button blockBtn = new Button("차단하기");
            blockBtn.getStyleClass().add("ghost-quiet");
            blockBtn.setOnAction(event -> {
                if (!com.safefood.dto.Session.isMember()) {
                    AppNav.warn("게스트는 차단 기능을 사용할 수 없습니다.");
                    return;
                }
                boolean ok = new HistoryService().blockHistory(row.getHistoryId(), me.getId(), row.getMenuId());
                if (ok) {
                    AppNav.success("'" + row.getMenu() + "' 메뉴를 차단했습니다.\n('차단됨' 탭에서 확인 및 해제하실 수 있습니다)");
                    render();
                } else {
                    AppNav.error("메뉴 차단에 실패했습니다.");
                }
            });

            // 2. 평가하기 버튼
            boolean rated = row.getFeedbackId() > 0;
            Button rate = new Button(rated ? "평가완료" : "평가하기");
            rate.getStyleClass().add("ghost-quiet");
            rate.setDisable(rated);
            rate.setOnAction(event -> {
                if (!com.safefood.dto.Session.isMember()) {
                    AppNav.warn("게스트는 평가할 수 없습니다.");
                    return;
                }
                List<Integer> choices = List.of(5, 4, 3, 2, 1);
                javafx.scene.control.ChoiceDialog<Integer> dialog =
                        new javafx.scene.control.ChoiceDialog<>(5, choices);
                dialog.setTitle("식사 평가");
                dialog.setHeaderText(row.getMenu() + "은(는) 어떠셨나요?");
                dialog.setContentText("별점을 선택해주세요 (1~5점):");
                dialog.showAndWait().ifPresent(rating -> {
                    boolean ok = new FeedbackService()
                            .saveFeedback(me.getId(), row.getHistoryId(), row.getMenuId(), rating);
                    if (ok) {
                        AppNav.info("평가가 저장되었습니다!\n(앞으로 추천 알고리즘 가중치에 반영됩니다)");
                        rate.setDisable(true);
                        rate.setText("평가완료");
                    } else {
                        AppNav.error("평가 저장에 실패했습니다.");
                    }
                });
            });

            line.getChildren().addAll(blockBtn, rate);

        } else if (isBlocked) {
            // [차단해제 버튼]: "차단됨" 탭에서만 노출
            if ("BLOCKED".equals(currentFilterType)) {
                Button unblockBtn = new Button("차단해제");
                unblockBtn.getStyleClass().add("ghost-quiet");
                unblockBtn.setOnAction(event -> {
                    if (!com.safefood.dto.Session.isMember()) {
                        AppNav.warn("게스트는 차단 해제 기능을 사용할 수 없습니다.");
                        return;
                    }
                    boolean ok = new HistoryService().unblockHistory(row.getHistoryId(), me.getId(), row.getMenuId());
                    if (ok) {
                        AppNav.success("'" + row.getMenu() + "' 차단을 해제하여 '먹음' 기록으로 변경했습니다.");
                        render();
                    } else {
                        AppNav.error("차단 해제에 실패했습니다.");
                    }
                });

                line.getChildren().add(unblockBtn);
            }
        }

        return line;
    }

    private void renderFavorites() {
        UserDto me = com.safefood.dto.Session.getCurrentUser();
        List<FavoriteDto> favs = com.safefood.dto.Session.isMember()
                ? new FavoriteService().getFavorites(me.getId())
                : new ArrayList<>();

        if (favs.isEmpty()) {
            timelineBox.getChildren().add(Widgets.sub("찜한 가게가 없습니다."));
            return;
        }
        for (FavoriteDto row : favs) {
            timelineBox.getChildren().add(favoriteRow(row));
        }
    }

    private HBox favoriteRow(FavoriteDto row) {
        Button remove = new Button("♥ 찜 취소");
        remove.getStyleClass().add("ghost-quiet");

        HBox line = new HBox(12,
                Widgets.tag("찜", Widgets.Variant.ACCENT),
                Widgets.label(row.getRestaurant(), "section-title"),
                Widgets.sub(row.getMenu()),
                Widgets.hSpacer(),
                Widgets.sub("★ " + row.getRating() + " · 리뷰 " + row.getReviewCount()),
                remove);
        line.setAlignment(Pos.CENTER_LEFT);
        line.getStyleClass().addAll("card", "soft");
        line.setPadding(new javafx.geometry.Insets(13, 18, 13, 18));

        remove.setOnAction(event -> {
            new FavoriteService().removeFavorite(row.getFavoriteId());
            timelineBox.getChildren().remove(line);
            if (timelineBox.getChildren().isEmpty()) {
                timelineBox.getChildren().add(Widgets.sub("찜한 가게가 없습니다."));
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
}
