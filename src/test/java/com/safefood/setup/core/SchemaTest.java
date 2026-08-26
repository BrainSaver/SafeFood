package com.safefood.setup.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 스키마가 <b>실행 가능한 순서</b>로 정의돼 있는지 봅니다.
 *
 * <p>{@link Schema#TABLES} 는 적힌 순서대로 실행되므로, 외래 키가 가리키는 테이블이
 * 먼저 나와야 합니다. 순서를 잘못 바꾸면 설정 마법사가 새 데이터베이스에서만 실패하는데,
 * 이미 테이블이 있는 개발자 PC 에서는 드러나지 않아 발견이 늦습니다.
 */
class SchemaTest {

    private static int indexOf(String tableName) {
        List<Schema.Table> tables = Schema.TABLES;
        for (int i = 0; i < tables.size(); i++) {
            if (tables.get(i).name().equals(tableName)) {
                return i;
            }
        }
        return -1;
    }

    @Test
    @DisplayName("user 를 참조하는 테이블 목록 — 바뀌면 UserDao.deleteUser 도 함께 고쳐야 한다")
    void tablesReferencingUserAreKnown() {
        java.util.Set<String> found = new java.util.TreeSet<>();
        java.util.regex.Pattern fk =
                java.util.regex.Pattern.compile("FOREIGN KEY \\(([^)]+)\\)\\s*REFERENCES\\s*`?(\\w+)`?\\(");
        for (Schema.Table table : Schema.TABLES) {
            java.util.regex.Matcher matcher = fk.matcher(table.ddl());
            while (matcher.find()) {
                if ("user".equals(matcher.group(2))) {
                    found.add(table.name());
                }
            }
        }

        assertEquals(
                java.util.Set.of("user_preference", "user_location", "user_category", "user_allergy",
                        "dining_group", "group_member", "favorite", "feedback", "history", "child"),
                found,
                "user 를 참조하는 테이블이 바뀌었습니다. UserDao.deleteUser 의 삭제 순서를 함께 고쳐 주세요");
    }

    @Test
    @DisplayName("history 는 feedback_id 컬럼과 외래 키를 갖는다")
    void historyHasFeedbackId() {
        String ddl = Schema.ddlOf("history");
        assertTrue(ddl.contains("feedback_id"), "history 에 feedback_id 컬럼이 있어야 합니다");
        assertTrue(ddl.contains("FOREIGN KEY (feedback_id) REFERENCES feedback(id)"),
                "feedback_id 는 feedback(id) 를 가리켜야 합니다");
    }

    @Test
    @DisplayName("외래 키가 가리키는 테이블이 먼저 만들어진다")
    void foreignKeyTargetsComeFirst() {
        assertTrue(indexOf("feedback") < indexOf("history"),
                "history.feedback_id 가 feedback(id) 를 가리키므로 feedback 이 먼저여야 합니다");
        assertTrue(indexOf("menu") < indexOf("history"), "history.menu_id → menu(id)");
        assertTrue(indexOf("dining_group") < indexOf("history"), "history.group_id → dining_group(id)");
        assertTrue(indexOf("allergy") < indexOf("user_allergy"), "user_allergy.allergy_id → allergy(id)");
        assertTrue(indexOf("restaurant") < indexOf("menu"), "menu.restaurant_id → restaurant(id)");
        assertTrue(indexOf("category") < indexOf("menu"), "menu.category_id → category(id)");
        assertTrue(indexOf("child") < indexOf("child_allergy"), "child_allergy.child_id → child(id)");
        assertTrue(indexOf("allergy") < indexOf("child_allergy"), "child_allergy.allergy_id → allergy(id)");
    }

    @Test
    @DisplayName("모든 DDL 은 IF NOT EXISTS 라서 여러 번 실행해도 안전하다")
    void everyTableIsIdempotent() {
        for (Schema.Table table : Schema.TABLES) {
            assertTrue(table.ddl().contains("CREATE TABLE IF NOT EXISTS"),
                    table.name() + " 의 DDL 이 IF NOT EXISTS 가 아닙니다");
        }
    }
}
