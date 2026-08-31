package com.safefood.service;

import com.safefood.service.GroupRecommendService.GroupResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * "메뉴를 못 불러왔다"와 "메뉴가 없다"가 구분되는지 봅니다.
 *
 * <p>둘을 뭉뚱그리면 조회가 실패했을 때 방에 <b>알레르기를 검사하지 않은</b> 대체 후보
 * (김치찌개·제육볶음·비빔밥)가 나갔습니다. 새우 알레르기가 있는 그룹에도 그대로 갔으므로
 * 빈 화면보다 위험했습니다.
 */
class GroupRecommendServiceTest {

    @Test
    @DisplayName("조회 실패는 failed 로 구분된다")
    void failureIsDistinguishable() {
        GroupResult failed = GroupResult.failure();

        assertTrue(failed.failed(), "조회 실패임을 호출부가 알 수 있어야 합니다");
        assertFalse(failed.hasMenuData());
        assertTrue(failed.candidates().isEmpty(), "실패했으면 후보를 지어내지 않습니다");
    }

    @Test
    @DisplayName("메뉴가 없는 것은 실패가 아니다")
    void emptyIsNotFailure() {
        GroupResult empty = GroupResult.empty();

        assertFalse(empty.failed(), "메뉴가 0건인 것과 못 불러온 것은 다릅니다");
        assertFalse(empty.hasMenuData());
        assertTrue(empty.candidates().isEmpty());
    }

    @Test
    @DisplayName("훑을 메뉴는 있었는데 전부 알레르기로 막힌 경우는 또 다르다")
    void allBlockedIsItsOwnCase() {
        GroupResult allBlocked = new GroupResult(java.util.List.of(), 29, 29, false);

        assertFalse(allBlocked.failed());
        assertTrue(allBlocked.hasMenuData(), "메뉴 자체는 있었습니다");
        assertTrue(allBlocked.allBlockedByAllergy(), "전원 알레르기로 후보가 0개인 경우");
    }

    @Test
    @DisplayName("제외 목록을 줘도 조건·limit 가드는 그대로 (제외 목록이 null 이어도)")
    void exclusionKeepsGuards() {
        GroupRecommendService service = new GroupRecommendService();

        assertTrue(service.recommend(null, 3, java.util.Set.of(1, 2)).candidates().isEmpty());
        assertTrue(service.recommend(
                new GroupRecommendService.GroupCondition(java.util.Set.of(), 5, 0), 0, null)
                .candidates().isEmpty());
    }

    @Test
    @DisplayName("limit 이 0 이하거나 조건이 없으면 DB를 건드리지 않고 빈 결과")
    void guardsBeforeQuery() {
        GroupRecommendService service = new GroupRecommendService();

        assertFalse(service.recommend(null, 3).failed());
        assertTrue(service.recommend(null, 3).candidates().isEmpty());
        assertTrue(service.recommend(
                new GroupRecommendService.GroupCondition(java.util.Set.of(), 5, 0), 0)
                .candidates().isEmpty());
    }
}
