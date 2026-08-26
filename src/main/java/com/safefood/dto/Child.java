package com.safefood.dto;

/**
 * 급식을 확인할 아이 한 명. 부모 계정({@code user}) 하나에 여러 명이 달릴 수 있습니다.
 *
 * <p>학교는 별도 테이블을 두지 않고 나이스 코드 두 개를 여기 그대로 적어 둡니다.
 * 학교 검색도 그때그때 나이스를 부르므로, 우리가 학교 목록을 들고 있을 이유가 없습니다.
 */
public record Child(int id, int userId, String name,
                    String atptCode, String schulCode, String schulNm) {

    /** 급식을 조회할 수 있는 상태인지 — 학교를 아직 안 고른 아이일 수 있습니다. */
    public boolean hasSchool() {
        return atptCode != null && !atptCode.isBlank()
                && schulCode != null && !schulCode.isBlank();
    }

    public String schoolLabel() {
        return (schulNm == null || schulNm.isBlank()) ? "학교 미지정" : schulNm;
    }
}
