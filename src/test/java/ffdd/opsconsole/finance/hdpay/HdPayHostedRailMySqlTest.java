package ffdd.opsconsole.finance.hdpay;

import ffdd.opsconsole.content.facade.SupportPaymentAttributionFacade;
import ffdd.opsconsole.content.facade.SupportPaymentAttributionFacade.Prepared;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.finance.application.AppVietQrIntentService;
import ffdd.opsconsole.finance.application.FinanceSensitiveDataCipher;
import ffdd.opsconsole.finance.application.OpsVietnamPaymentService;
import ffdd.opsconsole.finance.application.VietQrReceiptEvidenceService;
import ffdd.opsconsole.finance.dto.VietQrReceiptRegistrationRequest;
import ffdd.opsconsole.finance.mapper.AppVietQrIntentMapper;
import ffdd.opsconsole.finance.mapper.VietnamPaymentMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
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
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** Production SQL and transaction boundaries, with a fake gateway and a disposable UUID schema only. */
class HdPayHostedRailMySqlTest {
    private static final String PREFIX = "nexion_hosted_rail_it_";
    private static final String MIGRATION = "20260907_hdpay_optional_manual_bank.sql";

    @Test
    void connectionGuardRejectsBusinessEndpointsAndUnownedSchemas() {
        String schema = PREFIX + "a".repeat(32);
        assertThat(url("127.0.0.1:13306", schema)).contains(":13306/" + schema + "?");
        for (String endpoint : new String[]{null, "", "localhost:13306", "127.0.0.1:3306", "127.0.0.1:13306/other"}) {
            assertThatThrownBy(() -> url(endpoint, schema)).isInstanceOf(IllegalArgumentException.class);
        }
        for (String invalid : new String[]{null, "nexion", "mysql", PREFIX + "a", schema + "`"}) {
            assertThatThrownBy(() -> url("127.0.0.1:13306", invalid)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "NEXION_HOSTED_RAIL_IT", matches = "true")
    void manualOnlyMigrationIsRepeatableWithoutAnyProviderTablesAndPreservesHistory() throws Exception {
        inSchema(f -> {
            f.base();
            f.legacyIntent("VQR-MANUAL001", "manual-key");
            Map<String, Object> before = f.jdbc.queryForMap("SELECT requested_usdt,payable_vnd,bank_account_id,status FROM nx_vietqr_intent");
            assertThat(f.orders.countNullableIntentBankAccountColumn()).isZero();
            f.migrate(MIGRATION); f.migrate(MIGRATION);
            assertThat(f.orders.countNullableIntentBankAccountColumn()).isEqualTo(1);
            assertThat(f.orders.countIntentPaymentRailColumn()).isEqualTo(1);
            assertThat(f.jdbc.queryForMap("SELECT requested_usdt,payable_vnd,bank_account_id,status FROM nx_vietqr_intent")).isEqualTo(before);
            assertThat(f.jdbc.queryForObject("SELECT payment_rail FROM nx_vietqr_intent", String.class)).isEqualTo("MANUAL");
            assertThat(f.orders.countRequiredSchemaTables()).isZero();
            assertThat(f.intents.findIntentByCreateKey(41L, "manual-key")).containsEntry("paymentRail", "MANUAL");
        });
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "NEXION_HOSTED_RAIL_IT", matches = "true")
    void newHostedOrderUsesNoBankAndCrossRailRetriesCannotSubmitOrCancelIt() throws Exception {
        inSchema(f -> {
            f.base(); f.providerSchema(); f.migrate(MIGRATION);
            var properties = properties();
            new HdPaySchemaReadiness(properties, f.orders).verify();
            AppVietQrIntentService canonical = f.service();
            HdPayGateway gateway = mock(HdPayGateway.class);
            when(gateway.createPayOrder(any())).thenReturn(new HdPayGateway.PayPage("https://api.hdpayadmin.com/pay?id=isolated"));
            HdPayHostedDepositService hosted = new HdPayHostedDepositService(canonical, properties, gateway, f.orders,
                    mock(AuditLogService.class), new ObjectMapper());
            Map<?, ?> config = (Map<?, ?>) hosted.paymentConfig().getData().get("vietQr");
            assertThat(config.get("enabled")).isEqualTo(true);
            assertThat(config.get("dailyCapacityKnown")).isEqualTo(false);
            var first = hosted.create(41L, "hosted-key", new BigDecimal("25"), "127.0.0.1").getData();
            var repeated = hosted.create(41L, "hosted-key", new BigDecimal("25"), "127.0.0.1").getData();
            String intentNo = (String) first.get("intentNo");
            assertThat(repeated).containsEntry("intentNo", intentNo).containsEntry("providerStatus", "created");
            assertThat(first).doesNotContainKeys("bankAccount", "memoCode", "qrPayload");
            assertThat(f.jdbc.queryForObject("SELECT bank_account_id FROM nx_vietqr_intent", Long.class)).isNull();
            assertThat(f.jdbc.queryForObject("SELECT payment_rail FROM nx_vietqr_intent", String.class)).isEqualTo("HDPAY");
            assertThat(f.count("nx_vietqr_intent")).isEqualTo(1);
            assertThat(f.count("nx_hdpay_payin_order")).isEqualTo(1);
            assertThat(f.count("nx_vietqr_reconciliation")).isEqualTo(1);
            assertThat(f.intents.findIntentForUser(42L, intentNo)).isNull();
            assertThatThrownBy(() -> canonical.cancel(41L, intentNo, "cancel-key", 0L))
                    .hasMessage("HDPAY_PROVIDER_ORDER_NOT_CANCELLABLE");
            assertThat(f.jdbc.queryForObject("SELECT status FROM nx_vietqr_intent", String.class)).isEqualTo("AWAITING_PAYMENT");
            properties.setMode(HdPayProperties.Mode.DISABLED);
            assertThatThrownBy(() -> hosted.create(41L, "hosted-key", new BigDecimal("25"), "127.0.0.1"))
                    .hasMessage("VIETQR_PAYMENT_RAIL_CONFLICT");
            assertThat(hosted.get(41L, intentNo).getData()).containsEntry("paymentMode", "hosted")
                    .doesNotContainKeys("bankAccount", "memoCode", "paymentUrl");
            assertThatThrownBy(() -> hosted.cancel(41L, intentNo, "cancel-key", 0L))
                    .hasMessage("HDPAY_PROVIDER_ORDER_NOT_CANCELLABLE");
            f.bank();
            var manual = hosted.create(41L, "manual-key", new BigDecimal("25"), "127.0.0.1").getData();
            assertThat(manual).containsKeys("bankAccount", "memoCode");
            properties.setMode(HdPayProperties.Mode.PROVIDER);
            assertThatThrownBy(() -> hosted.create(41L, "manual-key", new BigDecimal("25"), "127.0.0.1"))
                    .hasMessage("VIETQR_PAYMENT_RAIL_CONFLICT");
            assertThat(hosted.get(41L, (String) manual.get("intentNo")).getData()).containsKeys("bankAccount", "memoCode");
            assertThat(f.count("nx_vietqr_intent")).isEqualTo(2);
            assertThat(f.count("nx_hdpay_payin_order")).isEqualTo(1);
            verify(gateway, times(1)).createPayOrder(any());
            verifyNoMoreInteractions(gateway);
        });
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "NEXION_HOSTED_RAIL_IT", matches = "true")
    void historicalProviderBackfillResumesTheExistingPageWithoutRepurposingManualOrders() throws Exception {
        inSchema(f -> {
            f.base(); f.providerSchema(); f.bank();
            f.legacyIntent("VQR-HOSTED001", "hosted-key");
            f.legacyIntent("VQR-MANUAL001", "manual-key");
            assertThat(f.orders.insertPending("VQR-HOSTED001", new BigDecimal("659750"), "a".repeat(64))).isEqualTo(1);
            // Historic provider fixture, before the new rail column exists.
            assertThat(f.jdbc.update("UPDATE nx_hdpay_payin_order SET submission_status='SUBMIT_UNKNOWN' WHERE merchant_order_id='VQR-HOSTED001'")).isEqualTo(1);
            assertThat(f.orders.markCreated("VQR-HOSTED001", "https://api.hdpayadmin.com/pay?id=history")).isEqualTo(1);
            f.migrate(MIGRATION); f.migrate(MIGRATION);
            assertThat(f.intents.findIntentByCreateKey(41L, "hosted-key"))
                    .containsEntry("paymentRail", "HDPAY").containsEntry("bankAccountId", 8L);
            assertThat(f.intents.findIntentByCreateKey(41L, "manual-key")).containsEntry("paymentRail", "MANUAL");
            HdPayGateway gateway = mock(HdPayGateway.class);
            var hosted = new HdPayHostedDepositService(f.service(), properties(), gateway, f.orders,
                    mock(AuditLogService.class), new ObjectMapper());
            assertThat(hosted.create(41L, "hosted-key", new BigDecimal("25"), "127.0.0.1").getData())
                    .containsEntry("paymentUrl", "https://api.hdpayadmin.com/pay?id=history")
                    .doesNotContainKeys("bankAccount", "memoCode");
            assertThatThrownBy(() -> hosted.create(41L, "manual-key", new BigDecimal("25"), "127.0.0.1"))
                    .hasMessage("VIETQR_PAYMENT_RAIL_CONFLICT");
            assertThat(f.intents.findIntentByMemoForUpdate("NX-hosted-key"))
                    .containsEntry("paymentRail", "HDPAY").containsEntry("intentNo", "VQR-HOSTED001");
            assertThat(f.intents.findIntentByMemoForUpdate("NX-manual-key")).containsEntry("paymentRail", "MANUAL");
            assertThat(f.intents.findIntentByMemoForUpdate("NX-UNKNOWN")).isNull();
            assertThat(f.intents.findIntentByMemoForUpdate("")).isNull();
            assertThat(f.intents.sumActiveReservedVnd(8L)).isEqualByComparingTo("659750");
            assertThat(f.intents.findMaxAvailableBankCapacityVnd()).isEqualByComparingTo("9340250");
            assertThat(f.intents.cancelAwaitingIntentsForFusedAccount(8L, null)).isEqualTo(1);
            assertThat(f.intents.cancelActiveIntentsForBankAccount(8L)).isZero();
            assertThat(f.intents.findIntentByCreateKey(41L, "hosted-key")).containsEntry("status", "AWAITING_PAYMENT");
            assertThat(f.count("nx_hdpay_payin_order")).isEqualTo(1);
            verifyNoInteractions(gateway, f.cipher);
        });
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "NEXION_HOSTED_RAIL_IT", matches = "true")
    void knownHdPayMemoIsRejectedBeforeAnyBankSideEffectWhileManualAndUnknownMemosStillRegister() throws Exception {
        inSchema(f -> {
            f.base(); f.providerSchema(); f.bank();
            f.legacyIntent("VQR-HOSTED001", "hosted-key");
            f.legacyIntent("VQR-MANUAL001", "manual-key");
            assertThat(f.orders.insertPending("VQR-HOSTED001", new BigDecimal("659750"), "a".repeat(64))).isEqualTo(1);
            f.migrate(MIGRATION);
            f.jdbc.update("UPDATE nx_vietqr_intent SET memo_code=UPPER(memo_code)");
            f.jdbc.execute("CREATE TABLE nx_user_wallet (user_id BIGINT PRIMARY KEY,usdt_available DECIMAL(24,6),cumulative_deposit_usdt DECIMAL(24,6),version BIGINT,updated_at DATETIME,is_deleted TINYINT)");
            f.jdbc.update("INSERT INTO nx_user_wallet VALUES (41,17,19,0,NOW(),0)");
            f.jdbc.execute("CREATE TABLE nx_wallet_ledger (id BIGINT AUTO_INCREMENT PRIMARY KEY,biz_no VARCHAR(96) UNIQUE,user_id BIGINT,biz_type VARCHAR(32),asset VARCHAR(16),direction VARCHAR(8),amount DECIMAL(24,6),balance_after DECIMAL(24,6),status VARCHAR(24),remark VARCHAR(255),created_at DATETIME,updated_at DATETIME,is_deleted TINYINT)");
            assertThat(f.intents.ensureInFlightReconciliation("VQR-HOSTED001")).isEqualTo(1);
            assertThat(f.intents.ensureInFlightReconciliation("VQR-MANUAL001")).isEqualTo(1);
            var receiptsBefore = f.jdbc.queryForList("SELECT * FROM nx_vietqr_reconciliation ORDER BY id");
            var bankBefore = f.jdbc.queryForMap("SELECT * FROM nx_vietqr_bank_account WHERE id=8");
            var walletBefore = f.jdbc.queryForMap("SELECT * FROM nx_user_wallet WHERE user_id=41");
            var providerBefore = f.jdbc.queryForList("SELECT * FROM nx_hdpay_payin_order ORDER BY id");
            BigDecimal pendingBefore = f.bankMapper.sumPendingUnverifiedDepositUsdt();
            var service = f.registrationService();
            var receivedAt = OffsetDateTime.now(ZoneOffset.UTC);
            var hostedReceipt = new VietQrReceiptRegistrationRequest(8L, "BANK-HOSTED-REF", "NX-HOSTED-KEY",
                    new BigDecimal("659750"), receivedAt, null, "reject hosted bank registration", "integration-admin");
            for (int deleted = 0; deleted <= 1; deleted++) {
                assertThat(f.jdbc.update("UPDATE nx_vietqr_intent SET is_deleted=? WHERE intent_no='VQR-HOSTED001'", deleted))
                        .isEqualTo(1);
                assertThat(f.intents.findIntentByMemoForUpdate("NX-HOSTED-KEY"))
                        .containsEntry("paymentRail", "HDPAY").containsEntry("intentNo", "VQR-HOSTED001");
                var intentBefore = f.jdbc.queryForList("SELECT * FROM nx_vietqr_intent ORDER BY id");
                for (int attempt = 0; attempt < 2; attempt++) {
                    assertThatThrownBy(() -> service.registerVietQrReceipt("reject-hosted-memo", hostedReceipt))
                            .hasMessage("VIETQR_PAYMENT_RAIL_CONFLICT");
                }
                assertThat(f.jdbc.queryForList("SELECT * FROM nx_vietqr_intent ORDER BY id")).isEqualTo(intentBefore);
                assertThat(f.jdbc.queryForList("SELECT * FROM nx_vietqr_reconciliation ORDER BY id")).isEqualTo(receiptsBefore);
                assertThat(f.jdbc.queryForMap("SELECT * FROM nx_vietqr_bank_account WHERE id=8")).isEqualTo(bankBefore);
                assertThat(f.jdbc.queryForMap("SELECT * FROM nx_user_wallet WHERE user_id=41")).isEqualTo(walletBefore);
                assertThat(f.jdbc.queryForList("SELECT * FROM nx_hdpay_payin_order ORDER BY id")).isEqualTo(providerBefore);
                assertThat(f.bankMapper.sumPendingUnverifiedDepositUsdt()).isEqualByComparingTo(pendingBefore);
                assertThat(f.count("nx_wallet_ledger")).isZero();
                verifyNoInteractions(f.audit, f.outbox, f.receiptEvidence);
            }
            assertThat(f.jdbc.update("UPDATE nx_vietqr_intent SET is_deleted=0 WHERE intent_no='VQR-HOSTED001'"))
                    .isEqualTo(1);
            assertThat(f.jdbc.update("UPDATE nx_vietqr_intent SET is_deleted=1 WHERE intent_no='VQR-MANUAL001'"))
                    .isEqualTo(1);
            assertThat(f.intents.findIntentByMemoForUpdate("NX-MANUAL-KEY")).isNull();
            assertThat(f.jdbc.update("UPDATE nx_vietqr_intent SET is_deleted=0 WHERE intent_no='VQR-MANUAL001'"))
                    .isEqualTo(1);

            assertThat(service.registerVietQrReceipt("register-manual-memo",
                    new VietQrReceiptRegistrationRequest(8L, "BANK-MANUAL-REF", "NX-MANUAL-KEY",
                            new BigDecimal("659750"), receivedAt, null, "register known manual bank receipt", "integration-admin"))
                    .getData()).containsEntry("viewType", "MATCHED").containsEntry("intentNo", "VQR-MANUAL001");
            assertThat(service.registerVietQrReceipt("register-unknown-memo",
                    new VietQrReceiptRegistrationRequest(8L, "BANK-UNKNOWN-REF", "NX-UNKNOWN",
                            new BigDecimal("10000"), receivedAt, null, "register unmatched real bank receipt", "integration-admin"))
                    .getData()).containsEntry("viewType", "ORPHAN");
            assertThat(f.jdbc.queryForMap("SELECT intent_no,user_id FROM nx_vietqr_reconciliation WHERE payment_reference='BANK-UNKNOWN-REF'"))
                    .containsEntry("intent_no", null).containsEntry("user_id", null);
            assertThat(f.intents.findIntentForUpdate("VQR-MANUAL001")).containsEntry("status", "RECEIPT_REVIEW");
            assertThat(f.intents.findIntentForUpdate("VQR-HOSTED001")).containsEntry("status", "AWAITING_PAYMENT");
            assertThat(f.jdbc.queryForObject("SELECT received_today_vnd FROM nx_vietqr_bank_account WHERE id=8", BigDecimal.class))
                    .isEqualByComparingTo("669750");
            assertThat(f.bankMapper.sumPendingUnverifiedDepositUsdt()).isGreaterThan(pendingBefore);
            assertThat(f.jdbc.queryForMap("SELECT * FROM nx_user_wallet WHERE user_id=41")).isEqualTo(walletBefore);
            assertThat(f.count("nx_wallet_ledger")).isZero();
            assertThat(f.jdbc.queryForList("SELECT * FROM nx_hdpay_payin_order ORDER BY id")).isEqualTo(providerBefore);
            verify(f.audit, times(2)).recordRequired(any());
            verifyNoInteractions(f.outbox, f.receiptEvidence);
        });
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "NEXION_HOSTED_RAIL_IT", matches = "true")
    void rejectedOrderRecoveryUsesVersionAndCallbackGuardsWithoutChangingCanonicalExpiry() throws Exception {
        inSchema(f -> {
            f.base(); f.providerSchema(); f.bank();
            f.legacyIntent("VQR-RETRY", "retry-key");
            assertThat(f.orders.insertPending("VQR-RETRY", new BigDecimal("659750"), "a".repeat(64))).isEqualTo(1);
            f.migrate(MIGRATION);
            assertThat(f.orders.authorizeSubmissionIfIntentPayable("VQR-RETRY")).isEqualTo(1);
            assertThat(f.orders.markRejected("VQR-RETRY", "HDPAY_CREATE_REJECTED")).isEqualTo(1);
            Map<String, Object> before = f.orders.findByMerchantOrderId("VQR-RETRY");
            long version = ((Number) before.get("version")).longValue();
            var canonical = f.jdbc.queryForMap("SELECT status,expires_at,payable_vnd FROM nx_vietqr_intent WHERE intent_no='VQR-RETRY'");
            String page = "https://api.hdpayadmin.com/pay?id=retry";

            assertThat(f.orders.resolveRejectedByQuery("VQR-RETRY", version - 1, "HDPAY_CREATE_REJECTED",
                    new BigDecimal("659750"), "P-RETRY", 1, page)).isZero();
            assertThat(f.orders.resolveRejectedByQuery("VQR-RETRY", version, "HDPAY_HTTP_500",
                    new BigDecimal("659750"), "P-RETRY", 1, page)).isZero();
            assertThat(f.orders.resolveRejectedByQuery("VQR-RETRY", version, "HDPAY_CREATE_REJECTED",
                    new BigDecimal("659751"), "P-RETRY", 1, page)).isZero();
            assertThat(f.orders.resolveRejectedByQuery("VQR-RETRY", version, "HDPAY_CREATE_REJECTED",
                    new BigDecimal("659750"), "P-RETRY", 3, page)).isZero();
            assertThat(f.orders.findByMerchantOrderId("VQR-RETRY")).isEqualTo(before);

            assertThat(f.orders.updateCallbackObservation("VQR-RETRY", "P-RETRY", 3)).isEqualTo(1);
            long observedVersion = ((Number) f.orders.findByMerchantOrderId("VQR-RETRY").get("version")).longValue();
            assertThat(f.orders.resolveRejectedByQuery("VQR-RETRY", version, "HDPAY_CREATE_REJECTED",
                    new BigDecimal("659750"), "P-RETRY", 1, page)).isZero();
            assertThat(f.orders.resolveRejectedByQuery("VQR-RETRY", observedVersion, "HDPAY_CREATE_REJECTED",
                    new BigDecimal("659750"), "P-RETRY", 1, page)).isZero();
            assertThat(f.orders.findByMerchantOrderId("VQR-RETRY"))
                    .containsEntry("providerStatus", 3).containsEntry("submissionStatus", "REJECTED");

            f.legacyIntent("VQR-RETRY-OK", "retry-ok-key");
            f.jdbc.update("UPDATE nx_vietqr_intent SET payment_rail='HDPAY',bank_account_id=NULL WHERE intent_no='VQR-RETRY-OK'");
            assertThat(f.orders.insertPending("VQR-RETRY-OK", new BigDecimal("659750"), "b".repeat(64))).isEqualTo(1);
            assertThat(f.orders.authorizeSubmissionIfIntentPayable("VQR-RETRY-OK")).isEqualTo(1);
            assertThat(f.orders.markRejected("VQR-RETRY-OK", "HDPAY_CREATE_REJECTED")).isEqualTo(1);
            var originalExpiry = f.jdbc.queryForObject("SELECT expires_at FROM nx_vietqr_intent WHERE intent_no='VQR-RETRY-OK'", java.sql.Timestamp.class);
            HdPayGateway gateway = mock(HdPayGateway.class);
            when(gateway.queryPayOrder("VQR-RETRY-OK")).thenReturn(new HdPayGateway.PayOrder(
                    "VQR-RETRY-OK", "P-OK", 1, new BigDecimal("659750"), "BANKQR", page));
            HdPayHostedDepositService hosted = new HdPayHostedDepositService(f.service(), properties(), gateway, f.orders,
                    mock(AuditLogService.class), new ObjectMapper());

            assertThat(hosted.create(41L, "retry-ok-key", new BigDecimal("25"), "203.0.113.9").getData())
                    .containsEntry("intentNo", "VQR-RETRY-OK").containsEntry("providerStatus", "created")
                    .containsEntry("paymentUrl", page);
            assertThat(f.orders.findByMerchantOrderId("VQR-RETRY-OK"))
                    .containsEntry("submissionStatus", "CREATED").containsEntry("providerOrderId", "P-OK")
                    .containsEntry("providerStatus", 1).containsEntry("settlementStatus", "UNSETTLED");
            assertThat(f.jdbc.queryForObject("SELECT expires_at FROM nx_vietqr_intent WHERE intent_no='VQR-RETRY-OK'", java.sql.Timestamp.class))
                    .isEqualTo(originalExpiry);
            assertThat(f.jdbc.queryForMap("SELECT status,expires_at,payable_vnd FROM nx_vietqr_intent WHERE intent_no='VQR-RETRY'"))
                    .isEqualTo(canonical);
            assertThat(f.count("nx_hdpay_payin_order")).isEqualTo(2);
            verify(gateway).queryPayOrder("VQR-RETRY-OK");
            verify(gateway, never()).createPayOrder(any());
        });
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "NEXION_HOSTED_RAIL_IT", matches = "true")
    void explicitRejectionAndLegacyRecoveryCannotOverwriteCallbackSettlementOrExpiredIntents() throws Exception {
        inSchema(f -> {
            f.base(); f.providerSchema(); f.bank();
            f.legacyIntent("VQR-GUARD", "guard-key");
            assertThat(f.orders.insertPending("VQR-GUARD", new BigDecimal("659750"), "c".repeat(64))).isEqualTo(1);
            f.migrate(MIGRATION);
            assertThat(f.orders.authorizeSubmissionIfIntentPayable("VQR-GUARD")).isEqualTo(1);
            assertThat(f.orders.updateCallbackObservation("VQR-GUARD", "P-GUARD", 3)).isEqualTo(1);
            assertThat(f.orders.markRejected("VQR-GUARD", "HDPAY_CREATE_EXPLICIT_REJECTED")).isZero();
            assertThat(f.orders.findByMerchantOrderId("VQR-GUARD"))
                    .containsEntry("submissionStatus", "SUBMIT_UNKNOWN").containsEntry("providerStatus", 3);

            for (String settlement : java.util.List.of("CREDITED", "MANUAL_REVIEW")) {
                f.jdbc.update("UPDATE nx_hdpay_payin_order SET provider_order_id=NULL,provider_status=NULL,settlement_status=? WHERE merchant_order_id='VQR-GUARD'", settlement);
                assertThat(f.orders.markRejected("VQR-GUARD", "HDPAY_CREATE_EXPLICIT_REJECTED")).isZero();
            }
            f.jdbc.update("UPDATE nx_hdpay_payin_order SET settlement_status='UNSETTLED' WHERE merchant_order_id='VQR-GUARD'");
            assertThat(f.orders.markRejected("VQR-GUARD", "HDPAY_CREATE_EXPLICIT_REJECTED")).isEqualTo(1);
            long version = ((Number) f.orders.findByMerchantOrderId("VQR-GUARD").get("version")).longValue();
            String page = "https://api.hdpayadmin.com/pay?id=guard";
            assertThat(f.orders.resolveRejectedByQuery("VQR-GUARD", version, "HDPAY_CREATE_EXPLICIT_REJECTED",
                    new BigDecimal("659750"), "P-GUARD", 1, page)).isZero();

            f.jdbc.update("UPDATE nx_hdpay_payin_order SET last_error_code='HDPAY_CREATE_REJECTED' WHERE merchant_order_id='VQR-GUARD'");
            for (String settlement : java.util.List.of("CREDITED", "MANUAL_REVIEW")) {
                f.jdbc.update("UPDATE nx_hdpay_payin_order SET settlement_status=? WHERE merchant_order_id='VQR-GUARD'", settlement);
                assertThat(f.orders.resolveRejectedByQuery("VQR-GUARD", version, "HDPAY_CREATE_REJECTED",
                        new BigDecimal("659750"), "P-GUARD", 1, page)).isZero();
            }
            f.jdbc.update("UPDATE nx_hdpay_payin_order SET settlement_status='UNSETTLED',provider_order_id='P-OTHER' WHERE merchant_order_id='VQR-GUARD'");
            assertThat(f.orders.resolveRejectedByQuery("VQR-GUARD", version, "HDPAY_CREATE_REJECTED",
                    new BigDecimal("659750"), "P-GUARD", 1, page)).isZero();
            f.jdbc.update("UPDATE nx_hdpay_payin_order SET provider_order_id=NULL WHERE merchant_order_id='VQR-GUARD'");
            f.jdbc.update("UPDATE nx_vietqr_intent SET expires_at=UTC_TIMESTAMP()-INTERVAL 1 SECOND WHERE intent_no='VQR-GUARD'");
            assertThat(f.orders.resolveRejectedByQuery("VQR-GUARD", version, "HDPAY_CREATE_REJECTED",
                    new BigDecimal("659750"), "P-GUARD", 1, page)).isZero();
            f.jdbc.update("UPDATE nx_vietqr_intent SET expires_at=UTC_TIMESTAMP()+INTERVAL 1 DAY,status='CREDITED' WHERE intent_no='VQR-GUARD'");
            assertThat(f.orders.resolveRejectedByQuery("VQR-GUARD", version, "HDPAY_CREATE_REJECTED",
                    new BigDecimal("659750"), "P-GUARD", 1, page)).isZero();
            assertThat(f.orders.findByMerchantOrderId("VQR-GUARD"))
                    .containsEntry("submissionStatus", "REJECTED").containsEntry("lastErrorCode", "HDPAY_CREATE_REJECTED")
                    .containsEntry("settlementStatus", "UNSETTLED");
        });
    }

    private static class Fixture {
        final DriverManagerDataSource dataSource;
        final JdbcTemplate jdbc;
        final AppVietQrIntentMapper intents;
        final VietnamPaymentMapper bankMapper;
        final HdPayOrderMapper orders;
        final FinanceSensitiveDataCipher cipher = mock(FinanceSensitiveDataCipher.class);
        final AuditLogService audit = mock(AuditLogService.class);
        final EventOutboxService outbox = mock(EventOutboxService.class);
        final VietQrReceiptEvidenceService receiptEvidence = mock(VietQrReceiptEvidenceService.class);
        Fixture(String schema) {
            dataSource = dataSource(schema); jdbc = new JdbcTemplate(dataSource);
            Configuration c = new Configuration(new Environment("isolated-hosted-rail", new SpringManagedTransactionFactory(), dataSource));
            c.addMapper(AppVietQrIntentMapper.class); c.addMapper(HdPayOrderMapper.class); c.addMapper(VietnamPaymentMapper.class);
            SqlSessionTemplate session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(c));
            intents = session.getMapper(AppVietQrIntentMapper.class); orders = session.getMapper(HdPayOrderMapper.class);
            bankMapper = session.getMapper(VietnamPaymentMapper.class);
            assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(schema);
        }
        void base() throws Exception {
            jdbc.execute("CREATE TABLE nx_config_item(config_key VARCHAR(100),config_value TEXT,updated_at DATETIME,is_deleted TINYINT)");
            jdbc.execute("CREATE TABLE nx_user(id BIGINT PRIMARY KEY,status VARCHAR(32),is_deleted TINYINT)");
            jdbc.update("INSERT INTO nx_user VALUES(41,'ACTIVE',0),(42,'ACTIVE',0)");
            migrate("20260725_vietnam_payment_real_tables.sql");
            migrate("20260725_vietqr_intent_app.sql");
            migrate("20260903_hdpay_commerce_direct_purchase.sql");
            migrate("20261004_hdpay_manual_confirmation.sql");
        }
        void providerSchema() throws Exception { migrate("20260901_hdpay_hosted_payin.sql"); }
        void migrate(String name) throws Exception {
            try (Connection c = dataSource.getConnection()) {
                ScriptUtils.executeSqlScript(c, new FileSystemResource("scripts/migrations/" + name));
            }
        }
        void bank() {
            jdbc.update("INSERT INTO nx_vietqr_bank_account(id,bank_code,bank_name,account_holder,account_number_encrypted,account_number_hash,account_number_last4,daily_cap_vnd) VALUES(8,'TEST','Isolated Bank','Fixture','cipher',?, '7890',10000000)", "a".repeat(64));
        }
        void legacyIntent(String no, String key) throws Exception {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("25.00".getBytes(StandardCharsets.UTF_8)));
            jdbc.update("INSERT INTO nx_vietqr_intent(intent_no,user_id,create_idempotency_key,create_request_hash,requested_usdt,payable_vnd,locked_fx_rate_vnd_per_usdt,fx_quote_version,bank_account_id,memo_code,expires_at) VALUES(?,41,?,?,25,659750,26390,0,8,?,UTC_TIMESTAMP()+INTERVAL 1 DAY)", no, key, hash, "NX-" + key);
        }
        AppVietQrIntentService service() {
            PlatformConfigFacade config = mock(PlatformConfigFacade.class);
            when(config.activeValue("finance.topup.channel.vietqr.enabled")).thenReturn(Optional.of("true"));
            when(cipher.decrypt(any(), any())).thenReturn("ISOLATED-ACCOUNT");
            MockEnvironment env = new MockEnvironment(); env.setActiveProfiles("prod");
            var target = new AppVietQrIntentService(intents, cipher, Clock.systemUTC(), env, config);
            ProxyFactory proxy = new ProxyFactory(target);
            proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource), new AnnotationTransactionAttributeSource()));
            return (AppVietQrIntentService) proxy.getProxy();
        }
        OpsVietnamPaymentService registrationService() {
            AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
            when(idempotency.execute(any(), any(), any(), any(), any()))
                    .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get());
            var target = new OpsVietnamPaymentService(paymentAttribution(bankMapper), bankMapper, audit, idempotency, cipher,
                    intents, outbox, receiptEvidence, Clock.systemUTC());
            ProxyFactory proxy = new ProxyFactory(target);
            proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource), new AnnotationTransactionAttributeSource()));
            return (OpsVietnamPaymentService) proxy.getProxy();
        }
        int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    }

    private static HdPayProperties properties() {
        HdPayProperties p = new HdPayProperties(); p.setMode(HdPayProperties.Mode.PROVIDER);
        p.setBaseUrl("https://api.hdpayadmin.com/api/order"); p.setCallbackBaseUrl("https://payments.example.com");
        p.setCallbackHosts(java.util.List.of("payments.example.com")); p.setMerchantId("1234567890123456789");
        p.setMd5Key("0123456789abcdef0123456789abcdef"); return p;
    }
    private static String url(String endpoint, String schema) {
        if (!"127.0.0.1:13306".equals(endpoint)) throw new IllegalArgumentException("isolated endpoint required");
        if (schema == null || (!schema.isEmpty() && !schema.matches(PREFIX + "[a-f0-9]{32}"))) throw new IllegalArgumentException("owned UUID schema required");
        return "jdbc:mysql://" + endpoint + "/" + schema + "?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
    }
    private static DriverManagerDataSource dataSource(String schema) {
        return new DriverManagerDataSource(url(System.getenv("NEXION_ISOLATED_MYSQL_ENDPOINT"), schema), "root",
                System.getenv().getOrDefault("NEXION_ISOLATED_MYSQL_PASSWORD", ""));
    }
    private static void inSchema(SchemaTest test) throws Exception {
        String schema = PREFIX + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate admin = new JdbcTemplate(dataSource(""));
        assertThat(admin.queryForObject("SELECT @@port", Integer.class)).isEqualTo(13306);
        admin.execute("CREATE DATABASE " + schema);
        try { test.run(new Fixture(schema)); } finally { admin.execute("DROP DATABASE " + schema); }
    }
    @FunctionalInterface private interface SchemaTest { void run(Fixture fixture) throws Exception; }

    private static SupportPaymentAttributionFacade paymentAttribution(ffdd.opsconsole.finance.mapper.VietnamPaymentMapper mapper) {
        // This older fixture mocks attribution only; financial writes remain real in its own database.
        // SupportPaymentCaptureMySqlIntegrationTest separately verifies the actual opaque receipt path.
        var capture=org.mockito.Mockito.mock(SupportPaymentAttributionFacade.class);
        var contexts=new java.util.IdentityHashMap<Prepared,Object[]>();
        org.mockito.stubbing.Answer<Prepared> prepare=invocation -> {
            Prepared token=org.mockito.Mockito.mock(Prepared.class);
            contexts.put(token,new Object[]{invocation.getArgument(0),invocation.getArgument(2)});
            return token;
        };
        org.mockito.Mockito.when(capture.prepare(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString())).thenAnswer(prepare);
        org.mockito.Mockito.when(capture.prepare(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenAnswer(prepare);
        org.mockito.Mockito.when(capture.insertLedger(org.mockito.ArgumentMatchers.any(Prepared.class),
                org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> {
                    Object[] identity=java.util.Objects.requireNonNull(contexts.get(invocation.getArgument(0)));
                    return mapper.insertVietQrWalletLedger((String)identity[1],(Long)identity[0],invocation.getArgument(1),invocation.getArgument(2),invocation.getArgument(3));
                });
        return capture;
    }
}
