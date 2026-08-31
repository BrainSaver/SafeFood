package com.safefood.view;

import com.safefood.dto.Session;
import com.safefood.network.GroupSession;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.TranslateTransition;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class AppNav {

    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;

    private static final String BASE = "/com/safefood/view/";
    private static final String CSS = BASE + "app.css";

    private static final String ICON = BASE + "logo.png";
    private static Image appIcon;

    /** 토스트가 쌓이는 층. 모든 Scene 의 루트 StackPane 이 하나씩 갖습니다. */
    private static final String TOAST_LAYER_ID = "toast-layer";

    /** 한 번에 보여 줄 최대 개수 — 넘치면 오래된 것부터 밀어냅니다. */
    private static final int MAX_TOASTS = 3;

    private static final Duration TOAST_IN = Duration.millis(180);
    private static final Duration TOAST_OUT = Duration.millis(240);

    private static Stage stage;
    private static Scene scene;

    /** {@link #init} 에서 받아 둡니다 — {@link #openExternal} 이 씁니다. */
    private static HostServices hostServices;

    /** 지금 고른 앱 스타일 — {@link #theme()} 이 처음 불릴 때 저장된 값에서 채워집니다. */
    private static Theme theme;

    private AppNav() {
    }

    /**
     * @param services 바깥 브라우저를 여는 통로 ({@link #openExternal}). {@code Application}
     *                 에서만 얻을 수 있어 여기로 넘겨받습니다 — null 이면 링크 열기만 막힙니다
     */
    public static void init(Stage primaryStage, HostServices services) {
        stage = primaryStage;
        hostServices = services;
        stage.setMinWidth(1000);
        stage.setMinHeight(640);
        applyIcon(stage);
    }

    /**
     * 기본 브라우저로 주소를 엽니다 — 길찾기처럼 앱 밖으로 넘기는 자리에 씁니다.
     *
     * <p>열지 못해도 <b>화면은 그대로 둡니다</b>. 브라우저가 없는 환경에서 창이 죽는 것보다
     * 안내만 띄우는 편이 낫습니다.
     */
    public static void openExternal(String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        if (hostServices == null) {
            System.err.println("[ui] HostServices 가 없어 링크를 열지 못했습니다: " + url);
            error("링크를 열 수 없는 상태입니다.");
            return;
        }
        try {
            hostServices.showDocument(url);
        } catch (RuntimeException e) {
            System.err.println("[ui] 링크를 열지 못했습니다: " + e.getMessage());
            error("브라우저를 열지 못했습니다.");
        }
    }

    /** {@link #init}보다 먼저 뜨거나 따로 만든 창에서도 직접 부르면 됩니다. */
    public static void applyIcon(Stage target) {
        if (appIcon == null) {
            try (InputStream source = AppNav.class.getResourceAsStream(ICON)) {
                if (source != null) {
                    appIcon = new Image(source);
                }
            } catch (IOException e) {
                System.err.println("[ui] 앱 아이콘을 읽지 못했습니다: " + e.getMessage());
            }
        }
        if (appIcon != null && !target.getIcons().contains(appIcon)) {
            target.getIcons().add(appIcon);
        }
    }

    public static Parent load(String fxml) {
        return read(loader(fxml));
    }

    public static <C> Parent load(String fxml, Consumer<C> initializer) {
        FXMLLoader loader = loader(fxml);
        Parent root = read(loader);
        initializer.accept(loader.getController());
        return root;
    }

    private static FXMLLoader loader(String fxml) {
        URL url = AppNav.class.getResource(BASE + fxml);
        if (url == null) {

            throw new IllegalStateException(
                    BASE + fxml + "을 찾지 못했습니다. src/main/resources 아래에 있는지 확인하세요.");
        }
        return new FXMLLoader(url);
    }

    private static Parent read(FXMLLoader loader) {
        try {
            return loader.load();
        } catch (IOException e) {

            throw new UncheckedIOException(
                    "FXML을 읽지 못했습니다: " + loader.getLocation(), e);
        }
    }

    public static void show(String title, String fxml) {
        showRoot(title, load(fxml));
    }

    private static void showRoot(String title, Parent root) {
        if (scene == null) {
            scene = new Scene(withToastLayer(root), WIDTH, HEIGHT);
            applyCss(scene);
            stage.setScene(scene);
        } else {
            // 루트를 통째로 갈지 않고 화면만 갈아 끼웁니다 — 토스트 층이 살아남아야 합니다
            ((StackPane) scene.getRoot()).getChildren().set(0, root);
        }
        stage.setTitle(title);
        if (!stage.isShowing()) {
            stage.show();
        }
    }

    /** 로그아웃 — 화면마다 따로 정리하다 빠뜨리지 않도록 나가는 길을 여기 하나로 모읍니다. */
    public static void logout() {
        Session.logout();
        GroupSession.get().setGuest(false);
        show("SafeFood 로그인", "login.fxml");
    }

    /**
     * ⚠️ 여는 시점을 {@code Platform.runLater}로 미루는 것은 필수입니다.
     * {@code close(root); dialog(...)}처럼 이전 다이얼로그를 닫는 중에 다음 {@code showAndWait}
     * 중첩 루프를 쌓으면 JavaFX가 렌더 펄스까지 멈춰, 새 창이 제목만 뜨고 하얗게 나옵니다.
     */
    public static void dialog(String title, String fxml) {
        Platform.runLater(() -> dialogRoot(title, load(fxml)));
    }

    public static <C> void dialog(String title, String fxml, Consumer<C> initializer) {
        Platform.runLater(() -> dialogRoot(title, load(fxml, initializer)));
    }

    private static void dialogRoot(String title, Parent root) {
        Stage modal = new Stage();
        modal.initOwner(stage);
        modal.initModality(Modality.APPLICATION_MODAL);
        modal.setTitle(title);

        // 다이얼로그도 자기 토스트 층을 갖습니다 — 안 그러면 '복사되었습니다' 같은 알림이
        // 모달 뒤(본 창)에 떠서 보이지 않습니다
        Scene modalScene = new Scene(withToastLayer(root));
        applyCss(modalScene);
        modal.setScene(modalScene);
        modal.setResizable(false);
        followContent(modal, root);
        modal.showAndWait();
    }

    /**
     * 내용이 길어지거나 짧아지면 창도 따라 커지게 합니다.
     * 다이얼로그는 {@code setResizable(false)}라 이게 없으면 처음 잰 크기로 굳어,
     * 나중에 펼쳐지는 영역(초대 코드·오류 문구·조건 편집)이 잘려 나갑니다.
     */
    private static void followContent(Stage dialog, Parent content) {
        if (content instanceof Region region) {
            new ContentFit(dialog, region).watch();
        }
    }

    /**
     * 내용이 원하는 크기가 달라질 때마다 창을 다시 맞춥니다.
     *
     * <p>⚠️ 견주는 값은 창의 실제 크기가 아니라 <b>마지막으로 맞춰 준 값</b>입니다.
     * 화면 배율이 100%가 아니면 창 크기가 요청값과 몇 px 어긋나는데, 실제 크기와 견주면
     * 그 차이가 사라지지 않아 배치마다 창을 다시 잡는 되돌이에 빠집니다.
     */
    private static final class ContentFit {

        private final Stage dialog;
        private final Region content;

        private double fittedWidth = -1;
        private double fittedHeight = -1;

        ContentFit(Stage dialog, Region content) {
            this.dialog = dialog;
            this.content = content;
        }

        void watch() {
            // needsLayout 은 자식이 배치를 요청한 순간 켜집니다. 실제 크기는 그 배치가 끝나야
            // 나오므로, 재는 일은 runLater 로 한 박자 미룹니다.
            content.needsLayoutProperty().addListener((property, was, needsLayout) -> {
                if (needsLayout) {
                    Platform.runLater(this::refit);
                }
            });
        }

        private void refit() {
            if (!dialog.isShowing()) {
                return;
            }
            double width = content.prefWidth(-1);
            double height = content.prefHeight(width);
            if (Math.abs(width - fittedWidth) < 1 && Math.abs(height - fittedHeight) < 1) {
                return;   // 지난번에 맞춰 준 그 크기 그대로입니다
            }
            fittedWidth = width;
            fittedHeight = height;

            double before = dialog.getHeight();
            dialog.sizeToScene();

            // 늘어난 만큼을 위아래로 나눠 가집니다 — 아래로만 자라면 창이 화면 밖으로 밀려납니다.
            // 다만 지금 놓인 모니터의 위쪽 경계는 넘지 않게 (제목 표시줄이 잘리지 않도록).
            double top = Screen.getScreensForRectangle(dialog.getX(), dialog.getY(),
                    dialog.getWidth(), dialog.getHeight()).get(0).getVisualBounds().getMinY();
            dialog.setY(Math.max(top, dialog.getY() - (dialog.getHeight() - before) / 2));
        }
    }

    public static void close(Node nodeInDialog) {
        if (nodeInDialog != null && nodeInDialog.getScene() != null
                && nodeInDialog.getScene().getWindow() != null) {
            nodeInDialog.getScene().getWindow().hide();
        }
    }

    /**
     * 다이얼로그가 창 닫기(X)로 닫힐 때 실행할 정리 동작을 등록합니다.
     * 코드로 {@link #close(Node)}를 부른 경우(hide)에는 실행되지 않습니다 —
     * 버튼으로 닫을 때와 X로 닫을 때의 정리를 다르게 하고 싶을 때 쓰세요.
     */
    public static void onDialogClosed(Node root, Runnable action) {
        root.sceneProperty().addListener((sceneObs, oldScene, scene) -> {
            if (scene == null) {
                return;
            }
            if (scene.getWindow() != null) {
                scene.getWindow().setOnCloseRequest(event -> action.run());
            } else {
                scene.windowProperty().addListener((winObs, oldWin, window) -> {
                    if (window != null) {
                        window.setOnCloseRequest(event -> action.run());
                    }
                });
            }
        });
    }

    // ══ 알림 — 토스트 (답을 받아야 하는 confirm()만 모달로 남습니다) ══

    public static void info(String message) {
        toast(message, "info");
    }

    public static void warn(String message) {
        toast(message, "warn");
    }

    public static void error(String message) {
        toast(message, "error");
    }

    /** 좋은 소식용 */
    public static void success(String message) {
        toast(message, "good");
    }

    private static void toast(String message, String variant) {
        // 수신 스레드에서 부르는 자리가 있어 UI 스레드로 넘깁니다.
        // 미루는 김에 close() 직후의 호출도 안전해집니다 — 창이 다 닫힌 뒤에 자리를 고릅니다.
        Platform.runLater(() -> showToast(message, variant));
    }

    private static void showToast(String message, String variant) {
        VBox layer = activeToastLayer();
        if (layer == null) {
            alert(Alert.AlertType.INFORMATION, "안내", message);   // 띄울 자리가 없으면 예전 방식으로
            return;
        }

        Label card = new Label(message);
        card.getStyleClass().addAll("toast", variant);
        card.setWrapText(true);
        card.setMaxWidth(440);
        card.setOpacity(0);
        card.setTranslateY(16);

        while (layer.getChildren().size() >= MAX_TOASTS) {
            layer.getChildren().remove(0);
        }
        layer.getChildren().add(card);

        FadeTransition fadeIn = new FadeTransition(TOAST_IN, card);
        fadeIn.setToValue(1);
        TranslateTransition riseIn = new TranslateTransition(TOAST_IN, card);
        riseIn.setToY(0);
        riseIn.play();

        PauseTransition stay = new PauseTransition(readingTime(message));

        FadeTransition fadeOut = new FadeTransition(TOAST_OUT, card);
        fadeOut.setToValue(0);

        SequentialTransition life = new SequentialTransition(fadeIn, stay, fadeOut);
        life.setOnFinished(event -> layer.getChildren().remove(card));
        life.play();

        // 눌러서 바로 치우기 — 긴 오류 문구를 다 읽은 사람이 기다리지 않아도 되게
        card.setOnMouseClicked(event -> {
            life.stop();
            layer.getChildren().remove(card);
        });
    }

    /** 글이 길수록 오래 머뭅니다 — 2.5초에서 시작해 8초까지. */
    private static Duration readingTime(String message) {
        double millis = 2500 + message.length() * 45.0;
        return Duration.millis(Math.min(millis, 8000));
    }

    /** 모달이 떠 있으면 그 위에 얹어야 보이므로 <b>포커스를 가진 창</b>을 기준으로 찾습니다. */
    private static VBox activeToastLayer() {
        Window target = null;
        for (Window window : Window.getWindows()) {
            if (window.isFocused() && window.isShowing()) {
                target = window;
                break;
            }
        }
        if (target == null) {
            target = stage;
        }
        if (target == null || target.getScene() == null) {
            return null;
        }
        Node found = target.getScene().getRoot().lookup("#" + TOAST_LAYER_ID);
        return found instanceof VBox layer ? layer : null;
    }

    /** 화면을 토스트 층과 함께 StackPane 에 담습니다. 화면이 아래, 토스트가 위. */
    private static Parent withToastLayer(Parent content) {
        VBox layer = new VBox(8);
        layer.setId(TOAST_LAYER_ID);
        layer.getStyleClass().add("toast-layer");
        layer.setAlignment(Pos.BOTTOM_CENTER);
        layer.setPickOnBounds(false);   // 층이 클릭을 가로채지 않게
        layer.setPrefSize(0, 0);        // 창 크기는 화면 내용만 정하게

        return new StackPane(content, layer);
    }

    // ══ 확인 — 답을 받아야 해서 모달로 남습니다 ══════════════════

    public static boolean confirm(String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.YES, ButtonType.NO);
        decorate(alert, "확인");
        return alert.showAndWait().filter(ButtonType.YES::equals).isPresent();
    }

    private static void alert(Alert.AlertType type, String header, String message) {
        Alert alert = new Alert(type, message, ButtonType.OK);
        decorate(alert, header);
        alert.showAndWait();
    }

    private static void decorate(Alert alert, String header) {
        alert.setTitle("SafeFood");
        alert.setHeaderText(header);
        if (stage != null) {
            alert.initOwner(stage);
        }
        applyCss(alert.getDialogPane().getScene());
    }

    // ══ 앱 스타일 (테마) ════════════════════════════════════════

    /** 지금 쓰는 앱 스타일. 처음 물어볼 때 지난번에 고른 값을 읽어 옵니다. */
    public static Theme theme() {
        if (theme == null) {
            theme = Theme.saved();
        }
        return theme;
    }

    /** 지금 떠 있는 창 전부에 곧바로 반영되고, 다음 실행에도 남습니다. */
    public static void theme(Theme choice) {
        if (choice == null || choice == theme()) {
            return;
        }
        theme = choice;
        choice.remember();

        // 우리가 스타일을 입혀 둔 창만 골라 다시 칠합니다 — 툴팁·콤보박스 팝업처럼
        // 우리 css 와 무관한 창까지 건드리지 않도록 base 를 갖고 있는지로 가릅니다
        String base = sheet(CSS);
        for (Window window : Window.getWindows()) {
            Scene target = window.getScene();
            if (target != null && base != null && target.getStylesheets().contains(base)) {
                applyCss(target);
            }
        }
    }

    /**
     * 기본 css 위에 테마 css 를 얹습니다.
     * ⚠️ 순서가 뒤바뀌면 색이 안 바뀌고, 더하기(add)로 쌓으면 옛 테마가 남습니다 — setAll 유지.
     */
    private static void applyCss(Scene target) {
        if (target == null) {
            return;
        }
        List<String> sheets = new ArrayList<>();

        String base = sheet(CSS);
        if (base == null) {
            System.err.println("[UI] " + CSS + "를 찾지 못했습니다. 스타일 없이 표시합니다.");
        } else {
            sheets.add(base);
        }

        String css = theme().css();
        if (css != null) {
            String skin = sheet(BASE + css);
            if (skin == null) {
                System.err.println("[UI] " + BASE + css + "를 찾지 못했습니다. 기본 색으로 표시합니다.");
            } else {
                sheets.add(skin);
            }
        }
        target.getStylesheets().setAll(sheets);
    }

    private static String sheet(String path) {
        URL url = AppNav.class.getResource(path);
        return url == null ? null : url.toExternalForm();
    }
}
