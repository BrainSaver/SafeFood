package com.safefood.view;

import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * 앱 스타일 — 화면 전체의 색을 정하는 테마.
 *
 * <p>{@code app.css} 는 색을 전부 {@code .root} 의 토큰({@code -bg}, {@code -accent} …)으로만
 * 씁니다. 덕분에 테마 하나를 더하는 일은 <b>토큰 값만 다시 정의한 css 한 장</b>을 app.css 뒤에
 * 얹는 것으로 끝납니다. 둥글기·여백·글꼴 같은 규칙은 손대지 않으니, 화면을 새로 만들어도
 * 모든 테마에서 그대로 동작합니다. 새 테마를 넣으려면 {@code view/theme/} 에 css 를 하나
 * 만들고 여기에 한 줄 추가하면 됩니다.
 *
 * <p><b>고른 값을 어디에 두는가</b> — {@link Preferences}(이 PC의 사용자 설정)에 둡니다.
 * config.properties 는 Setup Wizard 가 만드는 접속 정보 파일이라 화면 취향까지 섞으면
 * 마법사가 덮어쓸 때 같이 날아가고, DB의 회원 정보에 두면 게스트는 고를 수 없습니다.
 * 테마는 '이 컴퓨터에서 보기 좋은 색'이라 계정보다 PC에 붙는 편이 맞습니다.
 */
public enum Theme {

    /** 기본 — app.css 자체의 색이라 덧입힐 파일이 없습니다. */
    ORGANIC("오가닉", "따뜻한 테라코타. 기본값이에요.", null,
            "#F5EAD8", "#EBDDC5", "#C67139"),

    MIDNIGHT("미드나잇", "어두운 바탕 — 밤에 눈이 덜 부셔요.", "theme/midnight.css",
            "#1B1917", "#262320", "#E08A4C"),

    OCEAN("오션", "차분한 바다색. 오래 봐도 담담해요.", "theme/ocean.css",
            "#EEF4F8", "#DFEAF2", "#2F7EA8"),

    BERRY("베리", "부드러운 자두색. 화면이 화사해져요.", "theme/berry.css",
            "#F7EFF4", "#EEDFE9", "#9B4A78");

    private static final String PREF_KEY = "app.theme";

    private final String label;
    private final String description;
    private final String css;

    // 미리보기용 색 — '지금 테마'가 아니라 '저 테마'의 색이라 토큰으로는 못 그립니다
    private final String bg;
    private final String surface;
    private final String accent;

    Theme(String label, String description, String css,
          String bg, String surface, String accent) {
        this.label = label;
        this.description = description;
        this.css = css;
        this.bg = bg;
        this.surface = surface;
        this.accent = accent;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    /** {@code /com/safefood/view/} 아래의 상대 경로. 기본 테마는 덧입힐 것이 없어 {@code null}. */
    public String css() {
        return css;
    }

    public String bg() {
        return bg;
    }

    public String surface() {
        return surface;
    }

    public String accent() {
        return accent;
    }

    /** 지난번에 고른 테마. 저장된 값이 없거나 읽지 못하면 기본값입니다. */
    public static Theme saved() {
        try {
            String name = Preferences.userNodeForPackage(Theme.class).get(PREF_KEY, ORGANIC.name());
            return valueOf(name);
        } catch (IllegalArgumentException e) {
            return ORGANIC;   // 테마를 지우거나 이름을 바꾼 뒤 남아 있던 값
        } catch (SecurityException e) {
            System.err.println("[UI] 저장된 앱 스타일을 읽지 못했습니다: " + e.getMessage());
            return ORGANIC;
        }
    }

    /** 다음 실행에도 이 테마로 열리게 남깁니다. 실패해도 지금 화면은 이미 바뀐 뒤입니다. */
    public void remember() {
        try {
            Preferences prefs = Preferences.userNodeForPackage(Theme.class);
            prefs.put(PREF_KEY, name());
            prefs.flush();
        } catch (SecurityException | BackingStoreException e) {
            System.err.println("[UI] 앱 스타일을 저장하지 못했습니다: " + e.getMessage());
        }
    }
}
