package ffdd.commerce.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.commerce.domain.CommerceOrder;
import ffdd.commerce.domain.PaymentRecord;
import ffdd.commerce.dto.PaymentCheckoutRequest;
import ffdd.commerce.dto.PaymentCheckoutResponse;
import ffdd.commerce.dto.PaymentRecordQueryRequest;
import ffdd.commerce.service.CommerceService;
import ffdd.commerce.service.PaymentService;
import ffdd.common.api.PageResult;
import ffdd.common.audit.AuditLogService;
import ffdd.common.exception.BizException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class PaymentControllerTest {
    private final CommerceService commerceService = mock(CommerceService.class);
    private final PaymentService paymentService = mock(PaymentService.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final PaymentController controller = new PaymentController(commerceService, paymentService, auditLogService);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void userCheckoutRejectsAnotherUsersOrder() {
        asUser(10082L);
        when(commerceService.getOrder("ORD-1")).thenReturn(order("ORD-1", 10001L));

        PaymentCheckoutRequest request = new PaymentCheckoutRequest();
        request.setOrderNo("ORD-1");

        assertThatThrownBy(() -> controller.checkout(request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Order does not belong to authenticated user");
    }

    @Test
    void userPaymentPageForcesAuthenticatedUserId() {
        asUser(10082L);
        when(paymentService.pageRecords(any(PaymentRecordQueryRequest.class)))
                .thenReturn(new PageResult<>(0, 1, 10, List.of()));

        PaymentRecordQueryRequest request = new PaymentRecordQueryRequest();
        request.setUserId(10001L);
        controller.page(request);

        ArgumentCaptor<PaymentRecordQueryRequest> captor = ArgumentCaptor.forClass(PaymentRecordQueryRequest.class);
        verify(paymentService).pageRecords(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(10082L);
    }

    @Test
    void userPaymentDetailRejectsAnotherUsersPayment() {
        asUser(10082L);
        PaymentRecord record = new PaymentRecord();
        record.setPaymentNo("PAY-1");
        record.setUserId(10001L);
        when(paymentService.getRecord("PAY-1")).thenReturn(record);

        assertThatThrownBy(() -> controller.detail("PAY-1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Payment does not belong to authenticated user");
    }

    @Test
    void userCheckoutAllowsOwnedOrder() {
        asUser(10082L);
        when(commerceService.getOrder("ORD-1")).thenReturn(order("ORD-1", 10082L));
        PaymentCheckoutResponse response = new PaymentCheckoutResponse();
        response.setOrderNo("ORD-1");
        response.setPaymentNo("PAY-1");
        response.setProvider("MOCK");
        response.setPaymentStatus("PENDING");
        response.setAmountUsdt(new BigDecimal("899.00"));
        response.setCurrency("USDT");
        when(paymentService.checkout(any(PaymentCheckoutRequest.class))).thenReturn(response);

        PaymentCheckoutRequest request = new PaymentCheckoutRequest();
        request.setOrderNo("ORD-1");

        assertThat(controller.checkout(request).getData().getPaymentNo()).isEqualTo("PAY-1");
    }

    private void asUser(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                String.valueOf(userId),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    private CommerceOrder order(String orderNo, Long userId) {
        CommerceOrder order = new CommerceOrder();
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setAmountUsdt(new BigDecimal("899.00"));
        return order;
    }
}
