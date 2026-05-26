package ffdd.commerce.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.commerce.domain.CommerceOrder;
import ffdd.commerce.domain.PaymentCallbackEvent;
import ffdd.commerce.domain.PaymentRecord;
import ffdd.commerce.dto.PaymentCallbackResponse;
import ffdd.commerce.dto.PaymentCheckoutRequest;
import ffdd.commerce.dto.PaymentCheckoutResponse;
import ffdd.commerce.mapper.PaymentCallbackEventMapper;
import ffdd.commerce.mapper.PaymentRecordMapper;
import ffdd.common.exception.BizException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PaymentServiceTest {
    private static final String SECRET = "mock-payment-test-secret";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-26T00:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private final PaymentRecordMapper recordMapper = mock(PaymentRecordMapper.class);
    private final PaymentCallbackEventMapper eventMapper = mock(PaymentCallbackEventMapper.class);
    private final CommerceService commerceService = mock(CommerceService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PaymentService service = new PaymentService(
            recordMapper,
            eventMapper,
            commerceService,
            List.of(new MockPaymentProvider(objectMapper, SECRET, "https://mock-pay.nexion.local/checkout", 15, 300, CLOCK)),
            CLOCK);

    @Test
    void checkoutCreatesMockPaymentSessionForPendingOrder() {
        when(commerceService.getOrder("ORD-1")).thenReturn(order("ORD-1", "PENDING", "100.000000"));
        when(recordMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        PaymentCheckoutRequest request = new PaymentCheckoutRequest();
        request.setOrderNo("ORD-1");
        request.setProvider("mock");

        PaymentCheckoutResponse response = service.checkout(request);

        ArgumentCaptor<PaymentRecord> captor = ArgumentCaptor.forClass(PaymentRecord.class);
        verify(recordMapper).insert(captor.capture());
        PaymentRecord inserted = captor.getValue();
        assertThat(inserted.getPaymentNo()).startsWith("PAY-20260526080000-");
        assertThat(inserted.getOrderNo()).isEqualTo("ORD-1");
        assertThat(inserted.getProvider()).isEqualTo("MOCK");
        assertThat(inserted.getProviderPaymentId()).startsWith("MOCK-PAY-");
        assertThat(inserted.getPaymentStatus()).isEqualTo("PENDING");
        assertThat(inserted.getAmountUsdt()).isEqualByComparingTo("100.000000");
        assertThat(response.getPaymentNo()).isEqualTo(inserted.getPaymentNo());
        assertThat(response.getCheckoutUrl()).contains("paymentNo=" + inserted.getPaymentNo());
        assertThat(response.getExpiresAt()).isEqualTo(Instant.parse("2026-05-26T00:15:00Z").atZone(ZoneId.of("Asia/Shanghai")).toLocalDateTime());
    }

    @Test
    void callbackRejectsInvalidSignatureBeforeDatabaseMutation() {
        String rawBody = successPayload("evt-bad", "PAY-1", "MOCK-PAY-1", "ORD-1", "100.000000");
        Map<String, String> headers = signedHeaders("MOCK", rawBody, "invalid-secret");

        assertThatThrownBy(() -> service.handleCallback("mock", headers, rawBody))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Invalid payment callback signature");

        verifyNoInteractions(recordMapper, eventMapper, commerceService);
    }

    @Test
    void callbackMarksOrderPaidOnceAndTreatsRepeatedEventAsDuplicate() {
        PaymentRecord pendingRecord = paymentRecord("PAY-1", "MOCK-PAY-1", "ORD-1", "PENDING", "100.000000");
        CommerceOrder paidOrder = order("ORD-1", "PAID", "100.000000");
        paidOrder.setPaymentNo("PAY-1");
        PaymentCallbackEvent processedEvent = callbackEvent("evt-1", "PAY-1", "ORD-1", "SUCCESS");
        String rawBody = successPayload("evt-1", "PAY-1", "MOCK-PAY-1", "ORD-1", "100.000000");
        Map<String, String> headers = signedHeaders("MOCK", rawBody, SECRET);

        when(eventMapper.selectOne(any(Wrapper.class))).thenReturn(null, processedEvent);
        when(recordMapper.selectOne(any(Wrapper.class))).thenReturn(pendingRecord);
        when(commerceService.markPaid(eq("ORD-1"), any())).thenReturn(paidOrder);

        PaymentCallbackResponse first = service.handleCallback("mock", headers, rawBody);
        PaymentCallbackResponse second = service.handleCallback("mock", headers, rawBody);

        assertThat(first.isDuplicate()).isFalse();
        assertThat(first.getPaymentStatus()).isEqualTo("PAID");
        assertThat(second.isDuplicate()).isTrue();
        assertThat(second.getProviderEventId()).isEqualTo("evt-1");
        verify(commerceService).markPaid(eq("ORD-1"), any());
        verify(recordMapper).updateById(any(PaymentRecord.class));
    }

    @Test
    void callbackRejectsAmountMismatchAndMarksEventFailed() {
        PaymentRecord pendingRecord = paymentRecord("PAY-1", "MOCK-PAY-1", "ORD-1", "PENDING", "100.000000");
        String rawBody = successPayload("evt-2", "PAY-1", "MOCK-PAY-1", "ORD-1", "99.000000");
        Map<String, String> headers = signedHeaders("MOCK", rawBody, SECRET);

        when(eventMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(recordMapper.selectOne(any(Wrapper.class))).thenReturn(pendingRecord);

        assertThatThrownBy(() -> service.handleCallback("mock", headers, rawBody))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("amount mismatch");

        ArgumentCaptor<PaymentCallbackEvent> insertedCaptor = ArgumentCaptor.forClass(PaymentCallbackEvent.class);
        ArgumentCaptor<PaymentCallbackEvent> updatedCaptor = ArgumentCaptor.forClass(PaymentCallbackEvent.class);
        verify(eventMapper).insert(insertedCaptor.capture());
        verify(eventMapper).updateById(updatedCaptor.capture());
        assertThat(insertedCaptor.getValue().getProviderEventId()).isEqualTo("evt-2");
        assertThat(updatedCaptor.getValue().getProcessingStatus()).isEqualTo("FAILED");
        assertThat(updatedCaptor.getValue().getFailureReason()).contains("amount mismatch");
        verify(commerceService, never()).markPaid(any(), any());
    }

    private CommerceOrder order(String orderNo, String paymentStatus, String amount) {
        CommerceOrder order = new CommerceOrder();
        order.setId(10L);
        order.setUserId(9001L);
        order.setOrderNo(orderNo);
        order.setProductId(1L);
        order.setQuantity(1);
        order.setAmountUsdt(new BigDecimal(amount));
        order.setPaymentStatus(paymentStatus);
        order.setOrderStatus(paymentStatus);
        order.setActivationStatus("WAITING_PAYMENT");
        order.setIsDeleted(0);
        return order;
    }

    private PaymentRecord paymentRecord(
            String paymentNo, String providerPaymentId, String orderNo, String paymentStatus, String amount) {
        PaymentRecord record = new PaymentRecord();
        record.setId(20L);
        record.setPaymentNo(paymentNo);
        record.setProvider("MOCK");
        record.setProviderPaymentId(providerPaymentId);
        record.setOrderNo(orderNo);
        record.setUserId(9001L);
        record.setAmountUsdt(new BigDecimal(amount));
        record.setCurrency("USDT");
        record.setPaymentStatus(paymentStatus);
        record.setIsDeleted(0);
        return record;
    }

    private PaymentCallbackEvent callbackEvent(String eventId, String paymentNo, String orderNo, String status) {
        PaymentCallbackEvent event = new PaymentCallbackEvent();
        event.setId(30L);
        event.setProvider("MOCK");
        event.setProviderEventId(eventId);
        event.setPaymentNo(paymentNo);
        event.setOrderNo(orderNo);
        event.setEventStatus("PAID");
        event.setProcessingStatus(status);
        event.setIsDeleted(0);
        return event;
    }

    private String successPayload(
            String eventId, String paymentNo, String providerPaymentId, String orderNo, String amount) {
        return "{\"eventId\":\"" + eventId
                + "\",\"paymentNo\":\"" + paymentNo
                + "\",\"providerPaymentId\":\"" + providerPaymentId
                + "\",\"orderNo\":\"" + orderNo
                + "\",\"status\":\"PAID\",\"amountUsdt\":" + amount
                + ",\"currency\":\"USDT\"}";
    }

    private Map<String, String> signedHeaders(String provider, String rawBody, String secret) {
        String timestamp = String.valueOf(CLOCK.instant().getEpochSecond());
        String nonce = "nonce-1";
        String stringToSign = provider + "\n" + timestamp + "\n" + nonce + "\n" + sha256(rawBody);
        return Map.of(
                "X-Nexion-Payment-Timestamp", timestamp,
                "X-Nexion-Payment-Nonce", nonce,
                "X-Nexion-Payment-Signature", hmac(secret, stringToSign));
    }

    private String hmac(String secret, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
