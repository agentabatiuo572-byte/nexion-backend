package ffdd.opsconsole.finance.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Real mapper SQL against disposable UUID schemas, never the business database or payment gateway. */
class D1HdPayReadMySqlIntegrationTest {
    private static final String PREFIX = "nexion_d1_hdpay_it_";

    @Test
    void connectionGuardRejectsBusinessEndpointsAndUnownedSchemas() {
        assertThatThrownBy(() -> url("127.0.0.1:3306", "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> url("127.0.0.1:13306", "nexion")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "NEXION_D1_HDPAY_IT", matches = "true")
    void todayTotalsIncludeRealHdPayLedgerWithoutTreatingProviderConfirmationAsWalletCredit() throws Exception {
        fixture((connection, session) -> {
            var mapper = session.getMapper(DepositOrderMapper.class);
            var rows = mapper.aggregateToday();
            var hd = rows.stream().filter(row -> "HDPAY".equals(row.channel())).findFirst().orElseThrow();
            assertThat(hd.ledgerCount()).isEqualTo(1);
            assertThat(hd.ledgerAmount()).isEqualByComparingTo("33");
            // Broken ledger bindings must remain visible as discrepancies, not copied from the ledger side.
            assertThat(hd.providerCount()).isGreaterThan(hd.ledgerCount());
            assertThat(rows.stream().filter(row -> "TRC20".equals(row.channel())).findFirst().orElseThrow().ledgerAmount())
                    .isEqualByComparingTo("10");
            assertThat(mapper.aggregateToday()).isEqualTo(rows);
            assertThat(count(connection, "nx_wallet_ledger")).isEqualTo(10);
        });
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "NEXION_D1_HDPAY_IT", matches = "true")
    void matchedViewIncludesHistoricalCreditsWithStablePaginationAndNoWritableReceipt() throws Exception {
        fixture((connection, session) -> {
            var mapper = session.getMapper(VietnamPaymentMapper.class);
            var rows = mapper.listVietQrReconciliations("MATCHED", 50, 0);
            assertThat(mapper.countVietQrReconciliations("MATCHED")).isEqualTo(3);
            assertThat(rows).hasSize(3);
            assertThat(rows).extracting(row -> row.get("intentNo"))
                    .containsExactlyInAnyOrder("VQR-1", "VQR-2", "MANUAL-1");
            var hd = rows.stream().filter(row -> "VQR-1".equals(row.get("intentNo"))).findFirst().orElseThrow();
            assertThat(hd).containsEntry("status", "CREDITED").containsEntry("viewType", "MATCHED")
                    .containsEntry("reconciliationNo", "HDPAY-VQR-1");
            assertThat(((Number) hd.get("id")).longValue()).isNegative();
            assertThat(mapper.findVietQrReconciliationForUpdate(((Number) hd.get("id")).longValue())).isNull();
            assertThat(hd.get("note").toString()).contains("HDPay", "自动入账");
            assertThat(hd).doesNotContainKeys("paymentUrl", "requestHash", "sign", "payload", "rawPayload");
            var page1 = mapper.listVietQrReconciliations("MATCHED", 1, 0);
            var page2 = mapper.listVietQrReconciliations("MATCHED", 1, 1);
            assertThat(page1.get(0).get("id")).isNotEqualTo(page2.get(0).get("id"));
            assertThat(mapper.countVietQrReconciliations("INFLIGHT")).isZero();
            assertThat(mapper.countVietQrReconciliations("ORPHAN")).isZero();
            assertThat(mapper.countVietQrReconciliations(null)).isEqualTo(3);
            // Reading history must never insert receipts, credit wallets, or change settlement state.
            assertThat(count(connection, "nx_vietqr_reconciliation")).isEqualTo(1);
            assertThat(count(connection, "nx_wallet_ledger")).isEqualTo(10);
        });
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "NEXION_D1_HDPAY_IT", matches = "true")
    void existingMatchedReceiptIsNotDuplicatedAndOtherViewsRemainUnchanged() throws Exception {
        fixture((connection, session) -> {
            exec(connection, "INSERT INTO nx_vietqr_reconciliation VALUES (101,'LEGACY-HDPAY-1','VQR-1',7,NULL,'MATCHED','CREDITED',870870,870870,26390,33,'PSP-1','legacy',NOW(),NOW(),0,1,NOW(),NOW(),0)");
            exec(connection, "INSERT INTO nx_vietqr_reconciliation VALUES (102,'OPEN-1','MANUAL-2',7,1,'INFLIGHT','OPEN',10000,NULL,25000,0,NULL,'pending',NOW(),NULL,1,0,NOW(),NOW(),0)");
            exec(connection, "INSERT INTO nx_vietqr_reconciliation VALUES (103,'ORPHAN-1',NULL,NULL,1,'ORPHAN','OPEN',NULL,10000,25000,0,'BANK-ORPHAN','unmatched',NULL,NOW(),0,0,NOW(),NOW(),0)");
            var mapper = session.getMapper(VietnamPaymentMapper.class);
            assertThat(mapper.countVietQrReconciliations("MATCHED")).isEqualTo(3);
            assertThat(mapper.listVietQrReconciliations("MATCHED", 50, 0))
                    .filteredOn(row -> "VQR-1".equals(row.get("intentNo")))
                    .singleElement().satisfies(row -> assertThat(((Number) row.get("id")).longValue()).isEqualTo(101L));
            assertThat(mapper.countVietQrReconciliations("INFLIGHT")).isEqualTo(1);
            assertThat(mapper.countVietQrReconciliations("ORPHAN")).isEqualTo(1);
            assertThat(mapper.listVietQrReconciliations("INFLIGHT", 50, 0)).hasSize(1);
            assertThat(mapper.listVietQrReconciliations("ORPHAN", 50, 0)).hasSize(1);
            assertThat(mapper.countVietQrReconciliations(null)).isEqualTo(5);
            assertThat(count(connection, "nx_vietqr_reconciliation")).isEqualTo(4);
            assertThat(count(connection, "nx_wallet_ledger")).isEqualTo(10);
        });
    }

    private static void fixture(Check check) throws Exception {
        String schema = PREFIX + UUID.randomUUID().toString().replace("-", "");
        String endpoint = System.getenv("NEXION_ISOLATED_MYSQL_ENDPOINT");
        String password = System.getenv().getOrDefault("NEXION_ISOLATED_MYSQL_PASSWORD", "");
        try (Connection admin = DriverManager.getConnection(url(endpoint, ""), "root", password)) {
            try (var statement = admin.createStatement(); var rs = statement.executeQuery("SELECT @@port")) {
                rs.next(); assertThat(rs.getInt(1)).isEqualTo(13306);
            }
            exec(admin, "CREATE DATABASE " + schema);
            try (Connection c = DriverManager.getConnection(url(endpoint, schema), "root", password)) {
                schema(c);
                data(c);
                Configuration config = new Configuration(new Environment("isolated-d1-hdpay",
                        new JdbcTransactionFactory(), new DriverManagerDataSource(url(endpoint, schema), "root", password)));
                config.addMapper(DepositOrderMapper.class);
                config.addMapper(VietnamPaymentMapper.class);
                try (var session = new MybatisSqlSessionFactoryBuilder().build(config).openSession()) {
                    check.run(c, session);
                }
            } finally {
                exec(admin, "DROP DATABASE " + schema);
            }
        }
    }

    private static void schema(Connection c) throws Exception {
        exec(c, "CREATE TABLE nx_hdpay_payin_order (id BIGINT PRIMARY KEY,merchant_order_id VARCHAR(64) UNIQUE,amount_vnd DECIMAL(20,2),provider_order_id VARCHAR(64),provider_status INT,settlement_status VARCHAR(24),settled_usdt DECIMAL(18,6),wallet_ledger_biz_no VARCHAR(96),settled_at DATETIME,created_at DATETIME,updated_at DATETIME)");
        exec(c, "CREATE TABLE nx_vietqr_intent (id BIGINT PRIMARY KEY,intent_no VARCHAR(64) UNIQUE,user_id BIGINT,payment_rail VARCHAR(16),settlement_target_type VARCHAR(24),status VARCHAR(24),requested_usdt DECIMAL(18,6),payable_vnd DECIMAL(20,2),received_vnd DECIMAL(20,2),credited_usdt DECIMAL(18,6),locked_fx_rate_vnd_per_usdt DECIMAL(18,6),bank_account_id BIGINT,memo_code VARCHAR(64),expires_at DATETIME,version BIGINT,is_deleted TINYINT)");
        exec(c, "CREATE TABLE nx_wallet_ledger (id BIGINT PRIMARY KEY,biz_no VARCHAR(96),biz_type VARCHAR(32),user_id BIGINT,asset VARCHAR(16),direction VARCHAR(8),amount DECIMAL(18,6),status VARCHAR(24),created_at DATETIME,is_deleted TINYINT)");
        exec(c, "CREATE TABLE nx_vietqr_reconciliation (id BIGINT PRIMARY KEY,reconciliation_no VARCHAR(96),intent_no VARCHAR(64),user_id BIGINT,bank_account_id BIGINT,view_type VARCHAR(24),status VARCHAR(24),payable_vnd DECIMAL(20,2),received_vnd DECIMAL(20,2),locked_fx_rate_vnd_per_usdt DECIMAL(18,6),credited_usdt DECIMAL(18,6),payment_reference VARCHAR(96),note VARCHAR(255),expires_at DATETIME,received_at DATETIME,intent_transition_required BOOLEAN,version BIGINT,created_at DATETIME,updated_at DATETIME,is_deleted TINYINT)");
        exec(c, "CREATE TABLE nx_deposit_order (id BIGINT PRIMARY KEY,ledger_id BIGINT,user_id BIGINT,deposit_no VARCHAR(64),chain_name VARCHAR(32),asset VARCHAR(16),amount DECIMAL(18,6),is_deleted TINYINT)");
        exec(c, "CREATE TABLE nx_payment_record (id BIGINT PRIMARY KEY,wallet_ledger_id BIGINT,user_id BIGINT,payment_no VARCHAR(64),provider VARCHAR(64),amount_usdt DECIMAL(18,6),fee_amount_usdt DECIMAL(18,6),is_deleted TINYINT)");
        exec(c, "CREATE TABLE nx_topup_provider_statement (channel_code VARCHAR(32),amount_usdt DECIMAL(18,6),ingestion_event_id VARCHAR(64),payload_hash VARCHAR(64),observed_at DATETIME,statement_status VARCHAR(24),is_deleted TINYINT)");
    }

    private static void data(Connection c) throws Exception {
        for (int n = 1; n <= 10; n++) {
            String time = n == 2 ? "DATE_SUB(NOW(),INTERVAL 1 DAY)" : "NOW()";
            exec(c, "INSERT INTO nx_hdpay_payin_order VALUES (" + n + ",'VQR-" + n + "',870870,'PSP-" + n + "',3,'CREDITED',33,'VQR-" + n + "'," + time + "," + time + "," + time + ")");
            exec(c, "INSERT INTO nx_vietqr_intent VALUES (" + n + ",'VQR-" + n + "',7,'HDPAY','WALLET_TOPUP','CREDITED',33,870870,870870,33,26390,NULL,NULL,NOW(),1,0)");
            if (n != 3) exec(c, "INSERT INTO nx_wallet_ledger VALUES (" + n + ",'VQR-" + n + "','VIETQR_DEPOSIT',7,'USDT','IN',33,'SUCCESS'," + time + ",0)");
        }
        exec(c, "UPDATE nx_wallet_ledger SET user_id=8 WHERE id=4");
        exec(c, "UPDATE nx_wallet_ledger SET amount=32 WHERE id=5");
        exec(c, "UPDATE nx_wallet_ledger SET is_deleted=1 WHERE id=6");
        exec(c, "UPDATE nx_wallet_ledger SET biz_type='CARD_TOPUP' WHERE id=7");
        exec(c, "UPDATE nx_vietqr_intent SET settlement_target_type='COMMERCE_ORDER' WHERE id=8");
        exec(c, "UPDATE nx_hdpay_payin_order SET settlement_status='UNSETTLED',provider_status=1 WHERE id=9");
        exec(c, "UPDATE nx_vietqr_intent SET is_deleted=1 WHERE id=10");
        exec(c, "INSERT INTO nx_wallet_ledger VALUES (100,'CHAIN-1','CHAIN_TOPUP',7,'USDT','IN',10,'SUCCESS',NOW(),0)");
        exec(c, "INSERT INTO nx_deposit_order VALUES (1,100,7,'CHAIN-1','TRC20','USDT',10,0)");
        exec(c, "INSERT INTO nx_vietqr_reconciliation VALUES (100,'RECEIPT-1','MANUAL-1',7,1,'MATCHED','CREDITED',10000,10000,25000,0.4,'BANK-1','manual',NOW(),NOW(),0,1,NOW(),NOW(),0)");
    }

    private static long count(Connection c, String table) throws Exception {
        try (var s = c.createStatement(); var rs = s.executeQuery("SELECT COUNT(*) FROM " + table)) { rs.next(); return rs.getLong(1); }
    }

    private static void exec(Connection c, String sql) throws Exception {
        try (var s = c.createStatement()) { s.execute(sql); }
    }

    private static String url(String endpoint, String schema) {
        if (!"127.0.0.1:13306".equals(endpoint) || (!schema.isEmpty() && !schema.matches(PREFIX + "[a-f0-9]{32}")))
            throw new IllegalArgumentException("isolated endpoint and owned UUID schema required");
        return "jdbc:mysql://" + endpoint + "/" + schema + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8";
    }

    @FunctionalInterface
    private interface Check { void run(Connection c, org.apache.ibatis.session.SqlSession session) throws Exception; }
}
