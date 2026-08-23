package com.safefood.network;

import com.safefood.AppConfig;

/**
 * {@code config.properties}의 {@code socket.*} 값.
 *
 * <p>값이 없으면 기본값(localhost:5000)을 씁니다. 설정을 어디서 읽는지는
 * {@link AppConfig}가 정합니다 — 배포본은 jar 안에 묻힌 설정, 개발 환경은 프로젝트 루트 파일입니다.
 */
public final class SocketConfig {

    public static final String DEFAULT_HOST = "localhost";
    public static final int DEFAULT_PORT = 5000;

    private SocketConfig() {
    }

    /**
     * 방장이 초대 코드에 담을 주소를 직접 지정할 때 씁니다 — 어댑터가 여럿이라
     * {@link GroupServer#lanAddress()}가 엉뚱한 주소를 고를 때의 수동 지정용입니다.
     * 기본값({@value #DEFAULT_HOST})이면 자동 판별을 씁니다.
     *
     * <p>참여자 쪽은 이 값을 보지 않습니다 — 접속 주소는 초대 코드에서 꺼냅니다.
     */
    public static String host() {
        return AppConfig.trimmed("socket.host", DEFAULT_HOST);
    }

    public static int port() {
        try {
            return Integer.parseInt(AppConfig.trimmed("socket.port", String.valueOf(DEFAULT_PORT)));
        } catch (NumberFormatException e) {
            return DEFAULT_PORT;
        }
    }
}
