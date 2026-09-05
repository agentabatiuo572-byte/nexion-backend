package ffdd.opsconsole.finance.hdpay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.finance.mapper.AppVietQrIntentMapper;
import ffdd.opsconsole.finance.mapper.VietnamPaymentMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HdPayCallbackCommerceRoutingTest {
    @Test
    void legacyCommerceCallbackIsQuarantinedWithoutWalletOrOrderMutation() {
        var hdPayMapper = mock(HdPayOrderMapper.class);
        var intentMapper = mock(AppVietQrIntentMapper.class);
        var paymentMapper = mock(VietnamPaymentMapper.class);
        var outbox = mock(EventOutboxService.class);
        var audit = mock(AuditLogService.class);
        var clock = Clock.fixed(Instant.parse("2026-09-02T04:00:00Z"), ZoneOffset.UTC);
        var service = new HdPayCallbackSettlementService(
                hdPayMapper, intentMapper, paymentMapper, outbox, audit, clock);
        var order = new LinkedHashMap<String, Object>();
        order.put("amountVnd", new BigDecimal("26000"));
        order.put("submissionStatus", "CREATED");
        order.put("settlementStatus", "UNSETTLED");
        order.put("providerOrderId", "");
        when(hdPayMapper.findByMerchantOrderIdForUpdate("VQR-COMMERCE-1")).thenReturn(order);
        when(hdPayMapper.insertCallbackInbox(anyString(), eq("VQR-COMMERCE-1"), eq("PROVIDER-1"),
                eq(3), eq(new BigDecimal("26000")), eq("PROCESSING"), anyString())).thenReturn(1);
        when(hdPayMapper.updateCallbackObservation("VQR-COMMERCE-1", "PROVIDER-1", 3)).thenReturn(1);
        var intent = new LinkedHashMap<String, Object>();
        intent.put("status", "AWAITING_PAYMENT");
        intent.put("expiresAt", LocalDateTime.of(2026, 9, 2, 5, 0));
        intent.put("payableVnd", new BigDecimal("26000"));
        intent.put("requestedUsdt", new BigDecimal("1.000000"));
        intent.put("userId", 7L);
        intent.put("version", 4L);
        intent.put("settlementTargetType", "COMMERCE_ORDER");
        intent.put("targetOrderNo", "ORD-1");
        when(intentMapper.findIntentForUpdate("VQR-COMMERCE-1")).thenReturn(intent);
        when(hdPayMapper.markSettlementReview(
                "VQR-COMMERCE-1", "PROVIDER-1", 3,
                "HDPAY_COMMERCE_DIRECT_PAYMENT_RETIRED")).thenReturn(1);
        when(hdPayMapper.insertSettlementReview(
                anyString(), eq("VQR-COMMERCE-1"), eq("PROVIDER-1"),
                eq("HDPAY_COMMERCE_DIRECT_PAYMENT_RETIRED"))).thenReturn(1);
        when(hdPayMapper.markCallbackProcessedOwned(
                anyString(), anyString(), eq("MANUAL_REVIEW"), eq(3),
                eq("HDPAY_COMMERCE_DIRECT_PAYMENT_RETIRED"))).thenReturn(1);
        var callback = new HdPayCallbackVerifier.VerifiedCallback(
                "VQR-COMMERCE-1", "PROVIDER-1", 3, new BigDecimal("26000"),
                "2026-09-02 11:59:00", "2026-09-02 12:00:00", "abc");
        var confirmed = new HdPayGateway.PayOrder(
                "VQR-COMMERCE-1", "PROVIDER-1", 3,
                new BigDecimal("26000"), "BANKQR", "");

        var claim = service.claimForProviderQuery(callback);
        when(hdPayMapper.findCallbackInboxForUpdate(anyString())).thenReturn(Map.of(
                "processingStatus", "PROCESSING", "claimToken", claim.claimToken()));
        assertThat(service.settleConfirmed(claim.fact(), claim.claimToken(), confirmed))
                .isEqualTo("success");

        verifyNoInteractions(paymentMapper, outbox);
        verify(hdPayMapper).markSettlementReview(
                "VQR-COMMERCE-1", "PROVIDER-1", 3,
                "HDPAY_COMMERCE_DIRECT_PAYMENT_RETIRED");
    }
}
