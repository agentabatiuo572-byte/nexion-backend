package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.content.facade.SupportPaymentAttributionFacade;
import ffdd.opsconsole.content.facade.SupportPaymentAttributionFacade.Prepared;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts.Source;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.finance.hdpay.HdPayOrderMapper;
import ffdd.opsconsole.finance.hdpay.HdPayProperties;
import ffdd.opsconsole.finance.mapper.AppVietQrIntentMapper;
import ffdd.opsconsole.finance.mapper.D1BankOrderMapper;
import ffdd.opsconsole.finance.mapper.VietnamPaymentMapper;
import ffdd.opsconsole.shared.audit.AuditLogRecord;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.treasury.domain.TreasuryLedgerRepository;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class D1BankOrderReasonTest {
    private final SupportPaymentAttributionFacade capture = paymentAttribution();
    private final D1BankOrderMapper orders = mock(D1BankOrderMapper.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final HdPayOrderMapper provider = mock(HdPayOrderMapper.class);
    private final TreasuryLedgerRepository treasury = mock(TreasuryLedgerRepository.class);
    private final D1BankOrderService service = new D1BankOrderService(capture, orders, provider,
            mock(AppVietQrIntentMapper.class), mock(VietnamPaymentMapper.class),
            mock(VietQrReceiptEvidenceService.class), treasury, mock(AdminIdempotencyService.class),
            mock(EventOutboxService.class), audit, new HdPayProperties(), new ObjectMapper(), Clock.systemUTC());

    @Test
    void rejectedReasonIsReadForEveryLocalStatusWithoutRestoringPaymentOrChangingCreditRules() {
        AuditLogRecord saved = new AuditLogRecord();
        saved.setAction("HDPAY_CREATE_REJECTED");
        saved.setResourceType("HDPAY_ORDER");
        saved.setResourceId("VQR-1");
        saved.setBizNo("VQR-1");
        saved.setResult("REJECTED");
        saved.setDetailJson("{\"schemaVersion\":\"1\",\"providerReason\":\"金额必须为整数\","
                + "\"rejectionCode\":\"HDPAY_CREATE_EXPLICIT_REJECTED\",\"providerCreatedAt\":\"2026-10-05T01:00:00\"}");
        when(audit.list(any())).thenReturn(List.of(saved));
        when(provider.findByMerchantOrderId("VQR-1")).thenReturn(providerRow("HDPAY_CREATE_EXPLICIT_REJECTED"));
        for (String status : List.of("FAILED", "EXPIRED", "CREDITED")) {
            Map<String, Object> source = row("HDPAY", "REJECTED", status);
            when(orders.listOrders(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(List.of(source));
            Map<String, Object> projected = record();
            assertThat(projected).containsEntry("providerReason", "金额必须为整数")
                    .containsEntry("status", status).containsEntry("submissionStatus", "REJECTED")
                    .containsEntry("paymentUrl", null).containsEntry("manualCreditAllowed", false);
            assertThat(source).doesNotContainKey("providerReason");
        }
        verify(audit, org.mockito.Mockito.times(3)).list(org.mockito.ArgumentMatchers.argThat(query ->
                "HDPAY_CREATE_REJECTED".equals(query.getAction()) && "HDPAY_ORDER".equals(query.getResourceType())
                        && "VQR-1".equals(query.getResourceId()) && "VQR-1".equals(query.getBizNo())
                        && "REJECTED".equals(query.getResult()) && Integer.valueOf(1).equals(query.getLimit())));
        verify(provider, org.mockito.Mockito.times(3)).findByMerchantOrderId("VQR-1");
        verifyNoInteractions(treasury);
        verify(audit, never()).record(any());
    }

    @Test
    void rejectedRowWithHttpOrQueryErrorCannotUseAnOlderExplicitAudit() {
        when(orders.listOrders(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(row("HDPAY", "REJECTED", "FAILED")));
        for (String code : List.of("HDPAY_HTTP_503", "HDPAY_QUERY_STATUS_3")) {
            when(provider.findByMerchantOrderId("VQR-1")).thenReturn(providerRow(code));
            assertThat(record()).doesNotContainKey("providerReason");
        }
        Map<String, Object> missingCreation = providerRow("HDPAY_CREATE_EXPLICIT_REJECTED");
        missingCreation.remove("createdAt");
        when(provider.findByMerchantOrderId("VQR-1")).thenReturn(missingCreation);
        assertThat(record()).doesNotContainKey("providerReason");
        verifyNoInteractions(audit, treasury);
    }

    @Test
    void manualAndUnknownProviderRowsNeverReadOrDisplayAnExplicitRejectionReason() {
        for (Map<String, Object> source : List.of(row("MANUAL", "REJECTED", "FAILED"),
                row("HDPAY", "SUBMIT_UNKNOWN", "UNKNOWN"), row("HDPAY", "CREATED", "AWAITING_PAYMENT"))) {
            when(orders.listOrders(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(List.of(source));
            assertThat(record()).doesNotContainKey("providerReason");
        }
        verifyNoInteractions(audit, provider, treasury);
    }

    @Test
    void providerFactReadFailureKeepsTheCanonicalRowAndFinancialControls() {
        when(orders.listOrders(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(row("HDPAY", "REJECTED", "FAILED")));
        when(provider.findByMerchantOrderId("VQR-1")).thenThrow(new IllegalStateException("private read detail"));
        assertThat(record()).containsEntry("status", "FAILED").containsEntry("submissionStatus", "REJECTED")
                .containsEntry("manualCreditAllowed", false).containsEntry("paymentUrl", null)
                .doesNotContainKey("providerReason");
        verifyNoInteractions(audit, treasury);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> record() {
        return ((List<Map<String, Object>>) service.list(null, null, null, 1, 20).getData().get("records")).get(0);
    }

    private Map<String, Object> row(String rail, String submission, String status) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("intentNo", "VQR-1");
        row.put("paymentRail", rail);
        row.put("submissionStatus", submission);
        row.put("status", status);
        row.put("intentStatus", status);
        row.put("settlementTargetType", "WALLET_TOPUP");
        row.put("settlementStatus", "CREDITED".equals(status) ? "CREDITED" : "UNSETTLED");
        row.put("paymentUrl", "https://api.hdpayadmin.com/pay?id=1");
        return row;
    }

    private Map<String, Object> providerRow(String code) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("merchantOrderId", "VQR-1");
        row.put("submissionStatus", "REJECTED");
        row.put("lastErrorCode", code);
        row.put("createdAt", "2026-10-05T01:00:00");
        return row;
    }


    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void manualCreditCapturesBeforeWalletAndDoesNotSwallowCaptureFailure(boolean failCapture) {
        var intents = mock(AppVietQrIntentMapper.class);
        var payments = mock(VietnamPaymentMapper.class);
        var evidence = mock(VietQrReceiptEvidenceService.class);
        var idempotency = mock(AdminIdempotencyService.class);
        var outbox = mock(EventOutboxService.class);
        var now = java.time.LocalDateTime.of(2026, 10, 5, 2, 0);
        var clock = Clock.fixed(now.toInstant(java.time.ZoneOffset.UTC), java.time.ZoneOffset.UTC);
        var amount = new java.math.BigDecimal("5.000000");
        var payable = new java.math.BigDecimal("100000");
        when(provider.findByMerchantOrderIdForUpdate("VQR-MANUAL")).thenReturn(Map.of(
                "version", 3L, "settlementStatus", "UNSETTLED", "submissionStatus", "REJECTED", "amountVnd", payable));
        when(orders.lockBankReference("BANK-MANUAL-1")).thenReturn(List.of());
        when(orders.lockBankReceipts("VQR-MANUAL")).thenReturn(List.of());
        when(intents.findIntentForUpdate("VQR-MANUAL")).thenReturn(Map.of(
                "intentNo", "VQR-MANUAL", "userId", 42L, "version", 0L, "paymentRail", "HDPAY",
                "settlementTargetType", "WALLET_TOPUP", "status", "AWAITING_PAYMENT",
                "payableVnd", payable, "requestedUsdt", amount, "createdAt", now.minusHours(2)));
        when(orders.insertManualConfirmation(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(payments.findUsdtWalletForUpdate(42L)).thenReturn(Map.of(
                "usdtAvailable", new java.math.BigDecimal("10.000000"), "version", 7L));
        when(payments.creditUsdtWallet(42L, amount, 7L)).thenReturn(1);

        when(intents.transitionIntent(eq("VQR-MANUAL"), eq(0L), eq("AWAITING_PAYMENT"), eq("CREDITED"),
                eq(payable), eq(amount), eq(now))).thenReturn(1);
        when(provider.insertDepositNotification("HDPAY:VQR-MANUAL", 42L, amount)).thenReturn(1);
        when(orders.markManualCredited("VQR-MANUAL", 3L, amount, now)).thenReturn(1);
        when(idempotency.executeRetainedRepeatableRead(anyString(), anyString(), anyString(), eq(Map.class), any()))
                .thenAnswer(invocation -> ((java.util.function.Supplier<?>) invocation.getArgument(4)).get());
        var target = new D1BankOrderService(capture, orders, provider, intents, payments, evidence,
                treasury, idempotency, outbox, audit, new HdPayProperties(), new ObjectMapper(), clock);
        var request = new ffdd.opsconsole.finance.dto.HdPayManualCreditRequest(0L, 3L, payable,
                "BANK-MANUAL-1", now.minusHours(1).atOffset(java.time.ZoneOffset.UTC),
                "media:vqr_123e4567e89b12d3a456426614174000", "checked payment evidence", "finance-admin");
        if (failCapture) {
            org.mockito.Mockito.doThrow(new IllegalStateException("CAPTURE_WRITE_FAILED"))
                    .when(capture).record(any(Prepared.class));
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> target.manualCredit("VQR-MANUAL", "manual-1", request))
                    .hasMessage("CAPTURE_WRITE_FAILED");
            return;
        }
        assertThat(target.manualCredit("VQR-MANUAL", "manual-1", request).getData()).containsEntry("status", "CREDITED");
        org.mockito.Mockito.verify(payments,org.mockito.Mockito.never()).insertVietQrWalletLedger(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString());
        var sequence = org.mockito.Mockito.inOrder(capture, payments, orders);
        sequence.verify(capture).prepare(42L, Source.HDPAY, "VQR-MANUAL");
        sequence.verify(payments).findUsdtWalletForUpdate(42L);
        sequence.verify(capture).insertLedger(org.mockito.ArgumentMatchers.any(Prepared.class),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString());
        sequence.verify(orders).markManualCredited("VQR-MANUAL", 3L, amount, now);
        sequence.verify(capture).record(any(Prepared.class));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void canonicalLedgerZeroOrFailureStopsBeforeRecord(boolean thrown) {
        org.mockito.Mockito.when(capture.insertLedger(org.mockito.ArgumentMatchers.any(Prepared.class),
                org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> { if(thrown)throw new IllegalStateException("LEDGER_INSERT_FAILED"); return 0; });
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> manualCreditCapturesBeforeWalletAndDoesNotSwallowCaptureFailure(false))
                .hasMessage(thrown?"LEDGER_INSERT_FAILED":"HDPAY_LEDGER_WRITE_FAILED");
        org.mockito.Mockito.verify(capture,org.mockito.Mockito.never()).record(org.mockito.ArgumentMatchers.any());
    }

    private static SupportPaymentAttributionFacade paymentAttribution() {
        var capture = org.mockito.Mockito.mock(SupportPaymentAttributionFacade.class);
        org.mockito.Mockito.when(capture.prepare(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(org.mockito.Mockito.mock(
                        Prepared.class));
        org.mockito.Mockito.when(capture.insertLedger(org.mockito.ArgumentMatchers.any(Prepared.class),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(1);
        return capture;
    }
}
