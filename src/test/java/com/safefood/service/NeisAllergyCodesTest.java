package com.safefood.service;

import com.safefood.setup.core.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NEIS 번호표가 우리 알레르기 마스터와 어긋나지 않는지 봅니다. DB 없이 도는 검사입니다.
 *
 * <p>이름 오타 하나({@code 쇠고기}/{@code 소고기}, {@code 계란}/{@code 난류})면 그 알레르겐이
 * 통째로 판정에서 빠집니다. 그 순간 아이에게 걸리는 급식이 '표기상 없음'으로 보입니다.
 */
class NeisAllergyCodesTest {

    @Test
    @DisplayName("NEIS 번호표는 우리 마스터와 '같은 19종'이어야 한다")
    void isPermutationOfOurMaster() {
        assertEquals(19, Schema.NEIS_ALLERGY_NAMES.size(), "식약처 고시 19종");
        assertEquals(Schema.ALLERGIES.size(), Schema.NEIS_ALLERGY_NAMES.size());

        Set<String> ours = new HashSet<>(Schema.ALLERGIES);
        Set<String> neis = new HashSet<>(Schema.NEIS_ALLERGY_NAMES);
        assertEquals(ours, neis,
                "이름이 하나라도 다르면 그 알레르겐은 급식 판정에서 통째로 빠집니다");
        assertEquals(19, neis.size(), "중복된 이름이 있으면 안 됩니다");
    }

    @Test
    @DisplayName("번호와 순서가 우리 마스터와 다르다는 사실 자체를 고정한다")
    void orderDiffersFromOurMaster() {
        // 실제 식단으로 확인한 값입니다: 우유 (2) · 고등어구이 (7) · 돈육불고기 (10) · 오징어무침 (17)
        assertEquals("계란", NeisAllergyCodes.nameOf(1));
        assertEquals("우유", NeisAllergyCodes.nameOf(2));
        assertEquals("고등어", NeisAllergyCodes.nameOf(7));
        assertEquals("돼지고기", NeisAllergyCodes.nameOf(10));
        assertEquals("아황산류", NeisAllergyCodes.nameOf(13));
        assertEquals("오징어", NeisAllergyCodes.nameOf(17));
        assertEquals("잣", NeisAllergyCodes.nameOf(19));

        // 두 목록이 같은 순서였다면 번호를 그대로 써도 됐겠지만, 그렇지 않습니다
        assertNotEquals(Schema.ALLERGIES, Schema.NEIS_ALLERGY_NAMES,
                "순서가 같아지면 매핑이 필요 없다는 뜻이니 이 테스트를 지우세요");

        int same = 0;
        for (int code = 1; code <= 19; code++) {
            if (Schema.ALLERGIES.get(code - 1).equals(NeisAllergyCodes.nameOf(code))) {
                same++;
            }
        }
        assertEquals(5, same, "19종 중 우연히 일치하는 것은 5개뿐입니다 (메밀·땅콩·대두·밀·새우)");
    }

    @Test
    @DisplayName("범위 밖 번호는 이름이 없다")
    void outOfRangeHasNoName() {
        assertNull(NeisAllergyCodes.nameOf(0));
        assertNull(NeisAllergyCodes.nameOf(20));
        assertNull(NeisAllergyCodes.nameOf(-1));
    }
}
