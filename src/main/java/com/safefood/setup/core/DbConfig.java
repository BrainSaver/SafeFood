package com.safefood.setup.core;

/**
 * MySQL 접속 정보.
 *
 * <p>비밀번호를 들고 있으므로 {@link #toString()}에는 절대 포함하지 않습니다.
 * (로그·예외 메시지에 비밀번호가 찍히는 사고를 막기 위함)
 */
public final class DbConfig {

    /**
     * 접속 옵션.
     * <ul>
     *   <li>serverTimezone / characterEncoding — 날짜·한글 깨짐 방지</li>
     *   <li>sslMode=REQUIRED — 연결을 <b>반드시 암호화</b>합니다</li>
     *   <li>allowPublicKeyRetrieval=false — 서버에서 공개키를 받아 오지 않습니다</li>
     * </ul>
     *
     * <p><b>예전에는 {@code useSSL=false&allowPublicKeyRetrieval=true} 였습니다.</b> "로컬
     * 전용"을 전제로 둔 값인데 실제로는 인터넷 너머의 DB 에 붙고 있어서, 아이디·비밀번호와
     * 오가는 모든 자료(회원 알레르기 정보 포함)가 <b>평문</b>으로 흘렀습니다. 게다가 이 둘을
     * 함께 켜면 {@code caching_sha2_password} 인증에서 서버 공개키를 평문 통로로 받아 오는데,
     * 중간에서 가짜 키를 끼워 넣으면 <b>DB 비밀번호가 그대로 복구</b>됩니다. 드라이버가 이
     * 옵션을 기본으로 꺼 두고 명시적으로 켜게 해 둔 이유가 이것입니다.
     *
     * <p>{@code REQUIRED} 는 암호화만 요구하고 인증서 발급자는 따지지 않습니다. MySQL 이 스스로
     * 만든 자체 서명 인증서로도 붙으므로 팀 서버 설정을 바꾸지 않아도 됩니다. 정식 인증서를
     * 갖추면 {@code VERIFY_IDENTITY} 로 올려 중간자까지 막을 수 있습니다.
     */
    static final String PARAMS =
            "serverTimezone=Asia/Seoul&characterEncoding=UTF-8"
                    + "&sslMode=REQUIRED&allowPublicKeyRetrieval=false";

    private final String host;
    private final int port;
    private final String database;
    private final String user;
    private final String password;

    public DbConfig(String host, int port, String database, String user, String password) {
        this.host = host;
        this.port = port;
        this.database = database;
        this.user = user;
        this.password = password;
    }

    /** 데이터베이스를 지정하지 않은 URL — DB를 만들기 전 서버에만 붙을 때 사용 */
    String serverUrl() {
        return "jdbc:mysql://" + host + ":" + port + "/?" + PARAMS;
    }

    /** 애플리케이션이 실제로 쓰는 URL */
    String databaseUrl() {
        return "jdbc:mysql://" + host + ":" + port + "/" + database + "?" + PARAMS;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public String database() {
        return database;
    }

    public String user() {
        return user;
    }

    public String password() {
        return password;
    }

    @Override
    public String toString() {
        return user + "@" + host + ":" + port + " (DB: " + database + ")";
    }
}
