package com.safefood.view.controller;

import com.safefood.view.AppNav;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Screen;
import netscape.javascript.JSObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Consumer;

/**
 * 주소 검색 — 카카오(다음) 우편번호 서비스를 WebView 로 띄우고, 고른 주소만 받아 옵니다.
 *
 * <p>이 서비스는 REST가 없고 JavaScript 위젯으로만 제공됩니다. 그래서 화면 하나를 통째로
 * 웹으로 띄우고, 결과는 {@code window.bridge} 를 통해 자바로 건너옵니다
 * (껍데기 문서는 {@code postcode.html}).
 *
 * <p>순서가 중요합니다 — <b>문서가 다 읽힌 뒤에</b> bridge 를 심고 그 다음에 위젯을 시작합니다.
 * 문서 쪽에서 먼저 시작해 버리면 bridge 가 아직 없어서 결과를 넘길 곳이 없습니다.
 */
public class PostcodeController {

    private static final String PAGE = "/com/safefood/view/postcode.html";

    /** 배포 실행에서 쓰는 문서 사본 — 한 번만 풀어 두고 계속 씁니다 ({@link #pageUrl()}). */
    private static URL extractedPage;

    /*
     * 위젯 높이는 화면 상태에 따라 달라집니다 — 처음(안내 화면)에는 466px 안팎이고, 검색
     * 결과 목록이 뜨면 더 길어집니다. WebView 를 한 크기로 못박아 두면 그 차이만큼 아래가
     * 잘리므로(그 안에서는 스크롤도 안 됩니다), 위젯이 알려 주는 높이를 그대로 따라갑니다.
     * 창은 AppNav 의 ContentFit 이 내용 크기를 보고 다시 맞춰 줍니다.
     */

    /**
     * 너무 납작해지지 않게 잡아 두는 최소 높이.
     *
     * <p>낮게 잡은 이유: 검색 결과가 한두 건이면 위젯이 270px 안팎으로 <b>줄어듭니다</b>.
     * 여기서 크게 잡으면 그 차이만큼 아래에 흰 공백이 남습니다.
     */
    private static final double MIN_HEIGHT = 260;

    /** 창이 화면 밖으로 자라지 않게 하는 상한 — 넘으면 페이지 안에서 굴려 봅니다. */
    private static final double MAX_HEIGHT = 640;

    /** 우편번호 서비스가 돌려주는 주소 한 건. */
    public record Address(String zonecode, String roadAddress, String jibunAddress,
                          String buildingName) {

        /** 도로명 주소를 먼저 쓰고, 없으면 지번 주소. */
        public String preferred() {
            return roadAddress == null || roadAddress.isBlank() ? jibunAddress : roadAddress;
        }
    }

    @FXML private VBox root;
    @FXML private WebView webView;
    @FXML private Label status;

    /**
     * JavaScript 가 부르는 자리. 자바 쪽에서 참조를 들고 있어야 합니다 —
     * JS 만 잡고 있으면 가비지 컬렉션 대상이 되어 어느 순간 콜백이 사라집니다.
     */
    private final Bridge bridge = new Bridge();

    private Consumer<Address> onPicked;

    public void setOnPicked(Consumer<Address> onPicked) {
        this.onPicked = onPicked;
    }

    @FXML
    private void initialize() {
        WebEngine engine = webView.getEngine();
        engine.setJavaScriptEnabled(true);

        engine.getLoadWorker().stateProperty().addListener((observable, before, state) -> {
            if (state == Worker.State.FAILED) {
                showStatus("주소 검색 화면을 불러오지 못했습니다. 인터넷 연결을 확인해 주세요.");
            } else if (state == Worker.State.SUCCEEDED) {
                startWidget(engine);
            }
        });

        URL page;
        try {
            page = pageUrl();
        } catch (IOException e) {
            showStatus("주소 검색 화면을 준비하지 못했습니다: " + e.getMessage());
            return;
        }
        if (page == null) {
            showStatus("주소 검색 화면(postcode.html)을 찾지 못했습니다.");
            return;
        }
        engine.load(page.toExternalForm());
    }

    /**
     * 위젯을 띄울 문서의 주소.
     *
     * <p>IDE 에서는 클래스가 폴더에서 읽히므로 {@code file:...} 이 그대로 나오고, 그걸로 띄우면
     * 잘 돕니다. 배포(jar 하나로 묶인 실행 파일)에서는 {@code jar:file:...!/...} 가 나오는데,
     * <b>이 주소로 띄우면 화면이 빈 채로 멈춥니다</b> — 문서도 뜨고 카카오 스크립트도 내려와서
     * {@code isReady()} 와 {@code start()} 는 모두 성공하지만, 위젯이 그 안에 만드는 iframe 과
     * postMessage 로 주고받는 단계에서 막힙니다. jar: 문서는 출처(origin)가 없는 것으로
     * 취급되기 때문입니다. {@code loadContent} 로 문자열을 넣는 방법도 출처가 about:blank 라
     * 똑같이 막힙니다.
     *
     * <p>그래서 배포에서는 문서를 임시 파일로 한 번 풀어 두고 {@code file:} 로 띄웁니다. 이 문서가
     * 부르는 스크립트는 절대 주소(https)뿐이라 옆에 같이 풀어야 할 파일은 없습니다.
     *
     * @return 문서 주소. 문서 자체를 찾지 못하면 null
     */
    private static synchronized URL pageUrl() throws IOException {
        URL page = PostcodeController.class.getResource(PAGE);
        if (page == null || "file".equals(page.getProtocol())) {
            return page;
        }
        if (extractedPage == null) {
            Path copy = Files.createTempFile("safefood-postcode", ".html");
            copy.toFile().deleteOnExit();
            try (InputStream in = PostcodeController.class.getResourceAsStream(PAGE)) {
                Files.copy(in, copy, StandardCopyOption.REPLACE_EXISTING);
            }
            extractedPage = copy.toUri().toURL();
        }
        return extractedPage;
    }

    /** 문서가 준비된 뒤 — bridge 를 심고 위젯을 띄웁니다. */
    @SuppressWarnings("removal")   // JSObject 는 JavaFX 21 에서 WebView 와 주고받는 유일한 길입니다
    private void startWidget(WebEngine engine) {
        try {
            JSObject window = (JSObject) engine.executeScript("window");
            window.setMember("bridge", bridge);

            if (!Boolean.TRUE.equals(engine.executeScript("isReady()"))) {
                showStatus("카카오 우편번호 스크립트를 불러오지 못했습니다. "
                        + "사내망·방화벽에서 t1.daumcdn.net 이 막혀 있는지 확인해 주세요.");
                return;
            }
            engine.executeScript("start()");
            hideStatus();
        } catch (RuntimeException e) {
            showStatus("주소 검색을 시작하지 못했습니다: " + e.getMessage());
        }
    }

    @FXML
    private void handleClose() {
        AppNav.close(root);
    }

    /** 위젯이 알려 준 높이에 WebView 를 맞춥니다 (화면을 벗어나지 않는 선에서). */
    private void fitHeight(double height) {
        double limit = Math.min(MAX_HEIGHT, Screen.getPrimary().getVisualBounds().getHeight() * 0.72);
        double fitted = Math.max(MIN_HEIGHT, Math.min(height, limit));
        if (Math.abs(webView.getPrefHeight() - fitted) >= 1) {
            webView.setPrefHeight(fitted);
        }
    }

    private void showStatus(String message) {
        status.setText(message);
        status.setVisible(true);
    }

    private void hideStatus() {
        status.setVisible(false);
    }

    /**
     * JavaScript 에서 부르는 자리. 클래스와 메서드가 모두 {@code public} 이어야
     * WebView 가 찾아 부를 수 있습니다.
     */
    public final class Bridge {

        /** 주소를 고른 순간. 넘어오는 값은 우편번호 서비스가 준 문자열 그대로입니다. */
        public void onComplete(String zonecode, String roadAddress, String jibunAddress,
                               String buildingName) {
            Address picked = new Address(zonecode, roadAddress, jibunAddress, buildingName);
            // JS 호출 안에서 창을 닫지 않습니다 — 스크립트가 끝난 뒤로 미룹니다
            Platform.runLater(() -> {
                if (onPicked != null) {
                    onPicked.accept(picked);
                }
                AppNav.close(root);
            });
        }

        /** 사용자가 위젯 안에서 닫기를 누른 경우. */
        public void onClose(String state) {
            Platform.runLater(() -> AppNav.close(root));
        }

        /** 위젯이 필요한 높이를 알려 오는 자리 — 화면이 바뀔 때마다 다시 불립니다. */
        public void onResize(double height) {
            Platform.runLater(() -> fitHeight(height));
        }
    }
}
