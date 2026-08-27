package com.safefood.view.controller;

import com.safefood.dao.AllergyCatalog;
import com.safefood.dao.ChildDao;
import com.safefood.dto.Child;
import com.safefood.dto.Session;
import com.safefood.service.NeisClient;
import com.safefood.view.AppNav;
import com.safefood.view.AsyncLoader;
import com.safefood.view.Widgets;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 아이 관리 — 이름 · 학교 · 알레르기.
 *
 * <p>학교는 나이스에서 그때그때 검색합니다. 우리 DB 에 학교 테이블을 두지 않고, 고른 학교의
 * 코드 두 개만 아이 행에 적어 둡니다.
 *
 * <p>저장은 {@code ChildDao.insertWithAllergies} · {@code updateWithAllergies} 가 아이와 알레르기를
 * 한 트랜잭션으로 처리합니다 — 중간에 실패해도 이전 상태가 그대로 남습니다.
 *
 * <p><b>알레르기 목록이 도착하기 전에는 저장할 수 없습니다.</b> 아이를 고르면 알레르기는
 * 뒤따라 오는데, 그 사이에 저장을 누르면 <b>앞 아이의 알레르기가 이 아이에게 적힙니다.</b>
 * 그래서 고른 순간 목록을 비우고 저장 단추를 잠갔다가, 도착하면 풉니다.
 */
public class ChildController {

    @FXML private VBox root;
    @FXML private FlowPane childPane;
    @FXML private TextField nameField;
    @FXML private TextField schoolField;
    @FXML private Button searchButton;
    @FXML private Label pickedSchoolLabel;
    @FXML private ListView<String> schoolList;
    @FXML private FlowPane ownedPane;
    @FXML private FlowPane catalogPane;
    @FXML private Label errorLabel;
    @FXML private Button deleteButton;
    @FXML private Button saveButton;


    private final ChildDao childDao = new ChildDao();
    private final NeisClient neis = new NeisClient();
    private final AsyncLoader loader = new AsyncLoader();

    private List<Child> children = new ArrayList<>();
    private List<NeisClient.School> found = List.of();

    /** 지금 고쳐 쓰는 아이. null 이면 새로 만드는 중입니다. */
    private Child editing;

    private String atptCode;
    private String schulCode;
    private String schulNm;

    /**
     * 화면의 알레르기 목록이 <b>지금 고른 아이의 것</b>인지. 아이를 고른 직후부터
     * 목록이 도착할 때까지는 false 이고, 그동안에는 저장할 수 없습니다.
     */
    private boolean allergiesReady = true;

    /** 아이 목록이 바뀌었음을 부른 화면에 알립니다. */
    private Runnable onChanged;

    public void setOnChanged(Runnable onChanged) {
        this.onChanged = onChanged;
    }

    @FXML
    private void initialize() {
        schoolList.getSelectionModel().selectedIndexProperty().addListener((obs, before, after) -> {
            int index = after == null ? -1 : after.intValue();
            if (index >= 0 && index < found.size()) {
                pickSchool(found.get(index));
            }
        });
        renderCatalog();
        loadChildren(-1);
    }

    // ---- 아이 목록 ----

    /**
     * 아이 목록을 다시 읽습니다.
     *
     * @param selectId 읽은 뒤 고를 아이의 id. -1 이면 지금 고쳐 쓰는 아이를 그대로 둡니다.
     *                 저장 직후에 넘겨서, 방금 저장한 아이가 아닌 <b>첫 번째 아이</b>로
     *                 화면이 넘어가는 일을 막습니다
     */
    private void loadChildren(int selectId) {
        if (!Session.isMember()) {
            Widgets.showError(errorLabel, "게스트는 아이를 등록할 수 없습니다.");
            saveButton.setDisable(true);
            return;
        }
        int userId = Session.currentUserId();
        loader.load(() -> childDao.findByUser(userId),
                loaded -> {
                    children = new ArrayList<>(loaded);
                    renderChildChips();
                    Child target = null;
                    if (selectId > 0) {
                        target = children.stream()
                                .filter(child -> child.id() == selectId).findFirst().orElse(null);
                    }
                    if (target == null && editing == null && !children.isEmpty()) {
                        target = children.get(0);
                    }
                    if (target == null) {
                        renderDeleteButton();
                    } else {
                        select(target);
                    }
                },
                error -> Widgets.showError(errorLabel, error.getMessage()));
    }

    private void renderChildChips() {
        childPane.getChildren().clear();
        if (children.isEmpty()) {
            childPane.getChildren().add(Widgets.sub("아직 없어요. '새 아이'로 등록해 주세요."));
            return;
        }
        for (Child child : children) {
            Button chip = new Button(child.name());
            chip.getStyleClass().add("chip");
            chip.setOnAction(event -> select(child));
            childPane.getChildren().add(chip);
        }
    }

    private void select(Child child) {
        editing = child;
        nameField.setText(child.name());
        atptCode = child.atptCode();
        schulCode = child.schulCode();
        schulNm = child.schulNm();
        renderPickedSchool();
        hideSchoolList();
        Widgets.hideError(errorLabel);
        renderDeleteButton();

        // 앞 아이의 목록을 화면에 남겨 두면 안 됩니다 — 그대로 저장하면 이 아이에게 적힙니다
        owned.clear();
        setAllergiesReady(false);
        renderOwned();
        renderCatalog();

        int childId = child.id();
        loader.load(() -> childDao.getAllergies(childId),
                allergies -> {
                    owned.clear();
                    owned.putAll(allergies);
                    setAllergiesReady(true);
                    renderOwned();
                    renderCatalog();
                },
                error -> {
                    // 못 읽은 것은 '알레르기 없음'이 아닙니다 — 덮어쓰지 못하게 잠가 둡니다
                    Widgets.showError(errorLabel, error.getMessage());
                    setAllergiesReady(false);
                    renderOwned();
                });
    }

    /** 저장해도 되는 상태인지 표시합니다. 게스트는 어느 쪽이든 저장할 수 없습니다. */
    private void setAllergiesReady(boolean ready) {
        allergiesReady = ready;
        saveButton.setDisable(!ready || !Session.isMember());
    }

    @FXML
    private void handleNewChild() {
        // 앞서 띄운 아이의 알레르기 조회가 돌아와 새 아이의 목록을 채우지 못하게 버립니다
        loader.discardPending();
        editing = null;
        nameField.clear();
        atptCode = null;
        schulCode = null;
        schulNm = null;
        owned.clear();
        setAllergiesReady(true);
        renderPickedSchool();
        renderOwned();
        renderCatalog();
        hideSchoolList();
        renderDeleteButton();
        Widgets.hideError(errorLabel);
        nameField.requestFocus();
    }

    private void renderDeleteButton() {
        boolean editingExisting = editing != null;
        deleteButton.setVisible(editingExisting);
        deleteButton.setManaged(editingExisting);
    }

    // ---- 학교 검색 ----

    @FXML
    private void handleSearchSchool() {
        String keyword = schoolField.getText() == null ? "" : schoolField.getText().trim();
        if (keyword.isBlank()) {
            Widgets.showError(errorLabel, "학교 이름을 입력해 주세요.");
            return;
        }
        if (!NeisClient.hasApiKey()) {
            Widgets.showError(errorLabel,
                    "config.properties 의 neis.api.key 가 비어 있어 학교를 검색할 수 없습니다.");
            return;
        }

        Widgets.hideError(errorLabel);
        searchButton.setDisable(true);
        loader.submit(() -> neis.searchSchools(keyword),
                result -> {
                    searchButton.setDisable(false);
                    found = result;
                    if (found.isEmpty()) {
                        hideSchoolList();
                        Widgets.showError(errorLabel, "'" + keyword + "' 와(과) 맞는 학교를 찾지 못했습니다.");
                        return;
                    }
                    schoolList.getItems().setAll(found.stream()
                            .map(s -> s.schulName() + " · " + s.kind() + " · " + s.atptName()).toList());
                    schoolList.setVisible(true);
                    schoolList.setManaged(true);
                },
                error -> {
                    searchButton.setDisable(false);
                    Widgets.showError(errorLabel, "학교를 검색하지 못했습니다: " + error.getMessage());
                });
    }

    private void pickSchool(NeisClient.School school) {
        atptCode = school.atptCode();
        schulCode = school.schulCode();
        schulNm = school.schulName();
        renderPickedSchool();
        hideSchoolList();
    }

    private void renderPickedSchool() {
        pickedSchoolLabel.setText(schulNm == null || schulNm.isBlank()
                ? "아직 고르지 않았어요" : "선택 — " + schulNm);
    }

    private void hideSchoolList() {
        schoolList.setVisible(false);
        schoolList.setManaged(false);
        schoolList.getSelectionModel().clearSelection();
    }

    // ---- 알레르기 ----

    /** 화면에 붙어 있는 알레르기 → 심각도. */
    private final Map<String, Integer> owned = new LinkedHashMap<>();

    private void renderOwned() {
        ownedPane.getChildren().clear();
        if (owned.isEmpty()) {
            // 아직 못 읽은 것과 정말 없는 것을 가릅니다 — 둘을 같게 보이면 '없음'으로 저장됩니다
            ownedPane.getChildren().add(Widgets.sub(allergiesReady
                    ? "등록한 알레르기가 없습니다." : "알레르기를 불러오는 중이에요…"));
            return;
        }
        for (Map.Entry<String, Integer> entry : owned.entrySet()) {
            ownedPane.getChildren().add(allergyChip(entry.getKey(), entry.getValue()));
        }
    }

    private HBox allergyChip(String name, int severity) {
        ChoiceBox<Integer> level = new ChoiceBox<>();
        level.getItems().setAll(1, 2, 3, 4, 5);
        level.setValue(severity);
        level.valueProperty().addListener((obs, before, after) -> {
            if (after != null) {
                owned.put(name, after);
            }
        });

        Button remove = new Button("✕");
        remove.getStyleClass().add("ghost-quiet");
        remove.setOnAction(event -> {
            owned.remove(name);
            renderOwned();
            renderCatalog();
        });

        HBox chip = new HBox(6, new Label(name), level, remove);
        chip.setAlignment(Pos.CENTER_LEFT);
        chip.getStyleClass().addAll("tag", "accent");
        return chip;
    }

    private void renderCatalog() {
        catalogPane.getChildren().clear();
        List<String> rest = AllergyCatalog.names().stream().filter(n -> !owned.containsKey(n)).toList();
        if (rest.isEmpty()) {
            catalogPane.getChildren().add(Widgets.sub("모두 등록했습니다."));
            return;
        }
        for (String name : rest) {
            Button chip = new Button(name);
            chip.getStyleClass().add("chip");
            chip.setOnAction(event -> {
                owned.put(name, 3);
                renderOwned();
                renderCatalog();
            });
            catalogPane.getChildren().add(chip);
        }
    }

    // ---- 저장 · 삭제 ----

    @FXML
    private void handleSave() {
        if (!Session.isMember()) {
            Widgets.showError(errorLabel, "게스트는 아이를 등록할 수 없습니다.");
            return;
        }
        String name = nameField.getText() == null ? "" : nameField.getText().trim();
        if (name.isBlank()) {
            Widgets.showError(errorLabel, "아이 이름을 입력해 주세요.");
            return;
        }
        if (!allergiesReady) {
            // 지금 화면에 있는 목록은 이 아이의 것이 아닙니다
            Widgets.showError(errorLabel, "알레르기를 불러오는 중이에요. 잠시 뒤에 다시 눌러 주세요.");
            return;
        }

        Widgets.hideError(errorLabel);
        saveButton.setDisable(true);

        Child target = editing;
        Map<String, Integer> allergies = new LinkedHashMap<>(owned);
        int userId = Session.currentUserId();
        String atpt = atptCode;
        String schul = schulCode;
        String school = schulNm;

        loader.submit(() -> target == null
                        ? childDao.insertWithAllergies(userId, name, atpt, schul, school, allergies)
                        : childDao.updateWithAllergies(target.id(), name, atpt, schul, school, allergies),
                saved -> {
                    saveButton.setDisable(false);
                    if (!saved.ok()) {
                        // 한 트랜잭션이라 실패했으면 아이도 알레르기도 그대로입니다
                        Widgets.showError(errorLabel, saved.failure());
                        return;
                    }
                    editing = null;
                    notifyChanged();
                    loadChildren(saved.childId());
                    AppNav.success(name + " 정보를 저장했어요.");
                },
                error -> {
                    saveButton.setDisable(false);
                    Widgets.showError(errorLabel, "저장 중 오류가 났습니다: " + error.getMessage());
                });
    }

    @FXML
    private void handleDelete() {
        if (editing == null) {
            return;
        }
        Child target = editing;
        if (!AppNav.confirm(target.name() + " 을(를) 지울까요?\n등록한 알레르기도 함께 지워집니다.")) {
            return;
        }
        saveButton.setDisable(true);
        loader.submit(() -> childDao.delete(target.id()),
                ok -> {
                    setAllergiesReady(allergiesReady);
                    if (!ok) {
                        Widgets.showError(errorLabel, "지우지 못했습니다. 연결을 확인해 주세요.");
                        return;
                    }
                    editing = null;
                    handleNewChild();
                    notifyChanged();
                    loadChildren(-1);
                    AppNav.info(target.name() + " 을(를) 지웠어요.");
                },
                error -> {
                    setAllergiesReady(allergiesReady);
                    Widgets.showError(errorLabel, "지우는 중 오류가 났습니다: " + error.getMessage());
                });
    }

    private void notifyChanged() {
        if (onChanged != null) {
            onChanged.run();
        }
    }

    @FXML
    private void handleClose() {
        AppNav.close(root);
    }
}
