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

import java.net.URL;
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

    /** 창이 화면 밖으로 자라지 않게 하는 상한: 넘으면 페이지 안에서 굴려 봅니다. */
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

        URL page = getClass().getResource(PAGE);
        if (page == null) {
            showStatus("주소 검색 화면(postcode.html)을 찾지 못했습니다.");
            return;
        }
        engine.load(page.toExternalForm());
    }

    /** 문서가 준비된 뒤: bridge 를 심고 위젯을 띄웁니다. */
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

        /** 위젯이 필요한 높이를 알려 오는 자리: 화면이 바뀔 때마다 다시 불립니다. */
        public void onResize(double height) {
            Platform.runLater(() -> fitHeight(height));
        }
    }
}
