package ffdd.opsconsole.finance.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import ffdd.opsconsole.market.mapper.AppRepurchaseMapper;
import ffdd.opsconsole.market.mapper.AppStakingMapper;
import ffdd.opsconsole.market.mapper.AppExchangeMapper;
import ffdd.opsconsole.shared.api.HistorySnapshotId;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Executes the three production history mapper queries only in a disposable MySQL schema. */
@EnabledIfEnvironmentVariable(named = "NEXION_FUNDS_HISTORY_SNAPSHOT_IT", matches = "true")
class FundsHistorySnapshotMySqlTest {
    private static final String OPTIONS = "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8";

    @Test
    void stakingReadsAndStateChangesStayInTheirProductDomain() throws Exception {
        inSchema(schema -> {
            try (Connection connection = connect(schema)) {
                createSchema(connection);
                connection.createStatement().execute("ALTER TABLE nx_staking_position ADD updated_at DATETIME");
                // Position 35/36 reproduce the live G1 exit plus active G7 combination.
                insertPosition(connection, 35, 1, "USDT_30D", "STK-35");
                insertPosition(connection, 36, 1, "REPURCHASE_90D", "RPS-36");
                for (int index = 0; index < 4; index++) {
                    insertPosition(connection, 40 + index, 1,
                            List.of("USDT_30D", "USDT_90D", "USDT_180D", "usdt_365d").get(index), "STK-" + (40 + index));
                }
                insertPosition(connection, 50, 2, "USDT_30D", "STK-OTHER");
                insertPosition(connection, 60, 1, "USDT_30D", "STK-DELETED");
                insertPosition(connection, 70, 1, "REPURCHASE_90D", "STK-G7-PREFIX");
                insertPosition(connection, 80, 1, "USDT_45D", "STK-UNKNOWN");
                connection.createStatement().execute("UPDATE nx_staking_position SET status='EARLY_WITHDRAWN' WHERE id=35");
                connection.createStatement().execute("UPDATE nx_staking_position SET is_deleted=1 WHERE id=60");
                assertThat(scalar(connection, sql(AppStakingMapper.class, "maxIssuedHistoryId", Long.class), 1)).isEqualTo(60);
                assertThat(scalar(connection, sql(AppStakingMapper.class, "countUserPositions", Long.class), 1)).isEqualTo(5);
                assertThat(scalar(connection, sql(AppStakingMapper.class, "countUserPositionsAt", Long.class, long.class), 1, 60)).isEqualTo(5);
                String history = sql(AppStakingMapper.class, "listUserPositionsAt", Long.class, long.class, int.class, Long.class);
                // Keep ordering deterministic even if the fixture crosses a wall-clock second.
                connection.createStatement().execute("UPDATE nx_staking_position SET created_at='2026-10-16 00:00:00'");
                assertThat(rowsPage(connection, history, "positionNo", 1, 60, 0, 2)).containsExactly("STK-43", "STK-42");
                assertThat(rowsPage(connection, history, "positionNo", 1, 60, 2, 10)).containsExactly("STK-41", "STK-40", "STK-35");
                assertThat(rows(connection, history, "status", 1, 60)).containsExactly("ACTIVE", "ACTIVE", "ACTIVE", "ACTIVE", "EARLY_WITHDRAWN");
                String locked = sql(AppStakingMapper.class, "lockUserPosition", Long.class, String.class);
                for (String number : List.of("RPS-36", "STK-G7-PREFIX", "STK-UNKNOWN", "STK-OTHER", "STK-DELETED")) {
                    try (var statement = connection.prepareStatement(bindable(locked))) {
                        statement.setLong(1, 1); statement.setString(2, number);
                        try (var result = statement.executeQuery()) { assertThat(result.next()).isFalse(); }
                    }
                }
                var now = java.time.LocalDateTime.of(2026, 10, 16, 0, 0);
                connection.createStatement().execute("UPDATE nx_staking_position SET unlock_at='2026-10-15 00:00:00'");
                assertThat(mutatePosition(connection, AppStakingMapper.class, "matureDuePositions", 0, now)).isEqualTo(4);
                for (long id : new long[]{35, 36, 50, 60, 70, 80}) {
                    assertThat(mutatePosition(connection, AppStakingMapper.class, "markClaimed", id, now)).isZero();
                }
                assertThat(mutatePosition(connection, AppStakingMapper.class, "markClaimed", 40, now)).isEqualTo(1);
                assertThat(mutatePosition(connection, AppStakingMapper.class, "markClaimed", 40, now)).isZero();
                // G7 remains ACTIVE until its own mapper processes it; its claim remains usable.
                assertThat(scalar(connection, "SELECT COUNT(*) FROM nx_staking_position WHERE id IN (36,70) AND status='ACTIVE'")).isEqualTo(2);
                assertThat(mutatePosition(connection, AppRepurchaseMapper.class, "matureDue", 0, now)).isEqualTo(2);
                assertThat(mutatePosition(connection, AppRepurchaseMapper.class, "markClaimed", 36, now)).isEqualTo(1);
                connection.createStatement().execute("UPDATE nx_staking_position SET status='ACTIVE',unlock_at='2027-01-01 00:00:00' WHERE id<>35");
                for (long id : new long[]{35, 36, 50, 60, 70, 80}) {
                    assertThat(mutatePosition(connection, AppStakingMapper.class, "markEarlyWithdrawn", id, now)).isZero();
                }
                assertThat(mutatePosition(connection, AppStakingMapper.class, "markEarlyWithdrawn", 41, now)).isEqualTo(1);
                assertThat(mutatePosition(connection, AppStakingMapper.class, "markEarlyWithdrawn", 41, now)).isZero();
                assertThat(mutatePosition(connection, AppRepurchaseMapper.class, "markEarly", 70, now)).isEqualTo(1);
                assertThat(mutatePosition(connection, AppRepurchaseMapper.class, "markEarly", 42, now)).isZero();
            }
        });
    }

    private static int mutatePosition(Connection connection, Class<?> mapper, String method, long id,
                                      java.time.LocalDateTime now) throws Exception {
        Class<?>[] types = id == 0 ? new Class<?>[]{Long.class, java.time.LocalDateTime.class}
                : new Class<?>[]{Long.class, Long.class, java.time.LocalDateTime.class};
        String sql = String.join(" ", mapper.getMethod(method, types).getAnnotation(Update.class).value());
        try (var statement = connection.prepareStatement(bindable(sql))) {
            var parameters = java.util.regex.Pattern.compile("#\\{([^}]+)}").matcher(sql);
            int index = 1;
            while (parameters.find()) {
                switch (parameters.group(1)) {
                    case "now" -> statement.setObject(index++, now);
                    case "id" -> statement.setLong(index++, id);
                    case "userId" -> statement.setLong(index++, 1);
                    default -> throw new IllegalArgumentException("Unknown position parameter");
                }
            }
            return statement.executeUpdate();
        }
    }

    @Test
    void highWaterPagesAndTotalsRemainStableAfterNewRowsAndNeverCrossAccounts() throws Exception {
        inSchema(schema -> {
            try (Connection connection = connect(schema)) {
                createSchema(connection);
                withdrawalsStayAtTheirFirstHighWater(connection);
                stakingPositionsStayAtTheirFirstHighWater(connection);
                repurchaseOrdersStayAtTheirFirstHighWater(connection);
                exchangePagesRetainIssuedBoundariesAfterSoftDeletion(connection);
            }
        });
    }

    private static void withdrawalsStayAtTheirFirstHighWater(Connection connection) throws Exception {
        insertWithdrawal(connection, 50, 1, "WD-50");
        insertWithdrawal(connection, 100, 1, "WD-100");
        insertWithdrawal(connection, 90, 2, "WD-OTHER");
        long boundary = scalar(connection, sql(AppWithdrawalMapper.class, "maxIssuedHistoryId", Long.class), 1L);
        assertThat(boundary).isEqualTo(100L);
        assertThat(rows(connection, sql(AppWithdrawalMapper.class, "userWithdrawalsAt", Long.class, long.class, int.class, Long.class),
                "withdrawalNo", 1L, boundary)).containsExactly("WD-100", "WD-50");
        assertThat(scalar(connection, sql(AppWithdrawalMapper.class, "countUserWithdrawalsAt", Long.class, long.class), 1L, boundary)).isEqualTo(2L);

        assertThat(rowsPage(connection, sql(AppWithdrawalMapper.class, "userWithdrawalsAt", Long.class, long.class, int.class, Long.class),
                "withdrawalNo", 1L, boundary, 0, 1)).containsExactly("WD-100");
        insertWithdrawal(connection, 101, 1, "WD-101");
        assertThat(rowsPage(connection, sql(AppWithdrawalMapper.class, "userWithdrawalsAt", Long.class, long.class, int.class, Long.class),
                "withdrawalNo", 1L, boundary, 1, 1)).containsExactly("WD-50");
        assertThat(rows(connection, sql(AppWithdrawalMapper.class, "userWithdrawalsAt", Long.class, long.class, int.class, Long.class),
                "withdrawalNo", 1L, boundary)).containsExactly("WD-100", "WD-50");
        assertThat(scalar(connection, sql(AppWithdrawalMapper.class, "countUserWithdrawalsAt", Long.class, long.class), 1L, boundary)).isEqualTo(2L);
        assertRetainedAfterDeletion(connection, AppWithdrawalMapper.class, "userWithdrawalsAt", "countUserWithdrawalsAt",
                "nx_withdrawal_order", "withdrawalNo", boundary, 101, "WD-50");
    }

    private static void stakingPositionsStayAtTheirFirstHighWater(Connection connection) throws Exception {
        insertPosition(connection, 150, 1, "USDT_30D", "STK-150");
        insertPosition(connection, 200, 1, "USDT_30D", "STK-200");
        insertPosition(connection, 190, 2, "USDT_30D", "STK-OTHER");
        long boundary = scalar(connection, sql(AppStakingMapper.class, "maxIssuedHistoryId", Long.class), 1L);
        assertThat(boundary).isEqualTo(200L);
        assertThat(rows(connection, sql(AppStakingMapper.class, "listUserPositionsAt", Long.class, long.class, int.class, Long.class),
                "positionNo", 1L, boundary)).containsExactly("STK-200", "STK-150");
        assertThat(scalar(connection, sql(AppStakingMapper.class, "countUserPositionsAt", Long.class, long.class), 1L, boundary)).isEqualTo(2L);

        assertThat(rowsPage(connection, sql(AppStakingMapper.class, "listUserPositionsAt", Long.class, long.class, int.class, Long.class),
                "positionNo", 1L, boundary, 0, 1)).containsExactly("STK-200");
        insertPosition(connection, 201, 1, "USDT_30D", "STK-201");
        assertThat(rowsPage(connection, sql(AppStakingMapper.class, "listUserPositionsAt", Long.class, long.class, int.class, Long.class),
                "positionNo", 1L, boundary, 1, 1)).containsExactly("STK-150");
        assertThat(rows(connection, sql(AppStakingMapper.class, "listUserPositionsAt", Long.class, long.class, int.class, Long.class),
                "positionNo", 1L, boundary)).containsExactly("STK-200", "STK-150");
        assertThat(scalar(connection, sql(AppStakingMapper.class, "countUserPositionsAt", Long.class, long.class), 1L, boundary)).isEqualTo(2L);
        assertRetainedAfterDeletion(connection, AppStakingMapper.class, "listUserPositionsAt", "countUserPositionsAt",
                "nx_staking_position", "positionNo", boundary, 201, "STK-150");
    }

    private static void repurchaseOrdersStayAtTheirFirstHighWater(Connection connection) throws Exception {
        insertPosition(connection, 250, 1, "REPURCHASE_90D", "RPS-250");
        insertPosition(connection, 300, 1, "REPURCHASE_90D", "RPS-300");
        insertPosition(connection, 275, 2, "REPURCHASE_90D", "RPS-OTHER");
        long boundary = scalar(connection, sql(AppRepurchaseMapper.class, "maxIssuedHistoryId", Long.class), 1L);
        assertThat(boundary).isEqualTo(300L);
        assertThat(rows(connection, sql(AppRepurchaseMapper.class, "positionsAt", Long.class, long.class, int.class, Long.class),
                "positionNo", 1L, boundary)).containsExactly("RPS-300", "RPS-250");
        assertThat(scalar(connection, sql(AppRepurchaseMapper.class, "countPositionsAt", Long.class, long.class), 1L, boundary)).isEqualTo(2L);

        assertThat(rowsPage(connection, sql(AppRepurchaseMapper.class, "positionsAt", Long.class, long.class, int.class, Long.class),
                "positionNo", 1L, boundary, 0, 1)).containsExactly("RPS-300");
        insertPosition(connection, 301, 1, "REPURCHASE_90D", "RPS-301");
        assertThat(rowsPage(connection, sql(AppRepurchaseMapper.class, "positionsAt", Long.class, long.class, int.class, Long.class),
                "positionNo", 1L, boundary, 1, 1)).containsExactly("RPS-250");
        assertThat(rows(connection, sql(AppRepurchaseMapper.class, "positionsAt", Long.class, long.class, int.class, Long.class),
                "positionNo", 1L, boundary)).containsExactly("RPS-300", "RPS-250");
        assertThat(scalar(connection, sql(AppRepurchaseMapper.class, "countPositionsAt", Long.class, long.class), 1L, boundary)).isEqualTo(2L);
        assertRetainedAfterDeletion(connection, AppRepurchaseMapper.class, "positionsAt", "countPositionsAt",
                "nx_staking_position", "positionNo", boundary, 301, "RPS-250");
    }

    private static void assertRetainedAfterDeletion(Connection connection, Class<?> mapper, String list, String count,
                                                    String table, String column, long oldBoundary, long latest,
                                                    String remaining) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute("UPDATE " + table + " SET is_deleted=1 WHERE user_id=1 AND id IN (" + oldBoundary + "," + latest + ")");
        }
        long retained = scalar(connection, sql(mapper, "maxIssuedHistoryId", Long.class), 1L);
        assertThat(retained).isEqualTo(latest);
        assertThat(HistorySnapshotId.resolve(Long.toString(oldBoundary), () -> retained)).isEqualTo(oldBoundary);
        assertThat(rowsPage(connection, sql(mapper, list, Long.class, long.class, int.class, Long.class),
                column, 1L, oldBoundary, 0, 50)).containsExactly(remaining);
        assertThat(scalar(connection, sql(mapper, count, Long.class, long.class), 1L, oldBoundary)).isEqualTo(1);
    }

    private static String sql(Class<?> mapper, String method, Class<?>... types) throws Exception {
        return String.join(" ", mapper.getMethod(method, types).getAnnotation(Select.class).value());
    }

    private static void exchangePagesRetainIssuedBoundariesAfterSoftDeletion(Connection connection) throws Exception {
        try (var command = connection.createStatement()) {
            command.execute("CREATE TABLE nx_exchange_order (id BIGINT PRIMARY KEY,user_id BIGINT,exchange_no VARCHAR(64),from_asset VARCHAR(16),to_asset VARCHAR(16),from_amount DECIMAL(20,6),to_amount DECIMAL(20,6),rate DECIMAL(20,6),status VARCHAR(32),created_at DATETIME,is_deleted INT)");
            command.execute("INSERT INTO nx_exchange_order VALUES (500,1,'EX-500','USDT','UVT',25,25,1,'COMPLETED','2026-09-01',0),(450,1,'EX-450','USDT','UVT',25,25,1,'CANCELLED','2026-09-01',0),(900,2,'EX-OTHER','USDT','UVT',25,25,1,'COMPLETED','2026-09-01',0)");
        }
        String max = sql(AppExchangeMapper.class, "maxIssuedHistoryId", Long.class);
        String list = sql(AppExchangeMapper.class, "userOrdersAt", Long.class, long.class, int.class, long.class);
        String count = sql(AppExchangeMapper.class, "countUserOrdersAt", Long.class, long.class);
        long boundary = scalar(connection, max, 1L);
        assertThat(boundary).isEqualTo(500);
        assertThat(rowsPage(connection, list, "exchangeNo", 1L, boundary, 0, 1)).containsExactly("EX-500");
        try (var command = connection.createStatement()) {
            command.execute("INSERT INTO nx_exchange_order VALUES (501,1,'EX-501','USDT','UVT',25,25,1,'FAILED','2026-09-01',0)");
        }
        assertThat(rowsPage(connection, list, "exchangeNo", 1L, boundary, 1, 1)).containsExactly("EX-450");
        assertThat(scalar(connection, count, 1L, boundary)).isEqualTo(2);
        try (var command = connection.createStatement()) { command.execute("UPDATE nx_exchange_order SET is_deleted=1 WHERE id>=500 AND user_id=1"); }
        long retainedMax = scalar(connection, max, 1L);
        assertThat(retainedMax).isEqualTo(501);
        assertThat(HistorySnapshotId.resolve("500", () -> retainedMax)).isEqualTo(500);
        assertThat(rowsPage(connection, list, "exchangeNo", 1L, boundary, 0, 50)).containsExactly("EX-450");
        assertThat(scalar(connection, count, 1L, boundary)).isEqualTo(1);
    }

    private static long scalar(Connection connection, String sql, long... values) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(bindable(sql))) {
            for (int index = 0; index < values.length; index++) statement.setLong(index + 1, values[index]);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getLong(1);
            }
        }
    }

    private static List<String> rows(Connection connection, String sql, String column, long userId, long snapshotId) throws Exception {
        return rowsPage(connection, sql, column, userId, snapshotId, 0, 50);
    }

    private static List<String> rowsPage(Connection connection, String sql, String column, long userId, long snapshotId,
                                       long offset, int limit) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(bindable(sql))) {
            var parameters = java.util.regex.Pattern.compile("#\\{([^}]+)}").matcher(sql);
            int index = 1;
            while (parameters.find()) {
                switch (parameters.group(1)) {
                    case "userId" -> statement.setLong(index++, userId);
                    case "snapshotId" -> statement.setLong(index++, snapshotId);
                    case "offset" -> statement.setLong(index++, offset);
                    case "limit" -> statement.setInt(index++, limit);
                    default -> throw new IllegalArgumentException("Unknown mapper parameter");
                }
            }
            try (ResultSet result = statement.executeQuery()) {
                List<String> values = new ArrayList<>();
                while (result.next()) values.add(result.getString(column));
                return values;
            }
        }
    }

    private static String bindable(String sql) {
        return sql.replaceAll("#\\{[^}]+}", "?");
    }

    private static void createSchema(Connection connection) throws Exception {
        connection.createStatement().execute("CREATE TABLE nx_user (id BIGINT PRIMARY KEY,status VARCHAR(32),is_deleted INT,sandbox INT) ENGINE=InnoDB");
        connection.createStatement().execute("INSERT INTO nx_user VALUES (1,'ACTIVE',0,0),(2,'ACTIVE',0,0)");
        connection.createStatement().execute("CREATE TABLE nx_wallet_ledger (user_id BIGINT,asset VARCHAR(16),direction VARCHAR(16),biz_type VARCHAR(64),biz_no VARCHAR(128),is_deleted INT,amount DECIMAL(20,6),created_at DATETIME) ENGINE=InnoDB");
        connection.createStatement().execute("CREATE TABLE nx_withdrawal_order (id BIGINT PRIMARY KEY,user_id BIGINT,withdrawal_no VARCHAR(64),amount DECIMAL(20,6),fee DECIMAL(20,6),chain VARCHAR(32),target_address VARCHAR(128),status VARCHAR(32),d2_hold_until DATETIME,d2_penalty_fee_rate DECIMAL(20,6),d2_network_fee_rate DECIMAL(20,6),d2_network_fee_min DECIMAL(20,6),d2_network_fee_max DECIMAL(20,6),d2_network_fee DECIMAL(20,6),d2_gross_fee DECIMAL(20,6),d2_nex_burned DECIMAL(20,6),d2_fee_waived DECIMAL(20,6),d2_actual_fee DECIMAL(20,6),d2_net_receive DECIMAL(20,6),created_at DATETIME,d5_policy_version VARCHAR(32),d5_use_nex_fee_offset BOOLEAN,d2_k3_risk_route VARCHAR(32),failure_reason VARCHAR(64),terminal_reason VARCHAR(64),retriable BOOLEAN,is_deleted INT) ENGINE=InnoDB");
        connection.createStatement().execute("CREATE TABLE nx_staking_position (id BIGINT PRIMARY KEY,user_id BIGINT,position_no VARCHAR(64),product_id BIGINT,product_code VARCHAR(64),product_name VARCHAR(64),amount_usdt DECIMAL(20,6),apy_bps DECIMAL(20,6),early_penalty_bps DECIMAL(20,6),term_days INT,locked_at DATETIME,unlock_at DATETIME,estimated_interest_usdt DECIMAL(20,6),status VARCHAR(32),claimed_at DATETIME,early_withdrawn_at DATETIME,is_deleted INT,created_at DATETIME) ENGINE=InnoDB");
    }

    private static void insertWithdrawal(Connection connection, long id, long userId, String number) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO nx_withdrawal_order VALUES (?,?,?,?,?,'USDT-BEP20','0x1','REVIEW_PENDING',NOW(),0,0,0,0,1,1,0,0,1,24,NOW(),'p1',0,'MANUAL',NULL,NULL,NULL,0)")) {
            statement.setLong(1, id); statement.setLong(2, userId); statement.setString(3, number);
            statement.setBigDecimal(4, java.math.BigDecimal.valueOf(25)); statement.setBigDecimal(5, java.math.BigDecimal.ONE);
            statement.executeUpdate();
        }
    }

    private static void insertPosition(Connection connection, long id, long userId, String productCode, String number) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO nx_staking_position (id,user_id,position_no,product_id,product_code,product_name,amount_usdt,apy_bps,early_penalty_bps,term_days,locked_at,unlock_at,estimated_interest_usdt,status,claimed_at,early_withdrawn_at,is_deleted,created_at) VALUES (?,?,?,1,?,'Product',25,1000,200,30,NOW(),DATE_ADD(NOW(),INTERVAL 30 DAY),1,'ACTIVE',NULL,NULL,0,NOW())")) {
            statement.setLong(1, id); statement.setLong(2, userId); statement.setString(3, number); statement.setString(4, productCode);
            statement.executeUpdate();
        }
    }

    private static Connection connect(String schema) throws Exception {
        String endpoint = System.getenv("NEXION_ISOLATED_MYSQL_ENDPOINT");
        assertThat(endpoint).as("explicit local test-server endpoint").matches("127\\.0\\.0\\.1:[1-9][0-9]{0,4}");
        assertThat(endpoint).as("do not use the business MySQL listener").doesNotEndWith(":3306");
        return DriverManager.getConnection("jdbc:mysql://" + endpoint + "/" + schema + OPTIONS, "root",
                System.getenv().getOrDefault("NEXION_ISOLATED_MYSQL_PASSWORD", ""));
    }

    private static void inSchema(SchemaTest test) throws Exception {
        String schema = "nexion_funds_history_it_" + UUID.randomUUID().toString().replace("-", "");
        if (!schema.matches("nexion_funds_history_it_[0-9a-f]{32}")) throw new IllegalStateException("unsafe test schema");
        try (Connection admin = connect("")) {
            admin.createStatement().execute("CREATE DATABASE " + schema);
            try (Connection connection = connect(schema)) { test.run(schema); }
            finally { admin.createStatement().execute("DROP DATABASE " + schema); }
        }
    }

    @FunctionalInterface
    private interface SchemaTest { void run(String schema) throws Exception; }
}
