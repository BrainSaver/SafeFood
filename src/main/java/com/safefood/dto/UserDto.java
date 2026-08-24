package com.safefood.dto;

import java.time.LocalDateTime;

public class UserDto {
    private Integer id;
    private String loginId;
    private String password;
    private String nickname;
    private LocalDateTime createdAt;
    private String sessionToken;

    // 기본 생성자
    public UserDto() {}

    // 회원가입용 생성자 (id와 createdAt은 DB에서 자동 생성)
    public UserDto(String loginId, String password, String nickname) {
        this.loginId = loginId;
        this.password = password;
        this.nickname = nickname;
    }

    // Getter 및 Setter
    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }

    public String getLoginId() { return loginId; }
    public void setLoginId(String loginId) { this.loginId = loginId; }

    public String getPassword() { return password; }

    /**
     * 이 로그인이 받은 세션 표. 다른 기기에서 로그인하면 DB 값이 바뀌어 이 값과 어긋납니다.
     * (같은 {@code user} 테이블 칼럼이라 여기 함께 담습니다.)
     */
    public String getSessionToken() { return sessionToken; }

    public void setSessionToken(String sessionToken) { this.sessionToken = sessionToken; }
    public void setPassword(String password) { this.password = password; }

    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}