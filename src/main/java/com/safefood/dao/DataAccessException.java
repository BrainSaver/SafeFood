package com.safefood.dao;

/**
 * DB 조회가 실패했다는 뜻 — <b>결과가 없다는 뜻이 아닙니다.</b>
 *
 * <p>예전에는 DAO 가 {@code SQLException} 을 {@code printStackTrace} 로 삼키고 빈 목록을
 * 돌려줬습니다. 그러면 화면에서 "조회 실패"와 "데이터 없음"이 구분되지 않아,
 * 알레르기 조회가 실패한 메뉴가 <b>안전한 메뉴</b>로 보이고 추천 목록이 비면
 * 데모 데이터가 진짜 추천인 것처럼 그려졌습니다.
 *
 * <p>그래서 <b>안전 판정에 쓰이는 조회</b>는 실패를 감추지 않고 이 예외로 올립니다.
 * 화면은 이걸 받아 "불러오지 못했습니다"라고 정직하게 알립니다.
 */
public class DataAccessException extends RuntimeException {

    public DataAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
