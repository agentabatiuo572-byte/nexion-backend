package ffdd.opsconsole.finance.hdpay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import ffdd.opsconsole.shared.config.MybatisMetaObjectHandler;
import ffdd.opsconsole.finance.mapper.AppVietQrIntentMapper;
import ffdd.opsconsole.finance.mapper.VietnamPaymentMapper;
import ffdd.opsconsole.finance.application.D1BankOrderService;
import ffdd.opsconsole.finance.application.VietQrReceiptEvidenceService;
import ffdd.opsconsole.finance.application.OpsVietnamPaymentService;
import ffdd.opsconsole.finance.application.FinanceSensitiveDataCipher;
import ffdd.opsconsole.finance.dto.HdPayManualCreditRequest;
import ffdd.opsconsole.finance.dto.VietQrReceiptRegistrationRequest;
import ffdd.opsconsole.finance.mapper.D1BankOrderMapper;
import ffdd.opsconsole.finance.mapper.VietQrReceiptEvidenceMapper;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyTransactionExecutor;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyExpiryTransitionExecutor;
import ffdd.opsconsole.shared.idempotency.mapper.AdminIdempotencyRecordMapper;
import ffdd.opsconsole.shared.storage.ObjectStorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.treasury.infrastructure.MybatisTreasuryLedgerRepository;
import ffdd.opsconsole.treasury.mapper.TreasuryLedgerMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class HdPayCallbackSettlementSpringTransactionMySqlIntegrationTest {
    private static final String SCHEMA_PREFIX = "nexion_hosted_callback_it_";

    @Test
    @EnabledIfEnvironmentVariable(named = "NEXION_HOSTED_RAIL_IT", matches = "true")
    void manualAndAutomaticSettlementShareOneFinancialIdentityAndRecoverTheOriginalCommand() throws Exception {
        String schema = SCHEMA_PREFIX + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate admin = new JdbcTemplate(dataSource(""));
        assertThat(admin.queryForObject("SELECT @@port", Integer.class)).isEqualTo(13306);
        admin.execute("CREATE DATABASE " + schema);
        try {
            DataSource dataSource = dataSource(schema);
            createFixtureSchema(dataSource);
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            SqlSessionTemplate template = new SqlSessionTemplate(sessionFactory(dataSource));
            DataSourceTransactionManager tx = new DataSourceTransactionManager(dataSource);
            EventOutboxService outbox = transactionalOutbox(jdbc, false);
            AuditLogService audit = transactionalAudit(jdbc, false);
            D1BankOrderService manual = manualService(template, tx, outbox, audit);
            HdPayCallbackSettlementService automatic = proxiedService(template, outbox, tx);
            for (String mode : List.of("MANUAL_FIRST", "AUTO_FIRST", "RACE", "MANUAL_RACE",
                    "CREATE_PENDING", "CREATE_UNKNOWN", "EXPIRED_REVIEW", "CANCELLED",
                    "QUERY_OUTSTANDING", "CALLBACK_QUERY_OUTSTANDING", "QUERY_PENDING_VERSION_DRIFT",
                    "QUERY_PENDING_AUTO_FIRST", "QUERY_PENDING_INTENT_CHANGED")) {
                authenticate();
                String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
                String intentNo = "VQR-HP-" + suffix;
                String providerId = "P" + suffix;
                String assetId = insertManualFixture(dataSource, template, intentNo, providerId, suffix);
                BigDecimal before = decimal(jdbc, "SELECT usdt_available FROM nx_user_wallet WHERE user_id=?", 41L);
                BigDecimal cumulative = decimal(jdbc, "SELECT cumulative_deposit_usdt FROM nx_user_wallet WHERE user_id=?", 41L);
                if (mode.equals("CREATE_PENDING")) jdbc.update("UPDATE nx_hdpay_payin_order SET submission_status='PENDING',provider_order_id=NULL,provider_status=NULL WHERE merchant_order_id=?", intentNo);
                if (mode.equals("CREATE_UNKNOWN")) jdbc.update("UPDATE nx_hdpay_payin_order SET submission_status='SUBMIT_UNKNOWN',provider_order_id=NULL,provider_status=NULL WHERE merchant_order_id=?", intentNo);
                if (mode.equals("EXPIRED_REVIEW")) {
                    jdbc.update("UPDATE nx_vietqr_intent SET status='EXPIRED',expires_at=DATE_SUB(NOW(),INTERVAL 1 MINUTE) WHERE intent_no=?", intentNo);
                    jdbc.update("UPDATE nx_vietqr_reconciliation SET is_deleted=1 WHERE intent_no=?", intentNo);
                    jdbc.update("UPDATE nx_hdpay_payin_order SET submission_status='REJECTED',provider_status=4,settlement_status='MANUAL_REVIEW' WHERE merchant_order_id=?", intentNo);
                }
                if (mode.equals("CANCELLED")) {
                    jdbc.update("UPDATE nx_vietqr_intent SET status='CANCELLED',cancel_idempotency_key=?,cancel_request_hash=? WHERE intent_no=?",
                            "cancel-" + suffix, "c".repeat(64), intentNo);
                    jdbc.update("UPDATE nx_vietqr_reconciliation SET is_deleted=1 WHERE intent_no=?", intentNo);
                }
                long providerVersion = 0L;
                HdPayCallbackSettlementService.QueryClaim callbackClaim = null;
                if (mode.equals("QUERY_OUTSTANDING")) {
                    jdbc.update("UPDATE nx_hdpay_payin_order SET updated_at=DATE_SUB(NOW(),INTERVAL 1 MINUTE) WHERE merchant_order_id=?", intentNo);
                    assertThat(template.getMapper(HdPayOrderMapper.class).claimOrderQuery(intentNo, 0L,
                            java.time.LocalDateTime.now(ZoneOffset.UTC))).isOne();
                    providerVersion = 1L;
                }
                if (mode.equals("CALLBACK_QUERY_OUTSTANDING")) {
                    callbackClaim = automatic.claimForProviderQuery(callback(intentNo, providerId));
                    assertThat(callbackClaim.disposition()).isEqualTo(HdPayCallbackSettlementService.ClaimDisposition.QUERY_PROVIDER);
                    providerVersion = 1L;
                }
                HdPayManualCreditRequest request = manualRequest(assetId, providerVersion, suffix);
                String key = "manual-" + suffix;
                if (mode.startsWith("QUERY_PENDING_")) {
                    // Freeze the original form before a real pending-query claim and finish.
                    jdbc.update("UPDATE nx_hdpay_payin_order SET updated_at=DATE_SUB(NOW(),INTERVAL 1 MINUTE) WHERE merchant_order_id=?", intentNo);
                    assertThat(template.getMapper(HdPayOrderMapper.class).claimOrderQuery(intentNo, 0L,
                            java.time.LocalDateTime.now(ZoneOffset.UTC).minusSeconds(30))).isOne();
                    HdPayGateway.PayOrder pending = new HdPayGateway.PayOrder(intentNo, providerId, 1,
                            new BigDecimal("200000"), "BANKQR", "");
                    assertThat(automatic.settleOrderQuery(intentNo, 1L, pending)).isEqualTo("success");
                    assertThat(jdbc.queryForObject("SELECT version FROM nx_hdpay_payin_order WHERE merchant_order_id=?",
                            Long.class, intentNo)).isEqualTo(2L);
                    assertThat(text(jdbc, "SELECT last_error_code FROM nx_hdpay_payin_order WHERE merchant_order_id=?", intentNo))
                            .isEqualTo("HDPAY_ORDER_QUERY_PENDING");
                    assertThat(jdbc.queryForObject("SELECT version FROM nx_vietqr_intent WHERE intent_no=?", Long.class, intentNo)).isZero();
                    assertThat(request.providerVersion()).isZero();
                }
                if (mode.equals("QUERY_PENDING_INTENT_CHANGED")) {
                    assertThat(jdbc.update("UPDATE nx_vietqr_intent SET version=version+1 WHERE intent_no=?", intentNo)).isOne();
                    String financial = financialSnapshot(jdbc);
                    assertThatThrownBy(() -> manual.manualCredit(intentNo, key, request))
                            .isInstanceOf(BizException.class).hasMessage("HDPAY_MANUAL_VERSION_CONFLICT");
                    assertThat(financialSnapshot(jdbc)).isEqualTo(financial);
                    assertUnchanged(jdbc, intentNo, 41L, before, cumulative);
                    continue;
                }
                if (mode.equals("AUTO_FIRST") || mode.equals("QUERY_PENDING_AUTO_FIRST")) {
                    if (mode.equals("QUERY_PENDING_AUTO_FIRST")) {
                        jdbc.update("UPDATE nx_hdpay_payin_order SET updated_at=DATE_SUB(NOW(),INTERVAL 1 MINUTE) WHERE merchant_order_id=?", intentNo);
                        assertThat(template.getMapper(HdPayOrderMapper.class).claimOrderQuery(intentNo, 2L,
                                java.time.LocalDateTime.now(ZoneOffset.UTC).minusSeconds(30))).isOne();
                        assertThat(automatic.settleOrderQuery(intentNo, 3L, query(intentNo, providerId))).isEqualTo("success");
                    } else automatic.settleConfirmed(callback(intentNo, providerId), query(intentNo, providerId));
                    String financial = financialSnapshot(jdbc);
                    assertThatThrownBy(() -> manual.manualCredit(intentNo, key, request))
                            .isInstanceOf(BizException.class).hasMessage("HDPAY_ORDER_ALREADY_CREDITED");
                    assertThat(financialSnapshot(jdbc)).isEqualTo(financial);
                    assertThat(count(jdbc, "SELECT COUNT(*) FROM nx_hdpay_manual_confirmation WHERE merchant_order_id=?", intentNo)).isZero();
                    assertThat(text(jdbc, "SELECT status FROM nx_vietqr_receipt_evidence WHERE asset_id=?", assetId)).isEqualTo("AVAILABLE");
                } else if (mode.equals("RACE") || mode.equals("MANUAL_RACE")) {
                    var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
                    var start = new java.util.concurrent.CountDownLatch(1);
                    try {
                        var first = workers.submit(() -> {
                            authenticate(); start.await();
                            try { return manual.manualCredit(intentNo, key, request).getCode(); }
                            catch (BizException ex) { return ex.getCode(); }
                            finally { SecurityContextHolder.clearContext(); }
                        });
                        var second = workers.submit(() -> {
                            authenticate(); start.await();
                            try {
                                if (mode.equals("MANUAL_RACE")) return manual.manualCredit(intentNo, key + "-second", request).getCode();
                                automatic.settleConfirmed(callback(intentNo, providerId), query(intentNo, providerId));
                                return 0;
                            } catch (BizException ex) { return ex.getCode(); }
                            finally { SecurityContextHolder.clearContext(); }
                        });
                        start.countDown();
                        assertThat(first.get(20, java.util.concurrent.TimeUnit.SECONDS)).isIn(0, 409);
                        assertThat(second.get(20, java.util.concurrent.TimeUnit.SECONDS)).isIn(0, 409);
                    } finally { workers.shutdownNow(); }
                    assertThat(count(jdbc, "SELECT COUNT(*) FROM nx_hdpay_manual_confirmation WHERE merchant_order_id=?", intentNo))
                            .isBetween(mode.equals("MANUAL_RACE") ? 1L : 0L, 1L);
                } else {
                    Map<String, Object> result = manual.manualCredit(intentNo, key, request).getData();
                    assertThat(result).containsEntry("confirmationSource", "ADMIN_MANUAL");
                    if (mode.equals("QUERY_PENDING_VERSION_DRIFT")) {
                        assertThat(result).containsEntry("providerVersion", 3L);
                        assertThat(jdbc.queryForObject("SELECT version FROM nx_hdpay_payin_order WHERE merchant_order_id=?",
                                Long.class, intentNo)).isEqualTo(3L);
                        assertThat(jdbc.queryForObject("SELECT provider_status FROM nx_hdpay_payin_order WHERE merchant_order_id=?",
                                Integer.class, intentNo)).isEqualTo(1);
                    }
                    if (mode.equals("CANCELLED")) assertThat(jdbc.queryForObject(
                            "SELECT provider_status FROM nx_hdpay_payin_order WHERE merchant_order_id=?", Integer.class, intentNo)).isEqualTo(1);
                    assertThat(text(jdbc, "SELECT operator FROM nx_hdpay_manual_confirmation WHERE merchant_order_id=?", intentNo)).isEqualTo("test-finance");
                    assertThat(text(jdbc, "SELECT operator FROM nx_treasury_reserve_ledger WHERE voucher_no=?", intentNo)).isEqualTo("test-finance");
                    assertThat(text(jdbc, "SELECT bound_resource_type FROM nx_vietqr_receipt_evidence WHERE asset_id=?", assetId)).isEqualTo("HDPAY_MANUAL_CONFIRMATION");
                    String financial = financialSnapshot(jdbc);
                    if (mode.equals("QUERY_PENDING_VERSION_DRIFT")) {
                        assertThat(automatic.settleOrderQuery(intentNo, 1L, query(intentNo, providerId))).isEqualTo("success");
                        assertThat(financialSnapshot(jdbc)).isEqualTo(financial);
                        HdPayManualCreditRequest refreshed = new HdPayManualCreditRequest(request.expectedVersion(), 2L,
                                request.receivedVnd(), request.paymentReference(), request.receivedAt(),
                                request.evidenceRef(), request.reason(), request.operator());
                        assertThatThrownBy(() -> manual.manualCredit(intentNo, key, refreshed)).isInstanceOf(BizException.class)
                                .hasMessage("IDEMPOTENCY_KEY_PAYLOAD_MISMATCH");
                        assertThat(financialSnapshot(jdbc)).isEqualTo(financial);
                    }
                    jdbc.update("UPDATE nx_admin_idempotency_record SET expires_at=DATE_SUB(NOW(),INTERVAL 2 DAY) WHERE idempotency_key=?", key);
                    assertThat(manual.manualCredit(intentNo, key, request).getData())
                            .containsEntry("manualConfirmationNo", result.get("manualConfirmationNo"));
                    assertThat(financialSnapshot(jdbc)).isEqualTo(financial);
                    HdPayManualCreditRequest changed = new HdPayManualCreditRequest(0L, request.providerVersion(),
                            request.receivedVnd(), request.paymentReference(), request.receivedAt(), request.evidenceRef(), "different reason", "spoofed");
                    assertThatThrownBy(() -> manual.manualCredit(intentNo, key, changed)).isInstanceOf(BizException.class)
                            .hasMessage("IDEMPOTENCY_KEY_PAYLOAD_MISMATCH");
                    assertThatThrownBy(() -> manual.manualCredit(intentNo, key + "-new", request)).isInstanceOf(BizException.class)
                            .hasMessage("HDPAY_ORDER_ALREADY_CREDITED");
                    assertThat(financialSnapshot(jdbc)).isEqualTo(financial);
                    if (mode.equals("CREATE_PENDING")) {
                        assertThat(template.getMapper(HdPayOrderMapper.class).authorizeSubmissionIfIntentPayable(intentNo)).isZero();
                    } else if (mode.equals("CREATE_UNKNOWN")) {
                        assertThat(template.getMapper(HdPayOrderMapper.class).markCreated(intentNo, "https://api.hdpayadmin.com/pay?id=fixture")).isOne();
                        automatic.settleConfirmed(callback(intentNo, providerId), query(intentNo, providerId));
                        assertThat(count(jdbc, "SELECT COUNT(*) FROM nx_hdpay_settlement_review WHERE merchant_order_id=?", intentNo)).isZero();
                    } else if (mode.equals("EXPIRED_REVIEW")) {
                        assertThat(text(jdbc, "SELECT submission_status FROM nx_hdpay_payin_order WHERE merchant_order_id=?", intentNo)).isEqualTo("REJECTED");
                        assertThat(jdbc.queryForObject("SELECT provider_status FROM nx_hdpay_payin_order WHERE merchant_order_id=?", Integer.class, intentNo)).isEqualTo(4);
                    } else if (mode.equals("QUERY_OUTSTANDING")) {
                        automatic.settleOrderQuery(intentNo, 1L, query(intentNo, providerId));
                    } else if (mode.equals("CALLBACK_QUERY_OUTSTANDING")) {
                        automatic.settleConfirmed(callbackClaim.fact(), callbackClaim.claimToken(), query(intentNo, providerId));
                    } else {
                        automatic.settleConfirmed(callback(intentNo, providerId), query(intentNo, providerId));
                    }
                }
                assertThat(decimal(jdbc, "SELECT usdt_available FROM nx_user_wallet WHERE user_id=?", 41L)).isEqualByComparingTo(before.add(new BigDecimal("10")));
                assertThat(decimal(jdbc, "SELECT cumulative_deposit_usdt FROM nx_user_wallet WHERE user_id=?", 41L)).isEqualByComparingTo(cumulative.add(new BigDecimal("10")));
                assertThat(count(jdbc, "SELECT COUNT(*) FROM nx_wallet_ledger WHERE biz_no=?", intentNo)).isOne();
                assertReserve(jdbc, intentNo);
                assertThat(count(jdbc, "SELECT COUNT(*) FROM nx_notification WHERE biz_no=?", "HDPAY:" + intentNo)).isOne();
                assertThat(count(jdbc, "SELECT COUNT(*) FROM it_outbox WHERE intent_no=?", intentNo)).isOne();
                assertThat(text(jdbc, "SELECT status FROM nx_vietqr_intent WHERE intent_no=?", intentNo)).isEqualTo("CREDITED");
                assertThat(text(jdbc, "SELECT settlement_status FROM nx_hdpay_payin_order WHERE merchant_order_id=?", intentNo)).isEqualTo("CREDITED");
                var read = manual.list("HDPAY", "CREDITED", intentNo, 1, 20).getData();
                assertThat((Long) read.get("total")).isOne();
                @SuppressWarnings("unchecked") List<Map<String, Object>> rows = (List<Map<String, Object>>) read.get("records");
                assertThat(rows).hasSize(1);
                assertThat(rows.get(0)).containsEntry("manualCreditAllowed", false).containsEntry("paymentUrl", null);
                long manualCount = count(jdbc, "SELECT COUNT(*) FROM nx_hdpay_manual_confirmation WHERE merchant_order_id=?", intentNo);
                assertThat(rows.get(0).get("confirmationSource")).isEqualTo(manualCount == 1 ? "ADMIN_MANUAL" : "AUTO_PROVIDER");
                if (mode.equals("CANCELLED")) {
                    assertThat(text(jdbc, "SELECT cancel_idempotency_key FROM nx_vietqr_intent WHERE intent_no=?", intentNo)).isEqualTo("cancel-" + suffix);
                    assertThat(text(jdbc, "SELECT cancel_request_hash FROM nx_vietqr_intent WHERE intent_no=?", intentNo)).isEqualTo("c".repeat(64));
                    assertThat(text(jdbc, "SELECT previous_intent_status FROM nx_hdpay_manual_confirmation WHERE merchant_order_id=?", intentNo)).isEqualTo("CANCELLED");
                }
            }
            verifyExistingBankReceipt(dataSource, template, tx);
            verifyReferenceCannotBecomeAnotherBankReceipt(dataSource, template, tx);
            verifyManualRollback(dataSource, template, tx);
            verifyOrderReadContract(dataSource, template, tx);
        } finally {
            SecurityContextHolder.clearContext();
            admin.execute("DROP DATABASE " + schema);
        }
    }

    @Test
    void connectionGuardRejectsBusinessEndpointsAndUnownedSchemas() {
        String schema = SCHEMA_PREFIX + "a".repeat(32);
        assertThat(isolatedUrl("127.0.0.1:13306", schema)).contains(":13306/" + schema + "?");
        for (String endpoint : new String[]{null, "", "localhost:13306", "127.0.0.1:3306", "127.0.0.1:13306/other"}) {
            assertThatThrownBy(() -> isolatedUrl(endpoint, schema)).isInstanceOf(IllegalArgumentException.class);
        }
        for (String invalid : new String[]{null, "nexion", "mysql", SCHEMA_PREFIX + "a", schema + "`"}) {
            assertThatThrownBy(() -> isolatedUrl("127.0.0.1:13306", invalid)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    @EnabledIfEnvironmentVariable(named = "NEXION_HOSTED_RAIL_IT", matches = "true")
    void realMySqlCreditsExactlyOnceAndRollsBackEveryFinancialWriteOnFailure(boolean hosted) throws Exception {
        String schema = SCHEMA_PREFIX + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate admin = new JdbcTemplate(dataSource(""));
        assertThat(admin.queryForObject("SELECT @@port", Integer.class)).isEqualTo(13306);
        admin.execute("CREATE DATABASE " + schema);
        try {
            DataSource dataSource = dataSource(schema);
            assertThat(new JdbcTemplate(dataSource).queryForObject("SELECT DATABASE()", String.class)).isEqualTo(schema);
            createFixtureSchema(dataSource);
            verifySettlement(dataSource, hosted);
        } finally {
            admin.execute("DROP DATABASE " + schema);
        }
    }

    private void verifySettlement(DataSource dataSource, boolean hosted) throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String intentNo = "VQR-HP-" + suffix;
        String providerOrderId = "P" + suffix;
        String bankCode = "HP" + suffix.substring(0, 10).toUpperCase();
        long userId;
        Long bankAccountId;
        BigDecimal walletBefore;
        BigDecimal cumulativeBefore;

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            userId = activeUserId(connection);
            bankAccountId = hosted ? null : insertBankAccount(connection, bankCode, suffix);
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

            // Start with no callback: durable order scan, fenced claim, provider
            // query settlement, then late callback/replay must still credit once.
            HdPayOrderMapper orderMapper = template.getMapper(HdPayOrderMapper.class);
            new TransactionTemplate(txManager).executeWithoutResult(status -> {
                jdbc.update("UPDATE nx_hdpay_payin_order SET updated_at=DATE_SUB(NOW(), INTERVAL 60 SECOND) WHERE merchant_order_id=?", intentNo);
                var dueBefore = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusSeconds(30);
                assertThat(orderMapper.listOrdersDueForQuery(dueBefore, 20))
                        .anySatisfy(row -> assertThat(row.get("merchantOrderId")).isEqualTo(intentNo));
                assertThat(orderMapper.claimOrderQuery(intentNo, 0L, dueBefore)).isOne();
                assertThat(orderMapper.claimOrderQuery(intentNo, 0L, dueBefore)).isZero();
                // Simulate the first worker crashing after its durable claim.
                jdbc.update("UPDATE nx_hdpay_payin_order SET updated_at=DATE_SUB(NOW(), INTERVAL 60 SECOND) WHERE merchant_order_id=?", intentNo);
                assertThat(orderMapper.claimOrderQuery(intentNo, 1L, dueBefore)).isOne();
                assertThat(successful.settleOrderQuery(intentNo, 1L, query)).isEqualTo("success");
                assertThat(count(jdbc, "SELECT COUNT(*) FROM nx_wallet_ledger WHERE biz_no=?", intentNo)).isZero();
                assertThat(successful.settleOrderQuery(intentNo, 2L, query)).isEqualTo("success");
                assertThat(count(jdbc, "SELECT COUNT(*) FROM nx_hdpay_callback_inbox WHERE merchant_order_id=?", intentNo)).isZero();
                assertThat(successful.settleOrderQuery(intentNo, 1L, query)).isEqualTo("success");
                assertThat(successful.settleConfirmed(callback, query)).isEqualTo("success");
                assertThat(count(jdbc, "SELECT COUNT(*) FROM nx_hdpay_settlement_review WHERE merchant_order_id=?", intentNo)).isZero();
                assertThat(decimal(jdbc, "SELECT usdt_available FROM nx_user_wallet WHERE user_id=?", userId))
                        .isEqualByComparingTo(walletBefore.add(new BigDecimal("10.000000")));
                assertThat(count(jdbc, "SELECT COUNT(*) FROM nx_wallet_ledger WHERE biz_no=?", intentNo)).isOne();
                assertReserve(jdbc, intentNo);
                assertThat(count(jdbc, "SELECT COUNT(*) FROM nx_notification WHERE biz_no=?", "HDPAY:" + intentNo)).isOne();
                assertThat(orderMapper.listOrdersDueForQuery(dueBefore.plusMinutes(10), 20)).isEmpty();
                status.setRollbackOnly();
            });
            assertUnchanged(jdbc, intentNo, userId, walletBefore, cumulativeBefore);

            new TransactionTemplate(txManager).executeWithoutResult(status -> {
                assertThat(jdbc.update("UPDATE nx_vietqr_intent SET payment_rail='MANUAL' WHERE intent_no=?", intentNo)).isOne();
                assertThat(successful.settleConfirmed(callback, query)).isEqualTo("success");
                assertThat(decimal(jdbc, "SELECT usdt_available FROM nx_user_wallet WHERE user_id=?", userId))
                        .isEqualByComparingTo(walletBefore);
                assertThat(decimal(jdbc, "SELECT cumulative_deposit_usdt FROM nx_user_wallet WHERE user_id=?", userId))
                        .isEqualByComparingTo(cumulativeBefore);
                assertThat(count(jdbc, "SELECT COUNT(*) FROM nx_wallet_ledger WHERE biz_no=?", intentNo)).isZero();
                assertThat(count(jdbc, "SELECT COUNT(*) FROM nx_notification WHERE biz_no=?", "HDPAY:" + intentNo)).isZero();
                assertThat(text(jdbc, "SELECT status FROM nx_vietqr_intent WHERE intent_no=?", intentNo)).isEqualTo("AWAITING_PAYMENT");
                assertThat(text(jdbc, "SELECT settlement_status FROM nx_hdpay_payin_order WHERE merchant_order_id=?", intentNo)).isEqualTo("MANUAL_REVIEW");
                assertThat(text(jdbc, "SELECT reason FROM nx_hdpay_settlement_review WHERE merchant_order_id=?", intentNo)).isEqualTo("VIETQR_PAYMENT_RAIL_CONFLICT");
                assertThat(text(jdbc, "SELECT processing_status FROM nx_hdpay_callback_inbox WHERE merchant_order_id=?", intentNo)).isEqualTo("MANUAL_REVIEW");
                status.setRollbackOnly();
            });
            assertUnchanged(jdbc, intentNo, userId, walletBefore, cumulativeBefore);

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
                assertReserve(jdbc, intentNo);
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
            assertThatThrownBy(() -> failing.settleOrderQuery(intentNo, 0L, query))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("forced outbox failure");
            assertUnchanged(jdbc, intentNo, userId, walletBefore, cumulativeBefore);
            assertThatThrownBy(() -> failing.settleConfirmed(callback, query))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("forced outbox failure");
            assertUnchanged(jdbc, intentNo, userId, walletBefore, cumulativeBefore);

            // A pre-existing/conflicting voucher must not credit a wallet without
            // its reserve. No INSERT IGNORE or successful duplicate fallback.
            jdbc.update("INSERT INTO nx_treasury_reserve_ledger(reserve_no,voucher_no,direction,amount_usd) VALUES('CONFLICT',?,'IN',9)", intentNo);
            assertThatThrownBy(() -> successful.settleConfirmed(callback, query))
                    .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
            assertThat(decimal(jdbc, "SELECT amount_usd FROM nx_treasury_reserve_ledger WHERE voucher_no=?", intentNo))
                    .isEqualByComparingTo("9");
            jdbc.update("DELETE FROM nx_treasury_reserve_ledger WHERE reserve_no='CONFLICT'");
            assertUnchanged(jdbc, intentNo, userId, walletBefore, cumulativeBefore);

            // Two committed transactions contend on the order lock. The callback
            // replay and subsequent query replay must leave one wallet/reserve pair.
            var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                var results = workers.invokeAll(java.util.List.<java.util.concurrent.Callable<String>>of(
                        () -> successful.settleConfirmed(callback, query),
                        () -> successful.settleConfirmed(callback, query)));
                for (var result : results) assertThat(result.get(15, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("success");
            } finally {
                workers.shutdownNow();
            }
            assertThat(successful.settleOrderQuery(intentNo, 0L, query)).isEqualTo("success");
            assertThat(decimal(jdbc, "SELECT usdt_available FROM nx_user_wallet WHERE user_id=?", userId))
                    .isEqualByComparingTo(walletBefore.add(new BigDecimal("10")));
            assertThat(count(jdbc, "SELECT COUNT(*) FROM nx_wallet_ledger WHERE biz_no=?", intentNo)).isOne();
            assertReserve(jdbc, intentNo);
        } finally {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(true);
                execute(connection, "DELETE FROM nx_notification WHERE biz_no=?", "HDPAY:" + intentNo);
                execute(connection, "DELETE FROM nx_treasury_reserve_ledger WHERE voucher_no=?", intentNo);
                execute(connection, "DELETE FROM nx_hdpay_callback_inbox WHERE merchant_order_id=?", intentNo);
                execute(connection, "DELETE FROM nx_wallet_ledger WHERE biz_no=?", intentNo);
                execute(connection, "DELETE FROM nx_hdpay_payin_order WHERE merchant_order_id=?", intentNo);
                execute(connection, "DELETE FROM nx_vietqr_reconciliation WHERE intent_no=?", intentNo);
                execute(connection, "DELETE FROM nx_vietqr_intent WHERE intent_no=?", intentNo);
                execute(connection, "DELETE FROM nx_vietqr_bank_account WHERE bank_code=?", bankCode);
            }
        }
    }

    private void verifyReferenceCannotBecomeAnotherBankReceipt(DataSource dataSource, SqlSessionTemplate template,
                                                               DataSourceTransactionManager tx) throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        for (String mode : List.of("MANUAL_FIRST", "BANK_FIRST", "RACE")) {
            authenticate();
            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            String intentNo = "VQR-HP-" + suffix;
            String assetId = insertManualFixture(dataSource, template, intentNo, "P" + suffix, suffix);
            long bank;
            try (Connection connection = dataSource.getConnection()) { bank = insertBankAccount(connection, "RR" + suffix.substring(0, 10), suffix); }
            AuditLogService audit = transactionalAudit(jdbc, false);
            EventOutboxService outbox = transactionalOutbox(jdbc, false);
            D1BankOrderService manual = manualService(template, tx, outbox, audit);
            AdminIdempotencyService idem = idempotencyService(template, tx);
            var receiptEvidence = transactionProxy(new VietQrReceiptEvidenceService(template.getMapper(VietQrReceiptEvidenceMapper.class),
                    mock(ObjectStorageService.class), audit, idem, Clock.systemUTC()), VietQrReceiptEvidenceService.class, tx);
            OpsVietnamPaymentService bankService = transactionProxy(new OpsVietnamPaymentService(
                    template.getMapper(VietnamPaymentMapper.class), audit, idem, mock(FinanceSensitiveDataCipher.class),
                    template.getMapper(AppVietQrIntentMapper.class), outbox, receiptEvidence, Clock.systemUTC()), OpsVietnamPaymentService.class, tx);
            HdPayManualCreditRequest request = manualRequest(assetId, 0L, suffix);
            var bankRequest = new VietQrReceiptRegistrationRequest(bank, request.paymentReference(), null,
                    request.receivedVnd(), request.receivedAt(), null, "Actual unmatched receipt fixture", "spoofed");
            BigDecimal walletBefore = decimal(jdbc, "SELECT usdt_available FROM nx_user_wallet WHERE user_id=41");
            if (mode.equals("MANUAL_FIRST")) {
                manual.manualCredit(intentNo, "reference-manual-" + suffix, request);
                String before = financialSnapshot(jdbc);
                assertThatThrownBy(() -> bankService.registerVietQrReceipt("reference-bank-" + suffix, bankRequest))
                        .isInstanceOf(BizException.class).hasMessage("VIETQR_PAYMENT_REFERENCE_ALREADY_REGISTERED");
                assertThat(financialSnapshot(jdbc)).isEqualTo(before);
            } else if (mode.equals("BANK_FIRST")) {
                bankService.registerVietQrReceipt("reference-bank-" + suffix, bankRequest);
                String before = financialSnapshot(jdbc);
                assertThatThrownBy(() -> manual.manualCredit(intentNo, "reference-manual-" + suffix, request))
                        .isInstanceOf(BizException.class).hasMessage("VIETQR_PAYMENT_REFERENCE_CONFLICT");
                assertThat(financialSnapshot(jdbc)).isEqualTo(before);
            } else {
                var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
                var start = new java.util.concurrent.CountDownLatch(1);
                try {
                    var first = workers.submit(() -> {
                        authenticate(); start.await();
                        try { return manual.manualCredit(intentNo, "reference-manual-" + suffix, request).getCode(); }
                        catch (RuntimeException ex) { return ex instanceof BizException biz ? biz.getCode() : 500; }
                        finally { SecurityContextHolder.clearContext(); }
                    });
                    var second = workers.submit(() -> {
                        authenticate(); start.await();
                        try { return bankService.registerVietQrReceipt("reference-bank-" + suffix, bankRequest).getCode(); }
                        catch (RuntimeException ex) { return ex instanceof BizException biz ? biz.getCode() : 500; }
                        finally { SecurityContextHolder.clearContext(); }
                    });
                    start.countDown();
                    int a = first.get(20, java.util.concurrent.TimeUnit.SECONDS);
                    int b = second.get(20, java.util.concurrent.TimeUnit.SECONDS);
                    assertThat(List.of(a,b)).contains(0);
                    assertThat(a == 0 && b == 0).isFalse();
                } finally { workers.shutdownNow(); }
            }
            long manualCount = count(jdbc, "SELECT COUNT(*) FROM nx_hdpay_manual_confirmation WHERE merchant_order_id=?", intentNo);
            long bankCount = count(jdbc, "SELECT COUNT(*) FROM nx_vietqr_reconciliation WHERE payment_reference=? AND is_deleted=0", request.paymentReference());
            assertThat(manualCount + bankCount).as("reference belongs to exactly one real source").isOne();
            assertThat(decimal(jdbc, "SELECT usdt_available FROM nx_user_wallet WHERE user_id=41"))
                    .isEqualByComparingTo(walletBefore.add(new BigDecimal("10").multiply(BigDecimal.valueOf(manualCount))));
            assertThat(decimal(jdbc, "SELECT received_today_vnd FROM nx_vietqr_bank_account WHERE id=?", bank))
                    .isEqualByComparingTo(new BigDecimal("200000").multiply(BigDecimal.valueOf(bankCount)));
            assertThat(count(jdbc, "SELECT COUNT(*) FROM nx_wallet_ledger WHERE biz_no=?", intentNo)).isEqualTo(manualCount);
            assertThat(count(jdbc, "SELECT COUNT(*) FROM nx_treasury_reserve_ledger WHERE voucher_no=?", intentNo)).isEqualTo(manualCount);
            String completed = financialSnapshot(jdbc);
            if (manualCount == 1) {
                assertThat(manual.manualCredit(intentNo, "reference-manual-" + suffix, request).getCode()).isZero();
                assertThatThrownBy(() -> bankService.registerVietQrReceipt("reference-bank-" + suffix, bankRequest)).isInstanceOf(BizException.class);
            } else {
                assertThat(bankService.registerVietQrReceipt("reference-bank-" + suffix, bankRequest).getCode()).isZero();
                assertThatThrownBy(() -> manual.manualCredit(intentNo, "reference-manual-" + suffix, request)).isInstanceOf(BizException.class);
            }
            assertThat(financialSnapshot(jdbc)).as("original failed command cannot duplicate a claimed reference").isEqualTo(completed);
        }
    }

    private void verifyExistingBankReceipt(DataSource dataSource, SqlSessionTemplate template,
                                           DataSourceTransactionManager tx) throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        TreasuryLedgerMapper treasury = template.getMapper(TreasuryLedgerMapper.class);
        for (String mode : List.of("MANUAL_FIRST", "AUTO_FIRST", "RACE")) {
            authenticate();
            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            String intentNo = "VQR-HP-" + suffix;
            String providerId = "P" + suffix;
            String assetId = insertManualFixture(dataSource, template, intentNo, providerId, suffix);
            long receiptId = insertExistingReceipt(dataSource, template, intentNo, suffix);
            Map<String,Object> receiptBefore = jdbc.queryForMap("SELECT * FROM nx_vietqr_reconciliation WHERE id=?", receiptId);
            Map<String,Object> bankBefore = jdbc.queryForMap("SELECT * FROM nx_vietqr_bank_account WHERE id=?", receiptBefore.get("bank_account_id"));
            BigDecimal reserveBefore = treasury.vietQrHeldReserveUsdt().add(decimal(jdbc,
                    "SELECT COALESCE(SUM(CASE WHEN direction='IN' THEN amount_usd ELSE -amount_usd END),0) FROM nx_treasury_reserve_ledger WHERE status='CONFIRMED' AND is_deleted=0"));
            BigDecimal pendingBefore = treasury.pendingUnverifiedDepositUsdt();
            BigDecimal walletBefore = decimal(jdbc, "SELECT usdt_available FROM nx_user_wallet WHERE user_id=41");
            BigDecimal cumulativeBefore = decimal(jdbc, "SELECT cumulative_deposit_usdt FROM nx_user_wallet WHERE user_id=41");
            D1BankOrderService manual = manualService(template, tx, transactionalOutbox(jdbc, false), transactionalAudit(jdbc, false));
            HdPayCallbackSettlementService automatic = proxiedService(template, transactionalOutbox(jdbc, false), tx);
            if (!mode.equals("MANUAL_FIRST")) {
                // Real provider confirmation must not add a second reserve already represented by this receipt.
                if (mode.equals("AUTO_FIRST")) automatic.settleConfirmed(callback(intentNo, providerId), query(intentNo, providerId));
                else {
                    var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
                    var start = new java.util.concurrent.CountDownLatch(1);
                    HdPayManualCreditRequest initial = receiptRequest(jdbc, receiptId, assetId, 0L);
                    try {
                        var first = workers.submit(() -> {
                            authenticate(); start.await();
                            try { return manual.manualCredit(intentNo, "receipt-race-" + suffix, initial).getCode(); }
                            catch (BizException ex) { return ex.getCode(); }
                            finally { SecurityContextHolder.clearContext(); }
                        });
                        var second = workers.submit(() -> { start.await(); return automatic.settleConfirmed(callback(intentNo, providerId), query(intentNo, providerId)); });
                        start.countDown();
                        assertThat(first.get(20, java.util.concurrent.TimeUnit.SECONDS)).isIn(0, 409);
                        assertThat(second.get(20, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("success");
                    } finally { workers.shutdownNow(); }
                }
            }
            if (!"CREDITED".equals(text(jdbc, "SELECT status FROM nx_vietqr_intent WHERE intent_no=?", intentNo))) {
                assertThat(decimal(jdbc, "SELECT usdt_available FROM nx_user_wallet WHERE user_id=41")).isEqualByComparingTo(walletBefore);
                long providerVersion = jdbc.queryForObject("SELECT version FROM nx_hdpay_payin_order WHERE merchant_order_id=?", Long.class, intentNo);
                HdPayManualCreditRequest request = receiptRequest(jdbc, receiptId, assetId, providerVersion);
                manual.manualCredit(intentNo, "receipt-credit-" + suffix, request);
            }
            assertThat(decimal(jdbc, "SELECT usdt_available FROM nx_user_wallet WHERE user_id=41")).isEqualByComparingTo(walletBefore.add(new BigDecimal("10")));
            assertThat(decimal(jdbc, "SELECT cumulative_deposit_usdt FROM nx_user_wallet WHERE user_id=41")).isEqualByComparingTo(cumulativeBefore.add(new BigDecimal("10")));
            assertThat(treasury.pendingUnverifiedDepositUsdt()).isEqualByComparingTo(pendingBefore.subtract(new BigDecimal("10")));
            assertThat(treasury.vietQrHeldReserveUsdt().add(decimal(jdbc,
                    "SELECT COALESCE(SUM(CASE WHEN direction='IN' THEN amount_usd ELSE -amount_usd END),0) FROM nx_treasury_reserve_ledger WHERE status='CONFIRMED' AND is_deleted=0"))).isEqualByComparingTo(reserveBefore);
            assertThat(count(jdbc, "SELECT COUNT(*) FROM nx_treasury_reserve_ledger WHERE voucher_no=?", intentNo)).isZero();
            assertThat(count(jdbc, "SELECT COUNT(*) FROM nx_wallet_ledger WHERE biz_no=?", intentNo)).isOne();
            assertThat(count(jdbc, "SELECT COUNT(*) FROM nx_wallet_ledger WHERE biz_no=?", "D1-VIETQR-" + receiptBefore.get("reconciliation_no"))).isZero();
            assertThat(jdbc.queryForMap("SELECT * FROM nx_vietqr_bank_account WHERE id=?", receiptBefore.get("bank_account_id"))).isEqualTo(bankBefore);
            Map<String,Object> receiptAfter = jdbc.queryForMap("SELECT * FROM nx_vietqr_reconciliation WHERE id=?", receiptId);
            assertThat(receiptAfter).containsEntry("status", "CREDITED");
            assertThat(((Number) receiptAfter.get("version")).longValue()).isEqualTo(((Number) receiptBefore.get("version")).longValue() + 1);
            for (String field : List.of("received_vnd", "locked_fx_rate_vnd_per_usdt", "payment_reference", "received_at", "note", "view_type", "bank_account_id", "is_deleted")) {
                assertThat(receiptAfter.get(field)).as("real receipt fact %s preserved", field).isEqualTo(receiptBefore.get(field));
            }
            Map<String,Object> fact = jdbc.queryForMap("SELECT * FROM nx_hdpay_manual_confirmation WHERE merchant_order_id=?", intentNo);
            assertThat(fact).containsEntry("reserve_source", "EXISTING_BANK_RECEIPT");
            assertThat(((Number) fact.get("bank_receipt_id")).longValue()).isEqualTo(receiptId);
            assertThat(((Number) fact.get("bank_receipt_version")).longValue()).isEqualTo(((Number) receiptBefore.get("version")).longValue());
            @SuppressWarnings("unchecked") var rows = (List<Map<String,Object>>) manual.list("HDPAY", "CREDITED", intentNo, 1, 20).getData().get("records");
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0)).containsEntry("confirmationSource", "ADMIN_MANUAL").containsEntry("reserveSource", "EXISTING_BANK_RECEIPT");
            String snapshot = financialSnapshot(jdbc);
            assertThatThrownBy(() -> manual.manualCredit(intentNo, "receipt-repeat-" + suffix,
                    receiptRequest(jdbc, receiptId, assetId, 0L))).isInstanceOf(BizException.class).hasMessage("HDPAY_ORDER_ALREADY_CREDITED");
            assertThat(financialSnapshot(jdbc)).isEqualTo(snapshot);
            automatic.settleConfirmed(callback(intentNo, providerId), query(intentNo, providerId));
            assertThat(decimal(jdbc, "SELECT usdt_available FROM nx_user_wallet WHERE user_id=41")).isEqualByComparingTo(walletBefore.add(new BigDecimal("10")));
            assertThat(count(jdbc, "SELECT COUNT(*) FROM nx_treasury_reserve_ledger WHERE voucher_no=?", intentNo)).isZero();
            assertThat(treasury.pendingUnverifiedDepositUsdt()).isEqualByComparingTo(pendingBefore.subtract(new BigDecimal("10")));
            assertThat(count(jdbc, "SELECT COUNT(*) FROM nx_vietqr_receipt_evidence WHERE bound_resource_id=? AND status='BOUND'", "REGISTERED:" + receiptBefore.get("reconciliation_no"))).isOne();
        }
        for (String guard : List.of("MULTIPLE", "AMOUNT", "FX", "USER", "REFERENCE", "RECEIVED_AT", "RETURN_PENDING", "RETURNED", "LEGACY_LEDGER", "CHANGED_RECEIPT", "DELETED_RECEIPT")) {
            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            String intentNo = "VQR-HP-" + suffix;
            String assetId = insertManualFixture(dataSource, template, intentNo, "P" + suffix, suffix);
            long receiptId = insertExistingReceipt(dataSource, template, intentNo, suffix);
            HdPayManualCreditRequest request = receiptRequest(jdbc, receiptId, assetId, 0L);
            switch (guard) {
                case "MULTIPLE" -> insertExistingReceipt(dataSource, template, intentNo, UUID.randomUUID().toString().replace("-", "").substring(0, 12));
                case "AMOUNT" -> jdbc.update("UPDATE nx_vietqr_reconciliation SET received_vnd=199999 WHERE id=?", receiptId);
                case "FX" -> jdbc.update("UPDATE nx_vietqr_reconciliation SET locked_fx_rate_vnd_per_usdt=20001 WHERE id=?", receiptId);
                case "USER" -> jdbc.update("UPDATE nx_vietqr_reconciliation SET user_id=42 WHERE id=?", receiptId);
                case "REFERENCE" -> jdbc.update("UPDATE nx_vietqr_reconciliation SET payment_reference='OTHER-REFERENCE' WHERE id=?", receiptId);
                case "RECEIVED_AT" -> jdbc.update("UPDATE nx_vietqr_reconciliation SET received_at=DATE_ADD(received_at,INTERVAL 1 SECOND) WHERE id=?", receiptId);
                case "RETURN_PENDING", "RETURNED" -> jdbc.update("UPDATE nx_vietqr_reconciliation SET status=? WHERE id=?", guard, receiptId);
                case "LEGACY_LEDGER" -> jdbc.update("INSERT INTO nx_wallet_ledger(biz_no,user_id,biz_type,asset,direction,amount,balance_after,status) VALUES(?,41,'VIETQR_DEPOSIT','USDT','IN',10,110,'SUCCESS')", "D1-VIETQR-BANK-HP-" + suffix);
                case "CHANGED_RECEIPT" -> jdbc.update("UPDATE nx_vietqr_reconciliation SET version=version+1,received_vnd=199999 WHERE id=?", receiptId);
                case "DELETED_RECEIPT" -> jdbc.update("UPDATE nx_vietqr_reconciliation SET is_deleted=1 WHERE id=?", receiptId);
            }
            String before = financialSnapshot(jdbc);
            D1BankOrderService manual = manualService(template, tx, transactionalOutbox(jdbc, false), transactionalAudit(jdbc, false));
            assertThatThrownBy(() -> manual.manualCredit(intentNo, "receipt-guard-" + suffix, request)).isInstanceOf(BizException.class);
            assertThat(financialSnapshot(jdbc)).as("uncertain receipt %s has zero effects", guard).isEqualTo(before);
        }
        for (String boundary : List.of("RECEIPT", "EVIDENCE", "OUTBOX", "AUDIT", "LOCAL_SETTLEMENT")) {
            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            String intentNo = "VQR-HP-" + suffix;
            String assetId = insertManualFixture(dataSource, template, intentNo, "P" + suffix, suffix);
            long receiptId = insertExistingReceipt(dataSource, template, intentNo, suffix);
            String target = switch (boundary) {
                case "RECEIPT" -> "UPDATE ON nx_vietqr_reconciliation";
                case "EVIDENCE" -> "UPDATE ON nx_vietqr_receipt_evidence";
                case "LOCAL_SETTLEMENT" -> "UPDATE ON nx_hdpay_payin_order";
                default -> null;
            };
            D1BankOrderService manual = manualService(template, tx, transactionalOutbox(jdbc, boundary.equals("OUTBOX")), transactionalAudit(jdbc, boundary.equals("AUDIT")));
            if (target != null) jdbc.execute("CREATE TRIGGER it_receipt_failure BEFORE " + target + " FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='forced borrowed-reserve failure'");
            try {
                String before = financialSnapshot(jdbc);
                assertThatThrownBy(() -> manual.manualCredit(intentNo, "receipt-rollback-" + suffix,
                        receiptRequest(jdbc, receiptId, assetId, 0L))).isInstanceOf(RuntimeException.class);
                assertThat(financialSnapshot(jdbc)).as("borrowed reserve rollback at %s", boundary).isEqualTo(before);
            } finally { if (target != null) jdbc.execute("DROP TRIGGER it_receipt_failure"); }
        }
    }

    private long insertExistingReceipt(DataSource dataSource, SqlSessionTemplate template, String intentNo, String suffix) throws Exception {
        long bank;
        try (Connection connection = dataSource.getConnection()) { bank = insertBankAccount(connection, "BR" + suffix.substring(0, 10), suffix); }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("UPDATE nx_vietqr_bank_account SET received_today_vnd=200000,received_business_date=DATE(DATE_ADD(UTC_TIMESTAMP(),INTERVAL 7 HOUR)) WHERE id=?", bank);
        String reconciliationNo = "BANK-HP-" + suffix;
        java.time.LocalDateTime receivedAt = java.time.LocalDateTime.now(ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        String oldAsset = "vqr_" + UUID.randomUUID().toString().replace("-", "");
        var evidence = template.getMapper(VietQrReceiptEvidenceMapper.class);
        assertThat(evidence.insertAvailableEvidence(oldAsset, "it/" + oldAsset + ".png", "VIETQR_RECEIPT", "image/png", 128, "b".repeat(64), "test-finance")).isOne();
        assertThat(evidence.bindAvailableEvidence(oldAsset, "VIETQR_RECEIPT", "VIETQR_RECONCILIATION", "REGISTERED:" + reconciliationNo, "test-finance")).isOne();
        assertThat(template.getMapper(VietnamPaymentMapper.class).insertVietQrReceipt(reconciliationNo, intentNo, 41L, bank,
                "MATCHED", new BigDecimal("200000"), new BigDecimal("200000"), new BigDecimal("20000"),
                "PAY-" + suffix, "REGISTERED media:" + oldAsset, receivedAt.plusMinutes(30), receivedAt, true)).isOne();
        return jdbc.queryForObject("SELECT id FROM nx_vietqr_reconciliation WHERE reconciliation_no=?", Long.class, reconciliationNo);
    }

    private HdPayManualCreditRequest receiptRequest(JdbcTemplate jdbc, long receiptId, String assetId, long providerVersion) {
        Map<String,Object> row = jdbc.queryForMap("SELECT payment_reference,received_at FROM nx_vietqr_reconciliation WHERE id=?", receiptId);
        Object receivedAt = row.get("received_at");
        java.time.LocalDateTime local = receivedAt instanceof java.sql.Timestamp stamp ? stamp.toLocalDateTime() : (java.time.LocalDateTime) receivedAt;
        return new HdPayManualCreditRequest(0L, providerVersion, new BigDecimal("200000"), String.valueOf(row.get("payment_reference")),
                local.atOffset(ZoneOffset.UTC), "media:" + assetId, "Independently verified existing receipt", "spoofed");
    }

    private void verifyManualRollback(DataSource dataSource, SqlSessionTemplate template,
                                      DataSourceTransactionManager tx) throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        for (String boundary : List.of("EVIDENCE", "CONFIRMATION", "WALLET", "LEDGER", "RESERVE",
                "INTENT", "NOTIFICATION", "OUTBOX", "AUDIT", "LOCAL_SETTLEMENT", "IDEMPOTENCY_SUCCESS")) {
            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            String intentNo = "VQR-HP-" + suffix;
            String assetId = insertManualFixture(dataSource, template, intentNo, "P" + suffix, suffix);
            D1BankOrderService manual = manualService(template, tx,
                    transactionalOutbox(jdbc, boundary.equals("OUTBOX")), transactionalAudit(jdbc, boundary.equals("AUDIT")));
            String target = switch (boundary) {
                case "EVIDENCE" -> "UPDATE ON nx_vietqr_receipt_evidence";
                case "CONFIRMATION" -> "INSERT ON nx_hdpay_manual_confirmation";
                case "WALLET" -> "UPDATE ON nx_user_wallet";
                case "LEDGER" -> "INSERT ON nx_wallet_ledger";
                case "RESERVE" -> "INSERT ON nx_treasury_reserve_ledger";
                case "INTENT" -> "UPDATE ON nx_vietqr_intent";
                case "NOTIFICATION" -> "INSERT ON nx_notification";
                case "LOCAL_SETTLEMENT" -> "UPDATE ON nx_hdpay_payin_order";
                default -> null;
            };
            if (boundary.equals("IDEMPOTENCY_SUCCESS")) jdbc.execute("""
                    CREATE TRIGGER it_manual_failure BEFORE UPDATE ON nx_admin_idempotency_record
                    FOR EACH ROW BEGIN
                      IF NEW.status='SUCCEEDED' THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='forced idempotency success failure';
                      END IF;
                    END
                    """);
            else if (target != null) jdbc.execute("CREATE TRIGGER it_manual_failure BEFORE " + target
                    + " FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='forced financial write failure'");
            try {
                String before = financialSnapshot(jdbc);
                assertThatThrownBy(() -> manual.manualCredit(intentNo, "rollback-" + suffix,
                        manualRequest(assetId, 0L, suffix))).isInstanceOf(RuntimeException.class);
                assertThat(financialSnapshot(jdbc)).as("all financial writes rollback at %s", boundary).isEqualTo(before);
                assertThat(text(jdbc, "SELECT status FROM nx_vietqr_receipt_evidence WHERE asset_id=?", assetId)).isEqualTo("AVAILABLE");
                assertThat(text(jdbc, "SELECT status FROM nx_admin_idempotency_record WHERE idempotency_key=?", "rollback-" + suffix)).isEqualTo("FAILED");
            } finally { if (target != null || boundary.equals("IDEMPOTENCY_SUCCESS")) jdbc.execute("DROP TRIGGER it_manual_failure"); }
        }
        for (String guard : List.of("VERSION", "AMOUNT", "EVIDENCE_BOUND", "EVIDENCE_PURPOSE", "RETURNED",
                "RETURN_PENDING", "COMMERCE", "MANUAL_RAIL", "PREEXISTING_LEDGER", "OPEN_BANK_RECEIPT", "PREDATES")) {
            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            String intentNo = "VQR-HP-" + suffix;
            String assetId = insertManualFixture(dataSource, template, intentNo, "P" + suffix, suffix);
            if (guard.equals("EVIDENCE_BOUND")) jdbc.update("UPDATE nx_vietqr_receipt_evidence SET status='BOUND',bound_resource_id='REGISTERED' WHERE asset_id=?", assetId);
            if (guard.equals("EVIDENCE_PURPOSE")) jdbc.update("UPDATE nx_vietqr_receipt_evidence SET purpose='OTHER' WHERE asset_id=?", assetId);
            if (List.of("RETURNED", "RETURN_PENDING").contains(guard)) jdbc.update("UPDATE nx_vietqr_intent SET status=? WHERE intent_no=?", guard, intentNo);
            if (guard.equals("COMMERCE")) jdbc.update("UPDATE nx_vietqr_intent SET settlement_target_type='COMMERCE_ORDER',target_order_no=? WHERE intent_no=?", "OLD-" + suffix, intentNo);
            if (guard.equals("MANUAL_RAIL")) jdbc.update("UPDATE nx_vietqr_intent SET payment_rail='MANUAL' WHERE intent_no=?", intentNo);
            if (guard.equals("PREEXISTING_LEDGER")) jdbc.update("INSERT INTO nx_wallet_ledger(biz_no,user_id,biz_type,asset,direction,amount,balance_after,status) VALUES(?,41,'VIETQR_DEPOSIT','USDT','IN',9,109,'SUCCESS')", intentNo);
            if (guard.equals("OPEN_BANK_RECEIPT")) jdbc.update("UPDATE nx_vietqr_reconciliation SET received_vnd=200000 WHERE intent_no=?", intentNo);
            HdPayManualCreditRequest normal = manualRequest(assetId, 0L, suffix);
            HdPayManualCreditRequest request = new HdPayManualCreditRequest(guard.equals("VERSION") ? 1L : 0L, 0L,
                    guard.equals("AMOUNT") ? new BigDecimal("199999") : normal.receivedVnd(), normal.paymentReference(),
                    guard.equals("PREDATES") ? normal.receivedAt().minusDays(1) : normal.receivedAt(),
                    normal.evidenceRef(), normal.reason(), "spoofed-operator");
            String before = financialSnapshot(jdbc);
            D1BankOrderService manual = manualService(template, tx, transactionalOutbox(jdbc, false), transactionalAudit(jdbc, false));
            assertThatThrownBy(() -> manual.manualCredit(intentNo, "guard-" + suffix, request)).isInstanceOf(BizException.class);
            assertThat(financialSnapshot(jdbc)).as("zero financial effects for %s", guard).isEqualTo(before);
        }
    }

    private void verifyOrderReadContract(DataSource dataSource, SqlSessionTemplate template,
                                          DataSourceTransactionManager tx) throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String group = "VQR-READ-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        List<String> states = List.of("AWAITING_PAYMENT", "RECEIPT_REVIEW", "CREDITED", "EXPIRED",
                "MISMATCH_REVIEW", "LATE_REVIEW", "CANCELLED", "RETURN_PENDING", "RETURNED",
                "CREATING", "UNKNOWN", "FAILED", "PROCESSING");
        for (String state : states) {
            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            String intentNo = group + "-" + state;
            insertManualFixture(dataSource, template, intentNo, "P" + suffix, suffix);
            if (List.of("CREATING", "UNKNOWN", "FAILED", "PROCESSING").contains(state)) {
                String submission = state.equals("CREATING") ? "PENDING" : state.equals("UNKNOWN") ? "SUBMIT_UNKNOWN" : state.equals("FAILED") ? "REJECTED" : "CREATED";
                jdbc.update("UPDATE nx_hdpay_payin_order SET submission_status=?,settlement_status=? WHERE merchant_order_id=?",
                        submission, state.equals("PROCESSING") ? "MANUAL_REVIEW" : "UNSETTLED", intentNo);
            } else jdbc.update("UPDATE nx_vietqr_intent SET status=? WHERE intent_no=?", state, intentNo);
            jdbc.update("UPDATE nx_hdpay_payin_order SET payment_url='https://api.hdpayadmin.com/pay?id=fixture' WHERE merchant_order_id=?", intentNo);
        }
        // Extra real receipts must not multiply the canonical order; closed APP rows must not hide it.
        jdbc.update("UPDATE nx_vietqr_reconciliation SET is_deleted=1 WHERE intent_no LIKE CONCAT(?,'%')", group);
        for (String receipt : List.of("FIRST", "SECOND")) jdbc.update("""
                INSERT INTO nx_vietqr_reconciliation(reconciliation_no,intent_no,user_id,view_type,status,
                  payable_vnd,received_vnd,locked_fx_rate_vnd_per_usdt,credited_usdt,version,is_deleted)
                VALUES(?,?,41,'MISMATCH','OPEN',200000,100000,20000,0,0,0)
                """, "BANK-" + receipt + "-" + group, group + "-RECEIPT_REVIEW");
        String manualSuffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String manualNo = group + "-MANUAL";
        long bank;
        try (Connection connection = dataSource.getConnection()) {
            bank = insertBankAccount(connection, "MR" + manualSuffix.substring(0, 10), manualSuffix);
        }
        insertManualFixture(dataSource, template, manualNo, "P" + manualSuffix, manualSuffix);
        jdbc.update("UPDATE nx_vietqr_intent SET payment_rail='MANUAL',bank_account_id=? WHERE intent_no=?", bank, manualNo);
        for (String excluded : List.of("SOFT_DELETED", "COMMERCE")) {
            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            String no = group + "-" + excluded;
            insertManualFixture(dataSource, template, no, "P" + suffix, suffix);
            if (excluded.equals("SOFT_DELETED")) jdbc.update("UPDATE nx_vietqr_intent SET is_deleted=1 WHERE intent_no=?", no);
            else jdbc.update("UPDATE nx_vietqr_intent SET settlement_target_type='COMMERCE_ORDER',target_order_no=? WHERE intent_no=?", "OLD-" + suffix, no);
        }
        D1BankOrderService service = manualService(template, tx, transactionalOutbox(jdbc, false), transactionalAudit(jdbc, false));
        String before = financialSnapshot(jdbc);
        List<String> seen = new java.util.ArrayList<>();
        for (int page = 1; page <= 5; page++) {
            Map<String, Object> read = service.list(null, null, group, page, 3).getData();
            assertThat((Long) read.get("total")).isEqualTo(14L);
            @SuppressWarnings("unchecked") List<Map<String, Object>> rows = (List<Map<String, Object>>) read.get("records");
            for (var row : rows) {
                seen.add((String) row.get("intentNo"));
                assertThat(row).containsEntry("receivedVnd", null).containsEntry("receivedAt", null);
                assertThat((BigDecimal) row.get("creditedUsdt")).isEqualByComparingTo("0");
                if (!row.get("status").equals("AWAITING_PAYMENT") || !row.get("paymentRail").equals("HDPAY")) assertThat(row.get("paymentUrl")).isNull();
            }
        }
        assertThat(seen).hasSize(14).doesNotHaveDuplicates();
        for (String state : states) {
            assertThat((Long) service.list("HDPAY", state, group, 1, 20).getData().get("total")).isOne();
        }
        assertThat((Long) service.list("MANUAL", "AWAITING_PAYMENT", group, 1, 20).getData().get("total")).isOne();
        assertThat(financialSnapshot(jdbc)).as("GET never expires, credits, closes or registers orders").isEqualTo(before);
        assertThatThrownBy(() -> service.list("CARD", null, group, 1, 20)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.list(null, "MADE_UP", group, 1, 20)).isInstanceOf(BizException.class);
    }

    private String insertManualFixture(DataSource dataSource, SqlSessionTemplate template,
                                       String intentNo, String providerId, String suffix) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            insertIntent(connection, intentNo, suffix, 41L, null);
            insertInFlightReconciliation(connection, intentNo, 41L, null);
            insertHdPayOrder(connection, intentNo, providerId, suffix);
        }
        String assetId = "vqr_" + UUID.randomUUID().toString().replace("-", "");
        assertThat(template.getMapper(VietQrReceiptEvidenceMapper.class).insertAvailableEvidence(assetId,
                "it/" + assetId + ".png", "VIETQR_RECEIPT", "image/png", 128, "a".repeat(64), "test-finance")).isOne();
        return assetId;
    }

    private HdPayManualCreditRequest manualRequest(String assetId, long providerVersion, String suffix) {
        return new HdPayManualCreditRequest(0L, providerVersion, new BigDecimal("200000"), "PAY-" + suffix,
                OffsetDateTime.now(ZoneOffset.UTC), "media:" + assetId, "Verified real receipt fixture", "spoofed-operator");
    }

    private D1BankOrderService manualService(SqlSessionTemplate template, DataSourceTransactionManager tx,
                                              EventOutboxService outbox, AuditLogService audit) {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        var idem = idempotencyService(template, tx);
        var evidence = transactionProxy(new VietQrReceiptEvidenceService(template.getMapper(VietQrReceiptEvidenceMapper.class),
                mock(ObjectStorageService.class), audit, idem, Clock.systemUTC()), VietQrReceiptEvidenceService.class, tx);
        HdPayProperties properties = new HdPayProperties();
        properties.setBaseUrl("https://api.hdpayadmin.com");
        return transactionProxy(new D1BankOrderService(template.getMapper(D1BankOrderMapper.class),
                template.getMapper(HdPayOrderMapper.class), template.getMapper(AppVietQrIntentMapper.class),
                template.getMapper(VietnamPaymentMapper.class), evidence,
                new MybatisTreasuryLedgerRepository(template.getMapper(TreasuryLedgerMapper.class), outbox),
                idem, outbox, audit, properties, json, Clock.systemUTC()), D1BankOrderService.class, tx);
    }

    private AdminIdempotencyService idempotencyService(SqlSessionTemplate template, DataSourceTransactionManager tx) {
        var records = template.getMapper(AdminIdempotencyRecordMapper.class);
        var expiry = transactionProxy(new AdminIdempotencyExpiryTransitionExecutor(records), AdminIdempotencyExpiryTransitionExecutor.class, tx);
        var executor = transactionProxy(new AdminIdempotencyTransactionExecutor(records,
                new ObjectMapper().findAndRegisterModules(), expiry), AdminIdempotencyTransactionExecutor.class, tx);
        return new AdminIdempotencyService(executor, Clock.systemUTC());
    }

    private <T> T transactionProxy(T target, Class<T> type, DataSourceTransactionManager tx) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(tx, new AnnotationTransactionAttributeSource()));
        return type.cast(factory.getProxy());
    }

    private EventOutboxService transactionalOutbox(JdbcTemplate jdbc, boolean fail) {
        EventOutboxService outbox = mock(EventOutboxService.class);
        when(outbox.publish(anyString(), anyString(), anyString(), any())).thenAnswer(call -> {
            jdbc.update("INSERT INTO it_outbox(intent_no,payload) VALUES(?,?)", call.getArgument(1),
                    new ObjectMapper().writeValueAsString(call.getArgument(3)));
            if (fail) throw new IllegalStateException("forced outbox failure");
            return UUID.randomUUID().toString();
        });
        return outbox;
    }

    private AuditLogService transactionalAudit(JdbcTemplate jdbc, boolean fail) {
        AuditLogService audit = mock(AuditLogService.class);
        org.mockito.Mockito.doAnswer(call -> {
            ffdd.opsconsole.shared.audit.AuditLogWriteRequest request = call.getArgument(0);
            jdbc.update("INSERT INTO it_audit(intent_no,action,actor) VALUES(?,?,?)",
                    request.getResourceId(), request.getAction(), request.getActorUsername());
            if (fail) throw new IllegalStateException("forced audit failure");
            return null;
        }).when(audit).recordRequired(any());
        return audit;
    }

    private String financialSnapshot(JdbcTemplate jdbc) throws Exception {
        Map<String, Object> all = new java.util.LinkedHashMap<>();
        for (String table : List.of("nx_user_wallet", "nx_wallet_ledger", "nx_treasury_reserve_ledger", "nx_vietqr_intent",
                "nx_hdpay_payin_order", "nx_hdpay_manual_confirmation", "nx_vietqr_reconciliation", "nx_vietqr_bank_account",
                "nx_vietqr_receipt_evidence", "nx_notification", "nx_hdpay_callback_inbox", "nx_hdpay_settlement_review", "it_outbox", "it_audit")) {
            all.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY 1"));
        }
        return new ObjectMapper().findAndRegisterModules().writeValueAsString(all);
    }

    private static void authenticate() {
        var auth = new TestingAuthenticationToken("77", "unused", "finance_d1_bank_reconcile", "finance_d1_read");
        auth.setDetails(Map.of("subjectType", "ADMIN", "username", "test-finance"));
        SecurityContextHolder.getContext().setAuthentication(auth);
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
                new MybatisTreasuryLedgerRepository(template.getMapper(TreasuryLedgerMapper.class), outbox),
                Clock.systemUTC());
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(
                txManager, new AnnotationTransactionAttributeSource()));
        return (HdPayCallbackSettlementService) factory.getProxy();
    }

    private void assertReserve(JdbcTemplate jdbc, String intentNo) {
        assertThat(count(jdbc, "SELECT COUNT(*) FROM nx_treasury_reserve_ledger WHERE voucher_no=?", intentNo)).isOne();
        assertThat(decimal(jdbc, "SELECT amount_usd FROM nx_treasury_reserve_ledger WHERE voucher_no=?", intentNo))
                .isEqualByComparingTo("10");
        assertThat(text(jdbc, "SELECT direction FROM nx_treasury_reserve_ledger WHERE voucher_no=?", intentNo)).isEqualTo("IN");
        assertThat(text(jdbc, "SELECT status FROM nx_treasury_reserve_ledger WHERE voucher_no=?", intentNo)).isEqualTo("CONFIRMED");
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
        assertThat(count(jdbc, "SELECT COUNT(*) FROM nx_treasury_reserve_ledger WHERE voucher_no=?", intentNo)).isZero();
        assertThat(count(jdbc,
                "SELECT COUNT(*) FROM nx_hdpay_callback_inbox WHERE merchant_order_id=?", intentNo)).isZero();
        assertThat(count(jdbc,
                "SELECT COUNT(*) FROM nx_notification WHERE biz_no=?", "HDPAY:" + intentNo)).isZero();
    }

    private static String isolatedUrl(String endpoint, String schema) {
        if (!"127.0.0.1:13306".equals(endpoint)) throw new IllegalArgumentException("isolated endpoint required");
        if (schema == null || (!schema.isEmpty() && !schema.matches(SCHEMA_PREFIX + "[a-f0-9]{32}"))) {
            throw new IllegalArgumentException("owned UUID schema required");
        }
        return "jdbc:mysql://" + endpoint + "/" + schema
                + "?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
    }

    private DataSource dataSource(String schema) {
        return new DriverManagerDataSource(isolatedUrl(System.getenv("NEXION_ISOLATED_MYSQL_ENDPOINT"), schema),
                "root", System.getenv().getOrDefault("NEXION_ISOLATED_MYSQL_PASSWORD", ""));
    }

    private void createFixtureSchema(DataSource dataSource) throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE nx_config_item(config_key VARCHAR(100),config_value TEXT,updated_at DATETIME,is_deleted TINYINT)");
        jdbc.execute("CREATE TABLE nx_user(id BIGINT PRIMARY KEY,status VARCHAR(32),language VARCHAR(16),is_deleted TINYINT)");
        jdbc.update("INSERT INTO nx_user VALUES(41,'ACTIVE','en',0)");
        String schemaSql = Files.readString(Path.of("scripts/schema.sql"));
        for (String table : new String[]{"nx_user_wallet", "nx_wallet_ledger", "nx_notification", "nx_treasury_reserve_ledger", "nx_admin_idempotency_record"}) {
            var ddl = Pattern.compile("(?s)CREATE TABLE IF NOT EXISTS " + Pattern.quote(table) + " \\(.*?;")
                    .matcher(schemaSql);
            assertThat(ddl.find()).as("canonical DDL for %s", table).isTrue();
            jdbc.execute(ddl.group());
        }
        jdbc.update("INSERT INTO nx_user_wallet(user_id,usdt_available,cumulative_deposit_usdt) VALUES(41,100,100)");
        for (String migration : new String[]{"20260725_vietnam_payment_real_tables.sql", "20260725_vietqr_intent_app.sql",
                "20260901_hdpay_hosted_payin.sql", "20260903_hdpay_commerce_direct_purchase.sql", "20260907_hdpay_optional_manual_bank.sql",
                "20260825_vietqr_receipt_evidence.sql", "20261004_hdpay_manual_confirmation.sql"}) {
            try (Connection connection = dataSource.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new FileSystemResource("scripts/migrations/" + migration));
            }
        }
        jdbc.execute("CREATE TABLE it_outbox(id BIGINT AUTO_INCREMENT PRIMARY KEY,intent_no VARCHAR(64),payload TEXT)");
        jdbc.execute("CREATE TABLE it_audit(id BIGINT AUTO_INCREMENT PRIMARY KEY,intent_no VARCHAR(64),action VARCHAR(96),actor VARCHAR(128))");
    }

    private SqlSessionFactory sessionFactory(DataSource dataSource) {
        MybatisConfiguration configuration = new MybatisConfiguration(new Environment(
                "hdpay-settlement-integration", new SpringManagedTransactionFactory(), dataSource));
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setDbConfig(new GlobalConfig.DbConfig());
        globalConfig.setMetaObjectHandler(new MybatisMetaObjectHandler(Clock.systemUTC()));
        GlobalConfigUtils.setGlobalConfig(configuration, globalConfig);
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(HdPayOrderMapper.class);
        configuration.addMapper(AppVietQrIntentMapper.class);
        configuration.addMapper(VietnamPaymentMapper.class);
        configuration.addMapper(TreasuryLedgerMapper.class);
        configuration.addMapper(D1BankOrderMapper.class);
        configuration.addMapper(VietQrReceiptEvidenceMapper.class);
        configuration.addMapper(AdminIdempotencyRecordMapper.class);
        SqlSessionFactory factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        assertThat(factory.getConfiguration().hasStatement(AdminIdempotencyRecordMapper.class.getName() + ".insert"))
                .as("real retained idempotency BaseMapper insert is available").isTrue();
        return factory;
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
            Connection connection, String intentNo, String suffix, long userId, Long bankAccountId)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO nx_vietqr_intent (
                  intent_no,user_id,create_idempotency_key,create_request_hash,
                  requested_usdt,payable_vnd,credited_usdt,received_vnd,
                  locked_fx_rate_vnd_per_usdt,fx_quote_version,bank_account_id,memo_code,
                  payment_rail,status,expires_at,version,created_at,updated_at,is_deleted)
                VALUES (?,?,?, ?,10,200000,0,NULL,20000,1,?,?,'HDPAY','AWAITING_PAYMENT',
                        DATE_ADD(NOW(),INTERVAL 30 MINUTE),0,NOW(),NOW(),0)
                """)) {
            statement.setString(1, intentNo);
            statement.setLong(2, userId);
            statement.setString(3, "hdpay-create-" + suffix);
            statement.setString(4, sha256("hdpay-request-" + suffix));
            statement.setObject(5, bankAccountId, java.sql.Types.BIGINT);
            statement.setString(6, "NXHP" + suffix.substring(0, 8).toUpperCase());
            assertThat(statement.executeUpdate()).isOne();
        }
    }

    private void insertInFlightReconciliation(
            Connection connection, String intentNo, long userId, Long bankAccountId) throws Exception {
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
            statement.setObject(4, bankAccountId, java.sql.Types.BIGINT);
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

    private BigDecimal decimal(JdbcTemplate jdbc, String sql, Object... arguments) {
        return jdbc.queryForObject(sql, BigDecimal.class, arguments);
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
