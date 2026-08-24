package com.safefood.dto;

public class Session {

    /**
     * 게스트로 들어온 사람의 회원 번호 자리.
     *
     * <p>게스트도 이름을 들고 다녀야 해서 {@link UserDto} 를 쓰지만, DB 의 {@code user} 에는
     * 없는 사람이라 진짜 번호가 없습니다. 그래서 <b>회원인지 아닌지는 {@code currentUser} 가
     * null 인지가 아니라 이 번호인지로 판단해야 합니다</b> — 화면마다 다르게 검사하다가
     * 게스트가 회원용 경로로 새어 들어가 "찜했습니다" 같은 거짓 안내가 뜨곤 했습니다.
     * 판단은 {@link #isGuest()} · {@link #isMember()} 로만 하세요.
     */
    public static final int GUEST_ID = -1;

    private static UserDto currentUser; // 정회원 · 게스트 공용 (게스트는 id = GUEST_ID)
    private static PreferenceDto guestPreference; // 게스트용
    private static LocationDto myLocation; // 등록해 둔 내 위치 (회원은 DB 값의 사본, 게스트는 이것뿐)

    /**
     * {@link #myLocation} 이 <b>누구 것인지</b>.
     *
     * <p>캐시에 주인을 달아 두지 않으면 "비우는 것을 잊었을 때" 다른 사람 주소가 그대로
     * 보입니다. 주인을 붙여 두면 잊어도 잘못 읽히지 않습니다.
     */
    private static int locationOwnerId = GUEST_ID;


    /**
     * 지금 로그인한 사람을 바꿉니다.
     *
     * <p><b>사람이 바뀌면 그 사람 것이던 캐시를 함께 버립니다.</b> 로그인 화면으로 가는 길
     * 가운데 세션을 비우지 않는 것이 있었고(대기실에서 게스트가 방을 나가는 길, 회원가입 취소),
     * 그 뒤에 다른 아이디로 로그인하면 <b>이전 사람의 주소</b>가 화면에 남았습니다.
     * 나가는 길마다 비우기를 기억하는 방식은 이미 두 번 새어 나갔으므로,
     * 신원이 바뀌는 이 한 곳에서 끊습니다.
     */
    public static void setCurrentUser(UserDto user){
        if (!isSamePerson(currentUser, user)) {
            forgetPerUserCache();
        }
        currentUser = user;
    }

    /**
     * 두 번의 {@code setCurrentUser} 가 같은 사람인지.
     *
     * <p>게스트는 전부 {@link #GUEST_ID} 를 쓰므로 <b>번호가 같아도 같은 사람이 아닙니다</b> —
     * 게스트 A 가 나가고 게스트 B 가 들어온 경우도 캐시를 버려야 합니다. 그래서 신원으로
     * 인정하는 것은 <b>0보다 큰 회원 번호</b>뿐입니다. 같은 객체를 다시 넣는 경우
     * (온보딩이 방금 만든 회원을 다시 담는 경우)는 그대로 둡니다.
     */
    private static boolean isSamePerson(UserDto before, UserDto after){
        if (before == after) {
            return true;
        }
        if (before == null || after == null) {
            return false;
        }
        Integer beforeId = before.getId();
        Integer afterId = after.getId();
        return beforeId != null && afterId != null && beforeId > 0 && beforeId.equals(afterId);
    }

    /** 특정 사람에게 매인 캐시만 버립니다 (테마처럼 PC에 매인 값은 건드리지 않습니다). */
    private static void forgetPerUserCache(){
        myLocation = null;
        locationOwnerId = GUEST_ID;
        guestPreference = null;
    }

    // 유저 정보를 전달함
    public static UserDto getCurrentUser(){
        return currentUser;
    }

    /** 로그인한 회원인지 — 저장할 계정이 있는 사람인지. */
    public static boolean isMember(){
        return currentUserId() > 0;
    }

    /** 게스트로 들어와 있는지 — 화면은 보여 주되 DB 저장은 하지 않는 사람인지. */
    public static boolean isGuest(){
        return currentUser != null && currentUserId() == GUEST_ID;
    }

    /**
     * 저장에 쓸 회원 번호. 회원이 아니면 {@link #GUEST_ID} 이므로,
     * 이 값을 그대로 DB 에 넣지 말고 {@link #isMember()} 로 먼저 걸러 주세요.
     *
     * <p>{@code UserDto.getId()} 는 {@code Integer} 라서 아직 저장되지 않은 회원 객체는
     * null 일 수 있습니다. 그대로 풀면 NPE 가 나므로 여기서 한 번 걸러 둡니다.
     */
    public static int currentUserId(){
        if (currentUser == null || currentUser.getId() == null) {
            return GUEST_ID;
        }
        return currentUser.getId();
    }

    /** 화면 머리에 쓸 이름 — 닉네임이 비어 있으면 아이디로 대신합니다. */
    public static String displayName(String fallback){
        if (currentUser == null) {
            return fallback;
        }
        String nickname = currentUser.getNickname();
        if (nickname != null && !nickname.isBlank()) {
            return nickname;
        }
        String loginId = currentUser.getLoginId();
        return (loginId == null || loginId.isBlank()) ? fallback : loginId;
    }

    // 게스트 정보 저장&전달
    public static void setGuestPreference(PreferenceDto pref){ guestPreference = pref; }
    public static PreferenceDto getGuestPreferences(){ return guestPreference; }

    /*
     * 내 위치 — 추천의 '최대 거리'와 같이 먹기의 '중간 지점'이 기준으로 삼는 곳입니다.
     * 회원 것은 user_location 에 저장되고 여기엔 사본만 둡니다 (화면마다 다시 조회하지 않도록).
     * 게스트는 저장할 계정이 없어 앱을 켜 둔 동안만 남습니다.
     */
    public static void setMyLocation(LocationDto location){
        myLocation = location;
        locationOwnerId = currentUserId();
    }

    /**
     * 캐시해 둔 내 위치. 없으면 null 이고, 그때 화면이 DB 에서 한 번 읽어 옵니다.
     *
     * <p>담아 둘 때의 주인과 지금 로그인한 사람이 다르면 <b>없는 것으로 봅니다</b> —
     * 다른 사람 주소를 돌려주는 것보다 한 번 더 조회하는 편이 낫습니다.
     */
    public static LocationDto getMyLocation(){
        if (locationOwnerId != currentUserId()) {
            return null;
        }
        return myLocation;
    }

    /**
     * 로그아웃 — 세션에 남은 값을 <b>전부</b> 비웁니다.
     *
     * <p>{@code setCurrentUser(null)} 만 하면 위치 사본과 게스트 취향이 남아 다음 사람에게
     * 그대로 보입니다. 나가는 길은 화면마다 다르므로, 어느 화면에서 나가든 이 메서드를 부르세요.
     */
    public static void logout(){
        currentUser = null;
        forgetPerUserCache();
    }
}
