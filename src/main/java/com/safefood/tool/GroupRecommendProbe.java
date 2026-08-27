package com.safefood.tool;

import com.safefood.service.GroupRecommendService;
import com.safefood.service.GroupRecommendService.GroupCondition;
import com.safefood.service.GroupRecommendService.GroupResult;

import java.util.Set;

/**
 * 앱을 띄우지 않고 그룹 추천만 눈으로 확인하는 도구: {@code mvn compile exec:java -Pprobe-group}
 *
 * <p>조건을 몇 벌 넣어 보고 <b>훑은 메뉴 / 알레르기로 제외 / 후보</b> 개수를 찍습니다.
 * 후보가 비어 나올 때 "메뉴 데이터가 모자란 것"인지 "조건이 너무 빡빡한 것"인지를 가르는 데 씁니다.
 *
 * <p>{@code config.properties} 를 읽으므로 <b>프로젝트 루트에서</b> 실행하세요.
 *
 * <p>예전에는 이 코드가 {@link GroupRecommendService} 안의 {@code main} 이었습니다. 서비스 클래스에
 * 두면 배포 JAR 에 그대로 실려 나가고, 실행하면 곧장 운영 DB 를 두드립니다. 판정 규칙 자체는
 * {@code GroupRecommendServiceTest} 가 DB 없이 확인하므로, 여기 남는 것은 <b>실제 데이터로 눈으로
 * 보는 일</b>뿐이라 도구 자리로 옮겼습니다.
 */
public final class GroupRecommendProbe {

    private static final int CANDIDATES = 3;

    private GroupRecommendProbe() {
    }

    public static void main(String[] args) {
        GroupRecommendService service = new GroupRecommendService();

        print("조건 없음", service.recommend(new GroupCondition(Set.of(), 5, 0), CANDIDATES));
        print("새우·땅콩 제외 / 맵기 1 / 8,000원",
                service.recommend(new GroupCondition(Set.of("새우", "땅콩"), 1, 8000), CANDIDATES));
        print("대두·밀·돼지고기·계란·새우·토마토 제외",
                service.recommend(new GroupCondition(
                        Set.of("대두", "밀", "돼지고기", "계란", "새우", "토마토"), 5, 0), CANDIDATES));
    }

    private static void print(String title, GroupResult result) {
        System.out.println("=== " + title + " ===");
        if (result.failed()) {
            System.out.println("  메뉴를 불러오지 못했습니다. (config.properties 의 db.* 를 확인하세요)");
            System.out.println();
            return;
        }
        System.out.printf("  훑은 메뉴 %d개 · 알레르기로 제외 %d개 · 후보 %d개%n",
                result.scannedMenus(), result.blockedByAllergy(), result.candidates().size());
        result.candidates().forEach(candidate -> System.out.println("  " + candidate));
        System.out.println();
    }
}
