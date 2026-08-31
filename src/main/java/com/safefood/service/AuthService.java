package com.safefood.service;

import com.safefood.dao.UserDao;
import com.safefood.dto.UserDto;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import java.util.UUID;

public class AuthService {

    private final UserDao userDao;

    public AuthService() {
        this(new UserDao());
    }

    /**
     * DAO 를 갈아 끼우는 생성자 — 시험에서 DB 없이 계정 흐름만 확인할 때 씁니다.
     * 실행 중에는 {@link #AuthService()} 만 쓰입니다.
     */
    AuthService(UserDao userDao) {
        this.userDao = userDao;
    }

    /**
     * 아이디 중복 확인
     * @return true: 사용 가능 (중복 없음), false: 이미 사용 중
     */
    public boolean isLoginIdAvailable(String loginId) {
        return userDao.findByLoginId(loginId) == null;
    }


    /**
     * 로그인 아이디로 회원정보 조회
     * >> view가 DB를 알고싶을 때 service에게 물어보도록 함
     */

    public UserDto getUserInfo(String loginId){
        return userDao.findByLoginId(loginId);
    }


    /**
     * 회원 가입 처리
     * 비밀번호를 암호화하여 DB에 저장합니다.
     */
    public boolean register(String loginId, String password, String nickname) {
        // 한 번 더 중복 검증
        if (!isLoginIdAvailable(loginId)) {
            return false;
        }

        String hashedPassword = hashPassword(password);
        UserDto newUser = new UserDto(loginId, hashedPassword, nickname);

        return userDao.insertUser(newUser);
    }

    /**
     * 로그인 처리
     * @return 성공 시 UserDto 반환, 실패 시 null 반환
     */
    public UserDto login(String loginId, String password) {
        UserDto user = userDao.findByLoginId(loginId);

        // 아이디가 존재하고, 저장된 해시와 비밀번호가 맞는지 확인
        if (user == null || !verifyPassword(password, user.getPassword())) {
            return null; // 인증 실패 (아이디 없음 또는 비밀번호 불일치)
        }

        upgradeLegacyHash(user, password);
        issueSessionToken(user);
        return user;
    }

    // =========================================================================
    // 중복 로그인 — 나중에 로그인한 쪽이 이깁니다
    // =========================================================================
    /**
     * 로그인할 때마다 새 세션 표를 발급해 DB 와 세션 사본에 함께 적어 둡니다.
     *
     * <p>같은 계정으로 다른 기기에서 로그인하면 DB 의 표가 새 값으로 덮이므로, 먼저 있던
     * 기기가 들고 있는 표는 그 순간 낡은 값이 됩니다. 그 기기는 다음에 화면을 새로 그릴 때
     * {@link #isSessionCurrent} 로 알아채고 물러납니다.
     *
     * <p><b>먼저 로그인한 쪽을 지키는 방식(신규 거부)을 쓰지 않은 이유</b> — 앱이 강제 종료되면
     * DB 에 표가 남아 본인 계정에 다시 못 들어갑니다. 그걸 풀려면 만료 시간과 주기적인
     * 살아있음 신호가 필요한데, 원격 DB 에 주기적 왕복을 새로 만드는 셈이라 얻는 것보다
     * 잃는 것이 큽니다.
     *
     * <p>발급에 실패해도 로그인은 그대로 성공시킵니다 — 중복 로그인 차단은 편의 기능이라
     * 이것 때문에 로그인 자체를 막을 이유가 없습니다.
     */
    private void issueSessionToken(UserDto user) {
        String token = UUID.randomUUID().toString();
        if (userDao.updateSessionToken(user.getId(), token)) {
            user.setSessionToken(token);
        } else {
            user.setSessionToken(null);   // 못 받았으면 들고 있지 않습니다 — 확인도 건너뜁니다
            System.err.println("[auth] 세션 표를 발급하지 못해 중복 로그인 확인을 건너뜁니다.");
        }
    }

    /**
     * 이 세션이 아직 유효한지 — 그 사이 다른 기기에서 로그인하지 않았는지 확인합니다.
     *
     * <p><b>확실히 어긋났을 때만 {@code false}</b> 입니다. 표를 못 받았거나(발급 실패),
     * DB 를 못 읽었거나, 아직 아무 표도 없는 계정이면 모두 {@code true} 로 넘어갑니다 —
     * 판단이 서지 않을 때 쫓아내면, DB 가 잠깐 흔들릴 때마다 멀쩡히 쓰던 사람이 로그아웃됩니다.
     * 중복 로그인을 한 번 놓치는 편이 그보다 낫습니다.
     *
     * @param myToken 이 기기가 로그인할 때 받은 표 ({@code null} 이면 확인하지 않음)
     */
    public boolean isSessionCurrent(int userId, String myToken) {
        if (userId <= 0 || myToken == null || myToken.isBlank()) {
            return true;
        }
        try {
            String current = userDao.findSessionToken(userId);
            if (current == null || current.isBlank()) {
                return true;   // 아직 표가 없는 계정 (칼럼을 막 추가한 직후 등)
            }
            return current.equals(myToken);
        } catch (RuntimeException e) {
            System.err.println("[auth] 세션 표를 확인하지 못해 로그인을 유지합니다: " + e.getMessage());
            return true;
        }
    }

    /**
     * 예전 방식으로 저장된 계정을 로그인에 성공한 김에 새 형식으로 바꿔 둡니다.
     *
     * <p>평문 비밀번호를 손에 쥐고 있는 순간은 여기뿐이라, 이 자리를 놓치면 그 계정은
     * 비밀번호를 직접 바꾸기 전까지 계속 예전 해시로 남습니다.
     *
     * <p><b>실패해도 로그인은 성공시킵니다</b> — 이관은 덤이고, 여기서 막으면 DB 가 잠깐
     * 흔들릴 때 멀쩡한 비밀번호로도 못 들어갑니다.
     */
    private void upgradeLegacyHash(UserDto user, String plainPassword) {
        if (!needsRehash(user.getPassword())) {
            return;
        }
        String upgraded = hashPassword(plainPassword);
        if (userDao.updatePassword(user.getId(), upgraded)) {
            // 세션이 들고 다닐 사본도 새 값으로 — 정보 수정 화면이 이 값으로 검증합니다
            user.setPassword(upgraded);
        } else {
            System.err.println("[auth] 비밀번호 형식을 새로 바꾸지 못했습니다."
                    + " 다음 로그인 때 다시 시도합니다.");
        }
    }

    // =========================================================================
    // 비밀번호 해시 (PBKDF2-HMAC-SHA256, 솔트 + 반복)
    // =========================================================================
    /**
     * 저장 형식 — {@code pbkdf2$sha256$<반복수>$<솔트 Base64>$<해시 Base64>}.
     *
     * <p>구분자를 {@code $} 로 둔 것은 Base64 알파벳에 없는 글자여서 쪼갤 때 안전하기 때문입니다.
     * 길이는 약 90자로 {@code user.password VARCHAR(255)} 에 넉넉히 들어갑니다.
     */
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String PREFIX = "pbkdf2$sha256$";

    /** OWASP 권장치(PBKDF2-HMAC-SHA256). 이 기기 기준 약 143ms 걸립니다. */
    private static final int ITERATIONS = 600_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 예전 방식 — 솔트 없는 SHA-256 (소문자 16진수 64자). {@link #verifyPassword} 만 알아봅니다. */
    private static final int LEGACY_LENGTH = 64;

    /**
     * 저장용 비밀번호 해시를 만듭니다.
     *
     * <p><b>같은 비밀번호라도 부를 때마다 다른 값이 나옵니다</b> — 계정마다 새 솔트를 뽑기
     * 때문입니다. 그래서 이 값끼리 {@code equals} 로 비교하면 안 되고, 반드시
     * {@link #verifyPassword} 로 확인해야 합니다.
     *
     * <p>예전에는 솔트 없는 SHA-256 한 번이었습니다. 같은 비밀번호를 쓰는 두 계정의 해시가
     * 똑같이 나와 한 명이 뚫리면 같이 뚫렸고, 미리 계산된 표(레인보우 테이블)로 되돌리기도
     * 쉬웠습니다.
     */
    public String hashPassword(String plainPassword) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] hash = derive(plainPassword, salt, ITERATIONS);

        Base64.Encoder encoder = Base64.getEncoder().withoutPadding();
        return PREFIX + ITERATIONS + "$" + encoder.encodeToString(salt) + "$" + encoder.encodeToString(hash);
    }

    private static byte[] derive(String plainPassword, byte[] salt, int iterations) {
        char[] chars = plainPassword == null ? new char[0] : plainPassword.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(chars, salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new RuntimeException("비밀번호 암호화 중 시스템 오류가 발생했습니다.", e);
        } finally {
            spec.clearPassword();
        }
    }

    /** 예전 방식(솔트 없는 SHA-256)으로 만든 값인지. */
    private static boolean isLegacy(String storedHash) {
        if (storedHash == null || storedHash.length() != LEGACY_LENGTH) {
            return false;
        }
        for (int i = 0; i < LEGACY_LENGTH; i++) {
            char c = storedHash.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    /** 예전 방식 재현 — <b>검증 전용</b>입니다. 새로 저장할 때는 절대 쓰지 않습니다. */
    private static String legacyHash(String plainPassword) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] byteData = md.digest(
                    (plainPassword == null ? "" : plainPassword).getBytes(StandardCharsets.UTF_8));

            StringBuilder sb = new StringBuilder(LEGACY_LENGTH);
            for (byte b : byteData) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("비밀번호 검증 중 시스템 오류가 발생했습니다.", e);
        }
    }

    // 닉네임 사용 가능 여부 확인
    public boolean isNicknameAvailable(String nickname) {
        // Dao에서 찾아왔는데 null(아무도 안씀) 이면 true 반환
        return userDao.findByNickname(nickname) == null;
    }

    /**
     * 입력한 비밀번호가 저장된 해시와 맞는지 확인합니다.
     *
     * <p>새 형식(PBKDF2)과 예전 형식(솔트 없는 SHA-256)을 모두 알아봅니다 — 이미 가입한
     * 회원이 로그인하지 못하면 안 되니까요. 예전 형식 계정은 {@link #login} 이 성공한 김에
     * 새 형식으로 조용히 바꿔 둡니다.
     *
     * <p>비교는 {@link MessageDigest#isEqual} 로 합니다. {@code String.equals} 는 앞에서부터
     * 다른 글자가 나오면 바로 끝나서, 걸린 시간으로 '몇 글자까지 맞았는지'가 새어 나갑니다.
     */
    public boolean verifyPassword(String inputPassword, String savedHashedPassword) {
        if (savedHashedPassword == null || savedHashedPassword.isBlank()) {
            return false;
        }
        if (isLegacy(savedHashedPassword)) {
            return constantTimeEquals(legacyHash(inputPassword), savedHashedPassword);
        }
        if (!savedHashedPassword.startsWith(PREFIX)) {
            System.err.println("[auth] 알아볼 수 없는 비밀번호 형식이라 검증을 거부했습니다.");
            return false;
        }

        // pbkdf2$sha256$<반복수>$<솔트>$<해시>
        String[] parts = savedHashedPassword.split("\\$");
        if (parts.length != 5) {
            System.err.println("[auth] 비밀번호 해시가 손상되어 검증을 거부했습니다.");
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[2]);
            byte[] salt = Base64.getDecoder().decode(parts[3]);
            byte[] expected = Base64.getDecoder().decode(parts[4]);
            return MessageDigest.isEqual(derive(inputPassword, salt, iterations), expected);
        } catch (IllegalArgumentException e) {   // 반복수 파싱 실패, Base64 손상 모두 포함
            System.err.println("[auth] 비밀번호 해시를 읽지 못해 검증을 거부했습니다: " + e.getMessage());
            return false;
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /** 저장된 해시가 예전 방식이라 다시 만들어 둘 필요가 있는지. */
    public boolean needsRehash(String savedHashedPassword) {
        return isLegacy(savedHashedPassword);
    }

    /**
     * 닉네임과(비웠으면 그대로 두고) 비밀번호를 바꾼 뒤, 세션이 들고 다니는 사본까지 맞춥니다.
     *
     * <p><b>사본을 여기서 맞추는 이유</b> — {@code UserDto.password} 에는 언제나 <b>저장된 해시</b>가
     * 들어 있어야 합니다({@link #verifyPassword} 가 그 전제로 읽습니다). 예전에는 저장만 이 자리가
     * 하고 사본은 화면이 {@code me.setPassword(사용자가 입력한 값)} 으로 맞췄습니다. 그래서 비밀번호를
     * 한 번 바꾸면 그 세션의 사본이 <b>평문</b>이 되어, 이후 검증이 맞는 비밀번호에도 전부 실패했습니다
     * — 정보 수정도 탈퇴도 다시 로그인하기 전까지 막혔습니다.
     *
     * <p>무엇을 담아야 하는지는 해시를 만드는 이 자리만 알면 되므로, 화면에서 가져와 여기로 옮겼습니다.
     * {@link #upgradeLegacyHash} 가 이관한 해시를 사본에 적어 두는 것과 같은 규칙입니다.
     *
     * @param user             지금 로그인한 사람 — 성공하면 이 객체의 비밀번호·닉네임이 갱신됩니다
     * @param plainNewPassword 비워 두면 비밀번호를 바꾸지 않습니다
     * @return 저장에 실패하면 false — 이때 {@code user} 는 건드리지 않습니다
     */
    public boolean updateProfile(UserDto user, String plainNewPassword, String newNickname) {
        if (user == null || user.getId() == null || user.getId() <= 0) {
            return false;
        }

        // 사용자가 새 비밀번호 칸을 비웠다면 기존 비밀번호 유지
        boolean changingPassword = plainNewPassword != null && !plainNewPassword.isBlank();
        String passwordToSave = changingPassword
                ? hashPassword(plainNewPassword)
                : user.getPassword();

        // 그대로 두려는 값이 비어 있으면 저장하지 않습니다 — 한 문장으로 두 칼럼을 쓰는 UPDATE 라
        // 그냥 넘기면 비밀번호 칼럼이 비워져 그 계정으로 다시 못 들어갑니다.
        if (passwordToSave == null || passwordToSave.isBlank()) {
            System.err.println("[auth] 세션에 저장된 비밀번호가 없어 정보 변경을 중단했습니다.");
            return false;
        }

        if (!userDao.updateUserInfo(user.getId(), passwordToSave, newNickname)) {
            return false;
        }
        user.setPassword(passwordToSave);   // 언제나 해시입니다
        user.setNickname(newNickname);
        return true;
    }

    public boolean deleteAccount(int userId, String plainPassword, String savedHashedPassword) {
        if (userId <= 0 || plainPassword == null || plainPassword.isBlank()) {
            return false;
        }
        if (!verifyPassword(plainPassword, savedHashedPassword)) {
            return false;
        }
        return userDao.deleteUser(userId);
    }
}