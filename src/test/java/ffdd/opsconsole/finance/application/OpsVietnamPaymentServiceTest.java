package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.finance.dto.FxQuoteUpdateRequest;
import ffdd.opsconsole.finance.dto.VietQrReconciliationCommandRequest;
import ffdd.opsconsole.finance.dto.VietQrReceiptRegistrationRequest;
import ffdd.opsconsole.finance.mapper.AppVietQrIntentMapper;
import ffdd.opsconsole.finance.mapper.VietnamPaymentMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpsVietnamPaymentServiceTest {
    private final VietnamPaymentMapper mapper = mock(VietnamPaymentMapper.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final FinanceSensitiveDataCipher sensitiveDataCipher = mock(FinanceSensitiveDataCipher.class);
    private final AppVietQrIntentMapper appIntentMapper = mock(AppVietQrIntentMapper.class);
    private final EventOutboxService outbox = mock(EventOutboxService.class);
    private final OpsVietnamPaymentService service = new OpsVietnamPaymentService(
            mapper, audit, idempotency, sensitiveDataCipher, appIntentMapper, outbox,
            Clock.fixed(Instant.parse("2026-07-25T00:00:00Z"), ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        when(idempotency.execute(anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get());
        when(mapper.findVietQrConfig()).thenReturn(vietQrConfig(new BigDecimal("5000")));
        when(mapper.findVietQrBankAccountForUpdate(anyLong())).thenReturn(Map.of(
                "id", 8L, "dailyCapVnd", new BigDecimal("100000000"),
                "receivedTodayVnd", BigDecimal.ZERO, "version", 0L));
    }

    @Test
    void vietQrOverviewAcceptsAnEmptyRealTableWithoutManufacturingRows() {
        when(mapper.listVietQrBankAccounts()).thenReturn(List.of());
        when(mapper.countVietQrReconciliations("INFLIGHT")).thenReturn(0L);
        when(mapper.listVietQrReconciliations("INFLIGHT", 20, 0)).thenReturn(List.of());
        when(mapper.sumPendingUnverifiedDepositUsdt()).thenReturn(BigDecimal.ZERO);

        ApiResult<Map<String, Object>> result = service.vietQrOverview("inflight", 1, 20);

        assertThat(result.getData()).containsEntry("view", "inflight")
                .containsEntry("pendingUnverifiedDepositUsdt", new BigDecimal("0.00"));
        assertThat(result.getData().get("accounts")).asList().isEmpty();
        assertThat(result.getData().get("page")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("total", 0L);
        verify(appIntentMapper).expireAllIntents();
        verify(appIntentMapper).closeAllInactiveInFlightReconciliations();
    }

    @Test
    void quoteIsDerivedWithIntegerDomainHalfUpRoundingToTenVnd() {
        assertThat(VietnamPaymentPolicy.quoteRate(new BigDecimal("26000"), new BigDecimal("1.5")))
                .isEqualByComparingTo("26390");
        assertThat(VietnamPaymentPolicy.quoteRate(new BigDecimal("27000"), new BigDecimal("1.5")))
                .isEqualByComparingTo("27410");
    }

    @Test
    void fxWriteRejectsOutOfRangeSpreadBeforeAnyDatabaseWrite() {
        FxQuoteUpdateRequest request = new FxQuoteUpdateRequest(
                new BigDecimal("26000"), new BigDecimal("3.01"), 30, 0L,
                "weekly market calibration", "finance-admin");

        assertThatThrownBy(() -> service.updateFxQuote("fx-1", request))
                .isInstanceOf(BizException.class)
                .hasMessage("FX_SPREAD_OUT_OF_RANGE");
    }

    @Test
    void fxWriteUsesIdempotencyCasAndRequiredAudit() {
        when(mapper.findFxQuoteConfig()).thenReturn(
                Map.of(
                        "configCode", "VND_USDT",
                        "baseRateVndPerUsdt", new BigDecimal("26000"),
                        "buySpreadPct", new BigDecimal("1.5"),
                        "lockWindowMinutes", 30,
                        "version", 0L),
                Map.of(
                        "configCode", "VND_USDT",
                        "baseRateVndPerUsdt", new BigDecimal("25900"),
                        "buySpreadPct", new BigDecimal("1.5"),
                        "lockWindowMinutes", 30,
                        "version", 1L));
        when(mapper.updateFxQuoteConfig(
                new BigDecimal("25900"), new BigDecimal("1.50"), 30, 0L,
                "finance-admin", "weekly market calibration")).thenReturn(1);
        when(mapper.insertFxQuoteHistory(
                any(), any(), any(), any(), any(), any(), anyString(), anyString(), anyString())).thenReturn(1);
        ApiResult<Map<String, Object>> result = service.updateFxQuote(
                "fx-2",
                new FxQuoteUpdateRequest(new BigDecimal("25900"), new BigDecimal("1.5"), 30, 0L,
                        "weekly market calibration", "finance-admin"));

        assertThat(result.getData()).containsEntry("version", 1L);
        verify(mapper).updateFxQuoteConfig(
                new BigDecimal("25900"), new BigDecimal("1.50"), 30, 0L,
                "finance-admin", "weekly market calibration");
        verify(audit).recordRequired(any());
        verify(outbox).publish(eq("FX_QUOTE_CONFIG"), eq("VND_USDT"),
                eq("admin.fx_quote_updated"), any());
    }

    @Test
    void fxWriteRejectsNoopBeforeHistoryAuditOrOutbox() {
        when(mapper.findFxQuoteConfig()).thenReturn(Map.of(
                "configCode", "VND_USDT",
                "baseRateVndPerUsdt", new BigDecimal("26000"),
                "buySpreadPct", new BigDecimal("1.50"),
                "lockWindowMinutes", 30,
                "version", 8L));

        assertThatThrownBy(() -> service.updateFxQuote(
                "fx-noop",
                new FxQuoteUpdateRequest(new BigDecimal("26000"), new BigDecimal("1.5"), 30, 8L,
                        "noop must not create a false change", "finance-admin")))
                .isInstanceOf(BizException.class)
                .hasMessage("FX_QUOTE_NO_CHANGES");

        verify(mapper, never()).updateFxQuoteConfig(
                any(), any(), any(), any(), anyString(), anyString());
        verify(mapper, never()).insertFxQuoteHistory(
                any(), any(), any(), any(), any(), any(), anyString(), anyString(), anyString());
        verify(audit, never()).recordRequired(any());
        verify(outbox, never()).publish(anyString(), anyString(), anyString(), any());
    }

    @Test
    void manualMatchUsesCanonicalIntentOwnerAndTransitionsIntentInSameTransaction() {
        when(mapper.findVietQrReconciliationForUpdate(12L)).thenReturn(Map.of(
                "reconciliationNo", "REC-12",
                "intentNo", "",
                "bankAccountId", 8L,
                "viewType", "ORPHAN",
                "status", "OPEN",
                "receivedVnd", new BigDecimal("659750"),
                "receivedAt", LocalDateTime.of(2026, 7, 25, 0, 20),
                "paymentReference", "BANK-REC-12",
                "lockedFxRateVndPerUsdt", new BigDecimal("26390"),
                "version", 0L));
        when(appIntentMapper.findIntentForUpdate("VQR-CANONICAL")).thenReturn(Map.ofEntries(
                Map.entry("intentNo", "VQR-CANONICAL"),
                Map.entry("userId", 41L),
                Map.entry("status", "AWAITING_PAYMENT"),
                Map.entry("payableVnd", new BigDecimal("659750")),
                Map.entry("bankAccountId", 8L),
                Map.entry("lockedFxRateVndPerUsdt", new BigDecimal("26390")),
                Map.entry("createdAt", LocalDateTime.of(2026, 7, 25, 0, 20)),
                Map.entry("expiresAt", LocalDateTime.of(2026, 7, 25, 0, 30)),
                Map.entry("version", 3L)));
        when(mapper.findUsdtWalletForUpdate(41L)).thenReturn(Map.of(
                "usdtAvailable", new BigDecimal("100"), "version", 5L));
        when(mapper.findVietQrBankAccountForUpdate(8L)).thenReturn(Map.of(
                "id", 8L, "dailyCapVnd", new BigDecimal("100000000"),
                "receivedTodayVnd", BigDecimal.ZERO, "version", 0L));
        when(mapper.creditUsdtWallet(41L, new BigDecimal("25.000000"), 5L)).thenReturn(1);
        when(mapper.insertVietQrWalletLedger(
                "D1-VIETQR-REC-12", 41L, new BigDecimal("25.000000"),
                new BigDecimal("125.000000"),
                "VietQR settlement REC-12")).thenReturn(1);
        when(appIntentMapper.transitionIntent(
                "VQR-CANONICAL", 3L, "AWAITING_PAYMENT", "CREDITED",
                new BigDecimal("659750"), new BigDecimal("25.000000"),
                LocalDateTime.of(2026, 7, 25, 0, 0))).thenReturn(1);
        when(mapper.completeVietQrReconciliation(
                12L, 0L, "CREDITED", "MATCHED", 41L, "VQR-CANONICAL",
                new BigDecimal("25.000000"), "manual bank receipt match")).thenReturn(1);

        ApiResult<Map<String, Object>> result = service.reconcile(
                12L, "match-credit", "reconcile-12",
                new VietQrReconciliationCommandRequest(
                        0L, 41L, "VQR-CANONICAL",
                        "EVIDENCE-12",
                        "manual bank receipt match", "finance-admin"));

        assertThat(result.getData())
                .containsEntry("status", "CREDITED")
                .containsEntry("creditedUsdt", new BigDecimal("25.000000"));
        verify(appIntentMapper).transitionIntent(
                "VQR-CANONICAL", 3L, "AWAITING_PAYMENT", "CREDITED",
                new BigDecimal("659750"), new BigDecimal("25.000000"),
                LocalDateTime.of(2026, 7, 25, 0, 0));
        verify(appIntentMapper).closeInFlightReconciliation("VQR-CANONICAL", "CREDITED");
    }

    @Test
    void loweringTheCurrentLimitDoesNotBlockAnAlreadyRegisteredMatchedReceipt() {
        when(mapper.findVietQrConfig()).thenReturn(vietQrConfig(new BigDecimal("100")));
        when(mapper.findVietQrReconciliationForUpdate(18L)).thenReturn(Map.ofEntries(
                Map.entry("reconciliationNo", "REC-18"),
                Map.entry("intentNo", "VQR-LOCKED-LIMIT"),
                Map.entry("userId", 41L),
                Map.entry("bankAccountId", 8L),
                Map.entry("viewType", "MATCHED"),
                Map.entry("status", "OPEN"),
                Map.entry("receivedVnd", new BigDecimal("3298750")),
                Map.entry("receivedAt", LocalDateTime.of(2026, 7, 25, 0, 20)),
                Map.entry("paymentReference", "BANK-REC-18"),
                Map.entry("intentTransitionRequired", true),
                Map.entry("version", 0L)));
        when(appIntentMapper.findIntentForUpdate("VQR-LOCKED-LIMIT")).thenReturn(Map.ofEntries(
                Map.entry("intentNo", "VQR-LOCKED-LIMIT"),
                Map.entry("userId", 41L),
                Map.entry("status", "RECEIPT_REVIEW"),
                Map.entry("requestedUsdt", new BigDecimal("125.00")),
                Map.entry("payableVnd", new BigDecimal("3298750")),
                Map.entry("bankAccountId", 8L),
                Map.entry("lockedFxRateVndPerUsdt", new BigDecimal("26390")),
                Map.entry("createdAt", LocalDateTime.of(2026, 7, 25, 0, 10)),
                Map.entry("expiresAt", LocalDateTime.of(2026, 7, 25, 0, 30)),
                Map.entry("version", 1L)));
        when(mapper.findUsdtWalletForUpdate(41L)).thenReturn(Map.of(
                "usdtAvailable", new BigDecimal("100"), "version", 5L));
        when(mapper.creditUsdtWallet(
                41L, new BigDecimal("125.000000"), 5L)).thenReturn(1);
        when(mapper.insertVietQrWalletLedger(
                "D1-VIETQR-REC-18", 41L, new BigDecimal("125.000000"),
                new BigDecimal("225.000000"),
                "VietQR settlement REC-18")).thenReturn(1);
        when(appIntentMapper.transitionIntent(
                "VQR-LOCKED-LIMIT", 1L, "RECEIPT_REVIEW", "CREDITED",
                new BigDecimal("3298750"), new BigDecimal("125.000000"),
                LocalDateTime.of(2026, 7, 25, 0, 0))).thenReturn(1);
        when(mapper.completeVietQrReconciliation(
                18L, 0L, "CREDITED", "MATCHED", 41L, "VQR-LOCKED-LIMIT",
                new BigDecimal("125.000000"), "honor registered receipt snapshot"))
                .thenReturn(1);

        assertThat(service.reconcile(
                18L, "match-credit", "reconcile-18",
                new VietQrReconciliationCommandRequest(
                        0L, null, "VQR-LOCKED-LIMIT", "EVIDENCE-18",
                        "honor registered receipt snapshot", "finance-admin"))
                .getData())
                .containsEntry("creditedUsdt", new BigDecimal("125.000000"));
    }

    @Test
    void manualMatchRejectsOperatorSuppliedUserThatDiffersFromCanonicalIntent() {
        when(mapper.findVietQrReconciliationForUpdate(13L)).thenReturn(Map.of(
                "reconciliationNo", "REC-13",
                "intentNo", "",
                "bankAccountId", 8L,
                "viewType", "ORPHAN",
                "status", "OPEN",
                "receivedVnd", new BigDecimal("659750"),
                "receivedAt", LocalDateTime.of(2026, 7, 25, 0, 20),
                "paymentReference", "BANK-REC-13",
                "lockedFxRateVndPerUsdt", new BigDecimal("26390"),
                "version", 0L));
        when(appIntentMapper.findIntentForUpdate("VQR-OWNER")).thenReturn(Map.ofEntries(
                Map.entry("intentNo", "VQR-OWNER"),
                Map.entry("userId", 41L),
                Map.entry("status", "AWAITING_PAYMENT"),
                Map.entry("payableVnd", new BigDecimal("659750")),
                Map.entry("bankAccountId", 8L),
                Map.entry("lockedFxRateVndPerUsdt", new BigDecimal("26390")),
                Map.entry("createdAt", LocalDateTime.of(2026, 7, 25, 0, 10)),
                Map.entry("expiresAt", LocalDateTime.of(2026, 7, 25, 0, 30)),
                Map.entry("version", 0L)));

        assertThatThrownBy(() -> service.reconcile(
                13L, "match-credit", "reconcile-13",
                new VietQrReconciliationCommandRequest(
                        0L, 99L, "VQR-OWNER",
                        "EVIDENCE-13",
                        "manual bank receipt match", "finance-admin")))
                .isInstanceOf(BizException.class)
                .hasMessage("VIETQR_INTENT_USER_MISMATCH");
        verify(mapper, never()).creditUsdtWallet(anyLong(), any(), anyLong());
    }

    @Test
    void manualMatchRejectsAmountBeyondToleranceAndWrongBankAccount() {
        when(mapper.findVietQrReconciliationForUpdate(14L)).thenReturn(Map.ofEntries(
                Map.entry("reconciliationNo", "REC-14"),
                Map.entry("intentNo", ""),
                Map.entry("bankAccountId", 8L),
                Map.entry("viewType", "ORPHAN"),
                Map.entry("status", "OPEN"),
                Map.entry("receivedVnd", new BigDecimal("1000000000")),
                Map.entry("receivedAt", LocalDateTime.of(2026, 7, 25, 0, 20)),
                Map.entry("paymentReference", "BANK-REC-14"),
                Map.entry("lockedFxRateVndPerUsdt", new BigDecimal("26390")),
                Map.entry("version", 0L)));
        when(appIntentMapper.findIntentForUpdate("VQR-TARGET")).thenReturn(Map.ofEntries(
                Map.entry("intentNo", "VQR-TARGET"),
                Map.entry("userId", 41L),
                Map.entry("status", "AWAITING_PAYMENT"),
                Map.entry("payableVnd", new BigDecimal("659750")),
                Map.entry("bankAccountId", 8L),
                Map.entry("lockedFxRateVndPerUsdt", new BigDecimal("26390")),
                Map.entry("createdAt", LocalDateTime.of(2026, 7, 25, 0, 10)),
                Map.entry("expiresAt", LocalDateTime.of(2026, 7, 25, 0, 30)),
                Map.entry("version", 0L)));

        assertThatThrownBy(() -> service.reconcile(
                14L, "match-credit", "reconcile-14",
                new VietQrReconciliationCommandRequest(
                        0L, null, "VQR-TARGET", "EVIDENCE-14",
                        "reject giant overpayment", "finance-admin")))
                .isInstanceOf(BizException.class)
                .hasMessage("VIETQR_INTENT_AMOUNT_MISMATCH");

        when(mapper.findVietQrReconciliationForUpdate(14L)).thenReturn(Map.ofEntries(
                Map.entry("reconciliationNo", "REC-14"),
                Map.entry("intentNo", ""),
                Map.entry("bankAccountId", 9L),
                Map.entry("viewType", "ORPHAN"),
                Map.entry("status", "OPEN"),
                Map.entry("receivedVnd", new BigDecimal("659750")),
                Map.entry("receivedAt", LocalDateTime.of(2026, 7, 25, 0, 20)),
                Map.entry("paymentReference", "BANK-REC-14"),
                Map.entry("lockedFxRateVndPerUsdt", new BigDecimal("26390")),
                Map.entry("version", 0L)));

        assertThatThrownBy(() -> service.reconcile(
                14L, "match-credit", "reconcile-14b",
                new VietQrReconciliationCommandRequest(
                        0L, null, "VQR-TARGET", "EVIDENCE-14B",
                        "reject wrong bank receipt", "finance-admin")))
                .isInstanceOf(BizException.class)
                .hasMessage("VIETQR_INTENT_BANK_ACCOUNT_MISMATCH");
        verify(mapper, never()).creditUsdtWallet(anyLong(), any(), anyLong());
    }

    @Test
    void orphanReceiptCannotBeAssignedToAnIntentCreatedAfterTheMoneyArrived() {
        when(mapper.findVietQrReconciliationForUpdate(15L)).thenReturn(Map.ofEntries(
                Map.entry("reconciliationNo", "REC-15"),
                Map.entry("intentNo", ""),
                Map.entry("bankAccountId", 8L),
                Map.entry("viewType", "ORPHAN"),
                Map.entry("status", "OPEN"),
                Map.entry("receivedVnd", new BigDecimal("659750")),
                Map.entry("receivedAt", LocalDateTime.of(2026, 7, 25, 0, 20)),
                Map.entry("paymentReference", "BANK-REC-15"),
                Map.entry("lockedFxRateVndPerUsdt", new BigDecimal("26390")),
                Map.entry("version", 0L)));
        when(appIntentMapper.findIntentForUpdate("VQR-FUTURE")).thenReturn(Map.ofEntries(
                Map.entry("intentNo", "VQR-FUTURE"),
                Map.entry("userId", 41L),
                Map.entry("status", "AWAITING_PAYMENT"),
                Map.entry("payableVnd", new BigDecimal("659750")),
                Map.entry("bankAccountId", 8L),
                Map.entry("lockedFxRateVndPerUsdt", new BigDecimal("26390")),
                Map.entry("createdAt", LocalDateTime.of(2026, 7, 25, 0, 20, 1)),
                Map.entry("expiresAt", LocalDateTime.of(2026, 7, 25, 0, 53)),
                Map.entry("version", 0L)));

        assertThatThrownBy(() -> service.reconcile(
                15L, "match-credit", "reconcile-15",
                new VietQrReconciliationCommandRequest(
                        0L, null, "VQR-FUTURE", "EVIDENCE-15",
                        "reject retroactive orphan assignment", "finance-admin")))
                .isInstanceOf(BizException.class)
                .hasMessage("VIETQR_RECEIPT_PREDATES_INTENT");
        verify(mapper, never()).findUsdtWalletForUpdate(anyLong());
    }

    @Test
    void unboundOrphanReturnRejectsAnySuppliedTargetWithoutTouchingAnIntent() {
        when(mapper.findVietQrReconciliationForUpdate(28L)).thenReturn(Map.ofEntries(
                Map.entry("reconciliationNo", "REC-28"),
                Map.entry("intentNo", ""),
                Map.entry("bankAccountId", 8L),
                Map.entry("viewType", "ORPHAN"),
                Map.entry("status", "OPEN"),
                Map.entry("receivedVnd", new BigDecimal("659750")),
                Map.entry("receivedAt", LocalDateTime.of(2026, 7, 25, 0, 20)),
                Map.entry("paymentReference", "BANK-REC-28"),
                Map.entry("version", 0L)));

        assertThatThrownBy(() -> service.reconcile(
                28L, "return", "reconcile-28-intent",
                new VietQrReconciliationCommandRequest(
                        0L, null, "VQR-UNRELATED", "EVIDENCE-28A",
                        "reject poisoned orphan target", "finance-admin")))
                .isInstanceOf(BizException.class)
                .hasMessage("VIETQR_ORPHAN_RETURN_TARGET_NOT_ALLOWED");

        assertThatThrownBy(() -> service.reconcile(
                28L, "return", "reconcile-28-user",
                new VietQrReconciliationCommandRequest(
                        0L, 41L, null, "EVIDENCE-28B",
                        "reject poisoned orphan owner", "finance-admin")))
                .isInstanceOf(BizException.class)
                .hasMessage("VIETQR_ORPHAN_RETURN_TARGET_NOT_ALLOWED");

        verify(appIntentMapper, never()).findIntentForUpdate(anyString());
        verify(appIntentMapper, never()).transitionIntent(
                anyString(), anyLong(), anyString(), anyString(), any(), any(), any());
        verify(mapper, never()).completeVietQrReconciliation(
                anyLong(), anyLong(), anyString(), anyString(), any(), any(), any(), anyString());
    }

    @Test
    void unboundOrphanReturnCompletesWithoutInventingAnOwnerOrIntent() {
        when(mapper.findVietQrReconciliationForUpdate(29L)).thenReturn(Map.ofEntries(
                Map.entry("reconciliationNo", "REC-29"),
                Map.entry("intentNo", ""),
                Map.entry("bankAccountId", 8L),
                Map.entry("viewType", "ORPHAN"),
                Map.entry("status", "OPEN"),
                Map.entry("receivedVnd", new BigDecimal("659750")),
                Map.entry("receivedAt", LocalDateTime.of(2026, 7, 25, 0, 20)),
                Map.entry("paymentReference", "BANK-REC-29"),
                Map.entry("version", 0L)));
        when(mapper.completeVietQrReconciliation(
                29L, 0L, "RETURNED", "ORPHAN", null, null,
                new BigDecimal("0.000000"), "return unbound orphan receipt"))
                .thenReturn(1);

        ApiResult<Map<String, Object>> result = service.reconcile(
                29L, "return", "reconcile-29",
                new VietQrReconciliationCommandRequest(
                        0L, null, null, "EVIDENCE-29",
                        "return unbound orphan receipt", "finance-admin"));

        assertThat(result.getData())
                .containsEntry("status", "RETURNED")
                .containsEntry("viewType", "ORPHAN");
        verify(appIntentMapper, never()).findIntentForUpdate(anyString());
        verify(appIntentMapper, never()).transitionIntent(
                anyString(), anyLong(), anyString(), anyString(), any(), any(), any());
        verify(mapper).completeVietQrReconciliation(
                29L, 0L, "RETURNED", "ORPHAN", null, null,
                new BigDecimal("0.000000"), "return unbound orphan receipt");
    }

    @Test
    void lateReceiptIsReturnOnlyAndCannotReuseAnExpiredLockedRate() {
        when(mapper.findVietQrReconciliationForUpdate(16L)).thenReturn(Map.ofEntries(
                Map.entry("reconciliationNo", "REC-16"),
                Map.entry("intentNo", "VQR-OLD"),
                Map.entry("userId", 41L),
                Map.entry("bankAccountId", 8L),
                Map.entry("viewType", "LATE"),
                Map.entry("status", "OPEN"),
                Map.entry("receivedVnd", new BigDecimal("659750")),
                Map.entry("receivedAt", LocalDateTime.of(2026, 7, 25, 0, 40)),
                Map.entry("paymentReference", "BANK-REC-16"),
                Map.entry("intentTransitionRequired", false),
                Map.entry("version", 0L)));

        assertThatThrownBy(() -> service.reconcile(
                16L, "match-credit", "reconcile-16",
                new VietQrReconciliationCommandRequest(
                        0L, null, "VQR-OLD", "EVIDENCE-16",
                        "reject stale locked rate reuse", "finance-admin")))
                .isInstanceOf(BizException.class)
                .hasMessage("VIETQR_MATCH_CREDIT_NOT_ALLOWED");
        verify(appIntentMapper, never()).findIntentForUpdate(anyString());
        verify(mapper, never()).findUsdtWalletForUpdate(anyLong());
    }

    @Test
    void mismatchWriteOffCannotCreditMoreThanTheConfiguredPerTransactionLimit() {
        when(mapper.findVietQrReconciliationForUpdate(17L)).thenReturn(Map.ofEntries(
                Map.entry("reconciliationNo", "REC-17"),
                Map.entry("intentNo", "VQR-LIMIT"),
                Map.entry("userId", 41L),
                Map.entry("bankAccountId", 8L),
                Map.entry("viewType", "MISMATCH"),
                Map.entry("status", "OPEN"),
                Map.entry("receivedVnd", new BigDecimal("200000000")),
                Map.entry("receivedAt", LocalDateTime.of(2026, 7, 25, 0, 20)),
                Map.entry("paymentReference", "BANK-REC-17"),
                Map.entry("intentTransitionRequired", true),
                Map.entry("version", 0L)));
        when(appIntentMapper.findIntentForUpdate("VQR-LIMIT")).thenReturn(Map.ofEntries(
                Map.entry("intentNo", "VQR-LIMIT"),
                Map.entry("userId", 41L),
                Map.entry("status", "MISMATCH_REVIEW"),
                Map.entry("requestedUsdt", new BigDecimal("25.00")),
                Map.entry("payableVnd", new BigDecimal("659750")),
                Map.entry("bankAccountId", 8L),
                Map.entry("lockedFxRateVndPerUsdt", new BigDecimal("26390")),
                Map.entry("createdAt", LocalDateTime.of(2026, 7, 25, 0, 10)),
                Map.entry("expiresAt", LocalDateTime.of(2026, 7, 25, 0, 30)),
                Map.entry("version", 1L)));

        assertThatThrownBy(() -> service.reconcile(
                17L, "write-off", "reconcile-17",
                new VietQrReconciliationCommandRequest(
                        0L, null, "VQR-LIMIT", "EVIDENCE-17",
                        "reject over-limit writeoff", "finance-admin")))
                .isInstanceOf(BizException.class)
                .hasMessage("VIETQR_RECEIPT_CREDIT_LIMIT_EXCEEDED");
        verify(mapper, never()).findUsdtWalletForUpdate(anyLong());
    }

    @Test
    void reconciliationRequiresImmutablePaymentAndOperatorEvidence() {
        assertThatThrownBy(() -> service.reconcile(
                15L, "return", "reconcile-15",
                new VietQrReconciliationCommandRequest(
                        0L, null, null, "", "return bank transfer", "finance-admin")))
                .isInstanceOf(BizException.class)
                .hasMessage("VIETQR_EVIDENCE_REFERENCE_INVALID");
        verify(mapper, never()).findVietQrReconciliationForUpdate(anyLong());
    }

    @Test
    void receiptRegistrationClassifiesExactMemoAndClosesOtherIntentsWhenTheAccountFuses() {
        LocalDateTime receivedAt = LocalDateTime.of(2026, 7, 24, 23, 59);
        when(mapper.findVietQrBankAccountForUpdate(8L)).thenReturn(
                Map.of(
                        "id", 8L, "dailyCapVnd", new BigDecimal("100000000"),
                        "receivedTodayVnd", BigDecimal.ZERO, "status", "ACTIVE", "version", 0L),
                Map.of(
                        "id", 8L, "dailyCapVnd", new BigDecimal("100000000"),
                        "receivedTodayVnd", new BigDecimal("659750"),
                        "status", "FUSED", "version", 1L));
        when(appIntentMapper.findIntentByMemoForUpdate("NX-EXACT")).thenReturn(Map.ofEntries(
                Map.entry("intentNo", "VQR-EXACT"),
                Map.entry("userId", 41L),
                Map.entry("status", "AWAITING_PAYMENT"),
                Map.entry("payableVnd", new BigDecimal("659750")),
                Map.entry("bankAccountId", 8L),
                Map.entry("lockedFxRateVndPerUsdt", new BigDecimal("26390")),
                Map.entry("createdAt", LocalDateTime.of(2026, 7, 24, 23, 30)),
                Map.entry("expiresAt", LocalDateTime.of(2026, 7, 25, 0, 30)),
                Map.entry("version", 0L)));
        when(mapper.insertVietQrReceipt(
                anyString(), eq("VQR-EXACT"), eq(41L), eq(8L), eq("MATCHED"),
                eq(new BigDecimal("659750")), eq(new BigDecimal("659750")),
                eq(new BigDecimal("26390")), eq("BANK-EXACT-1"), anyString(),
                eq(LocalDateTime.of(2026, 7, 25, 0, 30)), eq(receivedAt), eq(true)))
                .thenReturn(1);
        when(mapper.addVietQrBankReceivedToday(
                8L, new BigDecimal("659750"), bankDate(receivedAt))).thenReturn(1);
        when(appIntentMapper.transitionIntent(
                "VQR-EXACT", 0L, "AWAITING_PAYMENT", "RECEIPT_REVIEW",
                new BigDecimal("659750"), new BigDecimal("0.000000"), receivedAt))
                .thenReturn(1);
        when(mapper.findVietQrReceiptByPaymentReference("BANK-EXACT-1")).thenReturn(Map.of(
                "id", 19L,
                "reconciliationNo", "VQR-REC-EXACT",
                "intentNo", "VQR-EXACT",
                "viewType", "MATCHED",
                "paymentReference", "BANK-EXACT-1",
                "version", 0L));

        ApiResult<Map<String, Object>> result = service.registerVietQrReceipt(
                "receipt-key-1",
                new VietQrReceiptRegistrationRequest(
                        8L, "BANK-EXACT-1", "nx-exact",
                        new BigDecimal("659750"), receivedAt.atOffset(ZoneOffset.UTC),
                        "EVIDENCE-EXACT-1", "register exact bank receipt", "finance-admin"));

        assertThat(result.getData())
                .containsEntry("viewType", "MATCHED")
                .containsEntry("intentNo", "VQR-EXACT");
        verify(appIntentMapper).closeInFlightReconciliation("VQR-EXACT", "RECEIPT_REGISTERED");
        verify(appIntentMapper).transitionIntent(
                "VQR-EXACT", 0L, "AWAITING_PAYMENT", "RECEIPT_REVIEW",
                new BigDecimal("659750"), new BigDecimal("0.000000"), receivedAt);
        verify(mapper).addVietQrBankReceivedToday(
                8L, new BigDecimal("659750"), bankDate(receivedAt));
        verify(appIntentMapper).cancelAwaitingIntentsForFusedAccount(8L, "VQR-EXACT");
        verify(appIntentMapper).closeCancelledInFlightReconciliationsForFusedAccount(
                8L, "VQR-EXACT");
        verify(audit).recordRequired(any());
    }

    @Test
    void receiptRegistrationFailsClosedWhenTheActualReceiptTotalCannotBeRecorded() {
        LocalDateTime receivedAt = LocalDateTime.of(2026, 7, 25, 0, 0);
        when(mapper.findFxQuoteConfig()).thenReturn(Map.of(
                "baseRateVndPerUsdt", new BigDecimal("26000"),
                "buySpreadPct", new BigDecimal("1.5")));
        when(mapper.insertVietQrReceipt(
                anyString(), eq(null), eq(null), eq(8L), eq("ORPHAN"),
                eq(null), eq(new BigDecimal("659750")),
                eq(new BigDecimal("26390")), eq("BANK-REC-20"), anyString(),
                eq(null), eq(receivedAt), eq(false))).thenReturn(1);
        when(mapper.addVietQrBankReceivedToday(
                8L, new BigDecimal("659750"), bankDate(receivedAt))).thenReturn(0);

        assertThatThrownBy(() -> service.registerVietQrReceipt(
                "receipt-cap-20",
                new VietQrReceiptRegistrationRequest(
                        8L, "BANK-REC-20", null, new BigDecimal("659750"),
                        receivedAt.atOffset(ZoneOffset.UTC), "EVIDENCE-20",
                        "record actual receipt total", "finance-admin")))
                .isInstanceOf(BizException.class)
                .hasMessage("VIETQR_BANK_ACCOUNT_RECEIPT_TOTAL_UPDATE_FAILED");
    }

    @Test
    void memoBoundReceiptCannotPredateTheCanonicalIntentByEvenOneSecond() {
        OpsVietnamPaymentService receiptService = serviceAt("2026-07-25T00:20:00Z");
        LocalDateTime receivedAt = LocalDateTime.of(2026, 7, 25, 0, 19, 59);
        when(appIntentMapper.findIntentByMemoForUpdate("NX-PREDATE")).thenReturn(Map.ofEntries(
                Map.entry("intentNo", "VQR-PREDATE"),
                Map.entry("userId", 41L),
                Map.entry("status", "AWAITING_PAYMENT"),
                Map.entry("payableVnd", new BigDecimal("659750")),
                Map.entry("bankAccountId", 8L),
                Map.entry("lockedFxRateVndPerUsdt", new BigDecimal("26390")),
                Map.entry("createdAt", LocalDateTime.of(2026, 7, 25, 0, 20)),
                Map.entry("expiresAt", LocalDateTime.of(2026, 7, 25, 0, 50)),
                Map.entry("version", 0L)));

        assertThatThrownBy(() -> receiptService.registerVietQrReceipt(
                "receipt-predate",
                new VietQrReceiptRegistrationRequest(
                        8L, "BANK-PREDATE", "NX-PREDATE",
                        new BigDecimal("659750"), receivedAt.atOffset(ZoneOffset.UTC),
                        "EVIDENCE-PREDATE", "reject historical receipt reuse", "finance-admin")))
                .isInstanceOf(BizException.class)
                .hasMessage("VIETQR_RECEIPT_PREDATES_INTENT");

        verify(mapper, never()).insertVietQrReceipt(
                anyString(), any(), any(), anyLong(), anyString(),
                any(), any(), any(), anyString(), anyString(), any(), any(), anyBoolean());
        verify(mapper, never()).addVietQrBankReceivedToday(anyLong(), any(), any());
        verify(appIntentMapper, never()).transitionIntent(
                anyString(), anyLong(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void exactReceiptReceivedBeforeExpirySettlesAfterTheOperatorDelay() {
        OpsVietnamPaymentService delayedService = serviceAt("2026-07-25T01:00:00Z");
        LocalDateTime receivedAt = LocalDateTime.of(2026, 7, 25, 0, 25);
        when(mapper.findVietQrReconciliationForUpdate(21L)).thenReturn(Map.ofEntries(
                Map.entry("reconciliationNo", "REC-21"),
                Map.entry("intentNo", "VQR-BEFORE-EXPIRY"),
                Map.entry("bankAccountId", 8L),
                Map.entry("viewType", "MATCHED"),
                Map.entry("status", "OPEN"),
                Map.entry("receivedVnd", new BigDecimal("659750")),
                Map.entry("receivedAt", receivedAt),
                Map.entry("paymentReference", "BANK-REC-21"),
                Map.entry("lockedFxRateVndPerUsdt", new BigDecimal("26390")),
                Map.entry("intentTransitionRequired", true),
                Map.entry("version", 0L)));
        when(appIntentMapper.findIntentForUpdate("VQR-BEFORE-EXPIRY")).thenReturn(Map.ofEntries(
                Map.entry("intentNo", "VQR-BEFORE-EXPIRY"),
                Map.entry("userId", 41L),
                Map.entry("status", "RECEIPT_REVIEW"),
                Map.entry("payableVnd", new BigDecimal("659750")),
                Map.entry("bankAccountId", 8L),
                Map.entry("lockedFxRateVndPerUsdt", new BigDecimal("26390")),
                Map.entry("expiresAt", LocalDateTime.of(2026, 7, 25, 0, 30)),
                Map.entry("version", 1L)));
        when(mapper.findUsdtWalletForUpdate(41L)).thenReturn(Map.of(
                "usdtAvailable", new BigDecimal("100"), "version", 5L));
        when(mapper.creditUsdtWallet(41L, new BigDecimal("25.000000"), 5L)).thenReturn(1);
        when(mapper.insertVietQrWalletLedger(
                "D1-VIETQR-REC-21", 41L, new BigDecimal("25.000000"),
                new BigDecimal("125.000000"), "VietQR settlement REC-21")).thenReturn(1);
        when(appIntentMapper.transitionIntent(
                "VQR-BEFORE-EXPIRY", 1L, "RECEIPT_REVIEW", "CREDITED",
                new BigDecimal("659750"), new BigDecimal("25.000000"),
                LocalDateTime.of(2026, 7, 25, 1, 0))).thenReturn(1);
        when(mapper.completeVietQrReconciliation(
                21L, 0L, "CREDITED", "MATCHED", 41L, "VQR-BEFORE-EXPIRY",
                new BigDecimal("25.000000"), "confirm delayed exact receipt")).thenReturn(1);

        assertThat(delayedService.reconcile(
                21L, "match-credit", "reconcile-delay-21",
                new VietQrReconciliationCommandRequest(
                        0L, null, "VQR-BEFORE-EXPIRY", "EVIDENCE-21",
                        "confirm delayed exact receipt", "finance-admin")).getData())
                .containsEntry("status", "CREDITED");
        verify(mapper, never()).addVietQrBankReceivedToday(anyLong(), any(), any());
    }

    @Test
    void receiptWithinGraceCanBeRegisteredAndSettledAfterGraceEnds() {
        OpsVietnamPaymentService delayedService = serviceAt("2026-07-25T01:00:00Z");
        LocalDateTime receivedAt = LocalDateTime.of(2026, 7, 25, 0, 35);
        Map<String, Object> awaiting = Map.ofEntries(
                Map.entry("intentNo", "VQR-WITHIN-GRACE"),
                Map.entry("userId", 41L),
                Map.entry("status", "AWAITING_PAYMENT"),
                Map.entry("payableVnd", new BigDecimal("659750")),
                Map.entry("bankAccountId", 8L),
                Map.entry("lockedFxRateVndPerUsdt", new BigDecimal("26390")),
                Map.entry("createdAt", LocalDateTime.of(2026, 7, 25, 0, 0)),
                Map.entry("expiresAt", LocalDateTime.of(2026, 7, 25, 0, 30)),
                Map.entry("version", 0L));
        when(appIntentMapper.findIntentByMemoForUpdate("NX-WITHIN-GRACE")).thenReturn(awaiting);
        when(mapper.insertVietQrReceipt(
                anyString(), eq("VQR-WITHIN-GRACE"), eq(41L), eq(8L), eq("MATCHED"),
                eq(new BigDecimal("659750")), eq(new BigDecimal("659750")),
                eq(new BigDecimal("26390")), eq("BANK-WITHIN-GRACE"), anyString(),
                eq(LocalDateTime.of(2026, 7, 25, 0, 30)), eq(receivedAt), eq(true)))
                .thenReturn(1);
        when(mapper.addVietQrBankReceivedToday(
                8L, new BigDecimal("659750"), bankDate(receivedAt))).thenReturn(1);
        when(appIntentMapper.transitionIntent(
                "VQR-WITHIN-GRACE", 0L, "AWAITING_PAYMENT", "RECEIPT_REVIEW",
                new BigDecimal("659750"), new BigDecimal("0.000000"), receivedAt))
                .thenReturn(1);
        when(mapper.findVietQrReceiptByPaymentReference("BANK-WITHIN-GRACE")).thenReturn(Map.of(
                "id", 22L, "reconciliationNo", "REC-22",
                "intentNo", "VQR-WITHIN-GRACE", "viewType", "MATCHED",
                "paymentReference", "BANK-WITHIN-GRACE", "version", 0L));

        assertThat(delayedService.registerVietQrReceipt(
                "receipt-within-grace",
                new VietQrReceiptRegistrationRequest(
                        8L, "BANK-WITHIN-GRACE", "NX-WITHIN-GRACE",
                        new BigDecimal("659750"), receivedAt.atOffset(ZoneOffset.UTC), "EVIDENCE-22",
                        "register receipt inside grace", "finance-admin")).getData())
                .containsEntry("viewType", "MATCHED");

        when(mapper.findVietQrReconciliationForUpdate(22L)).thenReturn(Map.ofEntries(
                Map.entry("reconciliationNo", "REC-22"),
                Map.entry("intentNo", "VQR-WITHIN-GRACE"),
                Map.entry("bankAccountId", 8L),
                Map.entry("viewType", "MATCHED"),
                Map.entry("status", "OPEN"),
                Map.entry("receivedVnd", new BigDecimal("659750")),
                Map.entry("receivedAt", receivedAt),
                Map.entry("paymentReference", "BANK-WITHIN-GRACE"),
                Map.entry("lockedFxRateVndPerUsdt", new BigDecimal("26390")),
                Map.entry("intentTransitionRequired", true),
                Map.entry("version", 0L)));
        when(appIntentMapper.findIntentForUpdate("VQR-WITHIN-GRACE")).thenReturn(Map.ofEntries(
                Map.entry("intentNo", "VQR-WITHIN-GRACE"),
                Map.entry("userId", 41L),
                Map.entry("status", "RECEIPT_REVIEW"),
                Map.entry("payableVnd", new BigDecimal("659750")),
                Map.entry("bankAccountId", 8L),
                Map.entry("lockedFxRateVndPerUsdt", new BigDecimal("26390")),
                Map.entry("expiresAt", LocalDateTime.of(2026, 7, 25, 0, 30)),
                Map.entry("version", 1L)));
        when(mapper.findUsdtWalletForUpdate(41L)).thenReturn(Map.of(
                "usdtAvailable", BigDecimal.ZERO, "version", 0L));
        when(mapper.creditUsdtWallet(41L, new BigDecimal("25.000000"), 0L)).thenReturn(1);
        when(mapper.insertVietQrWalletLedger(
                "D1-VIETQR-REC-22", 41L, new BigDecimal("25.000000"),
                new BigDecimal("25.000000"), "VietQR settlement REC-22")).thenReturn(1);
        when(appIntentMapper.transitionIntent(
                "VQR-WITHIN-GRACE", 1L, "RECEIPT_REVIEW", "CREDITED",
                new BigDecimal("659750"), new BigDecimal("25.000000"),
                LocalDateTime.of(2026, 7, 25, 1, 0))).thenReturn(1);
        when(mapper.completeVietQrReconciliation(
                22L, 0L, "CREDITED", "MATCHED", 41L, "VQR-WITHIN-GRACE",
                new BigDecimal("25.000000"), "confirm receipt after grace")).thenReturn(1);

        assertThat(delayedService.reconcile(
                22L, "match-credit", "reconcile-grace-22",
                new VietQrReconciliationCommandRequest(
                        0L, null, "VQR-WITHIN-GRACE", "EVIDENCE-22",
                        "confirm receipt after grace", "finance-admin")).getData())
                .containsEntry("status", "CREDITED");
    }

    @Test
    void receiptAfterGraceIsOnlyClassifiedAsLate() {
        OpsVietnamPaymentService delayedService = serviceAt("2026-07-25T01:00:00Z");
        LocalDateTime receivedAt = LocalDateTime.of(2026, 7, 25, 0, 41);
        when(appIntentMapper.findIntentByMemoForUpdate("NX-AFTER-GRACE")).thenReturn(Map.ofEntries(
                Map.entry("intentNo", "VQR-AFTER-GRACE"),
                Map.entry("userId", 41L),
                Map.entry("status", "AWAITING_PAYMENT"),
                Map.entry("payableVnd", new BigDecimal("659750")),
                Map.entry("bankAccountId", 8L),
                Map.entry("lockedFxRateVndPerUsdt", new BigDecimal("26390")),
                Map.entry("createdAt", LocalDateTime.of(2026, 7, 25, 0, 0)),
                Map.entry("expiresAt", LocalDateTime.of(2026, 7, 25, 0, 30)),
                Map.entry("version", 0L)));
        when(mapper.insertVietQrReceipt(
                anyString(), eq("VQR-AFTER-GRACE"), eq(41L), eq(8L), eq("LATE"),
                eq(new BigDecimal("659750")), eq(new BigDecimal("659750")),
                eq(new BigDecimal("26390")), eq("BANK-AFTER-GRACE"), anyString(),
                eq(LocalDateTime.of(2026, 7, 25, 0, 30)), eq(receivedAt), eq(true)))
                .thenReturn(1);
        when(mapper.addVietQrBankReceivedToday(
                8L, new BigDecimal("659750"), bankDate(receivedAt))).thenReturn(1);
        when(appIntentMapper.transitionIntent(
                "VQR-AFTER-GRACE", 0L, "AWAITING_PAYMENT", "LATE_REVIEW",
                new BigDecimal("659750"), new BigDecimal("0.000000"), receivedAt))
                .thenReturn(1);
        when(mapper.findVietQrReceiptByPaymentReference("BANK-AFTER-GRACE")).thenReturn(Map.of(
                "id", 23L, "reconciliationNo", "REC-23",
                "intentNo", "VQR-AFTER-GRACE", "viewType", "LATE",
                "paymentReference", "BANK-AFTER-GRACE", "version", 0L));

        assertThat(delayedService.registerVietQrReceipt(
                "receipt-after-grace",
                new VietQrReceiptRegistrationRequest(
                        8L, "BANK-AFTER-GRACE", "NX-AFTER-GRACE",
                        new BigDecimal("659750"), receivedAt.atOffset(ZoneOffset.UTC), "EVIDENCE-23",
                        "register receipt after grace", "finance-admin")).getData())
                .containsEntry("viewType", "LATE");
    }

    @Test
    void terminalIntentSecondReceiptStaysSupplementalAndCanOnlyBeReturned() {
        OpsVietnamPaymentService delayedService = serviceAt("2026-07-25T01:00:00Z");
        LocalDateTime receivedAt = LocalDateTime.of(2026, 7, 25, 0, 0);
        when(appIntentMapper.findIntentByMemoForUpdate("NX-DUPLICATE")).thenReturn(Map.ofEntries(
                Map.entry("intentNo", "VQR-DUPLICATE"),
                Map.entry("userId", 41L),
                Map.entry("status", "CREDITED"),
                Map.entry("payableVnd", new BigDecimal("659750")),
                Map.entry("bankAccountId", 8L),
                Map.entry("lockedFxRateVndPerUsdt", new BigDecimal("26390")),
                Map.entry("createdAt", LocalDateTime.of(2026, 7, 24, 23, 30)),
                Map.entry("expiresAt", LocalDateTime.of(2026, 7, 25, 0, 30)),
                Map.entry("version", 2L)));
        when(mapper.insertVietQrReceipt(
                anyString(), eq("VQR-DUPLICATE"), eq(41L), eq(8L), eq("LATE"),
                eq(new BigDecimal("659750")), eq(new BigDecimal("659750")),
                eq(new BigDecimal("26390")), eq("BANK-DUPLICATE-2"), anyString(),
                eq(LocalDateTime.of(2026, 7, 25, 0, 30)), eq(receivedAt), eq(false)))
                .thenReturn(1);
        when(mapper.addVietQrBankReceivedToday(
                8L, new BigDecimal("659750"), bankDate(receivedAt))).thenReturn(1);
        when(mapper.findVietQrReceiptByPaymentReference("BANK-DUPLICATE-2")).thenReturn(Map.of(
                "id", 24L, "reconciliationNo", "REC-24",
                "intentNo", "VQR-DUPLICATE", "viewType", "LATE",
                "paymentReference", "BANK-DUPLICATE-2", "version", 0L));

        assertThat(delayedService.registerVietQrReceipt(
                "receipt-duplicate-24",
                new VietQrReceiptRegistrationRequest(
                        8L, "BANK-DUPLICATE-2", "NX-DUPLICATE",
                        new BigDecimal("659750"), receivedAt.atOffset(ZoneOffset.UTC), "EVIDENCE-24",
                        "register supplemental receipt", "finance-admin")).getData())
                .containsEntry("viewType", "LATE");

        when(mapper.findVietQrReconciliationForUpdate(24L)).thenReturn(Map.ofEntries(
                Map.entry("reconciliationNo", "REC-24"),
                Map.entry("intentNo", "VQR-DUPLICATE"),
                Map.entry("bankAccountId", 8L),
                Map.entry("viewType", "LATE"),
                Map.entry("status", "OPEN"),
                Map.entry("receivedVnd", new BigDecimal("659750")),
                Map.entry("receivedAt", receivedAt),
                Map.entry("paymentReference", "BANK-DUPLICATE-2"),
                Map.entry("lockedFxRateVndPerUsdt", new BigDecimal("26390")),
                Map.entry("intentTransitionRequired", false),
                Map.entry("version", 0L)));
        when(appIntentMapper.findIntentForUpdate("VQR-DUPLICATE")).thenReturn(Map.ofEntries(
                Map.entry("intentNo", "VQR-DUPLICATE"),
                Map.entry("userId", 41L),
                Map.entry("status", "CREDITED"),
                Map.entry("payableVnd", new BigDecimal("659750")),
                Map.entry("bankAccountId", 8L),
                Map.entry("lockedFxRateVndPerUsdt", new BigDecimal("26390")),
                Map.entry("expiresAt", LocalDateTime.of(2026, 7, 25, 0, 30)),
                Map.entry("version", 2L)));
        when(mapper.completeVietQrReconciliation(
                24L, 0L, "RETURNED", "LATE", 41L, "VQR-DUPLICATE",
                new BigDecimal("0.000000"), "return supplemental receipt")).thenReturn(1);

        assertThat(delayedService.reconcile(
                24L, "return", "reconcile-duplicate-24",
                new VietQrReconciliationCommandRequest(
                        0L, null, "VQR-DUPLICATE", "EVIDENCE-24",
                        "return supplemental receipt", "finance-admin")).getData())
                .containsEntry("status", "RETURNED");
        verify(mapper, never()).findUsdtWalletForUpdate(anyLong());
        verify(appIntentMapper, never()).transitionIntent(
                anyString(), anyLong(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void boundReceiptRejectsAnOperatorIntentOverrideBeforeAnyWalletWrite() {
        when(mapper.findVietQrReconciliationForUpdate(25L)).thenReturn(Map.ofEntries(
                Map.entry("reconciliationNo", "REC-25"),
                Map.entry("intentNo", "VQR-BOUND"),
                Map.entry("bankAccountId", 8L),
                Map.entry("viewType", "MATCHED"),
                Map.entry("status", "OPEN"),
                Map.entry("receivedVnd", new BigDecimal("659750")),
                Map.entry("receivedAt", LocalDateTime.of(2026, 7, 25, 0, 20)),
                Map.entry("paymentReference", "BANK-REC-25"),
                Map.entry("lockedFxRateVndPerUsdt", new BigDecimal("26390")),
                Map.entry("intentTransitionRequired", true),
                Map.entry("version", 0L)));

        assertThatThrownBy(() -> service.reconcile(
                25L, "match-credit", "reconcile-bound-25",
                new VietQrReconciliationCommandRequest(
                        0L, null, "VQR-OTHER", "EVIDENCE-25",
                        "reject operator intent override", "finance-admin")))
                .isInstanceOf(BizException.class)
                .hasMessage("VIETQR_BOUND_INTENT_OVERRIDE_NOT_ALLOWED");
        verify(mapper, never()).creditUsdtWallet(anyLong(), any(), anyLong());
    }

    @Test
    void receiptRegistrationRejectsPathologicalDecimalBeforeDatabaseAccess() {
        assertThatThrownBy(() -> service.registerVietQrReceipt(
                "receipt-pathological",
                new VietQrReceiptRegistrationRequest(
                        8L, "BANK-PATHOLOGICAL", "NX-PATH",
                        new BigDecimal("1e+100000000"),
                        LocalDateTime.of(2026, 7, 25, 0, 0).atOffset(ZoneOffset.UTC),
                        "EVIDENCE-PATH", "reject pathological receipt amount", "finance-admin")))
                .isInstanceOf(BizException.class)
                .hasMessage("VIETQR_RECEIVED_AMOUNT_INVALID");
        verify(mapper, never()).findVietQrBankAccountForUpdate(anyLong());
    }

    @Test
    void receiptRegistrationRejectsFutureVietnamBusinessDateNearMidnight() {
        OpsVietnamPaymentService nearMidnight =
                serviceAt("2026-07-26T16:59:00Z");

        assertThatThrownBy(() -> nearMidnight.registerVietQrReceipt(
                "receipt-next-business-day",
                new VietQrReceiptRegistrationRequest(
                        8L, "BANK-NEXT-DAY", "NX-NEXT-DAY",
                        new BigDecimal("659750"),
                        OffsetDateTime.parse("2026-07-27T00:01:00+07:00"),
                        "EVIDENCE-NEXT-DAY",
                        "reject future vietnam business date", "finance-admin")))
                .isInstanceOf(BizException.class)
                .hasMessage("VIETQR_RECEIVED_AT_INVALID");
        verify(mapper, never()).findVietQrBankAccountForUpdate(anyLong());
    }

    private OpsVietnamPaymentService serviceAt(String instant) {
        return new OpsVietnamPaymentService(
                mapper, audit, idempotency, sensitiveDataCipher, appIntentMapper, outbox,
                Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    private LocalDate bankDate(LocalDateTime receivedAt) {
        return receivedAt.atOffset(ZoneOffset.UTC)
                .atZoneSameInstant(ZoneId.of("Asia/Ho_Chi_Minh"))
                .toLocalDate();
    }

    private Map<String, Object> vietQrConfig(BigDecimal perTxLimitUsd) {
        return Map.of(
                "id", 1L,
                "toleranceVnd", new BigDecimal("1000"),
                "graceMinutes", 10,
                "perTxLimitUsd", perTxLimitUsd,
                "trc20Confirmations", 20,
                "erc20Confirmations", 12,
                "bep20Confirmations", 15,
                "rotationStrategy", "ROUND_ROBIN",
                "version", 0L);
    }
}
