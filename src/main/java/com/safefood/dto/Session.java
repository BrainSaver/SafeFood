package com.safefood.dto;

public class Session {
    private static UserDto currentUser; // 정회원용
    private static PreferenceDto guestPreference; // 게스트용
    private static LocationDto myLocation; // 등록해 둔 내 위치 (회원은 DB 값의 사본, 게스트는 이것뿐)


    // 유저 정보를 저장함
    public static void setCurrentUser(UserDto user){
        currentUser = user;
    }

    // 유저 정보를 전달함
    public static UserDto getCurrentUser(){
        return currentUser;
    }

    // 게스트 정보 저장&전달
    public static void setGuestPreference(PreferenceDto pref){ guestPreference = pref; }
    public static PreferenceDto getGuestPreferences(){ return guestPreference; }

    /*
     * 내 위치 — 추천의 '최대 거리'와 같이 먹기의 '중간 지점'이 기준으로 삼는 곳입니다.
     * 회원 것은 user_location 에 저장되고 여기엔 사본만 둡니다 (화면마다 다시 조회하지 않도록).
     * 게스트는 저장할 계정이 없어 앱을 켜 둔 동안만 남습니다.
     */
    public static void setMyLocation(LocationDto location){ myLocation = location; }
    public static LocationDto getMyLocation(){ return myLocation; }

    // 로그아웃 시 유저 정보를 지움
    public static void logout(){
        currentUser = null;
        guestPreference = null;
        myLocation = null;
    }
}
