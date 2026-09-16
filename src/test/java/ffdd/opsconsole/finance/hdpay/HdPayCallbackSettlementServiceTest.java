package ffdd.opsconsole.finance.hdpay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.finance.mapper.AppVietQrIntentMapper;
import ffdd.opsconsole.finance.mapper.VietnamPaymentMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.treasury.domain.TreasuryLedgerRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HdPayCallbackSettlementServiceTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-02T04:00:00Z"), ZoneOffset.UTC);

    private final HdPayOrderMapper hdPayMapper = mock(HdPayOrderMapper.class);
    private final AppVietQrIntentMapper intentMapper = mock(AppVietQrIntentMapper.class);
    private final VietnamPaymentMapper paymentMapper = mock(VietnamPaymentMapper.class);
    private final EventOutboxService outbox = mock(EventOutboxService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final TreasuryLedgerRepository treasuryLedger = mock(TreasuryLedgerRepository.class);
    private final HdPayCallbackSettlementService service = new HdPayCallbackSettlementService(
            hdPayMapper, intentMapper, paymentMapper, outbox, audit, treasuryLedger, CLOCK);

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void confirmedPaymentCreditsWalletLedgerIntentAndNotificationWithoutReceiptImage(boolean orderQuery) {
        var callback = callback("abc", "100000");
        var query = payOrder("100000");
        when(hdPayMapper.findByMerchantOrderIdForUpdate("VQR-1"))
                .thenReturn(order("100000", "UNSETTLED"));
        when(hdPayMapper.insertCallbackInbox(anyString(), eq("VQR-1"), eq("P-1"), eq(3),
                eq(new BigDecimal("100000")), eq("PROCESSING"), anyString())).thenReturn(1);
        when(hdPayMapper.updateCallbackObservation("VQR-1", "P-1", 3)).thenReturn(1);
        when(intentMapper.findIntentForUpdate("VQR-1")).thenReturn(intent(
                "AWAITING_PAYMENT", LocalDateTime.of(2026, 9, 2, 5, 0), "5.000000"));
        when(paymentMapper.findUsdtWalletForUpdate(42L)).thenReturn(Map.of(
                "usdtAvailable", new BigDecimal("10.000000"), "version", 7L));
        when(paymentMapper.creditUsdtWallet(42L, new BigDecimal("5.000000"), 7L)).thenReturn(1);
        when(paymentMapper.insertVietQrWalletLedger(
                "VQR-1", 42L, new BigDecimal("5.000000"),
                new BigDecimal("15.000000"), "HDPay BANKQR deposit VQR-1")).thenReturn(1);
        when(intentMapper.transitionIntent(
                "VQR-1", 9L, "AWAITING_PAYMENT", "CREDITED",
                new BigDecimal("100000"), new BigDecimal("5.000000"),
                LocalDateTime.of(2026, 9, 2, 4, 0))).thenReturn(1);
        when(intentMapper.closeInFlightReconciliation("VQR-1", "CREDITED")).thenReturn(1);
        when(hdPayMapper.insertDepositNotification(
                "HDPAY:VQR-1", 42L, new BigDecimal("5.000000"))).thenReturn(1);
        when(hdPayMapper.markSettlementCredited(
                "VQR-1", "P-1", 3, new BigDecimal("5.000000"), "VQR-1")).thenReturn(1);
        when(hdPayMapper.markCallbackProcessedOwned(
                anyString(), anyString(), eq("CREDITED"), eq(3), eq("CREDITED")))
                .thenReturn(1);
        when(outbox.publish(eq("WALLET"), eq("VQR-1"),
                eq("wallet.topup_confirmed"), any())).thenReturn("event-1");

        if (orderQuery) {
            assertThat(service.settleOrderQuery("VQR-1", 0L, query)).isEqualTo("success");
            verify(hdPayMapper, never()).insertCallbackInbox(any(), any(), any(), any(), any(), any(), any());
            verify(hdPayMapper, never()).findCallbackInboxForUpdate(any());
        } else {
            var claim = service.claimForProviderQuery(callback);
            when(hdPayMapper.findCallbackInboxForUpdate(anyString())).thenReturn(Map.of(
                    "processingStatus", "PROCESSING", "claimToken", claim.claimToken()));
            assertThat(service.settleConfirmed(claim.fact(), claim.claimToken(), query)).isEqualTo("success");
        }

        verify(paymentMapper).creditUsdtWallet(42L, new BigDecimal("5.000000"), 7L);
        verify(treasuryLedger).recordTopupReserve("VQR-1", new BigDecimal("5.000000"), "HDPAY:VQR-1");
        verify(paymentMapper).insertVietQrWalletLedger(
                "VQR-1", 42L, new BigDecimal("5.000000"),
                new BigDecimal("15.000000"), "HDPay BANKQR deposit VQR-1");
        verify(intentMapper).transitionIntent(
                "VQR-1", 9L, "AWAITING_PAYMENT", "CREDITED",
                new BigDecimal("100000"), new BigDecimal("5.000000"),
                LocalDateTime.of(2026, 9, 2, 4, 0));
        verify(hdPayMapper).insertDepositNotification(
                "HDPAY:VQR-1", 42L, new BigDecimal("5.000000"));
        verify(outbox).publish(eq("WALLET"), eq("VQR-1"),
                eq("wallet.topup_confirmed"), any());
        verify(audit).recordRequired(any());
    }

    @Test
    void expiredIntentIsDurablyFlaggedForManualReviewWithoutCreditingFunds() {
        var callback = callback("abc", "100000");
        when(hdPayMapper.findByMerchantOrderIdForUpdate("VQR-1"))
                .thenReturn(order("100000", "UNSETTLED"));
        when(hdPayMapper.insertCallbackInbox(anyString(), eq("VQR-1"), eq("P-1"), eq(3),
                eq(new BigDecimal("100000")), eq("PROCESSING"), anyString())).thenReturn(1);
        when(hdPayMapper.updateCallbackObservation("VQR-1", "P-1", 3)).thenReturn(1);
        when(intentMapper.findIntentForUpdate("VQR-1")).thenReturn(intent(
                "AWAITING_PAYMENT", LocalDateTime.of(2026, 9, 2, 3, 59), "5.000000"));
        when(hdPayMapper.markSettlementReview("VQR-1", "P-1", 3, "HDPAY_INTENT_EXPIRED"))
                .thenReturn(1);
        when(hdPayMapper.insertSettlementReview(
                anyString(), eq("VQR-1"), eq("P-1"), eq("HDPAY_INTENT_EXPIRED")))
                .thenReturn(1);
        when(hdPayMapper.markCallbackProcessedOwned(
                anyString(), anyString(), eq("MANUAL_REVIEW"), eq(3),
                eq("HDPAY_INTENT_EXPIRED")))
                .thenReturn(1);

        var claim = service.claimForProviderQuery(callback);
        when(hdPayMapper.findCallbackInboxForUpdate(anyString())).thenReturn(Map.of(
                "processingStatus", "PROCESSING", "claimToken", claim.claimToken()));
        assertThat(service.settleConfirmed(
                claim.fact(), claim.claimToken(), payOrder("100000"))).isEqualTo("success");

        org.mockito.Mockito.verifyNoInteractions(treasuryLedger);
        verify(paymentMapper, never()).creditUsdtWallet(any(), any(), any());
        verify(paymentMapper, never()).insertVietQrWalletLedger(any(), any(), any(), any(), any());
        verify(outbox, never()).publish(any(), any(), any(), any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullAndEmptySource
    @org.junit.jupiter.params.provider.ValueSource(strings = {"MANUAL", "UNKNOWN"})
    void nonHostedIntentIsDurablyReviewedBeforeAnyWalletMutation(String rail) {
        var callback = callback("abc", "100000");
        when(hdPayMapper.findByMerchantOrderIdForUpdate("VQR-1"))
                .thenReturn(order("100000", "UNSETTLED"));
        when(hdPayMapper.insertCallbackInbox(anyString(), eq("VQR-1"), eq("P-1"), eq(3),
                eq(new BigDecimal("100000")), eq("PROCESSING"), anyString())).thenReturn(1);
        when(hdPayMapper.updateCallbackObservation("VQR-1", "P-1", 3)).thenReturn(1);
        Map<String, Object> target = intent("AWAITING_PAYMENT", LocalDateTime.of(2026, 9, 2, 5, 0), "5.000000");
        if (rail == null) target.remove("paymentRail");
        else target.put("paymentRail", rail);
        when(intentMapper.findIntentForUpdate("VQR-1")).thenReturn(target);
        when(hdPayMapper.markSettlementReview("VQR-1", "P-1", 3, "VIETQR_PAYMENT_RAIL_CONFLICT"))
                .thenReturn(1);
        when(hdPayMapper.insertSettlementReview(anyString(), eq("VQR-1"), eq("P-1"),
                eq("VIETQR_PAYMENT_RAIL_CONFLICT"))).thenReturn(1);
        when(hdPayMapper.markCallbackProcessedOwned(anyString(), anyString(), eq("MANUAL_REVIEW"),
                eq(3), eq("VIETQR_PAYMENT_RAIL_CONFLICT"))).thenReturn(1);
        var claim = service.claimForProviderQuery(callback);
        when(hdPayMapper.findCallbackInboxForUpdate(anyString())).thenReturn(Map.of(
                "processingStatus", "PROCESSING", "claimToken", claim.claimToken()));

        assertThat(service.settleConfirmed(claim.fact(), claim.claimToken(), payOrder("100000")))
                .isEqualTo("success");

        verify(hdPayMapper).markSettlementReview("VQR-1", "P-1", 3, "VIETQR_PAYMENT_RAIL_CONFLICT");
        verify(hdPayMapper).insertSettlementReview(anyString(), eq("VQR-1"), eq("P-1"),
                eq("VIETQR_PAYMENT_RAIL_CONFLICT"));
        org.mockito.Mockito.verifyNoInteractions(paymentMapper, outbox, treasuryLedger);
        verify(intentMapper, never()).transitionIntent(any(), any(), any(), any(), any(), any(), any());
        verify(intentMapper, never()).closeInFlightReconciliation(any(), any());
        verify(hdPayMapper, never()).insertDepositNotification(any(), any(), any());
        verify(hdPayMapper, never()).markSettlementCredited(any(), any(), any(), any(), any());
    }

    @Test
    void exactDuplicatePayloadIsAcknowledgedWithoutAnySecondCredit() {
        var callback = callback("abc", "100000");
        when(hdPayMapper.findByMerchantOrderIdForUpdate("VQR-1"))
                .thenReturn(order("100000", "CREDITED"));
        when(hdPayMapper.insertCallbackInbox(anyString(), eq("VQR-1"), eq("P-1"), eq(3),
                eq(new BigDecimal("100000")), eq("PROCESSING"), anyString())).thenReturn(0);
        when(hdPayMapper.findCallbackInboxForUpdate(anyString()))
                .thenReturn(Map.of("processingStatus", "CREDITED"));

        assertThat(service.settleConfirmed(callback, payOrder("100000"))).isEqualTo("success");

        verify(paymentMapper, never()).creditUsdtWallet(any(), any(), any());
        verify(paymentMapper, never()).insertVietQrWalletLedger(any(), any(), any(), any(), any());
        verify(intentMapper, never()).transitionIntent(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void signedPaidCallbackIsDurablyClaimedBeforeAnyProviderQuery() {
        var callback = callback("abc", "100000");
        when(hdPayMapper.findByMerchantOrderIdForUpdate("VQR-1"))
                .thenReturn(order("100000", "UNSETTLED"));
        when(hdPayMapper.insertCallbackInbox(anyString(), eq("VQR-1"), eq("P-1"), eq(3),
                eq(new BigDecimal("100000")), eq("PROCESSING"), anyString())).thenReturn(1);
        when(hdPayMapper.updateCallbackObservation("VQR-1", "P-1", 3)).thenReturn(1);

        var claim = service.claimForProviderQuery(callback);

        assertThat(claim.disposition())
                .isEqualTo(HdPayCallbackSettlementService.ClaimDisposition.QUERY_PROVIDER);
        assertThat(claim.fact().payloadHash()).hasSize(64);
        assertThat(claim.claimToken()).hasSize(36);
    }

    @Test
    void conflictingAmountAfterCreditCreatesReviewInsteadOfBeingSilentlySwallowed() {
        var callback = callback("different-sign", "100001");
        when(hdPayMapper.findByMerchantOrderIdForUpdate("VQR-1"))
                .thenReturn(order("100000", "CREDITED"));
        when(hdPayMapper.insertCallbackInbox(anyString(), eq("VQR-1"), eq("P-1"), eq(3),
                eq(new BigDecimal("100001")), eq("PROCESSING"), anyString())).thenReturn(1);
        when(hdPayMapper.updateCallbackObservation("VQR-1", "P-1", 3)).thenReturn(1);
        when(hdPayMapper.markPostCreditReview(
                "VQR-1", "P-1", "HDPAY_POST_CREDIT_CALLBACK_REVIEW")).thenReturn(1);
        when(hdPayMapper.insertSettlementReview(anyString(), eq("VQR-1"), eq("P-1"),
                eq("HDPAY_POST_CREDIT_CALLBACK_REVIEW"))).thenReturn(1);
        when(hdPayMapper.markCallbackProcessedOwned(anyString(), anyString(),
                eq("MANUAL_REVIEW"), eq(3),
                eq("HDPAY_POST_CREDIT_CALLBACK_REVIEW"))).thenReturn(1);

        var claim = service.claimForProviderQuery(callback);

        assertThat(claim.disposition())
                .isEqualTo(HdPayCallbackSettlementService.ClaimDisposition.ACKNOWLEDGED);
        verify(hdPayMapper).insertSettlementReview(anyString(), eq("VQR-1"), eq("P-1"),
                eq("HDPAY_POST_CREDIT_CALLBACK_REVIEW"));
        verify(audit).recordRequired(any());
        verify(paymentMapper, never()).creditUsdtWallet(any(), any(), any());
    }

    @Test
    void nonPaidProviderStateAfterCreditCreatesDurableReviewWithoutReversingFunds() {
        var callback = new HdPayCallbackVerifier.VerifiedCallback(
                "VQR-1", "P-1", 4, new BigDecimal("100000"),
                "2026-09-02 11:59:00", "2026-09-02 12:00:00", "non-paid-after-credit");
        when(hdPayMapper.findByMerchantOrderIdForUpdate("VQR-1"))
                .thenReturn(order("100000", "CREDITED"));
        when(hdPayMapper.insertCallbackInbox(anyString(), eq("VQR-1"), eq("P-1"), eq(4),
                eq(new BigDecimal("100000")), eq("OBSERVED"), eq(null))).thenReturn(1);
        when(hdPayMapper.updateCallbackObservation("VQR-1", "P-1", 4)).thenReturn(1);
        when(hdPayMapper.markPostCreditReview(
                "VQR-1", "P-1", "HDPAY_POST_CREDIT_PROVIDER_STATUS_REVIEW")).thenReturn(1);
        when(hdPayMapper.insertSettlementReview(anyString(), eq("VQR-1"), eq("P-1"),
                eq("HDPAY_POST_CREDIT_PROVIDER_STATUS_REVIEW"))).thenReturn(1);
        when(hdPayMapper.markCallbackProcessed(anyString(), eq("MANUAL_REVIEW"), eq(4),
                eq("HDPAY_POST_CREDIT_PROVIDER_STATUS_REVIEW"))).thenReturn(1);

        assertThat(service.observe(callback)).isEqualTo("success");

        verify(hdPayMapper).insertSettlementReview(anyString(), eq("VQR-1"), eq("P-1"),
                eq("HDPAY_POST_CREDIT_PROVIDER_STATUS_REVIEW"));
        verify(audit).recordRequired(any());
        verify(paymentMapper, never()).creditUsdtWallet(any(), any(), any());
        verify(paymentMapper, never()).insertVietQrWalletLedger(any(), any(), any(), any(), any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 1})
    void pendingOrderQueryDoesNotCreditAndRemainsRetryable(int status) {
        when(hdPayMapper.findByMerchantOrderIdForUpdate("VQR-1")).thenReturn(order("100000", "UNSETTLED"));
        when(hdPayMapper.finishOrderQueryAttempt("VQR-1", 0L, "HDPAY_ORDER_QUERY_PENDING")).thenReturn(1);
        assertThat(service.settleOrderQuery("VQR-1", 0L,
                new HdPayGateway.PayOrder("VQR-1", "P-1", status, new BigDecimal("100000"), "BANKQR", "")))
                .isEqualTo("success");
        org.mockito.Mockito.verifyNoInteractions(paymentMapper, intentMapper, outbox);
    }

    @Test
    void callbackWinningDuringQueryFencesOffTheStaleQueryResult() {
        when(hdPayMapper.findByMerchantOrderIdForUpdate("VQR-1")).thenReturn(order("100000", "UNSETTLED"));
        assertThat(service.settleOrderQuery("VQR-1", 9L, payOrder("100000"))).isEqualTo("success");
        org.mockito.Mockito.verifyNoInteractions(paymentMapper, intentMapper, outbox, audit);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"OTHER,P-1,100000,BANKQR", "VQR-1,P-2,100000,BANKQR", "VQR-1,P-1,99999,BANKQR", "VQR-1,P-1,100000,OTHER"})
    void mismatchedQueryCreatesReviewWithoutCreditingOrFabricatingCallback(String merchant, String provider, String amount, String type) {
        when(hdPayMapper.findByMerchantOrderIdForUpdate("VQR-1")).thenReturn(order("100000", "UNSETTLED"));
        when(hdPayMapper.markSettlementReview("VQR-1", "P-1", 3, "HDPAY_ORDER_QUERY_MISMATCH")).thenReturn(1);
        when(hdPayMapper.insertSettlementReview(anyString(), eq("VQR-1"), eq("P-1"), eq("HDPAY_ORDER_QUERY_MISMATCH"))).thenReturn(1);
        assertThat(service.settleOrderQuery("VQR-1", 0L,
                new HdPayGateway.PayOrder(merchant, provider, 3, new BigDecimal(amount), type, "")))
                .isEqualTo("success");
        org.mockito.Mockito.verifyNoInteractions(paymentMapper, intentMapper, outbox);
        verify(hdPayMapper, never()).insertCallbackInbox(any(), any(), any(), any(), any(), any(), any());
        verify(hdPayMapper, never()).markCallbackProcessedOwned(any(), any(), any(), any(), any());
        verify(audit).recordRequired(any());
    }

    private HdPayCallbackVerifier.VerifiedCallback callback(String sign, String amount) {
        return new HdPayCallbackVerifier.VerifiedCallback(
                "VQR-1", "P-1", 3, new BigDecimal(amount),
                "2026-09-02 11:59:00", "2026-09-02 12:00:00", sign);
    }

    private HdPayGateway.PayOrder payOrder(String amount) {
        return new HdPayGateway.PayOrder(
                "VQR-1", "P-1", 3, new BigDecimal(amount), "BANKQR", "");
    }

    private Map<String, Object> order(String amount, String settlementStatus) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", 0L);
        result.put("merchantOrderId", "VQR-1");
        result.put("amountVnd", new BigDecimal(amount));
        result.put("submissionStatus", "CREATED");
        result.put("settlementStatus", settlementStatus);
        result.put("providerOrderId", "P-1");
        return result;
    }

    private Map<String, Object> intent(String status, LocalDateTime expiresAt, String requestedUsdt) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("intentNo", "VQR-1");
        result.put("userId", 42L);
        result.put("status", status);
        result.put("paymentRail", "HDPAY");
        result.put("expiresAt", expiresAt);
        result.put("requestedUsdt", new BigDecimal(requestedUsdt));
        result.put("payableVnd", new BigDecimal("100000"));
        result.put("version", 9L);
        return result;
    }
}
