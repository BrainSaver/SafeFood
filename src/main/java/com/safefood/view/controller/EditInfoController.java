package com.safefood.view.controller;

import com.safefood.dto.Session;
import com.safefood.dto.UserDto;
import com.safefood.service.AuthService;
import com.safefood.view.AppNav;
import com.safefood.view.Widgets;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * 정보 변경 — 닉네임 중복 확인을 버튼에서 실시간 검증으로 옮겼습니다 (회원가입 1e 와 같은 방식).
 */
public class EditInfoController {

    private static final Duration SETTLE = Duration.millis(400);

    @FXML private VBox root;
    @FXML private PasswordField currentPassword;
    @FXML private TextField nicknameField;
    @FXML private Label nicknameHint;
    @FXML private PasswordField newPassword;
    @FXML private Label errorLabel;
    @FXML private VBox withdrawBox;
    @FXML private Button withdrawButton;
    @FXML private Button saveButton;
    @FXML private Button cancelButton;

    private final AuthService authService = new AuthService();
    private final PauseTransition nicknameSettle = new PauseTransition(SETTLE);

    /** 마지막 조회 결과 — 조회 전이면 null. 지금 쓰는 닉네임이면 true 로 봅니다. */
    private Boolean nicknameAvailable;

    /** 저장이 끝났음을 부른 화면에 알립니다 (프로필이 닉네임 라벨을 다시 그립니다). */
    private Runnable onSaved;

    public void setOnSaved(Runnable onSaved) {
        this.onSaved = onSaved;
    }

    @FXML
    private void initialize() {
        UserDto me = Session.getCurrentUser();
        if (me != null) {
            nicknameField.setText(me.getNickname());
            nicknameAvailable = true;   // 처음 값은 자기 것이라 그대로 둬도 됩니다
        }

        boolean member = Session.isMember();
        withdrawBox.setVisible(member);
        withdrawBox.setManaged(member);

        nicknameSettle.setOnFinished(event -> checkNickname());
        nicknameField.textProperty().addListener((observable, before, after) -> {
            nicknameAvailable = null;
            Widgets.markField(nicknameField, null);
            hide(nicknameHint);
            nicknameSettle.playFromStart();
        });
    }

    private void checkNickname() {
        String nickname = nicknameField.getText().trim();
        if (nickname.isBlank()) {
            return;
        }
        UserDto me = Session.getCurrentUser();

        // 내 원래 닉네임을 그대로 둔 경우는 중복이 아닙니다
        if (me != null && nickname.equals(me.getNickname())) {
            nicknameAvailable = true;
            hint(true, "지금 쓰고 있는 닉네임이에요");
            return;
        }

        nicknameAvailable = authService.isNicknameAvailable(nickname);
        hint(nicknameAvailable, nicknameAvailable
                ? "✓ 사용할 수 있어요" : "이미 누군가 쓰고 있어요");
    }

    @FXML
    private void handleSave() {
        String currentPw = currentPassword.getText();
        String newPw = newPassword.getText();
        String newNick = nicknameField.getText().trim();

        if (currentPw.isBlank()) {
            Widgets.showError(errorLabel, "현재 비밀번호를 입력해 주세요.");
            return;
        }
        if (newNick.isBlank()) {
            Widgets.showError(errorLabel, "닉네임을 입력해 주세요.");
            return;
        }

        UserDto me = Session.getCurrentUser();

        // 비밀번호 확인
        if (!authService.verifyPassword(currentPw, me.getPassword())) {
            Widgets.showError(errorLabel, "현재 비밀번호가 틀렸습니다.");
            return;
        }

        // 검증이 아직 안 돌았으면(빨리 눌렀거나 대기 중) 여기서 한 번에 확인합니다
        if (nicknameAvailable == null) {
            nicknameSettle.stop();
            checkNickname();
        }
        if (Boolean.FALSE.equals(nicknameAvailable)) {
            Widgets.showError(errorLabel, "이미 사용 중인 닉네임입니다. 다른 닉네임을 골라 주세요.");
            return;
        }

        // 세션 사본(닉네임·비밀번호 해시)까지 updateProfile 이 맞춥니다 — 여기서 손대면
        // 저장된 해시 대신 사용자가 입력한 평문이 들어가 이후 비밀번호 확인이 전부 실패합니다.
        if (!authService.updateProfile(me, newPw, newNick)) {
            Widgets.showError(errorLabel, "저장 중 오류가 발생했습니다.");
            return;
        }

        Widgets.hideError(errorLabel);
        if (onSaved != null) {
            onSaved.run();
        }
        AppNav.close(root);
        AppNav.success("개인정보를 변경했어요");
    }

    @FXML
    private void handleWithdraw() {
        UserDto me = Session.getCurrentUser();
        if (!Session.isMember() || me == null) {
            Widgets.showError(errorLabel, "게스트는 탈퇴할 계정이 없습니다.");
            return;
        }

        String currentPw = currentPassword.getText();
        if (currentPw.isBlank()) {
            Widgets.showError(errorLabel, "탈퇴하려면 현재 비밀번호를 입력해 주세요.");
            return;
        }
        if (!authService.verifyPassword(currentPw, me.getPassword())) {
            Widgets.showError(errorLabel, "현재 비밀번호가 틀렸습니다.");
            return;
        }
        if (!AppNav.confirm("정말 탈퇴할까요?\n\n"
                + "등록한 알레르기·취향·위치와 식사 기록, 찜, 평가가 모두 지워집니다.\n"
                + "되돌릴 수 없습니다.")) {
            return;
        }

        Widgets.hideError(errorLabel);
        setBusy(true);

        int userId = me.getId();
        String savedHash = me.getPassword();
        Task<Boolean> withdraw = new Task<>() {
            @Override
            protected Boolean call() {
                return authService.deleteAccount(userId, currentPw, savedHash);
            }
        };
        withdraw.setOnSucceeded(event -> {
            if (!Boolean.TRUE.equals(withdraw.getValue())) {
                setBusy(false);
                Widgets.showError(errorLabel,
                        "탈퇴 처리에 실패했습니다. 연결을 확인한 뒤 다시 시도해 주세요.");
                return;
            }
            AppNav.close(root);
            Platform.runLater(() -> {
                AppNav.logout();
                AppNav.info("탈퇴가 완료되었습니다. 그동안 이용해 주셔서 감사합니다.");
            });
        });
        withdraw.setOnFailed(event -> {
            setBusy(false);
            Throwable error = withdraw.getException();
            Widgets.showError(errorLabel, "탈퇴 처리 중 오류가 발생했습니다: "
                    + (error == null ? "알 수 없는 오류" : error.getMessage()));
        });

        Thread worker = new Thread(withdraw, "withdraw-account");
        worker.setDaemon(true);
        worker.start();
    }

    private void setBusy(boolean busy) {
        withdrawButton.setDisable(busy);
        withdrawButton.setText(busy ? "탈퇴 처리 중…" : "계정 탈퇴");
        saveButton.setDisable(busy);
        cancelButton.setDisable(busy);
        currentPassword.setDisable(busy);
        nicknameField.setDisable(busy);
        newPassword.setDisable(busy);
    }

    @FXML
    private void handleCancel() {
        AppNav.close(root);
    }

    private void hint(boolean ok, String text) {
        Widgets.markField(nicknameField, ok);
        nicknameHint.getStyleClass().removeAll("hint-ok", "hint-bad");
        nicknameHint.getStyleClass().add(ok ? "hint-ok" : "hint-bad");
        nicknameHint.setText(text);
        nicknameHint.setVisible(true);
        nicknameHint.setManaged(true);
    }

    private static void hide(Label label) {
        label.setVisible(false);
        label.setManaged(false);
    }
}
