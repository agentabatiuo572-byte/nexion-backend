package ffdd.commerce.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import ffdd.commerce.domain.CommerceOrder;
import ffdd.commerce.domain.PaymentRecord;
import ffdd.commerce.dto.PaymentAnomalyResponse;
import ffdd.commerce.dto.PaymentOpsResult;
import ffdd.commerce.dto.PaymentReconcileResponse;
import ffdd.commerce.mapper.PaymentCallbackEventMapper;
import ffdd.commerce.mapper.PaymentRecordMapper;
import ffdd.common.exception.BizException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PaymentOpsServiceTest {
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-05-26T00:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);

    private final PaymentRecordMapper recordMapper = mock(PaymentRecordMapper.class);
    private final PaymentCallbackEventMapper eventMapper = mock(PaymentCallbackEventMapper.class);
    private final CommerceService commerceService = mock(CommerceService.class);
    private final StubPaymentProvider provider = new StubPaymentProvider();
    private final PaymentService service =
            new PaymentService(recordMapper, eventMapper, commerceService, List.of(provider), CLOCK);

    @Test
    void expirePendingPaymentsMarksDueRowsExpiredIdempotently() {
        PaymentRecord due = paymentRecord("PAY-DUE", "ORD-DUE", "PENDING");
        due.setExpiresAt(NOW.minusMinutes(1));
        when(recordMapper.selectList(any(Wrapper.class))).thenReturn(List.of(due));

        PaymentOpsResult result = service.expirePendingPayments(20);

        ArgumentCaptor<PaymentRecord> captor = ArgumentCaptor.forClass(PaymentRecord.class);
        verify(recordMapper).updateById(captor.capture());
        PaymentRecord patch = captor.getValue();
        assertThat(patch.getId()).isEqualTo(due.getId());
        assertThat(patch.getPaymentStatus()).isEqualTo("EXPIRED");
        assertThat(patch.getExpiredAt()).isEqualTo(NOW);
        assertThat(patch.getFailedAt()).isEqualTo(NOW);
        assertThat(patch.getFailureReason()).isEqualTo("Payment session expired");
        assertThat(result.getScanned()).isEqualTo(1);
        assertThat(result.getExpired()).isEqualTo(1);
        assertThat(result.getSkipped()).isZero();
    }

    @Test
    void reconcilePaidProviderStatusMarksOrderPaidOnce() {
        PaymentRecord record = paymentRecord("PAY-1", "ORD-1", "PENDING");
        provider.status = new PaymentProviderStatus(
                "MOCK-PAY-1", "PAID", new BigDecimal("100.000000"), "USDT", "provider-event-1", null);
        CommerceOrder paidOrder = order("ORD-1", "PAID");
        paidOrder.setPaymentNo("PAY-1");
        when(recordMapper.selectOne(any(Wrapper.class))).thenReturn(record);
        when(commerceService.markPaid(eq("ORD-1"), any())).thenReturn(paidOrder);

        PaymentReconcileResponse response = service.reconcilePayment("PAY-1");

        ArgumentCaptor<PaymentRecord> captor = ArgumentCaptor.forClass(PaymentRecord.class);
        verify(recordMapper).updateById(captor.capture());
        PaymentRecord patch = captor.getValue();
        assertThat(patch.getPaymentStatus()).isEqualTo("PAID");
        assertThat(patch.getPaidAt()).isEqualTo(NOW);
        assertThat(patch.getCallbackEventId()).isEqualTo("provider-event-1");
        assertThat(patch.getReconcileAttempts()).isEqualTo(3);
        assertThat(patch.getLastReconcileAt()).isEqualTo(NOW);
        assertThat(patch.getLastReconcileError()).isNull();
        assertThat(response.isChanged()).isTrue();
        assertThat(response.getPaymentStatus()).isEqualTo("PAID");
        assertThat(response.getOrderPaymentStatus()).isEqualTo("PAID");
        verify(commerceService).markPaid(eq("ORD-1"), any());
    }

    @Test
    void reconcileFailedProviderStatusMarksPaymentFailedWithoutOrderMutation() {
        PaymentRecord record = paymentRecord("PAY-2", "ORD-2", "PENDING");
        provider.status = new PaymentProviderStatus(
                "MOCK-PAY-2", "FAILED", new BigDecimal("100.000000"), "USDT", "provider-event-2", "card declined");
        when(recordMapper.selectOne(any(Wrapper.class))).thenReturn(record);

        PaymentReconcileResponse response = service.reconcilePayment("PAY-2");

        ArgumentCaptor<PaymentRecord> captor = ArgumentCaptor.forClass(PaymentRecord.class);
        verify(recordMapper).updateById(captor.capture());
        PaymentRecord patch = captor.getValue();
        assertThat(patch.getPaymentStatus()).isEqualTo("FAILED");
        assertThat(patch.getFailedAt()).isEqualTo(NOW);
        assertThat(patch.getFailureReason()).isEqualTo("card declined");
        assertThat(response.isChanged()).isTrue();
        assertThat(response.getPaymentStatus()).isEqualTo("FAILED");
        verify(commerceService, never()).markPaid(any(), any());
    }

    @Test
    void reconcileProviderErrorRecordsRetryMetadata() {
        PaymentRecord record = paymentRecord("PAY-3", "ORD-3", "PENDING");
        provider.failure = new BizException("provider unavailable");
        when(recordMapper.selectOne(any(Wrapper.class))).thenReturn(record);

        assertThatThrownBy(() -> service.reconcilePayment("PAY-3"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("provider unavailable");

        ArgumentCaptor<PaymentRecord> captor = ArgumentCaptor.forClass(PaymentRecord.class);
        verify(recordMapper).updateById(captor.capture());
        PaymentRecord patch = captor.getValue();
        assertThat(patch.getReconcileAttempts()).isEqualTo(3);
        assertThat(patch.getLastReconcileAt()).isEqualTo(NOW);
        assertThat(patch.getNextReconcileAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(patch.getLastReconcileError()).isEqualTo("provider unavailable");
        verify(commerceService, never()).markPaid(any(), any());
    }

    @Test
    void listPaymentAnomaliesComparesRecordAndOrderStates() {
        PaymentRecord paidRecord = paymentRecord("PAY-PAID", "ORD-UNPAID", "PAID");
        PaymentRecord pendingRecord = paymentRecord("PAY-PENDING", "ORD-PAID", "PENDING");
        PaymentRecord expiredPending = paymentRecord("PAY-OLD", "ORD-OLD", "PENDING");
        expiredPending.setExpiresAt(NOW.minusMinutes(5));
        when(recordMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(paidRecord, pendingRecord, expiredPending));
        when(commerceService.getOrder("ORD-UNPAID")).thenReturn(order("ORD-UNPAID", "PENDING"));
        when(commerceService.getOrder("ORD-PAID")).thenReturn(order("ORD-PAID", "PAID"));
        when(commerceService.getOrder("ORD-OLD")).thenReturn(order("ORD-OLD", "PENDING"));

        List<PaymentAnomalyResponse> anomalies = service.listPaymentAnomalies(20);

        assertThat(anomalies)
                .extracting(PaymentAnomalyResponse::getAnomalyType)
                .containsExactly(
                        "PAYMENT_PAID_ORDER_NOT_PAID",
                        "ORDER_PAID_PAYMENT_NOT_PAID",
                        "PENDING_PAYMENT_EXPIRED");
    }

    private PaymentRecord paymentRecord(String paymentNo, String orderNo, String status) {
        PaymentRecord record = new PaymentRecord();
        record.setId(20L);
        record.setPaymentNo(paymentNo);
        record.setProvider("MOCK");
        record.setProviderPaymentId("MOCK-" + paymentNo);
        record.setOrderNo(orderNo);
        record.setUserId(9001L);
        record.setAmountUsdt(new BigDecimal("100.000000"));
        record.setCurrency("USDT");
        record.setPaymentStatus(status);
        record.setReconcileAttempts(2);
        record.setIsDeleted(0);
        return record;
    }

    private CommerceOrder order(String orderNo, String paymentStatus) {
        CommerceOrder order = new CommerceOrder();
        order.setId(10L);
        order.setUserId(9001L);
        order.setOrderNo(orderNo);
        order.setAmountUsdt(new BigDecimal("100.000000"));
        order.setPaymentStatus(paymentStatus);
        order.setOrderStatus(paymentStatus);
        order.setActivationStatus("ACTIVATED");
        order.setIsDeleted(0);
        return order;
    }

    private static class StubPaymentProvider implements PaymentProvider {
        private PaymentProviderStatus status;
        private RuntimeException failure;

        @Override
        public String code() {
            return "MOCK";
        }

        @Override
        public PaymentSession createSession(CommerceOrder order, String paymentNo, ffdd.commerce.dto.PaymentCheckoutRequest request) {
            return new PaymentSession("MOCK-" + paymentNo, "https://mock-pay/" + paymentNo, NOW.plusMinutes(15));
        }

        @Override
        public PaymentProviderCallback parseAndVerifyCallback(Map<String, String> headers, String rawBody) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public PaymentProviderStatus queryPayment(PaymentRecord record) {
            if (failure != null) {
                throw failure;
            }
            return status;
        }
    }
}
