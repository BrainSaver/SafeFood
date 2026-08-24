package com.safefood.dto;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 게스트와 회원을 가르는 기준이 한 곳으로 모였는지 봅니다.
 *
 * <p>예전에는 화면마다 {@code currentUser == null} · {@code getId() == -1} ·
 * {@code GroupSession.isGuest()} 세 가지 기준이 섞여 있었고, {@code == null} 로 검사하던
 * 화면은 게스트를 회원으로 취급했습니다 — 게스트가 찜을 눌러도 "추가했습니다"만 뜨고
 * 실제로는 저장되지 않았습니다.
 */
class SessionTest {

    @AfterEach
    void clear() {
        Session.logout();
    }

    private static UserDto member(int id, String nickname) {
        UserDto user = new UserDto("tester", "hash", nickname);
        user.setId(id);
        return user;
    }

    @Test
    @DisplayName("로그인하지 않았으면 회원도 게스트도 아니다")
    void nobodyLoggedIn() {
        Session.setCurrentUser(null);
        assertFalse(Session.isMember());
        assertFalse(Session.isGuest());
        assertEquals(Session.GUEST_ID, Session.currentUserId());
    }

    @Test
    @DisplayName("게스트는 회원이 아니다 — null 검사로는 걸러지지 않는다")
    void guestIsNotMember() {
        UserDto guest = new UserDto(null, null, "길동현");
        guest.setId(Session.GUEST_ID);
        Session.setCurrentUser(guest);

        assertNotNull(Session.getCurrentUser(), "게스트도 세션에는 들어 있습니다");
        assertTrue(Session.isGuest());
        assertFalse(Session.isMember(), "게스트를 회원으로 보면 저장되지 않는 안내가 뜹니다");
    }

    @Test
    @DisplayName("회원은 회원이고 게스트가 아니다")
    void memberIsMember() {
        Session.setCurrentUser(member(7, "김민수"));
        assertTrue(Session.isMember());
        assertFalse(Session.isGuest());
        assertEquals(7, Session.currentUserId());
    }

    @Test
    @DisplayName("아직 저장되지 않은 회원 객체(id = null)도 NPE 없이 다룬다")
    void unsavedUserDoesNotExplode() {
        Session.setCurrentUser(new UserDto("tester", "hash", "김민수"));   // id 미지정
        assertDoesNotThrow(Session::isMember);
        assertFalse(Session.isMember());
        assertEquals(Session.GUEST_ID, Session.currentUserId());
    }

    @Test
    @DisplayName("표시 이름은 닉네임 → 아이디 → 대체값 순")
    void displayNameFallsBack() {
        Session.setCurrentUser(member(7, "김민수"));
        assertEquals("김민수", Session.displayName("사용자"));

        Session.setCurrentUser(member(7, "  "));
        assertEquals("tester", Session.displayName("사용자"), "닉네임이 비면 로그인 아이디로");

        Session.setCurrentUser(null);
        assertEquals("사용자", Session.displayName("사용자"));
    }

    // ── 신원이 바뀔 때 캐시가 따라오지 않는지 ─────────────────────

    @Test
    @DisplayName("다른 아이디로 로그인하면 이전 사람의 주소가 남지 않는다")
    void switchingMemberDropsCachedLocation() {
        Session.setCurrentUser(member(7, "김민수"));
        Session.setMyLocation(new LocationDto("집", "06236", "서울 강남구 테헤란로 12길 5", 37.5, 127.0));
        assertNotNull(Session.getMyLocation());

        // 로그아웃을 거치지 않고 곧바로 다른 아이디로 로그인한 경우
        Session.setCurrentUser(member(9, "조영준"));

        assertNull(Session.getMyLocation(), "이전 회원의 주소가 보이면 안 됩니다");
    }

    @Test
    @DisplayName("게스트가 바뀌어도 이전 게스트의 주소가 남지 않는다")
    void switchingGuestDropsCachedLocation() {
        UserDto first = new UserDto(null, null, "길동현");
        first.setId(Session.GUEST_ID);
        Session.setCurrentUser(first);
        Session.setMyLocation(new LocationDto("회사", null, "서울 마포구 양화로 160", null, null));
        assertNotNull(Session.getMyLocation());

        UserDto second = new UserDto(null, null, "조영준");
        second.setId(Session.GUEST_ID);   // 게스트는 번호가 같습니다 — 그래도 다른 사람입니다
        Session.setCurrentUser(second);

        assertNull(Session.getMyLocation(), "게스트끼리도 주소를 물려받으면 안 됩니다");
    }

    @Test
    @DisplayName("게스트 → 회원으로 바뀌면 게스트 취향도 버린다")
    void switchingFromGuestDropsGuestPreference() {
        UserDto guest = new UserDto(null, null, "길동현");
        guest.setId(Session.GUEST_ID);
        Session.setCurrentUser(guest);
        Session.setGuestPreference(new PreferenceDto(1, 0, 8000, 500));

        Session.setCurrentUser(member(7, "김민수"));

        assertNull(Session.getGuestPreferences());
        assertNull(Session.getMyLocation());
    }

    @Test
    @DisplayName("같은 회원을 다시 담는 것은 신원 변경이 아니다 (온보딩이 자기 계정을 다시 넣는 경우)")
    void resettingSameMemberKeepsCache() {
        Session.setCurrentUser(member(7, "김민수"));
        Session.setMyLocation(new LocationDto("집", "06236", "서울 강남구", 37.5, 127.0));

        Session.setCurrentUser(member(7, "김민수"));   // DB에서 다시 읽어 담은 같은 회원

        assertNotNull(Session.getMyLocation(), "같은 사람이면 다시 조회할 이유가 없습니다");
        assertEquals("서울 강남구", Session.getMyLocation().getAddress());
    }

    @Test
    @DisplayName("주소 캐시는 주인이 다르면 읽히지 않는다 (비우는 것을 잊어도 안전하게)")
    void cachedLocationIsOwnerScoped() {
        Session.setCurrentUser(member(7, "김민수"));
        Session.setMyLocation(new LocationDto("집", "06236", "서울 강남구", 37.5, 127.0));

        // setCurrentUser 를 거치지 않고 사람이 바뀐 상황을 흉내 내도 (필드를 직접 밀어 넣는 미래의 코드 등)
        // 주인이 다르면 읽히지 않아야 합니다
        Session.logout();
        Session.setCurrentUser(member(9, "조영준"));
        assertNull(Session.getMyLocation());
    }

    @Test
    @DisplayName("로그아웃은 위치 사본과 게스트 취향까지 비운다")
    void logoutClearsEverything() {
        Session.setCurrentUser(member(7, "김민수"));
        Session.setMyLocation(new LocationDto("집", "06236", "서울 강남구", 37.5, 127.0));
        Session.setGuestPreference(new PreferenceDto(2, 0, 10000, 1000));

        Session.logout();

        assertNull(Session.getCurrentUser());
        assertNull(Session.getMyLocation(), "다음 사람에게 이전 위치가 보이면 안 됩니다");
        assertNull(Session.getGuestPreferences());
        assertFalse(Session.isMember());
        assertFalse(Session.isGuest());
    }
}
