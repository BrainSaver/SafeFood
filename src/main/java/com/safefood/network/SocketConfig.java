package com.safefood.network;

import com.safefood.AppConfig;

public final class SocketConfig {
    public static final String DEFAULT_HOST = "localhost";
    public static final int DEFAULT_PORT = 5000;

    private SocketConfig() {
    }

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
