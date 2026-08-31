package com.safefood.dao;

import com.safefood.setup.core.Schema;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 카테고리 마스터({@code category} 테이블)의 이름 ↔ id 대응표.
 * DB에서 category 목록을 읽어오며, 읽지 못할 경우 {@link Schema#CATEGORIES}를 기본값으로 사용합니다.
 */
public final class CategoryCatalog {

    /** 한 번 읽어 둔 대응표 캐시 */
    private static volatile Snapshot cache;

    private CategoryCatalog() {
    }

    private record Snapshot(Map<String, Integer> idByName, Map<Integer, String> nameById) {
        List<String> names() {
            return List.copyOf(idByName.keySet());
        }
    }

    /**
     * 이름에 해당하는 {@code category.id}.
     */
    public static Integer idOf(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Snapshot snapshot = snapshot();
        return snapshot == null ? null : snapshot.idByName().get(name.trim());
    }

    /**
     * id 에 해당하는 카테고리 이름.
     */
    public static String nameOf(int id) {
        Snapshot snapshot = snapshot();
        return snapshot == null ? null : snapshot.nameById().get(id);
    }

    /**
     * 화면에서 고를 카테고리 목록 — DB 의 id 순서입니다.
     * DB 를 읽지 못하면 {@link Schema#CATEGORIES}로 대신합니다.
     */
    public static List<String> names() {
        Snapshot snapshot = snapshot();
        if (snapshot == null || snapshot.idByName().isEmpty()) {
            return Schema.CATEGORIES;
        }
        return snapshot.names();
    }

    /** 다음 호출 때 DB 에서 다시 읽습니다. */
    public static void reload() {
        cache = null;
    }

    private static Snapshot snapshot() {
        Snapshot local = cache;
        if (local != null) {
            return local;
        }
        synchronized (CategoryCatalog.class) {
            if (cache == null) {
                cache = load();
            }
            return cache;
        }
    }

    private static Snapshot load() {
        Map<String, Integer> idByName = new LinkedHashMap<>();
        Map<Integer, String> nameById = new LinkedHashMap<>();
        String sql = "SELECT id, name FROM category ORDER BY id";

        try (Connection conn = DatabaseUtil.getConnection();
             Statement statement = conn.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {

            while (rs.next()) {
                int id = rs.getInt("id");
                String name = rs.getString("name");
                if (name == null || name.isBlank()) {
                    continue;
                }
                idByName.put(name.trim(), id);
                nameById.put(id, name.trim());
            }
        } catch (SQLException e) {
            System.err.println("[category] 카테고리 마스터를 읽지 못했습니다: " + e.getMessage());
            return new Snapshot(Map.of(), Map.of());
        }
        if (idByName.isEmpty()) {
            System.err.println("[category] category 테이블이 비어 있습니다.");
        }
        return new Snapshot(idByName, nameById);
    }
}
