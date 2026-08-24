package com.safefood;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class AppConfig {

    private static final String FILE_NAME = "config.properties";
    private static final Properties VALUES = new Properties();

    static {
        reload();
    }

    private AppConfig() {
    }

    public static String get(String key, String fallback) {
        String value = VALUES.getProperty(key);
        return value == null ? fallback : value;
    }

    public static String trimmed(String key, String fallback) {
        String value = get(key, "").trim();
        return value.isEmpty() ? fallback : value;
    }

    public static Path file() {
        return Path.of(FILE_NAME).toAbsolutePath();
    }

    public static boolean exists() {
        return Files.exists(Path.of(FILE_NAME));
    }

    public static synchronized void reload() {
        VALUES.clear();
        Path file = Path.of(FILE_NAME);
        if (!Files.exists(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            VALUES.load(reader);
        } catch (IOException e) {
            System.err.println("[config] " + FILE_NAME + "을 읽지 못했습니다: " + e.getMessage());
        }
    }
}
