package ffdd.commerce.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.commerce.domain.CommerceOrder;
import ffdd.commerce.dto.OrderCreateRequest;
import ffdd.commerce.dto.OrderQueryRequest;
import ffdd.commerce.service.CommerceService;
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

class OrderControllerTest {
    private final CommerceService commerceService = mock(CommerceService.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final OrderController controller = new OrderController(commerceService, auditLogService);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void userCreateForcesAuthenticatedUserId() {
        asUser(10082L);
        when(commerceService.createOrder(any(OrderCreateRequest.class))).thenReturn(order("ORD-1", 10082L));

        OrderCreateRequest request = new OrderCreateRequest();
        request.setProductId(1L);
        controller.create(request);

        ArgumentCaptor<OrderCreateRequest> captor = ArgumentCaptor.forClass(OrderCreateRequest.class);
        verify(commerceService).createOrder(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(10082L);
    }

    @Test
    void userPageForcesAuthenticatedUserId() {
        asUser(10082L);
        when(commerceService.pageOrders(any(OrderQueryRequest.class)))
                .thenReturn(new PageResult<>(0, 1, 10, List.of()));

        OrderQueryRequest request = new OrderQueryRequest();
        request.setUserId(10001L);
        controller.page(request);

        ArgumentCaptor<OrderQueryRequest> captor = ArgumentCaptor.forClass(OrderQueryRequest.class);
        verify(commerceService).pageOrders(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(10082L);
    }

    @Test
    void userDetailRejectsAnotherUsersOrder() {
        asUser(10082L);
        when(commerceService.getOrder("ORD-1")).thenReturn(order("ORD-1", 10001L));

        assertThatThrownBy(() -> controller.detail("ORD-1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Order does not belong to authenticated user");
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
        order.setProductId(1L);
        order.setQuantity(1);
        order.setAmountUsdt(new BigDecimal("899.00"));
        return order;
    }
}
