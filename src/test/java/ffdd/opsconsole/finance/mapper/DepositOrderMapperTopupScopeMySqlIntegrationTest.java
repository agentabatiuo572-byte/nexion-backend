package ffdd.opsconsole.finance.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import ffdd.opsconsole.finance.domain.DepositFlowView;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Runs the production D1 count/page mapper methods in a disposable isolated MySQL schema. */
class DepositOrderMapperTopupScopeMySqlIntegrationTest {
    private static final String PREFIX = "nexion_topup_scope_it_";

    @Test
    void connectionGuardRejectsBusinessEndpointsAndUnownedSchemas() {
        String schema = PREFIX + "a".repeat(32);
        assertThat(jdbcUrl("127.0.0.1:13306", schema)).contains(":13306/" + schema + "?");
        for (String endpoint : Arrays.asList(null, "", "localhost:13306", "127.0.0.1:3306",
                "127.0.0.1:013306", "127.0.0.1:13306/other")) {
            assertThatThrownBy(() -> jdbcUrl(endpoint, schema)).isInstanceOf(IllegalArgumentException.class);
        }
        for (String invalid : Arrays.asList(null, "nexion", "mysql", PREFIX + "a", schema + "`")) {
            assertThatThrownBy(() -> jdbcUrl("127.0.0.1:13306", invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "NEXION_TOPUP_SCOPE_IT", matches = "true")
    void paymentRowsRequireTopupEvidenceAndCountMatchesPagedRows() throws Exception {
        inSchema(schema -> {
            try (Connection connection = connect(schema)) {
                assertSelectedSchema(connection, schema);
                createSchema(connection);
                insertFixtures(connection);
                Configuration configuration = new Configuration(new Environment("isolated-topup-scope",
                        new JdbcTransactionFactory(), new DriverManagerDataSource(url(schema), "root", password())));
                configuration.addMapper(DepositOrderMapper.class);
                try (var session = new MybatisSqlSessionFactoryBuilder().build(configuration).openSession()) {
                    DepositOrderMapper mapper = session.getMapper(DepositOrderMapper.class);
                    List<String> expected = List.of(
                            "7:DEP-CHAIN", "7:PAY-PENDING", "7:PAY-FAILED", "7:PAY-CHARGEBACK",
                            "7:PAY-SETTLED", "7:PAY-LEDGER", "8:PAY-OTHER", "8:CARD-FOREIGN-ADMISSION",
                            "7:PAY-FOREIGN-DEPOSIT", "8:DEP-FOREIGN-PAYMENT",
                            "7:TOPUP-FOREIGN-DEPOSIT", "8:TOPUP-FOREIGN-DEPOSIT");
                    List<String> all = mapper.pageFlows(null, null, null, 50, 0).stream()
                            .map(DepositOrderMapperTopupScopeMySqlIntegrationTest::flowKey).toList();
                    Map<String, String> statuses = mapper.pageFlows(null, null, null, 50, 0).stream()
                            .collect(Collectors.toMap(DepositOrderMapperTopupScopeMySqlIntegrationTest::flowKey,
                                    flow -> flow.status()));
                    Map<String, BigDecimal> received = mapper.pageFlows(null, null, null, 50, 0).stream()
                            .collect(Collectors.toMap(DepositOrderMapperTopupScopeMySqlIntegrationTest::flowKey,
                                    flow -> flow.providerReceived()));
                    List<String> paged = java.util.stream.IntStream.iterate(0, offset -> offset + 2)
                            .limit(6)
                            .boxed()
                            .flatMap(offset -> mapper.pageFlows(null, null, null, 2, offset).stream())
                            .map(DepositOrderMapperTopupScopeMySqlIntegrationTest::flowKey)
                            .toList();

                    assertThat(mapper.countFlows(null, null, null)).isEqualTo(12L);
                    assertThat(all).containsExactlyInAnyOrderElementsOf(expected);
                    assertThat(paged).containsExactlyInAnyOrderElementsOf(expected);
                    assertThat(all).doesNotContain("7:PAY-GOODS-PAID", "7:PAY-GOODS-FAILED", "7:PAY-NOT-TOPUP",
                            "7:PAY-FOREIGN-LEDGER", "7:PAY-FOREIGN-ADMISSION");
                    assertThat(mapper.countFlows(null, 7L, null)).isEqualTo(8L);
                    assertThat(mapper.pageFlows(null, 8L, null, 50, 0)).extracting(flow -> flow.depositNo())
                            .containsExactlyInAnyOrder("PAY-OTHER", "CARD-FOREIGN-ADMISSION",
                                    "DEP-FOREIGN-PAYMENT", "TOPUP-FOREIGN-DEPOSIT");
                    assertThat(mapper.countFlows(List.of("CONFIRMED"), null, null)).isEqualTo(4L);
                    assertThat(statuses).containsEntry("7:PAY-PENDING", "PENDING")
                            .containsEntry("7:PAY-FAILED", "FAILED")
                            .containsEntry("7:PAY-CHARGEBACK", "CHARGEBACK")
                            .containsEntry("7:PAY-SETTLED", "CONFIRMED")
                            .containsEntry("7:PAY-LEDGER", "CONFIRMED");
                    assertThat(received).containsKeys("7:DEP-CHAIN", "7:PAY-PENDING", "7:PAY-FAILED",
                            "7:PAY-CHARGEBACK", "7:PAY-SETTLED", "7:PAY-LEDGER", "7:PAY-FOREIGN-DEPOSIT");
                    assertThat(received.get("7:DEP-CHAIN")).isEqualByComparingTo(new BigDecimal("10"));
                    assertThat(received.get("7:PAY-PENDING")).isEqualByComparingTo(new BigDecimal("0"));
                    assertThat(received.get("7:PAY-FAILED")).isEqualByComparingTo(new BigDecimal("0"));
                    assertThat(received.get("7:PAY-CHARGEBACK")).isEqualByComparingTo(new BigDecimal("41"));
                    assertThat(received.get("7:PAY-SETTLED")).isEqualByComparingTo(new BigDecimal("41"));
                    assertThat(received.get("7:PAY-LEDGER")).isEqualByComparingTo(new BigDecimal("41"));
                    assertThat(received.get("7:PAY-FOREIGN-DEPOSIT")).isEqualByComparingTo(new BigDecimal("41"));
                }
            }
        });
    }

    private static String flowKey(DepositFlowView flow) {
        return flow.userId() + ":" + flow.depositNo();
    }

    private static void createSchema(Connection connection) throws Exception {
        execute(connection, "CREATE TABLE nx_deposit_order (id BIGINT PRIMARY KEY,user_id BIGINT,deposit_no VARCHAR(64),chain_name VARCHAR(32),asset VARCHAR(16),amount DECIMAL(18,6),chain_tx_hash VARCHAR(128),status VARCHAR(32),failure_reason VARCHAR(255),created_at DATETIME,confirmed_at DATETIME NULL,credited_at DATETIME NULL,is_deleted TINYINT,ledger_id BIGINT NULL) ENGINE=InnoDB");
        execute(connection, "CREATE TABLE nx_payment_record (id BIGINT PRIMARY KEY,user_id BIGINT,payment_no VARCHAR(64),order_no VARCHAR(64),provider VARCHAR(64),provider_payment_id VARCHAR(128),currency VARCHAR(16),amount_usdt DECIMAL(18,6),fee_amount_usdt DECIMAL(18,6),payment_status VARCHAR(32),failure_reason VARCHAR(255),created_at DATETIME,paid_at DATETIME NULL,wallet_ledger_id BIGINT NULL,is_deleted TINYINT) ENGINE=InnoDB");
        execute(connection, "CREATE TABLE nx_topup_card_admission (id BIGINT PRIMARY KEY,user_id BIGINT,order_no VARCHAR(64),decision VARCHAR(32),expires_at DATETIME,admission_event_id VARCHAR(64),settlement_event_id VARCHAR(64) NULL,failure_event_id VARCHAR(64) NULL,amount_usdt DECIMAL(18,6),reason VARCHAR(255),created_at DATETIME,is_deleted TINYINT) ENGINE=InnoDB");
        execute(connection, "CREATE TABLE nx_wallet_ledger (id BIGINT PRIMARY KEY,user_id BIGINT,biz_type VARCHAR(32),direction VARCHAR(16),status VARCHAR(32),biz_no VARCHAR(64),asset VARCHAR(16),amount DECIMAL(18,6),is_deleted TINYINT) ENGINE=InnoDB");
    }

    private static void insertFixtures(Connection connection) throws Exception {
        execute(connection, "INSERT INTO nx_wallet_ledger VALUES (1,7,'CHAIN_TOPUP','IN','SUCCESS','DEP-CHAIN','USDT',10,0),(2,7,'CARD_TOPUP','IN','SUCCESS','PAY-SETTLED','USDT',40,0),(3,7,'CARD_TOPUP','IN','SUCCESS','PAY-LEDGER','USDT',40,0),(4,8,'CARD_TOPUP','IN','SUCCESS','PAY-FOREIGN-LEDGER','USDT',40,0),(5,7,'CARD_TOPUP','IN','SUCCESS','PAY-FOREIGN-DEPOSIT','USDT',40,0)");
        execute(connection, "INSERT INTO nx_deposit_order VALUES (1,7,'DEP-CHAIN','BEP20','USDT',10,'0xchain','CONFIRMED',NULL,NOW(),NOW(),NOW(),0,1),(2,8,'DEP-FOREIGN-PAYMENT','BEP20','USDT',10,'0xforeign-payment','PENDING',NULL,NOW(),NULL,NULL,0,NULL),(3,8,'TOPUP-FOREIGN-DEPOSIT','BEP20','USDT',10,'0xforeign-admission','PENDING',NULL,NOW(),NULL,NULL,0,NULL)");
        payment(connection, 1, 7, "PAY-PENDING", "CARD-PENDING", "Card", "PENDING", null, null);
        payment(connection, 2, 7, "PAY-FAILED", "CARD-FAILED", "Card", "FAILED", null, "declined");
        payment(connection, 3, 7, "PAY-CHARGEBACK", "CARD-CHARGEBACK", "Card", "CHARGEBACK", null, "chargeback");
        payment(connection, 4, 7, "PAY-SETTLED", "CARD-SETTLED", "Card", "CONFIRMED", 2L, null);
        payment(connection, 5, 7, "PAY-GOODS-PAID", "GOODS-PAID", "DEVELOPMENT_SIMULATED", "PAID", null, null);
        payment(connection, 6, 7, "PAY-GOODS-FAILED", "GOODS-FAILED", "NEXGRID_WALLET", "FAILED", null, "goods failure");
        payment(connection, 7, 7, "PAY-LEDGER", "CARD-LEDGER", "Card", "CONFIRMED", 3L, null);
        payment(connection, 8, 7, "PAY-NOT-TOPUP", "CARD-NOT-TOPUP", "Card", "CONFIRMED", null, null);
        payment(connection, 9, 7, "PAY-FOREIGN-LEDGER", "CARD-FOREIGN-LEDGER", "Card", "CONFIRMED", 4L, null);
        payment(connection, 10, 8, "PAY-OTHER", "CARD-OTHER", "Card", "PENDING", null, null);
        payment(connection, 11, 7, "PAY-FOREIGN-ADMISSION", "CARD-FOREIGN-ADMISSION", "Card", "CONFIRMED", null, null);
        payment(connection, 12, 7, "PAY-FOREIGN-DEPOSIT", "DEP-FOREIGN-PAYMENT", "Card", "CONFIRMED", 5L, null);
        admission(connection, 1, 7, "CARD-PENDING", null, null);
        admission(connection, 2, 7, "CARD-FAILED", null, "failed-event");
        admission(connection, 3, 7, "CARD-CHARGEBACK", "settled-event", null);
        admission(connection, 4, 7, "CARD-SETTLED", "settled-event", null);
        admission(connection, 5, 8, "CARD-OTHER", null, null);
        admission(connection, 6, 8, "CARD-FOREIGN-ADMISSION", null, null);
        admission(connection, 7, 7, "TOPUP-FOREIGN-DEPOSIT", null, null);
    }

    private static void payment(Connection connection, long id, long userId, String paymentNo, String orderNo,
                                String provider, String status, Long ledgerId, String failureReason) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO nx_payment_record VALUES (?,?,?,?,?,NULL,'USDT',40,1,?,?,NOW(),NOW(),?,0)")) {
            statement.setLong(1, id); statement.setLong(2, userId); statement.setString(3, paymentNo); statement.setString(4, orderNo);
            statement.setString(5, provider); statement.setString(6, status); statement.setString(7, failureReason);
            if (ledgerId == null) statement.setNull(8, java.sql.Types.BIGINT); else statement.setLong(8, ledgerId);
            statement.executeUpdate();
        }
    }

    private static void admission(Connection connection, long id, long userId, String orderNo, String settlementEventId,
                                  String failureEventId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO nx_topup_card_admission VALUES (?,?,?,'ALLOWED',DATE_ADD(NOW(), INTERVAL 1 DAY),?,?,?,40,NULL,NOW(),0)")) {
            statement.setLong(1, id); statement.setLong(2, userId); statement.setString(3, orderNo);
            statement.setString(4, "admission-" + id); statement.setString(5, settlementEventId); statement.setString(6, failureEventId);
            statement.executeUpdate();
        }
    }

    private static void assertSelectedSchema(Connection connection, String schema) throws Exception {
        try (var statement = connection.createStatement(); var selected = statement.executeQuery("SELECT DATABASE()")) {
            assertThat(selected.next()).isTrue();
            assertThat(selected.getString(1)).isEqualTo(schema);
        }
    }

    private static void execute(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement()) { statement.execute(sql); }
    }

    private static String jdbcUrl(String endpoint, String schema) {
        if (!"127.0.0.1:13306".equals(endpoint)) throw new IllegalArgumentException("isolated endpoint required");
        if (schema == null || (!schema.isEmpty() && !schema.matches(PREFIX + "[a-f0-9]{32}"))) {
            throw new IllegalArgumentException("owned UUID schema required");
        }
        return "jdbc:mysql://" + endpoint + "/" + schema
                + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8";
    }

    private static String url(String schema) {
        return jdbcUrl(System.getenv("NEXION_ISOLATED_MYSQL_ENDPOINT"), schema);
    }

    private static String password() {
        return System.getenv().getOrDefault("NEXION_ISOLATED_MYSQL_PASSWORD", "");
    }

    private static Connection connect(String schema) throws Exception {
        return DriverManager.getConnection(url(schema), "root", password());
    }

    private static void inSchema(SchemaTest test) throws Exception {
        String schema = PREFIX + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = connect("")) {
            try (var statement = admin.createStatement(); var port = statement.executeQuery("SELECT @@port")) {
                assertThat(port.next()).isTrue();
                assertThat(port.getInt(1)).isEqualTo(13306);
            }
            execute(admin, "CREATE DATABASE " + schema);
            try { test.run(schema); }
            finally { execute(admin, "DROP DATABASE " + schema); }
        }
    }

    @FunctionalInterface
    private interface SchemaTest { void run(String schema) throws Exception; }
}