package ffdd.commerce.controller;

import ffdd.commerce.domain.CommerceOrder;
import ffdd.commerce.dto.OrderCreateRequest;
import ffdd.commerce.dto.OrderPayRequest;
import ffdd.commerce.dto.OrderQueryRequest;
import ffdd.commerce.service.CommerceService;
import ffdd.common.api.ApiResult;
import ffdd.common.api.PageResult;
import ffdd.common.audit.AuditLogService;
import ffdd.common.audit.AuditLogWriteRequest;
import ffdd.common.exception.BizException;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/commerce/orders")
public class OrderController {
    private final CommerceService commerceService;
    private final AuditLogService auditLogService;

    public OrderController(CommerceService commerceService, AuditLogService auditLogService) {
        this.commerceService = commerceService;
        this.auditLogService = auditLogService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_COMMERCE_WRITE') or hasAuthority('ROLE_USER')")
    public ApiResult<CommerceOrder> create(@Valid @RequestBody OrderCreateRequest request) {
        Long roleUserId = currentRoleUserId();
        if (roleUserId != null) {
            request.setUserId(roleUserId);
        }
        CommerceOrder order = commerceService.createOrder(request);
        auditOrder("ORDER_CREATE", order, detail(
                "productId", order.getProductId(),
                "quantity", order.getQuantity(),
                "amountUsdt", order.getAmountUsdt()));
        return ApiResult.ok(order);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_COMMERCE_READ') or hasAuthority('ROLE_USER')")
    public ApiResult<PageResult<CommerceOrder>> page(OrderQueryRequest request) {
        Long roleUserId = currentRoleUserId();
        if (roleUserId != null) {
            request.setUserId(roleUserId);
        }
        return ApiResult.ok(commerceService.pageOrders(request));
    }

    @GetMapping("/{orderNo}")
    @PreAuthorize("hasAuthority('PERM_COMMERCE_READ') or hasAuthority('ROLE_USER')")
    public ApiResult<CommerceOrder> detail(@PathVariable String orderNo) {
        CommerceOrder order = commerceService.getOrder(orderNo);
        assertVisibleToCurrentUser(order);
        return ApiResult.ok(order);
    }

    @PutMapping("/{orderNo}/paid")
    @PreAuthorize("hasAuthority('PERM_COMMERCE_WRITE')")
    public ApiResult<CommerceOrder> markPaid(@PathVariable String orderNo, @Valid @RequestBody OrderPayRequest request) {
        CommerceOrder order = commerceService.markPaid(orderNo, request);
        auditOrder("ORDER_MARK_PAID", order, detail(
                "paymentNo", order.getPaymentNo(),
                "paymentStatus", order.getPaymentStatus(),
                "activationStatus", order.getActivationStatus()));
        return ApiResult.ok(order);
    }

    @PutMapping("/{orderNo}/activate")
    @PreAuthorize("hasAuthority('PERM_COMMERCE_WRITE')")
    public ApiResult<CommerceOrder> activate(@PathVariable String orderNo) {
        CommerceOrder order = commerceService.activatePaidOrder(orderNo);
        auditOrder("ORDER_ACTIVATE", order, detail("activationStatus", order.getActivationStatus()));
        return ApiResult.ok(order);
    }

    private void assertVisibleToCurrentUser(CommerceOrder order) {
        Long roleUserId = currentRoleUserId();
        if (roleUserId != null && !roleUserId.equals(order.getUserId())) {
            throw new BizException("Order does not belong to authenticated user");
        }
    }

    private Long currentRoleUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication.getAuthorities().stream().noneMatch(a -> "ROLE_USER".equals(a.getAuthority()))) {
            return null;
        }
        String subject = String.valueOf(authentication.getPrincipal());
        if (!StringUtils.hasText(subject)) {
            return null;
        }
        try {
            return Long.valueOf(subject);
        } catch (NumberFormatException ignored) {
            throw new BizException("Authenticated user id is invalid");
        }
    }

    private void auditOrder(String action, CommerceOrder order, Map<String, Object> detail) {
        auditLogService.record(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType("ORDER")
                .resourceId(order.getOrderNo())
                .bizNo(order.getOrderNo())
                .userId(order.getUserId())
                .riskLevel("HIGH")
                .detail(detail)
                .build());
    }

    private Map<String, Object> detail(Object... pairs) {
        Map<String, Object> detail = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            Object value = pairs[i + 1];
            if (value != null) {
                detail.put(String.valueOf(pairs[i]), value);
            }
        }
        return detail;
    }
}
