package ffdd.opsconsole.commerce.application;

import ffdd.opsconsole.commerce.mapper.AppOrderCommandMapper;
import ffdd.opsconsole.commerce.mapper.CommerceAcceptanceSandboxMapper;
import ffdd.opsconsole.finance.application.FundsSandboxProfileGuard;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.canonical.mapper.CanonicalStateMapper;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class AppOrderCommandService {
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
    private final AppOrderCommandMapper mapper;
    private final AdminIdempotencyService idempotency;
    private final AuditLogService audit;
    private final FundsSandboxProfileGuard sandboxGuard;
    private final CommerceAcceptanceSandboxMapper sandboxMapper;
    private final CommerceAcceptanceSandboxService sandboxService;
    private final CommerceAcceptanceRun acceptanceRun;

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> cancel(Long userId, String orderNo, String idempotencyKey) {
        if (userId == null || userId < 1 || !StringUtils.hasText(orderNo)) return ApiResult.fail(422, "ORDER_CANCEL_INPUT_INVALID");
        if (!StringUtils.hasText(idempotencyKey)) return ApiResult.fail(400, "IDEMPOTENCY_KEY_REQUIRED");
        String normalized = orderNo.trim();
        if (sandboxGuard.isLocalSandboxEnabled()) return cancelSandbox(userId, normalized, idempotencyKey);
        if (!sandboxGuard.isStrictProductionRuntime()) {
            return ApiResult.fail(503, "COMMERCE_SANDBOX_UNAVAILABLE");
        }
        CanonicalStateMapper.UserLock user = mapper.lockUser(userId);
        if (user == null) return ApiResult.fail(404, "USER_NOT_FOUND");
        if (user.sandbox()) return ApiResult.fail(403, "COMMERCE_SANDBOX_USER_FORBIDDEN");
        return execute("ORDER_CANCEL", userId, normalized, idempotencyKey, () -> cancelProduction(userId, normalized));
    }

    /**
     * User-confirmed checkout payment.  Only the explicitly enabled local
     * sandbox has a local success rail; remote/production deliberately stays
     * fail-closed until a real provider adapter is wired.
     */
    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> pay(Long userId, String orderNo, String idempotencyKey) {
        if (userId == null || userId < 1 || !StringUtils.hasText(orderNo)) {
            return ApiResult.fail(422, "ORDER_PAYMENT_INPUT_INVALID");
        }
        if (!StringUtils.hasText(idempotencyKey)) return ApiResult.fail(400, "IDEMPOTENCY_KEY_REQUIRED");
        String normalized = orderNo.trim();
        if (!sandboxGuard.isLocalSandboxEnabled()) {
            // No production order/wallet read is allowed on the provider HOLD path.
            return ApiResult.fail(409, "PAYMENT_PROVIDER_UNAVAILABLE");
        }
        if (sandboxMapper == null || sandboxService == null || acceptanceRun == null
                || !sandboxMapper.isSandboxUser(userId)) {
            return ApiResult.fail(403, "COMMERCE_SANDBOX_USER_REQUIRED");
        }
        // Resolve the immutable acceptance fence before entering durable
        // idempotency. Otherwise the same user/order/key can replay a Run-A
        // response when the backend is restarted with Run-B.
        String runId = acceptanceRun.requireRunId();
        String paymentScope = "APP:ORDER_PAYMENT:SANDBOX:" + runId + ":USER:" + userId;
        String paymentRequestHash = sha256("SANDBOX|" + runId + "|" + userId + "|" + normalized);
        return (ApiResult<Map<String, Object>>) (ApiResult) idempotency.execute(
                paymentScope, idempotencyKey, paymentRequestHash, ApiResult.class,
                (Supplier) () -> paySandbox(userId, normalized, runId));
    }

    private ApiResult<Map<String, Object>> paySandbox(Long userId, String orderNo, String runId) {
        var order = sandboxMapper.lockSandboxOrder(runId, orderNo);
        if (order == null || !userId.equals(order.userId())) return ApiResult.fail(403, "ORDER_FORBIDDEN");
        // Legacy/partially migrated sandbox rows must never reach the callback
        // CAS with a null version. Fail closed as a business-unavailable result
        // instead of auto-unboxing into an HTTP 500.
        Long orderVersion = order.version();
        if (orderVersion == null || orderVersion < 0) {
            return ApiResult.fail(503, "COMMERCE_SANDBOX_ORDER_UNAVAILABLE");
        }
        if (!"PENDING_PAYMENT".equalsIgnoreCase(order.state())
                && !"PAID".equalsIgnoreCase(order.state())) {
            return ApiResult.fail(409, "ORDER_NOT_PAYABLE");
        }
        String paymentNo = "PAY-SBX-" + sha256(runId + "|" + userId + "|" + orderNo)
                .substring(0, 32).toUpperCase(Locale.ROOT);
        // The event id is payment identity, not a client-generated order id.
        // It is stable across retries and scoped to this acceptance run/account.
        long expectedVersion = "PAID".equalsIgnoreCase(order.state()) && orderVersion > 0
                ? orderVersion - 1 : orderVersion;
        var result = sandboxService.applyCallback(orderNo, paymentNo, "PAYMENT_SUCCEEDED", expectedVersion,
                "user payment confirmation", String.valueOf(userId));
        return ApiResult.ok(Map.of(
                "orderNo", result.orderNo(), "paymentNo", paymentNo,
                "paymentStatus", "PAID", "orderStatus", "PAID", "canonicalStatus", result.canonicalStatus(),
                "amountUsdt", order.amountUsdt(), "source", "mock", "sourceEnvironment", "SANDBOX", "runId", runId));
    }

    private ApiResult<Map<String, Object>> cancelProduction(Long userId, String orderNo) {
        AppOrderCommandMapper.OrderRow order = mapper.lockOrder(orderNo);
        if (order == null || !userId.equals(order.userId())) return ApiResult.fail(403, "ORDER_FORBIDDEN");
        if ("CANCELLED".equalsIgnoreCase(order.orderStatus()) && "CANCELLED".equalsIgnoreCase(order.paymentStatus())) {
            return ApiResult.ok(Map.of("orderNo", orderNo, "orderStatus", "CANCELLED", "paymentStatus", "CANCELLED",
                    "idempotent", true, "serverCanonical", true, "source", "server",
                    "sourceEnvironment", "PRODUCTION", "runId", ""));
        }
        if (!"PENDING_PAYMENT".equalsIgnoreCase(order.orderStatus()) || !"PENDING".equalsIgnoreCase(order.paymentStatus())) {
            return ApiResult.fail(409, "ORDER_NOT_CANCELLABLE");
        }
        List<AppOrderCommandMapper.ItemRow> items = mapper.lockItems(orderNo);
        List<AppOrderCommandMapper.ItemRow> effective = items == null ? List.of() : items;
        String orderType = StringUtils.hasText(order.orderType()) ? order.orderType().trim().toUpperCase(Locale.ROOT) : "SINGLE";
        if ("BUNDLE".equals(orderType)) {
            validateBundleSnapshot(order, effective);
        } else if ("SINGLE".equals(orderType)) {
            effective = validateSingleSnapshot(orderNo, order, effective);
        } else {
            throw new BizException(409, "ORDER_ITEM_SNAPSHOT_CONFLICT");
        }
        for (AppOrderCommandMapper.ItemRow item : effective) {
            AppOrderCommandMapper.ProductRow product = mapper.lockProduct(item.productId());
            if (product == null || product.stock() == null || product.soldCount() == null
                    || product.stock() > Integer.MAX_VALUE - item.quantity() || product.soldCount() < item.quantity()) {
                throw new BizException(409, "ORDER_STOCK_RETURN_CONFLICT");
            }
            if (mapper.returnStock(item.productId(), item.quantity()) != 1) throw new BizException(409, "ORDER_STOCK_RETURN_CONFLICT");
        }
        if (mapper.cancelOrder(orderNo, userId) != 1) throw new BizException(409, "ORDER_STATE_CONFLICT");
        audit.recordRequired(AuditLogWriteRequest.builder().action("APP_ORDER_CANCELLED").resourceType("ORDER")
                .resourceId(orderNo).bizNo(orderNo).userId(userId).actorId(userId).actorType("USER")
                .method("POST").path("/api/orders/" + orderNo + "/cancel").result("SUCCESS").riskLevel("LOW")
                .detail(Map.of("orderType", order.orderType(), "environment", "PRODUCTION")).build());
        return ApiResult.ok(Map.of("orderNo", orderNo, "orderStatus", "CANCELLED", "paymentStatus", "CANCELLED",
                "idempotent", false, "serverCanonical", true, "source", "server",
                "sourceEnvironment", "PRODUCTION", "runId", ""));
    }

    private ApiResult<Map<String, Object>> cancelSandbox(Long userId, String orderNo, String key) {
        if (sandboxMapper == null || sandboxService == null || acceptanceRun == null || !sandboxMapper.isSandboxUser(userId)) {
            return ApiResult.fail(403, "COMMERCE_SANDBOX_USER_REQUIRED");
        }
        String runId = acceptanceRun.requireRunId();
        String normalizedKey = key.trim();
        if (normalizedKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            return ApiResult.fail(422, "IDEMPOTENCY_KEY_INVALID");
        }
        var order = sandboxMapper.lockSandboxOrder(runId, orderNo);
        if (order == null || !userId.equals(order.userId())) return ApiResult.fail(403, "ORDER_FORBIDDEN");
        if ("CANCELLED".equalsIgnoreCase(order.state())) return ApiResult.ok(Map.of("orderNo", orderNo, "orderStatus", "CANCELLED", "paymentStatus", "CANCELLED", "serverCanonical", true, "source", "mock", "sourceEnvironment", "SANDBOX", "runId", runId, "idempotent", true));
        if (!"PENDING_PAYMENT".equalsIgnoreCase(order.state())) return ApiResult.fail(409, "ORDER_NOT_CANCELLABLE");
        // The callback inbox is the durable replay authority. Scope the event to
        // the complete request identity so a key cannot collide across orders,
        // users, or acceptance runs; the callback service serializes contenders
        // on the sandbox order/version and replays the frozen first result.
        String eventId = "USER-CANCEL-" + sha256(runId + "|" + userId + "|" + orderNo + "|" + normalizedKey)
                .substring(0, 32).toUpperCase(Locale.ROOT);
        var result = sandboxService.applyCallback(orderNo, eventId, "USER_CANCELLED", order.version(), "user order cancellation", String.valueOf(userId));
        return ApiResult.ok(Map.of("orderNo", result.orderNo(), "orderStatus", "CANCELLED", "paymentStatus", "CANCELLED", "serverCanonical", true, "source", "mock", "sourceEnvironment", "SANDBOX", "runId", runId, "idempotent", false));
    }

    private List<AppOrderCommandMapper.ItemRow> validateSingleSnapshot(
            String orderNo, AppOrderCommandMapper.OrderRow order, List<AppOrderCommandMapper.ItemRow> items) {
        if (order.productId() == null || order.quantity() == null || order.quantity() < 1
                || order.itemCount() != null && !order.itemCount().equals(order.quantity())) {
            throw new BizException(409, "ORDER_ITEM_SNAPSHOT_CONFLICT");
        }
        if (items.isEmpty()) {
            return List.of(new AppOrderCommandMapper.ItemRow(orderNo, order.productId(), null, order.quantity()));
        }
        if (items.size() != 1) throw new BizException(409, "ORDER_ITEM_SNAPSHOT_CONFLICT");
        AppOrderCommandMapper.ItemRow item = items.get(0);
        if (item.productId() == null || !order.productId().equals(item.productId())
                || item.quantity() == null || !order.quantity().equals(item.quantity())) {
            throw new BizException(409, "ORDER_ITEM_SNAPSHOT_CONFLICT");
        }
        return items;
    }

    private void validateBundleSnapshot(AppOrderCommandMapper.OrderRow order,
                                        List<AppOrderCommandMapper.ItemRow> items) {
        if (order.itemCount() == null || order.itemCount() < 2 || order.quantity() == null
                || order.quantity() != order.itemCount() || items.size() != order.itemCount()
                || order.productId() == null || items.isEmpty()) {
            throw new BizException(409, "ORDER_ITEM_SNAPSHOT_CONFLICT");
        }
        java.util.Set<Long> productIds = new java.util.HashSet<>();
        int quantity = 0;
        for (int index = 0; index < items.size(); index++) {
            AppOrderCommandMapper.ItemRow item = items.get(index);
            if (item.productId() == null || item.quantity() == null || item.quantity() != 1
                    || !StringUtils.hasText(item.productNo()) || !productIds.add(item.productId())
                    || index == 0 && !order.productId().equals(item.productId())) {
                throw new BizException(409, "ORDER_ITEM_SNAPSHOT_CONFLICT");
            }
            quantity += item.quantity();
        }
        if (quantity != order.quantity()) throw new BizException(409, "ORDER_ITEM_SNAPSHOT_CONFLICT");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ApiResult<Map<String, Object>> execute(String operation, Long userId, String orderNo, String key,
                                                    Supplier<ApiResult<Map<String, Object>>> action) {
        return (ApiResult<Map<String, Object>>) (ApiResult) idempotency.execute("APP:" + operation + ":USER:" + userId,
                key, sha256(userId + "|" + orderNo), ApiResult.class, (Supplier) action);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte b : digest) result.append(String.format("%02x", b));
            return result.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
