package ffdd.opsconsole.commerce.application;

import ffdd.opsconsole.commerce.mapper.AppOrderCommandMapper;
import ffdd.opsconsole.commerce.mapper.CommerceAcceptanceSandboxMapper;
import ffdd.opsconsole.finance.application.FundsSandboxProfileGuard;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.canonical.mapper.CanonicalStateMapper;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.device.domain.ProductInventoryMode;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.time.Instant;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class AppOrderCommandService {
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
    private final AppOrderCommandMapper mapper;
    private final AdminIdempotencyService idempotency;
    private final AuditLogService audit;
    private final FundsSandboxProfileGuard sandboxGuard;
    private final CommerceAcceptanceSandboxMapper sandboxMapper;
    private final CommerceAcceptanceSandboxService sandboxService;
    private final CommerceAcceptanceRun acceptanceRun;
    private final EventOutboxService outbox;

    @Autowired
    public AppOrderCommandService(
            AppOrderCommandMapper mapper,
            AdminIdempotencyService idempotency,
            AuditLogService audit,
            FundsSandboxProfileGuard sandboxGuard,
            CommerceAcceptanceSandboxMapper sandboxMapper,
            CommerceAcceptanceSandboxService sandboxService,
            CommerceAcceptanceRun acceptanceRun,
            EventOutboxService outbox) {
        this.mapper = mapper;
        this.idempotency = idempotency;
        this.audit = audit;
        this.sandboxGuard = sandboxGuard;
        this.sandboxMapper = sandboxMapper;
        this.sandboxService = sandboxService;
        this.acceptanceRun = acceptanceRun;
        this.outbox = outbox;
    }

    AppOrderCommandService(
            AppOrderCommandMapper mapper,
            AdminIdempotencyService idempotency,
            AuditLogService audit,
            FundsSandboxProfileGuard sandboxGuard,
            CommerceAcceptanceSandboxMapper sandboxMapper,
            CommerceAcceptanceSandboxService sandboxService,
            CommerceAcceptanceRun acceptanceRun) {
        this(mapper, idempotency, audit, sandboxGuard, sandboxMapper, sandboxService, acceptanceRun, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> cancel(Long userId, String orderNo, String idempotencyKey) {
        if (userId == null || userId < 1 || !StringUtils.hasText(orderNo)) return ApiResult.fail(422, "ORDER_CANCEL_INPUT_INVALID");
        if (!StringUtils.hasText(idempotencyKey)) return ApiResult.fail(400, "IDEMPOTENCY_KEY_REQUIRED");
        String normalized = orderNo.trim();
        if (sandboxGuard.isStrictDevelopmentRuntime()) {
            Integer userEnvironment = mapper.activeUserEnvironment(userId);
            if (userEnvironment == null) return ApiResult.fail(404, "USER_NOT_FOUND");
            if (userEnvironment != 1) return ApiResult.fail(403, "CANONICAL_DEVELOPMENT_USER_REQUIRED");
            return execute("ORDER_CANCEL", userId, normalized, idempotencyKey,
                    () -> cancelProduction(userId, normalized));
        }
        if (sandboxGuard.isLocalSandboxEnabled()) return cancelSandbox(userId, normalized, idempotencyKey);
        if (!sandboxGuard.isStrictProductionRuntime()) {
            return ApiResult.fail(503, "COMMERCE_SANDBOX_UNAVAILABLE");
        }
        CanonicalStateMapper.UserLock user = mapper.lockUser(userId);
        if (user == null) return ApiResult.fail(404, "USER_NOT_FOUND");
        if (user.sandbox()) return ApiResult.fail(403, "COMMERCE_SANDBOX_USER_FORBIDDEN");
        return execute("ORDER_CANCEL", userId, normalized, idempotencyKey, () -> cancelProduction(userId, normalized));
    }

    /** User-confirmed checkout payment. Development is locally simulated; production remains provider fail-closed. */
    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> pay(Long userId, String orderNo, String idempotencyKey) {
        if (userId == null || userId < 1 || !StringUtils.hasText(orderNo)) {
            return ApiResult.fail(422, "ORDER_PAYMENT_INPUT_INVALID");
        }
        if (!StringUtils.hasText(idempotencyKey)) return ApiResult.fail(400, "IDEMPOTENCY_KEY_REQUIRED");
        String normalized = orderNo.trim();
        if (sandboxGuard.isStrictDevelopmentRuntime()) {
            Integer userEnvironment = mapper.activeUserEnvironment(userId);
            if (userEnvironment == null) return ApiResult.fail(404, "USER_NOT_FOUND");
            if (userEnvironment != 1) return ApiResult.fail(403, "CANONICAL_DEVELOPMENT_USER_REQUIRED");
            String paymentScope = "APP:ORDER_PAYMENT:DEVELOPMENT:USER:" + userId;
            String requestHash = sha256("DEVELOPMENT|" + userId + "|" + normalized);
            return (ApiResult<Map<String, Object>>) (ApiResult) idempotency.execute(
                    paymentScope, idempotencyKey, requestHash, ApiResult.class,
                    (Supplier) () -> payDevelopment(userId, normalized));
        }
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

    private ApiResult<Map<String, Object>> payDevelopment(Long userId, String orderNo) {
        AppOrderCommandMapper.DevelopmentPayOrder order = mapper.lockDevelopmentPayOrder(orderNo);
        if (order == null || !userId.equals(order.userId())) return ApiResult.fail(403, "ORDER_FORBIDDEN");
        if (order.amountUsdt() == null || order.amountUsdt().signum() <= 0
                || order.quantity() == null || order.quantity() < 1 || order.quantity() > 100) {
            return ApiResult.fail(409, "ORDER_PAYMENT_FACT_INVALID");
        }
        String paymentNo = "PAY-DEV-" + sha256(userId + "|" + orderNo)
                .substring(0, 32).toUpperCase(Locale.ROOT);
        AppOrderCommandMapper.DevelopmentWallet wallet = mapper.lockDevelopmentWallet(userId);
        if (wallet == null || wallet.usdtAvailable() == null || wallet.usdtAvailable().signum() < 0
                || wallet.version() == null || wallet.version() < 0) {
            return ApiResult.fail(409, "ORDER_WALLET_UNAVAILABLE");
        }
        if ("PAID".equalsIgnoreCase(order.paymentStatus())
                && "COMPLETED".equalsIgnoreCase(order.orderStatus())
                && "ACTIVATED".equalsIgnoreCase(order.activationStatus())) {
            if (!paymentNo.equals(order.paymentNo())) return ApiResult.fail(409, "ORDER_PAYMENT_STATE_CONFLICT");
            return developmentPaymentReceipt(order, paymentNo, wallet.usdtAvailable(), true);
        }
        if (!"PENDING".equalsIgnoreCase(order.paymentStatus())
                || !"PENDING_PAYMENT".equalsIgnoreCase(order.orderStatus())
                || !"WAITING_PAYMENT".equalsIgnoreCase(order.activationStatus())) {
            return ApiResult.fail(409, "ORDER_NOT_PAYABLE");
        }
        if (wallet.usdtAvailable().compareTo(order.amountUsdt()) < 0) {
            return ApiResult.fail(409, "ORDER_WALLET_INSUFFICIENT");
        }
        BigDecimal balanceAfter = wallet.usdtAvailable().subtract(order.amountUsdt());
        if (mapper.debitDevelopmentWallet(userId, order.amountUsdt(), wallet.version()) != 1) {
            throw new BizException(409, "ORDER_WALLET_CONFLICT");
        }
        if (mapper.insertDevelopmentPurchaseLedger(
                orderNo, userId, order.amountUsdt(), balanceAfter) != 1) {
            throw new BizException(409, "ORDER_WALLET_LEDGER_CONFLICT");
        }
        if (mapper.markDevelopmentOrderActivated(orderNo, userId, paymentNo) != 1) {
            throw new BizException(409, "ORDER_STATE_CONFLICT");
        }
        if (mapper.insertDevelopmentPayment(orderNo, userId, paymentNo, order.amountUsdt()) != 1) {
            throw new BizException(409, "PAYMENT_RECORD_CONFLICT");
        }
        for (int unitIndex = 0; unitIndex < order.quantity(); unitIndex++) {
            String instanceNo = "DEV-ORD-" + sha256(orderNo + "|" + unitIndex)
                    .substring(0, 24).toUpperCase(Locale.ROOT);
            if (mapper.insertDevelopmentDevice(orderNo, userId, instanceNo, unitIndex) != 1) {
                throw new BizException(409, "DEVICE_ACTIVATION_CONFLICT");
            }
            publishDevelopmentDeviceActivated(userId, instanceNo);
        }
        if (mapper.insertDevelopmentOrderHistory(
                orderNo, "placed", "activated", "DEV-PAY-" + paymentNo) != 1) {
            throw new BizException(409, "ORDER_HISTORY_CONFLICT");
        }
        audit.recordRequired(AuditLogWriteRequest.builder().action("APP_ORDER_DEVELOPMENT_SIMULATED_PAYMENT")
                .resourceType("ORDER").resourceId(orderNo).bizNo(orderNo).userId(userId).actorId(userId)
                .actorType("USER").method("POST").path("/api/orders/" + orderNo + "/pay")
                .result("SUCCESS").riskLevel("LOW")
                .detail(Map.of("paymentNo", paymentNo, "quantity", order.quantity(),
                        "amountUsdt", order.amountUsdt(), "walletBalanceAfterUsdt", balanceAfter,
                        "environment", "DEVELOPMENT", "simulated", true)).build());
        return developmentPaymentReceipt(order, paymentNo, balanceAfter, false);
    }

    private void publishDevelopmentDeviceActivated(Long userId, String instanceNo) {
        if (outbox == null) return;
        AppOrderCommandMapper.DevelopmentDeviceFact device = mapper.developmentDeviceFact(instanceNo);
        Map<String, Object> attribution = mapper.attribution(userId);
        if (device == null || device.deviceId() == null || !StringUtils.hasText(device.instanceNo())
                || attribution == null || attribution.get("accountAgeMonths") == null
                || !StringUtils.hasText(String.valueOf(attribution.get("cohort")))) {
            throw new BizException(409, "DEVICE_ACTIVATION_EVENT_ATTRIBUTION_UNAVAILABLE");
        }
        String phase = normalizePhase(attribution.get("phase"));
        int accountAgeMonths = Integer.parseInt(String.valueOf(attribution.get("accountAgeMonths")));
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("deviceId", device.deviceId());
        detail.put("instanceNo", device.instanceNo());
        detail.put("beforeStatus", "PROVISIONING");
        detail.put("afterStatus", "ACTIVE");
        detail.put("mode", "DEVELOPMENT_SIMULATED");
        detail.put("operator", "system:development-checkout");
        detail.put("reason", "Development simulated checkout activated device");
        detail.put("ts", Instant.now().toString());
        outbox.publishUserEvent("DEVICE", String.valueOf(device.deviceId()), "admin.device_activated",
                userId, phase, accountAgeMonths, String.valueOf(attribution.get("cohort")), detail);
    }

    private String normalizePhase(Object raw) {
        String phase = raw == null ? "P1" : String.valueOf(raw).trim().toUpperCase(Locale.ROOT);
        if (phase.matches("[1-6]")) phase = "P" + phase;
        return phase.matches("P[1-6]") ? phase : "P1";
    }

    private ApiResult<Map<String, Object>> developmentPaymentReceipt(
            AppOrderCommandMapper.DevelopmentPayOrder order, String paymentNo,
            BigDecimal walletBalanceAfterUsdt, boolean idempotent) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderNo", order.orderNo());
        data.put("paymentNo", paymentNo);
        data.put("paymentStatus", "PAID");
        data.put("orderStatus", "COMPLETED");
        data.put("activationStatus", "ACTIVATED");
        data.put("canonicalStatus", "activated");
        data.put("amountUsdt", order.amountUsdt());
        data.put("walletBalanceAfterUsdt", walletBalanceAfterUsdt);
        data.put("idempotent", idempotent);
        data.put("source", "mock");
        data.put("sourceEnvironment", "SANDBOX");
        data.put("runId", "local-dev");
        data.put("serverCanonical", true);
        return ApiResult.ok(data);
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
            if (product == null || ProductInventoryMode.parse(product.inventoryMode()) == null
                    || product.soldCount() == null || product.soldCount() < item.quantity()
                    || (!ProductInventoryMode.isUnlimited(product.inventoryMode())
                        && (product.stock() == null || product.stock() > Integer.MAX_VALUE - item.quantity()))) {
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
