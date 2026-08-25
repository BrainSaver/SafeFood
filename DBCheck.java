import java.sql.*;
public class DBCheck {
    public static void main(String[] args) throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");
        Connection conn = DriverManager.getConnection("jdbc:mysql://example-db-host:8306/safefood?serverTimezone=Asia/Seoul", "root", "***REMOVED***");
        System.out.println("=== 1. CategoryCatalog.names() (DB 카테고리 목록) ===");
        var categories = com.safefood.dao.CategoryCatalog.names();
        System.out.println("카테고리 수: " + categories.size() + " -> " + categories);

        System.out.println("\n=== 2. MenuDao 전체 메뉴 조회 ===");
        com.safefood.dao.MenuDao menuDao = new com.safefood.dao.MenuDao();
        var menus = menuDao.getAllCandidateMenus();
        System.out.println("조회된 메뉴 수: " + menus.size());

        System.out.println("\n=== 3. RecommendService 개인 추천 테스트 ===");
        com.safefood.service.RecommendService recommendService = new com.safefood.service.RecommendService();
        var results = recommendService.getPersonalizedRecommendations(
                0, java.util.List.of(), "전체", java.util.List.of(), 5, 0, false, null, true
        );
        System.out.println("추천 결과 성공! (상위 5건):");
        for (int i = 0; i < Math.min(5, results.size()); i++) {
            var r = results.get(i);
            System.out.println(" - [" + r.getCategory() + "] " + r.getMenuName() + " (" + r.getPrice() + "원) @ " + r.getRestaurantName());
        }
    }
}
