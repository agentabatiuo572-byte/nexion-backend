package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import ffdd.opsconsole.finance.dto.VietQrReconciliationCommandRequest;
import ffdd.opsconsole.finance.mapper.AppVietQrIntentMapper;
import ffdd.opsconsole.finance.mapper.VietnamPaymentMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
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
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

class OpsVietnamPaymentSpringTransactionMySqlIntegrationTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "NEXION_TEST_DB_PASSWORD", matches = ".+")
    void springTransactionalProxyRollsBackWalletLedgerIntentAndReconciliationTogether()
            throws Exception {
        DataSource dataSource = dataSource();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String intentNo = "VQR-TX-" + suffix;
        String reconciliationNo = "REC-TX-" + suffix;
        String paymentReference = "BANK-TX-" + suffix;
        String bankCode = "TX" + suffix.substring(0, 8).toUpperCase();
        long userId;
        long bankAccountId;
        long reconciliationId;

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            userId = activeUserId(connection);
            bankAccountId = insertBankAccount(connection, bankCode, suffix);
            insertReceiptReviewIntent(connection, intentNo, suffix, userId, bankAccountId);
            reconciliationId = insertMatchedReceipt(
                    connection, reconciliationNo, intentNo, paymentReference, userId, bankAccountId);
        }

        try {
            SqlSessionFactory sessionFactory = springManagedSessionFactory(dataSource);
            SqlSessionTemplate template = new SqlSessionTemplate(sessionFactory);
            AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
            when(idempotency.execute(anyString(), anyString(), anyString(), any(), any()))
                    .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get());
            AuditLogService audit = mock(AuditLogService.class);
            doThrow(new IllegalStateException("forced audit failure"))
                    .when(audit).recordRequired(any());
            OpsVietnamPaymentService target = new OpsVietnamPaymentService(
                    template.getMapper(VietnamPaymentMapper.class),
                    audit,
                    idempotency,
                    mock(FinanceSensitiveDataCipher.class),
                    template.getMapper(AppVietQrIntentMapper.class),
                    mock(ffdd.opsconsole.shared.outbox.EventOutboxService.class),
                    Clock.system(ZoneId.of("Asia/Shanghai")));
            DataSourceTransactionManager transactionManager =
                    new DataSourceTransactionManager(dataSource);
            TransactionInterceptor transactionInterceptor = new TransactionInterceptor(
                    transactionManager, new AnnotationTransactionAttributeSource());
            ProxyFactory proxyFactory = new ProxyFactory(target);
            proxyFactory.setProxyTargetClass(true);
            proxyFactory.addAdvice(transactionInterceptor);
            OpsVietnamPaymentService service =
                    (OpsVietnamPaymentService) proxyFactory.getProxy();

            BigDecimal walletBefore;
            BigDecimal cumulativeBefore;
            try (Connection connection = dataSource.getConnection()) {
                walletBefore = decimalQuery(
                        connection,
                        "SELECT usdt_available FROM nx_user_wallet WHERE user_id = ?",
                        userId);
                cumulativeBefore = decimalQuery(
                        connection,
                        "SELECT cumulative_deposit_usdt FROM nx_user_wallet WHERE user_id = ?",
                        userId);
            }

            assertThatThrownBy(() -> service.reconcile(
                    reconciliationId,
                    "match-credit",
                    "spring-tx-" + suffix,
                    new VietQrReconciliationCommandRequest(
                            0L, null, intentNo, "SPRING-TX-" + suffix,
                            "force audit rollback boundary", "integration-admin")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("forced audit failure");

            try (Connection connection = dataSource.getConnection()) {
                assertThat(decimalQuery(
                        connection,
                        "SELECT usdt_available FROM nx_user_wallet WHERE user_id = ?",
                        userId)).isEqualByComparingTo(walletBefore);
                assertThat(decimalQuery(
                        connection,
                        "SELECT cumulative_deposit_usdt FROM nx_user_wallet WHERE user_id = ?",
                        userId)).isEqualByComparingTo(cumulativeBefore);
                assertThat(textQuery(
                        connection,
                        "SELECT status FROM nx_vietqr_intent WHERE intent_no = ?",
                        intentNo)).isEqualTo("RECEIPT_REVIEW");
                assertThat(textQuery(
                        connection,
                        "SELECT status FROM nx_vietqr_reconciliation WHERE id = ?",
                        reconciliationId)).isEqualTo("OPEN");
                assertThat(countQuery(
                        connection,
                        "SELECT COUNT(1) FROM nx_wallet_ledger WHERE biz_no = ?",
                        "D1-VIETQR-" + reconciliationNo)).isZero();
            }
        } finally {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(true);
                execute(connection,
                        "DELETE FROM nx_wallet_ledger WHERE biz_no = ?",
                        "D1-VIETQR-" + reconciliationNo);
                execute(connection,
                        "DELETE FROM nx_vietqr_reconciliation WHERE reconciliation_no = ?",
                        reconciliationNo);
                execute(connection,
                        "DELETE FROM nx_vietqr_intent WHERE intent_no = ?",
                        intentNo);
                execute(connection,
                        "DELETE FROM nx_vietqr_bank_account WHERE bank_code = ?",
                        bankCode);
            }
        }
    }

    private DataSource dataSource() {
        String url = System.getenv().getOrDefault(
                "NEXION_TEST_DB_URL",
                "jdbc:mysql://127.0.0.1:3306/nexion"
                        + "?useUnicode=true&characterEncoding=utf8"
                        + "&serverTimezone=Asia/Shanghai&useSSL=false"
                        + "&allowPublicKeyRetrieval=true");
        String username = System.getenv().getOrDefault("NEXION_TEST_DB_USERNAME", "root");
        return new DriverManagerDataSource(
                url, username, System.getenv("NEXION_TEST_DB_PASSWORD"));
    }

    private SqlSessionFactory springManagedSessionFactory(DataSource dataSource) {
        Configuration configuration = new Configuration(new Environment(
                "vietqr-spring-tx-integration",
                new SpringManagedTransactionFactory(),
                dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AppVietQrIntentMapper.class);
        configuration.addMapper(VietnamPaymentMapper.class);
        return new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    private long activeUserId(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT u.id
                  FROM nx_user u
                  JOIN nx_user_wallet w ON w.user_id = u.id AND w.is_deleted = 0
                 WHERE u.status = 'ACTIVE' AND u.is_deleted = 0
                 ORDER BY u.id LIMIT 1
                """);
             ResultSet result = statement.executeQuery()) {
            assertThat(result.next()).as("real MySQL test requires one active user").isTrue();
            return result.getLong(1);
        }
    }

    private long insertBankAccount(Connection connection, String bankCode, String suffix)
            throws Exception {
        String accountNumber = "970436" + suffix.substring(0, 10);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO nx_vietqr_bank_account (
                    bank_code, bank_name, account_holder,
                    account_number_encrypted, account_number_hash, account_number_last4,
                    daily_cap_vnd, received_today_vnd, received_business_date, status,
                    version, created_at, updated_at, is_deleted
                ) VALUES (?, 'Spring Tx Bank', 'NEXION TX',
                          ?, ?, ?, 100000000, 659750, CURRENT_DATE, 'ACTIVE',
                          0, NOW(), NOW(), 0)
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, bankCode);
            statement.setString(2, "integration-ciphertext-" + suffix);
            statement.setString(3, sha256(accountNumber));
            statement.setString(4, accountNumber.substring(accountNumber.length() - 4));
            assertThat(statement.executeUpdate()).isEqualTo(1);
            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertThat(keys.next()).isTrue();
                return keys.getLong(1);
            }
        }
    }

    private void insertReceiptReviewIntent(
            Connection connection, String intentNo, String suffix,
            long userId, long bankAccountId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO nx_vietqr_intent (
                    intent_no, user_id, create_idempotency_key, create_request_hash,
                    requested_usdt, payable_vnd, credited_usdt, received_vnd,
                    locked_fx_rate_vnd_per_usdt, fx_quote_version,
                    bank_account_id, memo_code, status, expires_at, matched_at,
                    version, created_at, updated_at, is_deleted
                ) VALUES (?, ?, ?, ?, 25, 659750, 0, 659750,
                          26390, 0, ?, ?, 'RECEIPT_REVIEW',
                          DATE_ADD(NOW(), INTERVAL 30 MINUTE), NOW(),
                          1, NOW(), NOW(), 0)
                """)) {
            statement.setString(1, intentNo);
            statement.setLong(2, userId);
            statement.setString(3, "spring-create-" + suffix);
            statement.setString(4, sha256("spring-request-" + suffix));
            statement.setLong(5, bankAccountId);
            statement.setString(6, "NX-TX-" + suffix.substring(0, 8).toUpperCase());
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private long insertMatchedReceipt(
            Connection connection, String reconciliationNo, String intentNo,
            String paymentReference, long userId, long bankAccountId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO nx_vietqr_reconciliation (
                    reconciliation_no, intent_no, user_id, bank_account_id,
                    view_type, status, payable_vnd, received_vnd,
                    locked_fx_rate_vnd_per_usdt, credited_usdt,
                    payment_reference, note, expires_at, received_at,
                    intent_transition_required,
                    version, created_at, updated_at, is_deleted
                ) VALUES (?, ?, ?, ?, 'MATCHED', 'OPEN', 659750, 659750,
                          26390, 0, ?, 'SPRING_TX_FIXTURE',
                          DATE_ADD(NOW(), INTERVAL 30 MINUTE), NOW(),
                          1, 0, NOW(), NOW(), 0)
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, reconciliationNo);
            statement.setString(2, intentNo);
            statement.setLong(3, userId);
            statement.setLong(4, bankAccountId);
            statement.setString(5, paymentReference);
            assertThat(statement.executeUpdate()).isEqualTo(1);
            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertThat(keys.next()).isTrue();
                return keys.getLong(1);
            }
        }
    }

    private BigDecimal decimalQuery(
            Connection connection, String sql, long argument) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, argument);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getBigDecimal(1);
            }
        }
    }

    private String textQuery(
            Connection connection, String sql, Object argument) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, argument);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getString(1);
            }
        }
    }

    private long countQuery(
            Connection connection, String sql, String argument) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, argument);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getLong(1);
            }
        }
    }

    private void execute(Connection connection, String sql, String argument)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, argument);
            statement.executeUpdate();
        }
    }

    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
