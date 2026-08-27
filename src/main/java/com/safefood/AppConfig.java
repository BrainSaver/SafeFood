package com.safefood;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 애플리케이션 환경 설정 및 API 키 관리자.
 * config.properties 및 .env 파일을 자동으로 읽어와 설정을 제공합니다.
 */
public final class AppConfig {

    private static final String CONFIG_FILE = "config.properties";
    private static final String ENV_FILE = ".env";
    private static final Properties VALUES = new Properties();

    static {
        reload();
    }

    private AppConfig() {
    }

    public static String get(String key, String fallback) {
        if (key == null) {
            return fallback;
        }
        String value = VALUES.getProperty(key);
        if (value == null) {
            String envKey = key.toUpperCase().replace('.', '_').replace('-', '_');
            value = VALUES.getProperty(envKey);
            if (value == null) {
                value = System.getenv(envKey);
                if (value == null) {
                    value = System.getenv(key);
                }
            }
        }
        return value == null ? fallback : value;
    }

    public static String trimmed(String key, String fallback) {
        String value = get(key, "").trim();
        return value.isEmpty() ? fallback : value;
    }

    public static String kakaoJsKey() {
        String key = trimmed("kakao.js.key", "");
        if (key.isEmpty()) {
            key = trimmed("map.js.key", "");
        }
        return key;
    }

    public static String kakaoRestKey() {
        String key = trimmed("kakao.rest.key", "");
        if (key.isEmpty()) {
            key = trimmed("map.api.key", "");
        }
        return key;
    }

    public static Path file() {
        return Path.of(CONFIG_FILE).toAbsolutePath();
    }

    public static boolean exists() {
        return Files.exists(Path.of(CONFIG_FILE)) || Files.exists(Path.of(ENV_FILE));
    }

    public static synchronized void reload() {
        VALUES.clear();

        // 1. config.properties 로드
        Path configFile = Path.of(CONFIG_FILE);
        if (Files.exists(configFile)) {
            try (Reader reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
                VALUES.load(reader);
            } catch (IOException e) {
                System.err.println("[config] " + CONFIG_FILE + "을 읽지 못했습니다: " + e.getMessage());
            }
        }

        // 2. .env 로드
        Path envFile = Path.of(ENV_FILE);
        if (Files.exists(envFile)) {
            try (BufferedReader br = Files.newBufferedReader(envFile, StandardCharsets.UTF_8)) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) {
                        continue;
                    }
                    int eqIdx = line.indexOf('=');
                    String k = line.substring(0, eqIdx).trim();
                    String v = line.substring(eqIdx + 1).trim();
                    if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))) {
                        v = v.substring(1, v.length() - 1);
                    }
                    VALUES.setProperty(k, v);
                }
            } catch (IOException e) {
                System.err.println("[config] " + ENV_FILE + "을 읽지 못했습니다: " + e.getMessage());
            }
        }
    }
}
