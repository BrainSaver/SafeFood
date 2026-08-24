package com.safefood.dao;

import com.safefood.dto.LocationDto;
import com.safefood.setup.core.Schema;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;

/**
 * 등록해 둔 내 위치 읽기·쓰기 (user_location 테이블, 회원당 1행).
 *
 * <p>이 테이블은 설정 마법사가 처음 만들어진 뒤에 추가됐습니다. 이미 데이터베이스를 만들어
 * 쓰고 있는 팀원은 마법사를 다시 돌리지 않는 한 테이블이 없으므로, 처음 쓸 때 한 번
 * {@code CREATE TABLE IF NOT EXISTS}를 실행해 둡니다 — DDL은 {@link Schema}에 있는 그 문장
 * 그대로라 스키마가 두 벌로 갈라지지 않습니다.
 *
 * <p>테이블이 이미 있으면 {@link #migrate} 가 <b>모자란 컬럼만</b> 채웁니다. 정의가 한 번
 * 바뀌었기 때문입니다(zonecode 추가, 좌표를 비워 둘 수 있게). {@code CREATE TABLE IF NOT EXISTS}
 * 는 이미 있는 테이블의 구조를 고치지 않아서, 그것만으로는 옛 모양이 그대로 남습니다.
 */
public class LocationDao {

    private static final String TABLE = "user_location";

    /** JVM 한 번에 한 번만 확인하면 됩니다 — 매 조회마다 DDL을 던지지 않도록. */
    private static volatile boolean tableReady = false;

    public LocationDto getLocation(int userId) {
        String sql = "SELECT label, zonecode, address, latitude, longitude "
                   + "FROM user_location WHERE user_id = ?";
        try (Connection conn = DatabaseUtil.getConnection()) {
            ensureTable(conn);
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setInt(1, userId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return new LocationDto(
                                rs.getString("label"), rs.getString("zonecode"),
                                rs.getString("address"),
                                nullableDouble(rs, "latitude"), nullableDouble(rs, "longitude"));
                    }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;   // 아직 등록하지 않았습니다
    }

    /** 등록·수정 겸용 — 회원당 1행이라 있으면 덮어씁니다. */
    public boolean saveLocation(int userId, LocationDto location) {
        String sql = "INSERT INTO user_location (user_id, label, zonecode, address, latitude, longitude) "
                   + "VALUES (?, ?, ?, ?, ?, ?) "
                   + "ON DUPLICATE KEY UPDATE label = ?, zonecode = ?, address = ?, "
                   + "latitude = ?, longitude = ?";
        try (Connection conn = DatabaseUtil.getConnection()) {
            ensureTable(conn);
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                // INSERT 용
                pstmt.setInt(1, userId);
                pstmt.setString(2, location.getLabel());
                pstmt.setString(3, location.getZonecode());
                pstmt.setString(4, location.getAddress());
                setNullableDouble(pstmt, 5, location.getLatitude());
                setNullableDouble(pstmt, 6, location.getLongitude());

                // UPDATE 용
                pstmt.setString(7, location.getLabel());
                pstmt.setString(8, location.getZonecode());
                pstmt.setString(9, location.getAddress());
                setNullableDouble(pstmt, 10, location.getLatitude());
                setNullableDouble(pstmt, 11, location.getLongitude());

                pstmt.executeUpdate();
                return true;
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    /** 등록 해제 — 행을 지웁니다. 없던 행이어도 오류가 아닙니다. */
    public boolean deleteLocation(int userId) {
        String sql = "DELETE FROM user_location WHERE user_id = ?";
        try (Connection conn = DatabaseUtil.getConnection()) {
            ensureTable(conn);
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setInt(1, userId);
                pstmt.executeUpdate();
                return true;
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    /** DECIMAL 컬럼은 비어 있을 수 있습니다 — getDouble 은 NULL을 0.0으로 돌려주므로 따로 봅니다. */
    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private static void setNullableDouble(PreparedStatement pstmt, int index, Double value)
            throws SQLException {
        if (value == null) {
            pstmt.setNull(index, Types.DECIMAL);
        } else {
            pstmt.setDouble(index, value);
        }
    }

    private static void ensureTable(Connection conn) throws SQLException {
        if (tableReady) {
            return;
        }
        synchronized (LocationDao.class) {
            if (tableReady) {
                return;
            }
            if (exists(conn)) {
                migrate(conn);
            } else {
                run(conn, Schema.ddlOf(TABLE));
                System.out.println("[db] " + TABLE + " 테이블을 새로 만들었습니다.");
            }
            tableReady = true;
        }
    }

    /**
     * 이미 만들어져 있는 테이블을 지금 정의에 맞춥니다.
     *
     * <p>첫 판에는 {@code zonecode} 가 없었고 좌표가 {@code NOT NULL} 이었습니다 — 우편번호
     * 서비스로 <b>주소만</b> 등록하는 길이 생기기 전이라서요. {@code CREATE TABLE IF NOT EXISTS}
     * 는 이미 있는 테이블의 구조를 건드리지 않으므로({@link Schema} 클래스 주석 참고),
     * 모자란 것만 여기서 채웁니다. 데이터는 그대로 남습니다.
     */
    private static void migrate(Connection conn) throws SQLException {
        if (!hasColumn(conn, "zonecode")) {
            run(conn, "ALTER TABLE user_location ADD COLUMN zonecode VARCHAR(10) AFTER label");
            System.out.println("[db] " + TABLE + ".zonecode 컬럼을 추가했습니다.");
        }
        if (isRequired(conn, "latitude") || isRequired(conn, "longitude")) {
            run(conn, "ALTER TABLE user_location "
                    + "MODIFY latitude DECIMAL(10, 7) NULL, MODIFY longitude DECIMAL(10, 7) NULL");
            System.out.println("[db] " + TABLE + " 좌표 컬럼을 비워 둘 수 있게 바꿨습니다.");
        }
    }

    private static void run(Connection conn, String sql) throws SQLException {
        try (Statement statement = conn.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static boolean exists(Connection conn) throws SQLException {
        try (ResultSet tables = conn.getMetaData()
                .getTables(conn.getCatalog(), null, TABLE, new String[]{"TABLE"})) {
            return tables.next();
        }
    }

    private static boolean hasColumn(Connection conn, String column) throws SQLException {
        try (ResultSet columns = conn.getMetaData()
                .getColumns(conn.getCatalog(), null, TABLE, column)) {
            return columns.next();
        }
    }

    /** 그 컬럼이 아직 NOT NULL 인지 — 없는 컬럼이면 false 입니다. */
    private static boolean isRequired(Connection conn, String column) throws SQLException {
        try (ResultSet columns = conn.getMetaData()
                .getColumns(conn.getCatalog(), null, TABLE, column)) {
            return columns.next() && "NO".equals(columns.getString("IS_NULLABLE"));
        }
    }
}
