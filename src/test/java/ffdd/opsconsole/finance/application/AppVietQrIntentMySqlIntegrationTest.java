package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import ffdd.opsconsole.finance.mapper.AppVietQrIntentMapper;
import ffdd.opsconsole.finance.mapper.VietnamPaymentMapper;
import ffdd.opsconsole.finance.dto.VietQrReceiptRegistrationRequest;
import ffdd.opsconsole.finance.dto.VietQrReconciliationCommandRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Savepoint;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class AppVietQrIntentMySqlIntegrationTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "NEXION_TEST_DB_PASSWORD", matches = ".+")
    void createsReplaysListsAndCancelsAgainstRealMySqlMapper() throws Exception {
        String url = System.getenv().getOrDefault(
                "NEXION_TEST_DB_URL",
                "jdbc:mysql://127.0.0.1:3306/nexion"
                        + "?useUnicode=true&characterEncoding=utf8"
                        + "&serverTimezone=Asia/Shanghai&useSSL=false"
                        + "&allowPublicKeyRetrieval=true");
        String username = System.getenv().getOrDefault("NEXION_TEST_DB_USERNAME", "root");
        String password = System.getenv("NEXION_TEST_DB_PASSWORD");
        String encryptionKey = System.getenv().getOrDefault(
                "NEXION_TEST_FINANCE_DATA_KEY",
                "nexion-vietqr-mysql-integration-key-2026");
        DataSource dataSource = new DriverManagerDataSource(url, username, password);

        Configuration configuration = new Configuration(new Environment(
                "vietqr-mysql-integration", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AppVietQrIntentMapper.class);
        configuration.addMapper(VietnamPaymentMapper.class);
        SqlSessionFactory factory =
                new MybatisSqlSessionFactoryBuilder().build(configuration);

        try (SqlSession session = factory.openSession(false)) {
            Connection connection = session.getConnection();
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement timezone =
                        connection.prepareStatement("SET time_zone = '+08:00'")) {
                    timezone.execute();
                }
                long userId = activeUserId(connection);
                String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
                String accountNumber = "970436" + suffix.substring(0, 10);
                FinanceSensitiveDataCipher cipher = new FinanceSensitiveDataCipher(encryptionKey);
                seedBankAccount(connection, suffix, accountNumber, cipher.encrypt(accountNumber));

                AppVietQrIntentService service = new AppVietQrIntentService(
                        session.getMapper(AppVietQrIntentMapper.class),
                        cipher,
                        Clock.system(ZoneId.of("Asia/Shanghai")),
                        new org.springframework.mock.env.MockEnvironment().withProperty("spring.profiles.active", "prod"));
                String createKey = "mysql-create-" + suffix;

                ApiResult<Map<String, Object>> created =
                        service.create(userId, createKey, new BigDecimal("25"));
                String intentNo = String.valueOf(created.getData().get("intentNo"));
                assertThat(created.getData())
                        .containsEntry("status", "awaiting_payment")
                        .containsEntry("usdtAmount", new BigDecimal("25.00"));
                assertThat(created.getData().get("bankAccount"))
                        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                        .containsEntry("accountNumber", accountNumber);
                assertInFlightReconciliation(connection, intentNo, userId, false);
                VietnamPaymentMapper pcMapper = session.getMapper(VietnamPaymentMapper.class);
                assertThat(pcMapper.listVietQrReconciliations("INFLIGHT", 100, 0))
                        .anySatisfy(row -> assertThat(row)
                                .containsEntry("intentNo", intentNo)
                                .containsEntry("userId", userId)
                                .containsEntry("lockedFxRateVndPerUsdt", created.getData().get("fxRate")));
                long pcFxVersion =
                        ((Number) pcMapper.findFxQuoteConfig().get("version")).longValue();
                assertThat(service.fxQuote("VND", "USDT").getData())
                        .containsEntry("version", pcFxVersion);

                assertThat(service.create(userId, createKey, new BigDecimal("25")).getData())
                        .containsEntry("intentNo", intentNo);
                assertThat(service.list(userId, 20).getData().get("items"))
                        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                        .isNotEmpty();

                AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
                when(idempotency.execute(anyString(), anyString(), anyString(), any(), any()))
                        .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get());
                OpsVietnamPaymentService opsService = new OpsVietnamPaymentService(
                        pcMapper,
                        mock(AuditLogService.class),
                        idempotency,
                        cipher,
                        session.getMapper(AppVietQrIntentMapper.class),
                        mock(ffdd.opsconsole.shared.outbox.EventOutboxService.class),
                        mock(VietQrReceiptEvidenceService.class),
                        Clock.system(ZoneId.of("Asia/Shanghai")));
                BigDecimal walletBefore = walletAvailable(connection, userId);
                BigDecimal cumulativeBefore = walletCumulativeDeposit(connection, userId);
                BigDecimal receivedBefore = bankReceivedToday(connection, intentNo);
                LocalDateTime receivedAt = LocalDateTime.now(Clock.system(
                        ZoneId.of("Asia/Shanghai"))).plusSeconds(1);
                String paymentReference = "MYSQL-BANK-" + suffix;
                @SuppressWarnings("unchecked")
                Map<String, Object> bank = (Map<String, Object>) created.getData().get("bankAccount");
                Long bankAccountId = intentBankAccountId(connection, intentNo);
                ApiResult<Map<String, Object>> receipt = opsService.registerVietQrReceipt(
                        "mysql-receipt-" + suffix,
                        new VietQrReceiptRegistrationRequest(
                                bankAccountId,
                                paymentReference,
                                String.valueOf(created.getData().get("memoCode")),
                                new BigDecimal("659750"),
                                receivedAt.atZone(ZoneId.of("Asia/Shanghai")).toOffsetDateTime(),
                                receiptEvidence(),
                                "register real mysql bank receipt",
                                "integration-admin"));
                assertThat(bank).containsKey("accountNumber");
                assertThat(receipt.getData())
                        .containsEntry("viewType", "MATCHED")
                        .containsEntry("intentNo", intentNo);
                assertThat(intentStatus(connection, intentNo))
                        .isEqualTo("RECEIPT_REVIEW");
                assertThat(bankReceivedToday(connection, intentNo))
                        .isEqualByComparingTo(receivedBefore.add(new BigDecimal("659750")));
                assertThatThrownBy(() -> service.cancel(
                        userId, intentNo, "mysql-cancel-after-receipt-" + suffix, 1L))
                        .isInstanceOf(BizException.class)
                        .hasMessage("VIETQR_INTENT_NOT_CANCELLABLE");
                long reconciliationId = ((Number) receipt.getData().get("id")).longValue();
                ApiResult<Map<String, Object>> settled = opsService.reconcile(
                        reconciliationId,
                        "match-credit",
                        "mysql-settle-" + suffix,
                        new VietQrReconciliationCommandRequest(
                                0L, null, intentNo,
                                "MYSQL-EVIDENCE-" + suffix,
                                "confirm exact mysql receipt",
                                "integration-admin"));
                assertThat(settled.getData())
                        .containsEntry("status", "CREDITED")
                        .containsEntry("creditedUsdt", new BigDecimal("25.000000"));
                assertThat(walletAvailable(connection, userId))
                        .isEqualByComparingTo(walletBefore.add(new BigDecimal("25.000000")));
                assertThat(walletCumulativeDeposit(connection, userId))
                        .isEqualByComparingTo(cumulativeBefore.add(new BigDecimal("25.000000")));
                assertThat(bankReceivedToday(connection, intentNo))
                        .isEqualByComparingTo(receivedBefore.add(new BigDecimal("659750")));
                assertThat(countLedger(connection, "D1-VIETQR-"
                        + receipt.getData().get("reconciliationNo"))).isEqualTo(1);
                assertThatThrownBy(() -> opsService.reconcile(
                        reconciliationId,
                        "match-credit",
                        "mysql-settle-repeat-" + suffix,
                        new VietQrReconciliationCommandRequest(
                                1L, null, intentNo,
                                "MYSQL-EVIDENCE-" + suffix,
                                "reject duplicate mysql settlement",
                                "integration-admin")))
                        .isInstanceOf(BizException.class)
                        .hasMessage("VIETQR_RECONCILIATION_ALREADY_TERMINAL");
                assertThat(countLedger(connection, "D1-VIETQR-"
                        + receipt.getData().get("reconciliationNo"))).isEqualTo(1);

                ApiResult<Map<String, Object>> supplementalReceipt =
                        opsService.registerVietQrReceipt(
                                "mysql-receipt-supplemental-" + suffix,
                                new VietQrReceiptRegistrationRequest(
                                        bankAccountId,
                                        "MYSQL-BANK-SUP-" + suffix,
                                        String.valueOf(created.getData().get("memoCode")),
                                        new BigDecimal("659750"),
                                        receivedAt.atZone(ZoneId.of("Asia/Shanghai")).toOffsetDateTime(),
                                        receiptEvidence(),
                                        "register terminal supplemental receipt",
                                        "integration-admin"));
                assertThat(supplementalReceipt.getData())
                        .containsEntry("viewType", "LATE")
                        .containsEntry("intentNo", intentNo);
                assertThat(intentStatus(connection, intentNo)).isEqualTo("CREDITED");
                long supplementalId =
                        ((Number) supplementalReceipt.getData().get("id")).longValue();
                assertThat(opsService.reconcile(
                        supplementalId,
                        "return",
                        "mysql-return-supplemental-" + suffix,
                        new VietQrReconciliationCommandRequest(
                                0L, null, intentNo,
                                "MYSQL-EVIDENCE-SUP-" + suffix,
                                "return supplemental duplicate receipt",
                                "integration-admin")).getData())
                        .containsEntry("status", "RETURNED");
                assertThat(intentStatus(connection, intentNo)).isEqualTo("CREDITED");

                ApiResult<Map<String, Object>> rollbackIntent =
                        service.create(
                                userId,
                                "mysql-create-rollback-" + suffix,
                                new BigDecimal("25"));
                String rollbackIntentNo =
                        String.valueOf(rollbackIntent.getData().get("intentNo"));
                String rollbackPaymentReference = "MYSQL-ROLLBACK-" + suffix;
                ApiResult<Map<String, Object>> rollbackReceipt =
                        opsService.registerVietQrReceipt(
                                "mysql-receipt-rollback-" + suffix,
                                new VietQrReceiptRegistrationRequest(
                                        intentBankAccountId(connection, rollbackIntentNo),
                                        rollbackPaymentReference,
                                        String.valueOf(rollbackIntent.getData().get("memoCode")),
                                        new BigDecimal("659750"),
                                        receivedAt.atZone(ZoneId.of("Asia/Shanghai")).toOffsetDateTime(),
                                        receiptEvidence(),
                                        "register rollback mysql receipt",
                                        "integration-admin"));
                long rollbackReconciliationId =
                        ((Number) rollbackReceipt.getData().get("id")).longValue();
                String rollbackReconciliationNo =
                        String.valueOf(rollbackReceipt.getData().get("reconciliationNo"));
                insertDuplicateLedger(
                        connection,
                        "D1-VIETQR-" + rollbackReconciliationNo,
                        userId,
                        walletAvailable(connection, userId));
                BigDecimal rollbackWalletBefore = walletAvailable(connection, userId);
                BigDecimal rollbackBankBefore =
                        bankReceivedToday(connection, rollbackIntentNo);
                Savepoint beforeFailedSettlement =
                        connection.setSavepoint("before_failed_vietqr_settlement");
                assertThatThrownBy(() -> opsService.reconcile(
                        rollbackReconciliationId,
                        "match-credit",
                        "mysql-settle-rollback-" + suffix,
                        new VietQrReconciliationCommandRequest(
                                0L, null, rollbackIntentNo,
                                "MYSQL-EVIDENCE-ROLLBACK-" + suffix,
                                "force ledger conflict rollback",
                                "integration-admin")))
                        .isInstanceOf(RuntimeException.class);
                connection.rollback(beforeFailedSettlement);
                assertThat(walletAvailable(connection, userId))
                        .isEqualByComparingTo(rollbackWalletBefore);
                assertThat(bankReceivedToday(connection, rollbackIntentNo))
                        .isEqualByComparingTo(rollbackBankBefore);
                assertThat(intentStatus(connection, rollbackIntentNo))
                        .isEqualTo("RECEIPT_REVIEW");
                assertThat(reconciliationStatus(
                        connection, rollbackReconciliationId)).isEqualTo("OPEN");

                String cancelKey = "mysql-create-cancel-" + suffix;
                ApiResult<Map<String, Object>> cancellable =
                        service.create(userId, cancelKey, new BigDecimal("25"));
                String cancellableIntentNo =
                        String.valueOf(cancellable.getData().get("intentNo"));
                assertThat(service.cancel(
                                userId, cancellableIntentNo,
                                "mysql-cancel-" + suffix, 0L)
                        .getData())
                        .containsEntry("status", "cancelled")
                        .containsEntry("version", 1L);
                ApiResult<Map<String, Object>> cancelledReceipt =
                        opsService.registerVietQrReceipt(
                                "mysql-receipt-after-cancel-" + suffix,
                                new VietQrReceiptRegistrationRequest(
                                        intentBankAccountId(connection, cancellableIntentNo),
                                        "MYSQL-CANCELLED-RECEIPT-" + suffix,
                                        String.valueOf(cancellable.getData().get("memoCode")),
                                        new BigDecimal("659750"),
                                        receivedAt.atZone(ZoneId.of("Asia/Shanghai")).toOffsetDateTime(),
                                        receiptEvidence(),
                                        "register receipt after app cancellation",
                                        "integration-admin"));
                assertThat(cancelledReceipt.getData())
                        .containsEntry("viewType", "LATE")
                        .containsEntry("intentNo", cancellableIntentNo);
                assertThat(intentStatus(connection, cancellableIntentNo))
                        .isEqualTo("CANCELLED");
                assertInFlightReconciliation(connection, cancellableIntentNo, userId, true);
                assertThat(pcMapper.listVietQrReconciliations("INFLIGHT", 100, 0))
                        .noneSatisfy(row -> assertThat(row.get("intentNo"))
                                .isEqualTo(cancellableIntentNo));

                BigDecimal beforeFuse = bankReceivedToday(connection, intentNo);
                ApiResult<Map<String, Object>> fusePeer =
                        service.create(
                                userId,
                                "mysql-create-fuse-peer-" + suffix,
                                new BigDecimal("25"));
                String fusePeerIntentNo =
                        String.valueOf(fusePeer.getData().get("intentNo"));
                setBankDailyCap(
                        connection, bankAccountId, beforeFuse.add(new BigDecimal("100000")));
                assertThat(opsService.registerVietQrReceipt(
                                "mysql-receipt-fuse-" + suffix,
                                new VietQrReceiptRegistrationRequest(
                                        bankAccountId,
                                        "MYSQL-FUSE-RECEIPT-" + suffix,
                                        null,
                                        new BigDecimal("200000"),
                                        receivedAt.atZone(ZoneId.of("Asia/Shanghai"))
                                                .toOffsetDateTime(),
                                        receiptEvidence(),
                                        "fuse account with physical receipt",
                                        "integration-admin"))
                        .getData())
                        .containsEntry("viewType", "ORPHAN");
                assertThat(bankReceivedToday(connection, intentNo))
                        .isEqualByComparingTo(beforeFuse.add(new BigDecimal("200000")));
                assertThat(bankStatus(connection, bankAccountId)).isEqualTo("FUSED");
                assertThat(intentStatus(connection, fusePeerIntentNo))
                        .isEqualTo("CANCELLED");
                assertInFlightReconciliation(
                        connection, fusePeerIntentNo, userId, true);
            } finally {
                connection.rollback();
            }
        }
    }

    private long activeUserId(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                """
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

    private Long intentBankAccountId(Connection connection, String intentNo) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT bank_account_id FROM nx_vietqr_intent WHERE intent_no = ?")) {
            statement.setString(1, intentNo);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getLong(1);
            }
        }
    }

    private BigDecimal walletAvailable(Connection connection, long userId) throws Exception {
        return decimalQuery(connection,
                "SELECT usdt_available FROM nx_user_wallet WHERE user_id = ?", userId);
    }

    private BigDecimal walletCumulativeDeposit(Connection connection, long userId)
            throws Exception {
        return decimalQuery(connection,
                "SELECT cumulative_deposit_usdt FROM nx_user_wallet WHERE user_id = ?", userId);
    }

    private BigDecimal bankReceivedToday(Connection connection, String intentNo)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT CASE WHEN b.received_business_date = DATE(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 7 HOUR))
                            THEN b.received_today_vnd ELSE 0 END
                  FROM nx_vietqr_bank_account b
                  JOIN nx_vietqr_intent i ON i.bank_account_id = b.id
                 WHERE i.intent_no = ?
                """)) {
            statement.setString(1, intentNo);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getBigDecimal(1);
            }
        }
    }

    private String bankStatus(Connection connection, long bankAccountId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT status FROM nx_vietqr_bank_account WHERE id = ?")) {
            statement.setLong(1, bankAccountId);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getString(1);
            }
        }
    }

    private void setBankDailyCap(
            Connection connection, long bankAccountId, BigDecimal dailyCap) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE nx_vietqr_bank_account SET daily_cap_vnd = ? WHERE id = ?")) {
            statement.setBigDecimal(1, dailyCap);
            statement.setLong(2, bankAccountId);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private int countLedger(Connection connection, String bizNo) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(1) FROM nx_wallet_ledger WHERE biz_no = ?")) {
            statement.setString(1, bizNo);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getInt(1);
            }
        }
    }

    private void insertDuplicateLedger(
            Connection connection, String bizNo, long userId, BigDecimal balanceAfter)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO nx_wallet_ledger (
                    biz_no, user_id, biz_type, asset, direction, amount,
                    balance_after, status, remark, created_at, updated_at, is_deleted
                ) VALUES (?, ?, 'VIETQR_DEPOSIT', 'USDT', 'IN', 1,
                          ?, 'SUCCESS', 'integration duplicate guard',
                          NOW(), NOW(), 0)
                """)) {
            statement.setString(1, bizNo);
            statement.setLong(2, userId);
            statement.setBigDecimal(3, balanceAfter);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private String intentStatus(Connection connection, String intentNo) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT status FROM nx_vietqr_intent WHERE intent_no = ?")) {
            statement.setString(1, intentNo);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getString(1);
            }
        }
    }

    private String reconciliationStatus(Connection connection, long id) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT status FROM nx_vietqr_reconciliation WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getString(1);
            }
        }
    }

    private BigDecimal decimalQuery(
            Connection connection, String sql, long userId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getBigDecimal(1);
            }
        }
    }

    private void seedBankAccount(
            Connection connection, String suffix, String accountNumber, String ciphertext)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO nx_vietqr_bank_account (
                    bank_code, bank_name, account_holder,
                    account_number_encrypted, account_number_hash, account_number_last4,
                    daily_cap_vnd, received_today_vnd, status,
                    version, created_at, updated_at, is_deleted
                ) VALUES (?, 'Integration Bank', 'NEXION INTEGRATION',
                          ?, ?, ?, 100000000, 0, 'ACTIVE',
                          0, NOW(), NOW(), 0)
                """)) {
            statement.setString(1, "IT" + suffix.substring(0, 6).toUpperCase());
            statement.setString(2, ciphertext);
            statement.setString(3, sha256(accountNumber));
            statement.setString(4, accountNumber.substring(accountNumber.length() - 4));
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private void assertInFlightReconciliation(
            Connection connection, String intentNo, long userId, boolean deleted)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT user_id, view_type, status, is_deleted
                  FROM nx_vietqr_reconciliation
                 WHERE reconciliation_no = CONCAT('APP-', ?)
                """)) {
            statement.setString(1, intentNo);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong("user_id")).isEqualTo(userId);
                assertThat(result.getString("view_type")).isEqualTo("INFLIGHT");
                assertThat(result.getString("status")).isEqualTo("OPEN");
                assertThat(result.getBoolean("is_deleted")).isEqualTo(deleted);
            }
        }
    }

    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private String receiptEvidence() {
        return "media:vqr_123e4567e89b12d3a456426614174000";
    }
}
