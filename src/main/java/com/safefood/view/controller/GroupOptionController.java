package com.safefood.view.controller;

import com.safefood.dto.Session;
import com.safefood.network.GroupClient;
import com.safefood.network.GroupServer;
import com.safefood.network.GroupSession;
import com.safefood.network.InviteCode;
import com.safefood.network.Message;
import com.safefood.network.RoomFinder;
import com.safefood.network.SocketConfig;
import com.safefood.view.AppNav;
import com.safefood.view.Widgets;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Optional;

/**
 * 같이 먹기 — 방 만들기와 참여를 한 창에서 처리합니다.
 *
 * <p>서버는 창을 열 때가 아니라 <b>방 만들기를 누를 때</b> 엽니다 — 참여만 하려고 들어온 사람이
 * 포트를 잡고 탐색 비컨을 띄우지 않게 하기 위해서입니다.
 */
public class GroupOptionController {

    @FXML private VBox root;

    // 방 만들기
    @FXML private VBox createIntro;
    @FXML private VBox createdBox;
    @FXML private Label hostTag;
    @FXML private Button createButton;
    @FXML private TextField codeField;
    @FXML private Label addressLabel;
    @FXML private Label discoveryLabel;
    @FXML private Button copyButton;

    // 참여
    @FXML private VBox joinBox;
    @FXML private TextField joinField;
    @FXML private Button joinButton;
    @FXML private Label previewLabel;
    @FXML private Label errorLabel;
    @FXML private ProgressIndicator progress;

    private final GroupSession session = GroupSession.get();

    // 접속됐지만 서버가 JOINED/ERROR를 안 주는 경우 대비. 접속·탐색에 걸린 시간이 섞이지 않도록
    // 소켓이 붙은 뒤에 시작합니다.
    private final PauseTransition responseTimeout = new PauseTransition(Duration.seconds(5));

    // 접속·탐색은 배경 스레드에서 몇 초씩 도는데 그 사이에 창을 닫을 수 있습니다.
    // 닫힌 화면에 소켓을 새로 물리지 않도록 각 단계에서 확인합니다.
    private volatile boolean cancelled;

    @FXML
    private void initialize() {
        // X로 창을 닫아도 서버·소켓·응답 대기가 몰래 남지 않게 — 버튼으로 닫을 때는 실행되지 않습니다
        AppNav.onDialogClosed(root, () -> {
            cancelled = true;
            responseTimeout.stop();   // 닫힌 창의 타이머가 나중에 새 세션을 끄지 않도록
            session.shutdown();
        });

        responseTimeout.setOnFinished(event -> {
            session.shutdown();
            fail("방장이 응답하지 않습니다. 코드가 방장 화면의 값과 같은지 확인해 주세요.");
        });

        // 소문자·O/I/L 혼동은 입력 즉시 교정하고, 붙임표는 다섯 자마다 알아서 끼워 넣습니다
        joinField.setTextFormatter(new TextFormatter<>(GroupOptionController::reformat));
        joinField.textProperty().addListener((observable, old, text) -> updatePreview(text));
        updatePreview("");
    }

    /**
     * 초대 코드 입력칸의 서식 — 무엇을 치든 {@code XXXXX-XXXXX-XXXX} 모양으로 정리합니다.
     *
     * <p>붙임표는 쳐도 되고 안 쳐도 됩니다. 어차피 다섯 자마다 다시 넣어 주므로, 받은 코드를
     * 보이는 대로 옮겨 적어도 되고 5-5-4 로 적힌 코드를 통째로 붙여 넣어도 됩니다.
     */
    private static TextFormatter.Change reformat(TextFormatter.Change change) {
        if (!change.isContentChange()) {
            return change;
        }

        String proposed = change.getControlNewText();
        int caret = Math.max(0, Math.min(change.getCaretPosition(), proposed.length()));
        String code = InviteCode.normalize(proposed);
        int typed = InviteCode.normalize(proposed.substring(0, caret)).length();

        // 붙임표만 지운 경우 — 서식이 곧바로 도로 넣으므로 앞의 코드 글자까지 지웁니다.
        // 그러지 않으면 백스페이스를 눌러도 아무 일이 없는 것처럼 보입니다.
        if (typed > 0 && removedOnlySeparators(change)) {
            code = code.substring(0, typed - 1) + code.substring(typed);
            typed--;
        }

        if (code.length() > InviteCode.LENGTH) {
            return null;   // 정해진 길이를 넘기는 입력은 아예 받지 않습니다
        }

        String formatted = InviteCode.format(code);
        change.setRange(0, change.getControlText().length());
        change.setText(formatted);
        int at = InviteCode.caretAfter(formatted, typed);
        change.selectRange(at, at);
        return change;
    }

    /** 지운 자리에 코드 글자는 없고 붙임표·공백뿐이었는지. */
    private static boolean removedOnlySeparators(TextFormatter.Change change) {
        if (!change.isDeleted() || !change.getText().isEmpty()) {
            return false;
        }
        String removed = change.getControlText()
                .substring(change.getRangeStart(), change.getRangeEnd());
        return InviteCode.normalize(removed).isEmpty();
    }

    /** 로그인 화면에서 이미 받아 둔 코드를 채워 둡니다 — 게스트가 두 번 적지 않게. */
    public void prefillCode(String code) {
        if (code == null || code.isBlank()) {
            return;
        }
        joinField.setText(code);   // 포매터가 교정하고, 리스너가 미리보기를 채웁니다
        joinField.requestFocus();
    }

    /**
     * 방에 들어가기 직전에 표시 이름을 세션에서 다시 가져옵니다.
     *
     * <p>{@link GroupSession} 이 들고 있는 이름은 <b>로그인할 때 떠 둔 사본</b>입니다. 그래서 정보
     * 변경에서 닉네임을 바꿔도 사본은 예전 이름 그대로였고, 같이 먹기에 들어가면 일행에게
     * <b>바꾸기 전 닉네임</b>이 보였습니다. 닉네임이 바뀌는 자리마다 사본을 맞추는 방식은
     * 새 경로가 생길 때마다 다시 새므로, 사본이 실제로 쓰이는 이 <b>한 자리</b>에서 맞춥니다.
     *
     * <p>맞추는 것은 <b>JOIN 을 보내기 전까지</b>입니다 — 서버는 이때 실어 보낸 이름으로 사람을
     * 구분하므로, 들어간 뒤에는 사본이 그대로여야 {@code JOINED} 확인과 채팅의 '내 말' 판정이
     * 맞습니다. 그래서 방을 여는 · 참여하는 입구에서만 부르고, 방 안에서는 부르지 않습니다.
     *
     * <p>세션이 비어 있으면(로그인 전) 지금 사본을 그대로 둡니다 — {@code SocketMain} 처럼 화면
     * 없이 이름을 직접 넣어 쓰는 쪽의 값을 지우지 않기 위해서입니다.
     */
    private void syncDisplayName() {
        session.setDisplayName(Session.displayName(session.displayName()));
    }

    // ══ 방 만들기 ═══════════════════════════════════════════════

    @FXML
    private void handleCreate() {
        syncDisplayName();
        InetAddress host = GroupServer.lanAddress();
        int port = SocketConfig.port();

        // 주소를 코드에 담습니다 — 서버를 열기 전에 먼저 확인해, 실패하면 포트를 잡지 않습니다
        String inviteCode;
        try {
            inviteCode = InviteCode.issue(host, port);
        } catch (IllegalArgumentException e) {
            AppNav.error("초대 코드를 만들지 못했습니다.\n\n원인: " + e.getMessage());
            return;
        }

        try {
            session.hostRoom(port, inviteCode);
        } catch (IOException e) {
            AppNav.error("방(서버)을 열지 못했습니다.\n"
                    + "포트 " + port + "이(가) 이미 사용 중이라면 config.properties의 "
                    + "socket.port 값을 바꾼 뒤 다시 시도하세요.\n\n원인: " + e.getMessage());
            return;
        }
        // 방장도 자기 서버에 클라이언트로 접속 — 참여자와 화면 로직이 같아집니다
        session.client().join(inviteCode, session.displayName());

        codeField.setText(InviteCode.format(inviteCode));
        addressLabel.setText("같은 와이파이에서만 접속돼요 · 주소 "
                + host.getHostAddress() + ":" + port + " (코드에 포함)");

        discoveryLabel.setText(session.server().discoverable()
                ? "이 PC의 주소가 바뀌어도 같은 네트워크라면 참여자가 이 코드로 방을 다시 찾아냅니다."
                : "자동 탐색을 켜지 못했습니다. 주소가 바뀌면 방을 다시 만들어 새 코드를 전달하세요.");

        showCreated();
    }

    /** 카드를 코드 상태로 바꾸고, 이제 필요 없는 참여 쪽을 잠급니다. */
    private void showCreated() {
        show(createIntro, false);
        show(createdBox, true);
        show(hostTag, true);
        joinBox.setDisable(true);
        previewLabel.setText("이미 방장입니다. 참여하려면 창을 닫고 다시 여세요.");
    }

    @FXML
    private void handleCopyCode() {
        ClipboardContent content = new ClipboardContent();
        content.putString(codeField.getText());
        Clipboard.getSystemClipboard().setContent(content);
        AppNav.success("초대 코드를 복사했어요 — 일행에게 그대로 전달하세요");
    }

    @FXML
    private void handleEnter() {
        if (!session.isConnected()) {
            AppNav.warn("서버가 열려 있지 않습니다. 창을 닫고 다시 시도해 주세요.");
            return;
        }
        AppNav.close(root);
        AppNav.dialog("그룹 조건 확인", "group-condition.fxml");
    }

    // ══ 참여 ════════════════════════════════════════════════════

    /** 입력 중 실시간 안내 — 다 입력하면 코드에서 꺼낸 방장 주소를 보여 줍니다. */
    private void updatePreview(String text) {
        String code = InviteCode.normalize(text);
        if (code.length() < InviteCode.LENGTH) {
            previewLabel.setText(code.isEmpty()
                    ? "전달받은 초대 코드를 입력하세요."
                    : code.length() + " / " + InviteCode.LENGTH + "자");
            previewLabel.getStyleClass().remove("error-text");
            return;
        }
        try {
            previewLabel.setText("✓ 코드 확인 — 방장 주소 " + InviteCode.parse(code).address()
                    + " 을(를) 찾았어요");
            previewLabel.getStyleClass().remove("error-text");
        } catch (IllegalArgumentException e) {
            previewLabel.setText(e.getMessage());
            if (!previewLabel.getStyleClass().contains("error-text")) {
                previewLabel.getStyleClass().add("error-text");
            }
        }
    }

    @FXML
    private void handleJoin() {
        syncDisplayName();
        InviteCode invite;
        try {
            invite = InviteCode.parse(joinField.getText());
        } catch (IllegalArgumentException e) {
            Widgets.showError(errorLabel, e.getMessage());
            return;
        }

        Widgets.hideError(errorLabel);
        cancelled = false;
        setBusy(true);

        // 접속도 탐색도 몇 초씩 걸릴 수 있어 UI 스레드 밖에서 시도합니다
        Thread connector = new Thread(() -> connect(invite), "join-room-connect");
        connector.setDaemon(true);
        connector.start();
    }

    /**
     * 접속 두 단계 — 코드의 주소로 바로 붙어 보고, 안 되면 같은 네트워크에서 방을 찾습니다.
     * 탐색을 먼저 하면 모든 참여자가 매번 몇 초씩 기다리고 방화벽 확인 창까지 보게 되므로,
     * 실제로 필요할 때만 켭니다. 화면을 건드릴 때만 {@link Platform#runLater}로 넘깁니다.
     */
    private void connect(InviteCode invite) {
        try {
            open(invite.host(), invite.port(), invite.code());
            return;   // 1차 성공 — 이후는 JOINED/ERROR 응답이 판정합니다
        } catch (IOException direct) {
            if (cancelled) {
                return;
            }
            Platform.runLater(() -> status("코드에 담긴 주소(" + invite.address() + ")에 닿지 않았습니다."
                    + " 같은 네트워크에서 방을 찾는 중…"));
        }

        Optional<InetSocketAddress> found = RoomFinder.find(invite.code());
        if (cancelled) {
            return;
        }
        if (found.isEmpty()) {
            Platform.runLater(() -> fail("방을 찾지 못했습니다.\n"
                    + "방이 아직 열려 있는지, 방장과 같은 와이파이에 있는지 확인해 주세요.\n"
                    + "(코드에 담긴 주소 " + invite.address() + " 에도 닿지 않았습니다.)"));
            return;
        }

        InetSocketAddress host = found.get();
        String moved = host.getAddress().getHostAddress() + ":" + host.getPort();
        Platform.runLater(() -> status("방을 찾았습니다 — " + moved + " 로 접속합니다."));
        try {
            open(host.getAddress().getHostAddress(), host.getPort(), invite.code());
        } catch (IOException e) {
            Platform.runLater(() -> fail(moved + " 에서 방을 찾았지만 접속하지 못했습니다.\n("
                    + e.getMessage() + ")"));
        }
    }

    /** 소켓 접속 → 리스너 부착 → JOIN 전송. 여기까지가 배경 스레드의 몫입니다. */
    private void open(String host, int port, String code) throws IOException {
        session.joinRoom(host, port);
        if (cancelled) {
            session.shutdown();   // 붙는 사이에 창이 닫혔습니다 — 방금 연 소켓을 도로 정리합니다
            return;
        }
        session.setInviteCode(code);
        session.setUiListener(joinResultListener());
        // JOIN을 보내기 전에 걸어 두므로, 응답이 아무리 빨라도 playFromStart → succeed 순서가 지켜집니다
        Platform.runLater(responseTimeout::playFromStart);
        session.client().join(code, session.displayName());
    }

    private GroupClient.Listener joinResultListener() {
        return new GroupClient.Listener() {
            @Override
            public void onMessage(Message message) {
                if (message.type() == Message.Type.JOINED
                        && message.part(0).equals(session.displayName())) {
                    Platform.runLater(GroupOptionController.this::succeed);
                } else if (message.type() == Message.Type.ERROR) {
                    Platform.runLater(() -> {
                        session.shutdown();
                        fail(message.part(0));
                    });
                }
            }

            @Override
            public void onDisconnected() {
                Platform.runLater(() -> {
                    session.shutdown();
                    fail("서버와의 연결이 끊겼습니다.");
                });
            }
        };
    }

    private void succeed() {
        responseTimeout.stop();
        if (!session.isConnected()) {
            return;   // 응답 직전에 X로 취소한 경우 — 죽은 세션으로 진행하지 않습니다
        }
        setBusy(false);
        session.setUiListener(null);   // 다음 화면이 자기 리스너를 답니다
        AppNav.close(root);
        AppNav.dialog("그룹 조건 확인", "group-condition.fxml");
    }

    private void status(String text) {
        previewLabel.getStyleClass().remove("error-text");
        previewLabel.setText(text);
    }

    private void fail(String reason) {
        responseTimeout.stop();
        setBusy(false);
        updatePreview(joinField.getText());
        Widgets.showError(errorLabel, reason);
    }

    private void setBusy(boolean busy) {
        joinButton.setDisable(busy);
        joinField.setDisable(busy);
        createButton.setDisable(busy);   // 접속 중에 방을 열면 세션이 엎어집니다
        show(progress, busy);
    }

    private static void show(javafx.scene.Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }
}
