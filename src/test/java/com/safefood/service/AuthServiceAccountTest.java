package com.safefood.service;

import com.safefood.dao.UserDao;
import com.safefood.dto.UserDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 계정 흐름 — 가입 · 로그인 · 정보 변경 · 탈퇴.
 *
 * <p>{@code AuthServiceTest} 가 해시 계산만 보는 것과 달리, 여기서는 <b>DAO 를 거치는 경로</b>를
 * 봅니다. 진짜 DB 대신 {@link FakeUserDao} 를 끼워 넣으므로 서버 없이 돌아갑니다
 * ({@code AuthService(UserDao)} 생성자가 그 자리입니다).
 *
 * <p>이 파일이 생긴 계기는 <b>비밀번호를 바꾸면 그 세션에서 다시는 비밀번호 확인이 통과하지
 * 않던 버그</b>입니다. 저장은 서비스가 해시로 하고 세션 사본은 화면이 평문으로 맞추는 바람에,
 * 갈라진 두 곳 어느 쪽만 봐도 멀쩡해 보였습니다. 그래서 아래 시험은 한쪽을 확인하는 대신
 * <b>화면이 실제로 밟는 순서</b>를 그대로 밟습니다.
 */
class AuthServiceAccountTest {

    /** 메모리에만 쓰는 가짜 DAO — 무엇이 저장됐는지 그대로 들고 있습니다. */
    private static final class FakeUserDao extends UserDao {

        private final Map<String, UserDto> byLoginId = new HashMap<>();
        private final Map<Integer, String> sessionTokens = new HashMap<>();

        /** false 로 두면 쓰기가 전부 실패합니다 — DB 가 흔들릴 때를 흉내 냅니다. */
        boolean writeSucceeds = true;

        String savedPassword;
        String savedNickname;
        int deleteCalls;

        void preset(UserDto user) {
            byLoginId.put(user.getLoginId(), user);
        }

        @Override
        public UserDto findByLoginId(String loginId) {
            return byLoginId.get(loginId);
        }

        @Override
        public UserDto findByNickname(String nickname) {
            return null;   // 시험에서는 늘 쓸 수 있는 닉네임으로 봅니다
        }

        @Override
        public boolean insertUser(UserDto user) {
            if (!writeSucceeds) {
                return false;
            }
            savedPassword = user.getPassword();
            byLoginId.put(user.getLoginId(), user);
            return true;
        }

        @Override
        public boolean updateUserInfo(int userId, String newPassword, String newNickname) {
            if (!writeSucceeds) {
                return false;
            }
            savedPassword = newPassword;
            savedNickname = newNickname;
            return true;
        }

        @Override
        public boolean updatePassword(int userId, String newPassword) {
            if (!writeSucceeds) {
                return false;
            }
            savedPassword = newPassword;
            return true;
        }

        @Override
        public boolean updateSessionToken(int userId, String token) {
            if (!writeSucceeds) {
                return false;
            }
            sessionTokens.put(userId, token);
            return true;
        }

        @Override
        public String findSessionToken(int userId) {
            return sessionTokens.get(userId);
        }

        @Override
        public boolean deleteUser(int userId) {
            deleteCalls++;
            return writeSucceeds;
        }
    }

    private final FakeUserDao dao = new FakeUserDao();
    private final AuthService auth = new AuthService(dao);

    /** 로그인해 둔 회원 한 명 — 비밀번호는 새 형식으로 저장돼 있습니다. */
    private UserDto member(String plainPassword) {
        UserDto user = new UserDto("safefood", auth.hashPassword(plainPassword), "민수");
        user.setId(7);
        dao.preset(user);
        return user;
    }

    private static String legacySha256(String plain) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(plain.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // =========================================================================
    // 정보 변경 — 세션 사본에 무엇이 들어가는지
    // =========================================================================

    @Test
    @DisplayName("비밀번호를 바꾸면 세션 사본에도 평문이 아니라 해시가 들어간다")
    void changedPasswordIsStoredAsHashInSession() {
        UserDto me = member("예전비밀번호");

        assertTrue(auth.updateProfile(me, "새비밀번호!2026", "민수"));

        assertAll(
                () -> assertNotEquals("새비밀번호!2026", me.getPassword(),
                        "세션에 평문이 들어가면 이후 검증이 전부 실패합니다"),
                () -> assertTrue(auth.verifyPassword("새비밀번호!2026", me.getPassword()),
                        "세션 사본은 새 비밀번호로 검증돼야 합니다"),
                () -> assertEquals(me.getPassword(), dao.savedPassword,
                        "DB 에 넣은 값과 세션이 든 값이 같아야 합니다"),
                () -> assertFalse(auth.verifyPassword("예전비밀번호", me.getPassword()))
        );
    }

    @Test
    @DisplayName("비밀번호를 바꾼 뒤에도 같은 세션에서 정보 수정·탈퇴를 계속할 수 있다")
    void passwordChangeDoesNotLockOutTheSession() {
        // 화면(EditInfoController)이 밟는 순서 그대로입니다:
        // 현재 비밀번호로 관문을 통과 → 저장 → 다시 열어 관문을 통과.
        // 예전에는 두 번째 관문에서 맞는 비밀번호에도 "현재 비밀번호가 틀렸습니다" 가 떴습니다.
        UserDto me = member("처음비밀번호");

        assertTrue(auth.verifyPassword("처음비밀번호", me.getPassword()), "1차 관문");
        assertTrue(auth.updateProfile(me, "바꾼비밀번호", "민수"));

        assertTrue(auth.verifyPassword("바꾼비밀번호", me.getPassword()),
                "2차 관문 — 여기가 막히면 재로그인 전까지 정보 수정도 탈퇴도 못 합니다");

        // 이어서 탈퇴까지 — deleteAccount 도 같은 사본을 봅니다
        assertTrue(auth.deleteAccount(me.getId(), "바꾼비밀번호", me.getPassword()));
        assertEquals(1, dao.deleteCalls);
    }

    @Test
    @DisplayName("비밀번호 칸을 비우면 기존 해시가 그대로 저장된다")
    void blankPasswordKeepsTheStoredHash() {
        UserDto me = member("그대로둘비밀번호");
        String before = me.getPassword();

        assertTrue(auth.updateProfile(me, "   ", "새닉네임"));

        assertAll(
                () -> assertEquals(before, me.getPassword(), "건드리지 않았어야 합니다"),
                () -> assertEquals(before, dao.savedPassword),
                () -> assertEquals("새닉네임", me.getNickname(), "닉네임은 사본에도 반영됩니다"),
                () -> assertEquals("새닉네임", dao.savedNickname),
                () -> assertTrue(auth.verifyPassword("그대로둘비밀번호", me.getPassword()))
        );
    }

    @Test
    @DisplayName("저장에 실패하면 세션 사본을 건드리지 않는다")
    void failedWriteLeavesSessionUntouched() {
        UserDto me = member("원래비밀번호");
        String before = me.getPassword();
        dao.writeSucceeds = false;

        assertFalse(auth.updateProfile(me, "새비밀번호", "새닉네임"));

        assertAll(
                () -> assertEquals(before, me.getPassword(),
                        "DB 에 안 들어간 값이 화면에만 반영되면 둘이 어긋납니다"),
                () -> assertEquals("민수", me.getNickname()),
                () -> assertTrue(auth.verifyPassword("원래비밀번호", me.getPassword()))
        );
    }

    @Test
    @DisplayName("세션에 저장된 비밀번호가 없으면 저장하지 않는다 (칼럼을 비우지 않기)")
    void missingHashIsNotWrittenOver() {
        UserDto me = member("아무거나");
        me.setPassword(null);

        // 비밀번호를 그대로 두려는 요청인데 그대로 둘 값이 없습니다.
        // 한 문장으로 두 칼럼을 쓰는 UPDATE 라, 넘기면 비밀번호가 지워져 계정이 잠깁니다.
        assertFalse(auth.updateProfile(me, "", "새닉네임"));
        assertNull(dao.savedPassword, "쓰기를 시도조차 하지 않아야 합니다");
    }

    @Test
    @DisplayName("게스트나 저장할 계정이 없는 경우는 거절한다")
    void guestHasNothingToUpdate() {
        UserDto guest = new UserDto("게스트", "무엇이든", "손님");
        guest.setId(-1);

        assertAll(
                () -> assertFalse(auth.updateProfile(guest, "새비밀번호", "손님")),
                () -> assertFalse(auth.updateProfile(null, "새비밀번호", "손님")),
                () -> assertNull(dao.savedPassword)
        );
    }

    // =========================================================================
    // 로그인
    // =========================================================================

    @Test
    @DisplayName("예전 해시로 로그인하면 새 형식으로 이관되고 사본도 새 해시가 된다")
    void legacyHashIsUpgradedOnLogin() throws Exception {
        UserDto stored = new UserDto("safefood", legacySha256("예전비밀번호"), "민수");
        stored.setId(7);
        dao.preset(stored);

        UserDto loggedIn = auth.login("safefood", "예전비밀번호");

        assertNotNull(loggedIn, "예전 형식으로 가입한 회원도 로그인할 수 있어야 합니다");
        assertAll(
                () -> assertFalse(auth.needsRehash(loggedIn.getPassword()), "새 형식으로 바뀌어야 합니다"),
                () -> assertTrue(auth.verifyPassword("예전비밀번호", loggedIn.getPassword())),
                () -> assertEquals(loggedIn.getPassword(), dao.savedPassword,
                        "이관한 해시가 DB 에도 들어가야 합니다"),
                () -> assertNotNull(loggedIn.getSessionToken(), "세션 표가 발급돼야 합니다")
        );
    }

    @Test
    @DisplayName("비밀번호가 틀리면 로그인하지 않고 표도 발급하지 않는다")
    void wrongPasswordDoesNotLogIn() {
        member("맞는비밀번호");

        assertNull(auth.login("safefood", "틀린비밀번호"));
        assertNull(auth.login("없는아이디", "맞는비밀번호"));
        assertNull(dao.findSessionToken(7), "인증에 실패했으면 표를 발급하지 않습니다");
    }

    @Test
    @DisplayName("나중에 로그인한 쪽이 이기고, 먼저 있던 세션은 낡은 표로 알아챈다")
    void laterLoginWins() {
        member("같은비밀번호");

        UserDto first = auth.login("safefood", "같은비밀번호");
        String firstToken = first.getSessionToken();
        UserDto second = auth.login("safefood", "같은비밀번호");

        assertAll(
                () -> assertNotEquals(firstToken, second.getSessionToken()),
                () -> assertFalse(auth.isSessionCurrent(7, firstToken), "먼저 있던 기기는 물러납니다"),
                () -> assertTrue(auth.isSessionCurrent(7, second.getSessionToken()))
        );
    }

    // =========================================================================
    // 가입 · 탈퇴
    // =========================================================================

    @Test
    @DisplayName("가입할 때 평문 비밀번호를 저장하지 않는다")
    void registerNeverStoresPlaintext() {
        assertTrue(auth.register("newbie", "가입비밀번호!", "새사람"));

        assertAll(
                () -> assertNotEquals("가입비밀번호!", dao.savedPassword),
                () -> assertTrue(auth.verifyPassword("가입비밀번호!", dao.savedPassword)),
                () -> assertFalse(auth.needsRehash(dao.savedPassword), "처음부터 새 형식이어야 합니다")
        );
    }

    @Test
    @DisplayName("이미 있는 아이디로는 가입시키지 않는다")
    void duplicateLoginIdIsRejected() {
        member("아무거나");

        assertFalse(auth.isLoginIdAvailable("safefood"));
        assertFalse(auth.register("safefood", "다른비밀번호", "다른사람"));
    }

    @Test
    @DisplayName("탈퇴는 비밀번호가 맞아야만 DAO 까지 간다")
    void withdrawRequiresCorrectPassword() {
        UserDto me = member("탈퇴비밀번호");

        assertAll(
                () -> assertFalse(auth.deleteAccount(me.getId(), "틀린비밀번호", me.getPassword())),
                () -> assertFalse(auth.deleteAccount(me.getId(), "", me.getPassword())),
                () -> assertFalse(auth.deleteAccount(-1, "탈퇴비밀번호", me.getPassword())),
                () -> assertEquals(0, dao.deleteCalls, "인증 전에는 삭제를 부르면 안 됩니다")
        );

        assertTrue(auth.deleteAccount(me.getId(), "탈퇴비밀번호", me.getPassword()));
        assertEquals(1, dao.deleteCalls);
    }
}
