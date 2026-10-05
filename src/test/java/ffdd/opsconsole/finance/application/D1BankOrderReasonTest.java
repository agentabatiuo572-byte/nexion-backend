package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
    private final D1BankOrderMapper orders = mock(D1BankOrderMapper.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final HdPayOrderMapper provider = mock(HdPayOrderMapper.class);
    private final TreasuryLedgerRepository treasury = mock(TreasuryLedgerRepository.class);
    private final D1BankOrderService service = new D1BankOrderService(orders, provider,
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
}
