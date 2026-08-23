import java.sql.*;
public class DBCheck {
    public static void main(String[] args) throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");
        Connection conn = DriverManager.getConnection("jdbc:mysql://example-db-host:8306/safefood?serverTimezone=Asia/Seoul", "root", "***REMOVED***");
        ResultSet rs = conn.createStatement().executeQuery("DESC history");
        while(rs.next()) {
            System.out.println(rs.getString("Field") + " | " + rs.getString("Type"));
        }
    }
}
