package ffdd.opsconsole.finance.hdpay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import ffdd.opsconsole.finance.mapper.AppVietQrIntentMapper;
import ffdd.opsconsole.finance.mapper.VietnamPaymentMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

class HdPayCallbackSettlementSpringTransactionMySqlIntegrationTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "NEXION_TEST_DB_PASSWORD", matches = ".+")
    void realMySqlCreditsExactlyOnceAndRollsBackEveryFinancialWriteOnFailure() throws Exception {
        DataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String intentNo = "VQR-HP-" + suffix;
        String providerOrderId = "P" + suffix;
        String bankCode = "HP" + suffix.substring(0, 10).toUpperCase();
        long userId;
        long bankAccountId;
        BigDecimal walletBefore;
        BigDecimal cumulativeBefore;

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            userId = activeUserId(connection);
            bankAccountId = insertBankAccount(connection, bankCode, suffix);
            insertIntent(connection, intentNo, suffix, userId, bankAccountId);
            insertInFlightReconciliation(connection, intentNo, userId, bankAccountId);
            insertHdPayOrder(connection, intentNo, providerOrderId, suffix);
            walletBefore = decimalQuery(connection,
                    "SELECT usdt_available FROM nx_user_wallet WHERE user_id=?", userId);
            cumulativeBefore = decimalQuery(connection,
                    "SELECT cumulative_deposit_usdt FROM nx_user_wallet WHERE user_id=?", userId);
        }

        try {
            SqlSessionTemplate template = new SqlSessionTemplate(sessionFactory(dataSource));
            DataSourceTransactionManager txManager = new DataSourceTransactionManager(dataSource);
            EventOutboxService successfulOutbox = mock(EventOutboxService.class);
            when(successfulOutbox.publish(anyString(), anyString(), anyString(), any()))
                    .thenReturn("event-1");
            HdPayCallbackSettlementService successful = proxiedService(
                    template, successfulOutbox, txManager);
            HdPayCallbackVerifier.VerifiedCallback callback = callback(intentNo, providerOrderId);
            HdPayGateway.PayOrder query = query(intentNo, providerOrderId);

            new TransactionTemplate(txManager).executeWithoutResult(status -> {
                assertThat(successful.settleConfirmed(callback, query)).isEqualTo("success");
                assertThat(successful.settleConfirmed(callback, query)).isEqualTo("success");
                assertThat(decimal(jdbc, "SELECT usdt_available FROM nx_user_wallet WHERE user_id=?", userId))
                        .isEqualByComparingTo(walletBefore.add(new BigDecimal("10.000000")));
                assertThat(decimal(jdbc,
                        "SELECT cumulative_deposit_usdt FROM nx_user_wallet WHERE user_id=?", userId))
                        .isEqualByComparingTo(cumulativeBefore.add(new BigDecimal("10.000000")));
                assertThat(count(jdbc, "SELECT COUNT(*) FROM nx_wallet_ledger WHERE biz_no=?", intentNo))
                        .isOne();
                assertThat(text(jdbc, "SELECT status FROM nx_vietqr_intent WHERE intent_no=?", intentNo))
                        .isEqualTo("CREDITED");
                assertThat(text(jdbc,
                        "SELECT settlement_status FROM nx_hdpay_payin_order WHERE merchant_order_id=?",
                        intentNo)).isEqualTo("CREDITED");
                assertThat(count(jdbc,
                        "SELECT COUNT(*) FROM nx_hdpay_callback_inbox WHERE merchant_order_id=?", intentNo))
                        .isOne();
                assertThat(count(jdbc,
                        "SELECT COUNT(*) FROM nx_notification WHERE biz_no=?", "HDPAY:" + intentNo))
                        .isOne();
                status.setRollbackOnly();
            });

            assertUnchanged(jdbc, intentNo, userId, walletBefore, cumulativeBefore);

            EventOutboxService failingOutbox = mock(EventOutboxService.class);
            when(failingOutbox.publish(anyString(), anyString(), anyString(), any()))
                    .thenThrow(new IllegalStateException("forced outbox failure"));
            HdPayCallbackSettlementService failing = proxiedService(template, failingOutbox, txManager);
            assertThatThrownBy(() -> failing.settleConfirmed(callback, query))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("forced outbox failure");
            assertUnchanged(jdbc, intentNo, userId, walletBefore, cumulativeBefore);
        } finally {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(true);
                execute(connection, "DELETE FROM nx_notification WHERE biz_no=?", "HDPAY:" + intentNo);
                execute(connection, "DELETE FROM nx_hdpay_callback_inbox WHERE merchant_order_id=?", intentNo);
                execute(connection, "DELETE FROM nx_wallet_ledger WHERE biz_no=?", intentNo);
                execute(connection, "DELETE FROM nx_hdpay_payin_order WHERE merchant_order_id=?", intentNo);
                execute(connection, "DELETE FROM nx_vietqr_reconciliation WHERE intent_no=?", intentNo);
                execute(connection, "DELETE FROM nx_vietqr_intent WHERE intent_no=?", intentNo);
                execute(connection, "DELETE FROM nx_vietqr_bank_account WHERE bank_code=?", bankCode);
            }
        }
    }

    private HdPayCallbackSettlementService proxiedService(
            SqlSessionTemplate template,
            EventOutboxService outbox,
            DataSourceTransactionManager txManager) {
        HdPayCallbackSettlementService target = new HdPayCallbackSettlementService(
                template.getMapper(HdPayOrderMapper.class),
                template.getMapper(AppVietQrIntentMapper.class),
                template.getMapper(VietnamPaymentMapper.class),
                outbox,
                mock(AuditLogService.class),
                Clock.system(ZoneId.of("Asia/Shanghai")));
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(
                txManager, new AnnotationTransactionAttributeSource()));
        return (HdPayCallbackSettlementService) factory.getProxy();
    }

    private void assertUnchanged(
            JdbcTemplate jdbc,
            String intentNo,
            long userId,
            BigDecimal walletBefore,
            BigDecimal cumulativeBefore) {
        assertThat(decimal(jdbc, "SELECT usdt_available FROM nx_user_wallet WHERE user_id=?", userId))
                .isEqualByComparingTo(walletBefore);
        assertThat(decimal(jdbc,
                "SELECT cumulative_deposit_usdt FROM nx_user_wallet WHERE user_id=?", userId))
                .isEqualByComparingTo(cumulativeBefore);
        assertThat(text(jdbc, "SELECT status FROM nx_vietqr_intent WHERE intent_no=?", intentNo))
                .isEqualTo("AWAITING_PAYMENT");
        assertThat(text(jdbc,
                "SELECT settlement_status FROM nx_hdpay_payin_order WHERE merchant_order_id=?", intentNo))
                .isEqualTo("UNSETTLED");
        assertThat(count(jdbc, "SELECT COUNT(*) FROM nx_wallet_ledger WHERE biz_no=?", intentNo)).isZero();
        assertThat(count(jdbc,
                "SELECT COUNT(*) FROM nx_hdpay_callback_inbox WHERE merchant_order_id=?", intentNo)).isZero();
        assertThat(count(jdbc,
                "SELECT COUNT(*) FROM nx_notification WHERE biz_no=?", "HDPAY:" + intentNo)).isZero();
    }

    private DataSource dataSource() {
        String url = System.getenv().getOrDefault(
                "NEXION_TEST_DB_URL",
                "jdbc:mysql://127.0.0.1:3306/nexion?useUnicode=true&characterEncoding=utf8"
                        + "&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true");
        String username = System.getenv().getOrDefault("NEXION_TEST_DB_USERNAME", "root");
        return new DriverManagerDataSource(url, username, System.getenv("NEXION_TEST_DB_PASSWORD"));
    }

    private SqlSessionFactory sessionFactory(DataSource dataSource) {
        Configuration configuration = new Configuration(new Environment(
                "hdpay-settlement-integration", new SpringManagedTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(HdPayOrderMapper.class);
        configuration.addMapper(AppVietQrIntentMapper.class);
        configuration.addMapper(VietnamPaymentMapper.class);
        return new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    private long activeUserId(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT u.id FROM nx_user u
                JOIN nx_user_wallet w ON w.user_id=u.id AND w.is_deleted=0
                WHERE u.status='ACTIVE' AND u.is_deleted=0 ORDER BY u.id LIMIT 1
                """); ResultSet result = statement.executeQuery()) {
            assertThat(result.next()).as("real MySQL requires one active user wallet").isTrue();
            return result.getLong(1);
        }
    }

    private long insertBankAccount(Connection connection, String bankCode, String suffix) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO nx_vietqr_bank_account (
                  bank_code,bank_name,account_holder,account_number_encrypted,
                  account_number_hash,account_number_last4,daily_cap_vnd,
                  received_today_vnd,received_business_date,status,version,created_at,updated_at,is_deleted)
                VALUES (?, 'HDPay Tx Bank', 'NEXION TX', ?, ?, '0001',
                        100000000,0,CURRENT_DATE,'ACTIVE',0,NOW(),NOW(),0)
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, bankCode);
            statement.setString(2, "integration-ciphertext-" + suffix);
            statement.setString(3, sha256("970436" + suffix));
            assertThat(statement.executeUpdate()).isOne();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertThat(keys.next()).isTrue();
                return keys.getLong(1);
            }
        }
    }

    private void insertIntent(
            Connection connection, String intentNo, String suffix, long userId, long bankAccountId)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO nx_vietqr_intent (
                  intent_no,user_id,create_idempotency_key,create_request_hash,
                  requested_usdt,payable_vnd,credited_usdt,received_vnd,
                  locked_fx_rate_vnd_per_usdt,fx_quote_version,bank_account_id,memo_code,
                  status,expires_at,version,created_at,updated_at,is_deleted)
                VALUES (?,?,?, ?,10,200000,0,NULL,20000,1,?,?,'AWAITING_PAYMENT',
                        DATE_ADD(NOW(),INTERVAL 30 MINUTE),0,NOW(),NOW(),0)
                """)) {
            statement.setString(1, intentNo);
            statement.setLong(2, userId);
            statement.setString(3, "hdpay-create-" + suffix);
            statement.setString(4, sha256("hdpay-request-" + suffix));
            statement.setLong(5, bankAccountId);
            statement.setString(6, "NXHP" + suffix.substring(0, 8).toUpperCase());
            assertThat(statement.executeUpdate()).isOne();
        }
    }

    private void insertInFlightReconciliation(
            Connection connection, String intentNo, long userId, long bankAccountId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO nx_vietqr_reconciliation (
                  reconciliation_no,intent_no,user_id,bank_account_id,view_type,status,
                  payable_vnd,received_vnd,locked_fx_rate_vnd_per_usdt,credited_usdt,
                  payment_reference,note,expires_at,received_at,version,created_at,updated_at,is_deleted)
                VALUES (CONCAT('APP-',?),?,?,?,'INFLIGHT','OPEN',200000,NULL,20000,0,
                        NULL,'APP_INTENT_CREATED',DATE_ADD(NOW(),INTERVAL 30 MINUTE),NULL,
                        0,NOW(),NOW(),0)
                """)) {
            statement.setString(1, intentNo);
            statement.setString(2, intentNo);
            statement.setLong(3, userId);
            statement.setLong(4, bankAccountId);
            assertThat(statement.executeUpdate()).isOne();
        }
    }

    private void insertHdPayOrder(
            Connection connection, String intentNo, String providerOrderId, String suffix) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO nx_hdpay_payin_order (
                  merchant_order_id,amount_vnd,submission_status,provider_order_id,
                  provider_status,request_hash,settlement_status,version,created_at,updated_at)
                VALUES (?,200000,'CREATED',?,1,?,'UNSETTLED',0,NOW(),NOW())
                """)) {
            statement.setString(1, intentNo);
            statement.setString(2, providerOrderId);
            statement.setString(3, sha256("hdpay-order-" + suffix));
            assertThat(statement.executeUpdate()).isOne();
        }
    }

    private HdPayCallbackVerifier.VerifiedCallback callback(String intentNo, String providerOrderId) {
        return new HdPayCallbackVerifier.VerifiedCallback(
                intentNo, providerOrderId, 3, new BigDecimal("200000"),
                "2026-09-02 11:59:00", "2026-09-02 12:00:00", "test-signature");
    }

    private HdPayGateway.PayOrder query(String intentNo, String providerOrderId) {
        return new HdPayGateway.PayOrder(
                intentNo, providerOrderId, 3, new BigDecimal("200000"), "BANKQR", "");
    }

    private BigDecimal decimal(JdbcTemplate jdbc, String sql, Object argument) {
        return jdbc.queryForObject(sql, BigDecimal.class, argument);
    }

    private String text(JdbcTemplate jdbc, String sql, Object argument) {
        return jdbc.queryForObject(sql, String.class, argument);
    }

    private long count(JdbcTemplate jdbc, String sql, Object argument) {
        Long value = jdbc.queryForObject(sql, Long.class, argument);
        return value == null ? 0 : value;
    }

    private BigDecimal decimalQuery(Connection connection, String sql, long argument) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, argument);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getBigDecimal(1);
            }
        }
    }

    private void execute(Connection connection, String sql, String argument) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, argument);
            statement.executeUpdate();
        }
    }

    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
