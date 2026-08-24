package com.safefood.view.controller;

import com.safefood.dto.LocationDto;
import com.safefood.dto.Session;
import com.safefood.dto.UserDto;
import com.safefood.service.LocationService;
import com.safefood.service.MapService;
import com.safefood.view.AppNav;
import com.safefood.view.DemoData;
import com.safefood.view.Widgets;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

/**
 * 위치 등록 — 내 정보에서 여는 작은 창.
 *
 * <p>주소는 <b>카카오(다음) 우편번호 서비스</b>에서 고릅니다({@link PostcodeController}).
 * 그 서비스는 좌표를 주지 않기 때문에, 고른 주소를 {@link MapService}(카카오 지도 API)에
 * 다시 물어 위도·경도를 채웁니다.
 *
 * <p>좌표는 못 채울 수도 있습니다 — {@code map.api.key} 가 비었거나 카카오가 그 주소를 못 찾은
 * 경우입니다. 그때도 주소만으로 등록할 수 있게 두고(거리 계산에서만 빠집니다), 아래 칸에
 * 직접 넣거나 대표 지점으로 대신 채울 수 있게 했습니다.
 *
 * <p>등록한 곳은 회원이면 user_location 에, 게스트면 {@link Session} 에만 남습니다
 * (프로필의 다른 값들과 같은 규칙 — 저장할 계정이 없으니까요).
 */
public class LocationController {

    @FXML private VBox root;
    @FXML private Label currentTag;
    @FXML private TextField zonecodeField;
    @FXML private TextField addressField;
    @FXML private TextField detailField;
    @FXML private TextField labelField;
    @FXML private TextField latField;
    @FXML private TextField lngField;
    @FXML private Label coordTag;
    @FXML private Label coordHint;
    @FXML private Label errorLabel;
    @FXML private Button clearButton;
    @FXML private FlowPane placePane;

    private final LocationService locationService = new LocationService();
    private final MapService mapService = new MapService();

    /** 저장·해제가 끝났음을 부른 화면에 알립니다 (프로필이 라벨을 다시 그립니다). */
    private Runnable onSaved;

    public void setOnSaved(Runnable onSaved) {
        this.onSaved = onSaved;
    }

    @FXML
    private void initialize() {
        latField.textProperty().addListener((observable, before, after) -> checkCoordinates());
        lngField.textProperty().addListener((observable, before, after) -> checkCoordinates());

        renderPlaces();
        fill(myLocation());
        checkCoordinates();
    }

    /** 지금 등록돼 있는 위치 — 세션에 사본이 없으면 DB에서 한 번 읽어 옵니다. */
    private LocationDto myLocation() {
        LocationDto cached = Session.getMyLocation();
        if (cached != null) {
            return cached;
        }
        UserDto me = Session.getCurrentUser();
        if (me == null) {
            return null;   // 게스트 — 세션에 없으면 없는 것입니다
        }
        LocationDto saved = locationService.getMyLocation(me.getId());
        Session.setMyLocation(saved);
        return saved;
    }

    private void fill(LocationDto location) {
        boolean registered = location != null;
        currentTag.setText(registered ? location.describe() : "없음");
        currentTag.getStyleClass().removeAll("accent", "neutral");
        currentTag.getStyleClass().add(registered ? "accent" : "neutral");

        // 등록한 것이 없으면 지울 것도 없습니다
        clearButton.setVisible(registered);
        clearButton.setManaged(registered);

        if (!registered) {
            return;
        }
        labelField.setText(text(location.getLabel()));
        zonecodeField.setText(text(location.getZonecode()));
        // 저장된 주소는 상세 주소까지 합쳐진 한 줄이라 그대로 주소 칸에 둡니다
        addressField.setText(text(location.getAddress()));
        latField.setText(location.getLatitude() == null ? "" : String.valueOf(location.getLatitude()));
        lngField.setText(location.getLongitude() == null ? "" : String.valueOf(location.getLongitude()));
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    // ── 주소 검색 ───────────────────────────────────────────────

    @FXML
    private void handleSearchAddress() {
        AppNav.dialog("주소 검색", "postcode.fxml",
                (PostcodeController controller) -> controller.setOnPicked(this::applyPicked));
    }

    /** 우편번호 서비스가 돌려준 주소를 칸에 옮기고, 이어서 좌표를 찾습니다. */
    private void applyPicked(PostcodeController.Address picked) {
        zonecodeField.setText(text(picked.zonecode()));
        addressField.setText(text(picked.preferred()));
        detailField.clear();

        // 건물 이름이 있으면 장소 이름을 비워 두지 않게 채워 줍니다 (사용자가 고쳐도 됩니다)
        if (labelField.getText().isBlank() && !text(picked.buildingName()).isBlank()) {
            labelField.setText(picked.buildingName());
        }
        Widgets.hideError(errorLabel);
        detailField.requestFocus();

        findCoordinates(picked.preferred());
    }

    /**
     * 주소 → 좌표. 네트워크를 타는 일이라 화면 스레드에서 하지 않습니다
     * (여기서 기다리면 창이 몇 초씩 멎습니다).
     */
    private void findCoordinates(String address) {
        if (!MapService.hasApiKey()) {
            markCoordTag("아직 없음", "neutral");
            hint("config.properties 의 map.api.key(카카오 REST API 키)를 채우면 주소에서 좌표를 자동으로 찾습니다. "
                    + "지금은 아래에서 대표 지점을 고르거나 좌표를 직접 넣어 주세요.");
            return;
        }

        markCoordTag("찾는 중…", "neutral");
        hint("카카오 지도 API에 좌표를 물어보는 중이에요…");

        Task<MapService.Coordinates> lookup = new Task<>() {
            @Override
            protected MapService.Coordinates call() throws Exception {
                return mapService.findCoordinates(address);
            }
        };
        lookup.setOnSucceeded(event -> {
            MapService.Coordinates found = lookup.getValue();
            if (found == null) {
                markCoordTag("못 찾음", "warn");
                hint("이 주소로는 좌표를 찾지 못했어요. 아래에서 대표 지점을 고르거나 직접 넣어 주세요.");
                return;
            }
            latField.setText(String.valueOf(found.latitude()));
            lngField.setText(String.valueOf(found.longitude()));
            // 좌표 칸이 바뀌면서 checkCoordinates 가 태그·안내를 정리합니다
        });
        lookup.setOnFailed(event -> {
            markCoordTag("못 찾음", "warn");
            Throwable error = lookup.getException();
            hint("좌표를 찾는 중 문제가 생겼어요: "
                    + (error == null ? "알 수 없는 오류" : error.getMessage()));
        });

        Thread worker = new Thread(lookup, "geocode");
        worker.setDaemon(true);
        worker.start();
    }

    // ── 대표 지점 (좌표 대신 채우기) ────────────────────────────

    /*
     * 우편번호 서비스는 주소만 주고 좌표는 주지 않습니다. 지도 API 키가 없는 팀원도 거리
     * 계산을 시험해 볼 수 있도록, 대표 지점의 좌표를 눌러 채우는 길을 남겨 뒀습니다.
     */

    private void renderPlaces() {
        placePane.getChildren().clear();
        for (DemoData.Place place : DemoData.PLACES) {
            Button chip = new Button(place.name());
            chip.getStyleClass().add("chip");
            chip.setOnAction(event -> pick(place));
            placePane.getChildren().add(chip);
        }
    }

    /** 누르는 순간 이름·주소·좌표가 대표 지점의 값으로 바뀝니다 — 저장은 아직입니다. */
    private void pick(DemoData.Place place) {
        labelField.setText(place.name());
        zonecodeField.clear();   // 검색으로 받은 우편번호가 아니므로 비웁니다
        addressField.setText(place.address());
        detailField.clear();
        latField.setText(String.valueOf(place.latitude()));
        lngField.setText(String.valueOf(place.longitude()));
        Widgets.hideError(errorLabel);
    }

    // ── 좌표 확인 ───────────────────────────────────────────────

    /*
     * 자동으로 채워진 값이든 직접 넣은 값이든 같은 자리에서 봐 줍니다. 직접 넣을 때는
     * 위도·경도를 바꿔 넣는 실수(37.5 ↔ 127.0)가 가장 흔해서 국내 범위로 한 번 더 거릅니다.
     */

    private void checkCoordinates() {
        Double latitude = LocationService.parseCoordinate(latField.getText());
        Double longitude = LocationService.parseCoordinate(lngField.getText());
        boolean latBlank = latField.getText() == null || latField.getText().isBlank();
        boolean lngBlank = lngField.getText() == null || lngField.getText().isBlank();

        Widgets.markField(latField, latBlank ? null : latitude != null);
        Widgets.markField(lngField, lngBlank ? null : longitude != null);

        if (latBlank && lngBlank) {
            markCoordTag("아직 없음", "neutral");
            hint(MapService.hasApiKey()
                    ? "주소를 고르면 좌표를 자동으로 찾습니다. 좌표가 없어도 주소만으로 등록할 수 있어요 (거리 계산에서만 빠집니다)."
                    : "config.properties 의 map.api.key(카카오 REST API 키)를 채우면 좌표가 자동으로 채워집니다.");
            return;
        }
        if (latitude == null || longitude == null) {
            markCoordTag("확인 필요", "warn");
            hint("위도와 경도를 둘 다 숫자로 넣어 주세요.");
            return;
        }
        if (!LocationService.isValidLatitude(latitude) || !LocationService.isValidLongitude(longitude)) {
            markCoordTag("확인 필요", "warn");
            hint("위도는 -90~90, 경도는 -180~180 사이여야 합니다.");
            return;
        }
        if (!LocationService.isInKorea(latitude, longitude)) {
            markCoordTag("확인 필요", "warn");
            hint("국내 좌표 범위를 벗어났어요 — 위도와 경도가 바뀌지 않았는지 확인해 주세요.");
            return;
        }
        markCoordTag("확인됨", "accent");
        hint("거리 계산에 이 좌표를 씁니다.");
    }

    // ── 저장 · 해제 ─────────────────────────────────────────────

    @FXML
    private void handleSave() {
        Double latitude = LocationService.parseCoordinate(latField.getText());
        Double longitude = LocationService.parseCoordinate(lngField.getText());
        boolean latBlank = latField.getText() == null || latField.getText().isBlank();
        boolean lngBlank = lngField.getText() == null || lngField.getText().isBlank();

        if (!latBlank || !lngBlank) {
            if (latitude == null || longitude == null) {
                Widgets.showError(errorLabel, "좌표는 위도와 경도를 둘 다 숫자로 넣어 주세요. "
                        + "둘 다 비우면 주소만으로 등록됩니다.");
                return;
            }
            if (!LocationService.isValidLatitude(latitude)
                    || !LocationService.isValidLongitude(longitude)) {
                Widgets.showError(errorLabel, "위도는 -90~90, 경도는 -180~180 사이여야 합니다.");
                return;
            }
        }

        String address = fullAddress();
        if (address.isEmpty() && (latitude == null || longitude == null)) {
            Widgets.showError(errorLabel,
                    "'주소 검색'으로 주소를 고르거나, 대표 지점을 눌러 좌표를 채워 주세요.");
            return;
        }

        String label = labelField.getText() == null ? "" : labelField.getText().trim();
        if (label.isEmpty() && address.isEmpty()) {
            label = "내 위치";   // 화면에 보여 줄 이름이 하나는 있어야 합니다
        }
        String zonecode = zonecodeField.getText() == null ? "" : zonecodeField.getText().trim();

        LocationDto location = new LocationDto(
                label.isEmpty() ? null : label,
                zonecode.isEmpty() ? null : zonecode,
                address.isEmpty() ? null : address,
                latitude, longitude);

        UserDto me = Session.getCurrentUser();
        if (me != null && !locationService.saveMyLocation(me.getId(), location)) {
            Widgets.showError(errorLabel, "저장 중 오류가 발생했습니다. 데이터베이스 연결을 확인해 주세요.");
            return;
        }

        Widgets.hideError(errorLabel);
        Session.setMyLocation(location);
        notifyCaller();
        AppNav.close(root);

        AppNav.success("위치를 등록했어요 — " + location.describe());
        if (!location.hasCoordinates()) {
            AppNav.warn("좌표가 없어 거리 계산에는 아직 쓰이지 않아요.");
        }
        if (me == null) {
            AppNav.info("게스트라 이 위치는 앱을 켜 둔 동안만 유지됩니다.");
        }
    }

    /** 도로명 주소 + 상세 주소를 한 줄로. 둘 다 비었으면 빈 문자열입니다. */
    private String fullAddress() {
        String road = addressField.getText() == null ? "" : addressField.getText().trim();
        String detail = detailField.getText() == null ? "" : detailField.getText().trim();
        if (road.isEmpty()) {
            return detail;
        }
        return detail.isEmpty() ? road : road + " " + detail;
    }

    @FXML
    private void handleClear() {
        if (!AppNav.confirm("등록한 위치를 지울까요?\n거리 조건과 중간 지점 계산에서 빠집니다.")) {
            return;
        }
        UserDto me = Session.getCurrentUser();
        if (me != null && !locationService.clearMyLocation(me.getId())) {
            Widgets.showError(errorLabel, "지우는 중 오류가 발생했습니다. 데이터베이스 연결을 확인해 주세요.");
            return;
        }

        Session.setMyLocation(null);
        notifyCaller();
        AppNav.close(root);
        AppNav.info("등록한 위치를 지웠어요");
    }

    @FXML
    private void handleCancel() {
        AppNav.close(root);
    }

    private void notifyCaller() {
        if (onSaved != null) {
            onSaved.run();
        }
    }

    // ── 안내 문구 ───────────────────────────────────────────────

    private void markCoordTag(String text, String variant) {
        coordTag.setText(text);
        coordTag.getStyleClass().removeAll("accent", "neutral", "warn");
        coordTag.getStyleClass().add(variant);
    }

    private void hint(String message) {
        coordHint.setText(message);
    }
}
