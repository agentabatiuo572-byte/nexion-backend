package ffdd.opsconsole.commerce.application;

import ffdd.opsconsole.commerce.mapper.AppOrderCommandMapper;
import ffdd.opsconsole.commerce.mapper.CommerceAcceptanceSandboxMapper;
import ffdd.opsconsole.finance.application.FundsSandboxProfileGuard;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.canonical.mapper.CanonicalStateMapper;
import ffdd.opsconsole.emergency.domain.KillSwitchState;
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
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class AppOrderCommandService {
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
    private static final String MAINTENANCE_KILLSWITCH_KEY = "killswitch.maintenance";
    private static final String MAINTENANCE_LEGACY_KILLSWITCH_KEY = "emergency.killswitch.maintenance";
    private final AppOrderCommandMapper mapper;
    private final AdminIdempotencyService idempotency;
    private final AuditLogService audit;
    private final FundsSandboxProfileGuard sandboxGuard;
    private final CommerceAcceptanceSandboxMapper sandboxMapper;
    private final CommerceAcceptanceSandboxService sandboxService;
    private final CommerceAcceptanceRun acceptanceRun;
    private final EventOutboxService outbox;
    @SuppressWarnings("ArchitectureConfigField") // Explicit constructor parameter carries the @Value binding.
    private final int pendingOrderTtlMinutes;

    @Autowired
    public AppOrderCommandService(
            AppOrderCommandMapper mapper,
            AdminIdempotencyService idempotency,
            AuditLogService audit,
            FundsSandboxProfileGuard sandboxGuard,
            CommerceAcceptanceSandboxMapper sandboxMapper,
            CommerceAcceptanceSandboxService sandboxService,
            CommerceAcceptanceRun acceptanceRun,
            EventOutboxService outbox,
            @Value("${nexion.commerce.pending-order-ttl-minutes:30}") int pendingOrderTtlMinutes) {
        this.mapper = mapper;
        this.idempotency = idempotency;
        this.audit = audit;
        this.sandboxGuard = sandboxGuard;
        this.sandboxMapper = sandboxMapper;
        this.sandboxService = sandboxService;
        this.acceptanceRun = acceptanceRun;
        this.outbox = outbox;
        this.pendingOrderTtlMinutes = Math.max(1, pendingOrderTtlMinutes);
    }

    AppOrderCommandService(
            AppOrderCommandMapper mapper,
            AdminIdempotencyService idempotency,
            AuditLogService audit,
            FundsSandboxProfileGuard sandboxGuard,
            CommerceAcceptanceSandboxMapper sandboxMapper,
            CommerceAcceptanceSandboxService sandboxService,
            CommerceAcceptanceRun acceptanceRun,
            EventOutboxService outbox) {
        this(mapper, idempotency, audit, sandboxGuard, sandboxMapper, sandboxService,
                acceptanceRun, outbox, 30);
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
        if (sandboxGuard.isLocalSandboxEnabled()) return cancelSandbox(userId, normalized, idempotencyKey);
        if (!sandboxGuard.isStrictProductionRuntime()) {
            return ApiResult.fail(503, "COMMERCE_SANDBOX_UNAVAILABLE");
        }
        CanonicalStateMapper.UserLock user = mapper.lockUser(userId);
        if (user == null) return ApiResult.fail(404, "USER_NOT_FOUND");
        if (user.sandbox()) return ApiResult.fail(403, "COMMERCE_SANDBOX_USER_FORBIDDEN");
        return execute("ORDER_CANCEL", userId, normalized, idempotencyKey, () -> cancelProduction(userId, normalized));
    }

    /** User-confirmed checkout payment. Commerce settles only from the canonical NexGrid wallet. */
    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> pay(Long userId, String orderNo, String idempotencyKey) {
        if (userId == null || userId < 1 || !StringUtils.hasText(orderNo)) {
            return ApiResult.fail(422, "ORDER_PAYMENT_INPUT_INVALID");
        }
        if (!StringUtils.hasText(idempotencyKey)) return ApiResult.fail(400, "IDEMPOTENCY_KEY_REQUIRED");
        String normalized = orderNo.trim();
        if (sandboxGuard.isStrictDevelopmentRuntime() || sandboxGuard.isStrictProductionRuntime()) {
            if (!Integer.valueOf(0).equals(mapper.activeUserEnvironment(userId))) {
                return ApiResult.fail(403, "COMMERCE_PRODUCTION_USER_REQUIRED");
            }
            return execute("ORDER_PAYMENT", userId, normalized, idempotencyKey,
                    () -> payFromWallet(userId, normalized));
        }
        if (!sandboxGuard.isLocalSandboxEnabled()) {
            return ApiResult.fail(409, "COMMERCE_PAYMENT_RUNTIME_UNAVAILABLE");
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

    private ApiResult<Map<String, Object>> payFromWallet(Long userId, String orderNo) {
        AppOrderCommandMapper.DevelopmentPayOrder order = mapper.lockDevelopmentPayOrder(orderNo);
        if (order == null || !userId.equals(order.userId())) return ApiResult.fail(403, "ORDER_FORBIDDEN");
        if (order.amountUsdt() == null || order.amountUsdt().signum() < 0
                || order.quantity() == null || order.quantity() < 1 || order.quantity() > 100) {
            return ApiResult.fail(409, "ORDER_PAYMENT_FACT_INVALID");
        }
        boolean fullyDiscounted = order.amountUsdt().signum() == 0;
        if (fullyDiscounted) {
            List<AppOrderCommandMapper.VoucherGrantRow> usedVouchers =
                    mapper.lockUsedVouchersForOrder(userId, orderNo);
            if (usedVouchers == null || usedVouchers.size() != 1
                    || usedVouchers.get(0) == null
                    || !StringUtils.hasText(usedVouchers.get(0).grantId())) {
                return ApiResult.fail(409, "ORDER_VOUCHER_SETTLEMENT_INVALID");
            }
        }
        String paymentRail = fullyDiscounted ? "VOUCHER" : "NEXGRID_WALLET";
        String paymentNo = (fullyDiscounted ? "PAY-VOUCHER-" : "PAY-WALLET-") + sha256(userId + "|" + orderNo)
                .substring(0, 32).toUpperCase(Locale.ROOT);
        AppOrderCommandMapper.DevelopmentWallet wallet = null;
        if ("PAID".equalsIgnoreCase(order.paymentStatus())
                && "COMPLETED".equalsIgnoreCase(order.orderStatus())
                && "ACTIVATED".equalsIgnoreCase(order.activationStatus())) {
            if (!paymentNo.equals(order.paymentNo())) return ApiResult.fail(409, "ORDER_PAYMENT_STATE_CONFLICT");
            if (!fullyDiscounted) {
                wallet = mapper.lockDevelopmentWallet(userId);
                if (!validWallet(wallet)) return ApiResult.fail(409, "ORDER_WALLET_UNAVAILABLE");
            }
            return developmentPaymentReceipt(order, paymentNo,
                    wallet == null ? null : wallet.usdtAvailable(), paymentRail, true);
        }
        // A wallet-paid order remains replayable even when a stale legacy HDPay
        // session is discovered later. Pending orders with such a session are
        // quarantined to avoid a wallet debit racing a provider callback.
        if (mapper.countNonCancellableHdPaySessions(orderNo) > 0) {
            return ApiResult.fail(409, "HDPAY_COMMERCE_PAYMENT_REVIEW_REQUIRED");
        }
        if (!"PENDING".equalsIgnoreCase(order.paymentStatus())
                || !"PENDING_PAYMENT".equalsIgnoreCase(order.orderStatus())
                || !"WAITING_PAYMENT".equalsIgnoreCase(order.activationStatus())) {
            return ApiResult.fail(409, "ORDER_NOT_PAYABLE");
        }
        if (mapper.countExpiredPayableOrder(orderNo, userId, pendingOrderTtlMinutes) > 0) {
            return ApiResult.fail(409, "ORDER_PAYMENT_EXPIRED");
        }
        // A pending order is only a price/stock reservation, never an authority
        // to bypass the current E1 sale state or a platform-wide maintenance stop.
        // Re-read this immediately before the wallet CAS so an old checkout page
        // cannot settle an item that operations has just unlisted.
        if (commercePaymentEmergencyStopped()) {
            return ApiResult.fail(409, "COMMERCE_PAYMENT_EMERGENCY_STOPPED");
        }
        // Lock the E1 product root and its SKU extension in the same order used by
        // E1 writes. The following predicate and wallet debit then share one atomic
        // sale-state window instead of racing an operator unlist/spec change.
        mapper.lockOrderProductsForPayment(orderNo);
        mapper.lockOrderSkusForPayment(orderNo);
        if (mapper.hasNonPayableOrderProduct(orderNo)) {
            return ApiResult.fail(409, "ORDER_PRODUCT_NOT_PAYABLE");
        }
        String orderType = StringUtils.hasText(order.orderType())
                ? order.orderType().trim().toUpperCase(Locale.ROOT) : "SINGLE";
        List<AppOrderCommandMapper.DevelopmentPaymentItem> paymentItems = List.of();
        if ("BUNDLE".equals(orderType)) {
            paymentItems = mapper.lockDevelopmentPaymentItems(orderNo);
            if (order.itemCount() == null || order.itemCount() < 2 || paymentItems == null
                    || paymentItems.size() != order.itemCount()) {
                return ApiResult.fail(409, "ORDER_PAYMENT_FACT_INVALID");
            }
            java.util.HashSet<Long> productIds = new java.util.HashSet<>();
            int units = 0;
            for (AppOrderCommandMapper.DevelopmentPaymentItem item : paymentItems) {
                if (item == null || item.productId() == null || item.productId() < 1
                        || !StringUtils.hasText(item.productNo()) || item.quantity() == null
                        || item.quantity() < 1 || item.quantity() > 100 || item.sortOrder() == null
                        || item.sortOrder() < 0 || !productIds.add(item.productId())) {
                    return ApiResult.fail(409, "ORDER_PAYMENT_FACT_INVALID");
                }
                units += item.quantity();
            }
            if (units < 2 || units > 100 || units != order.quantity()) {
                return ApiResult.fail(409, "ORDER_PAYMENT_FACT_INVALID");
            }
        } else if (!"SINGLE".equals(orderType) || order.productId() == null || order.productId() < 1) {
            return ApiResult.fail(409, "ORDER_PAYMENT_FACT_INVALID");
        }
        // Keep the cross-flow lock order aligned with trade-in settlement:
        // product/SKU first, wallet second. Otherwise payment and trade-in for
        // the same product and wallet can deadlock each other.
        if (!fullyDiscounted) {
            wallet = mapper.lockDevelopmentWallet(userId);
            if (!validWallet(wallet)) return ApiResult.fail(409, "ORDER_WALLET_UNAVAILABLE");
        }
        if (!fullyDiscounted && wallet.usdtAvailable().compareTo(order.amountUsdt()) < 0) {
            // Insufficient balance is recoverable by topping up. Throw so the
            // idempotency record becomes FAILED and the same payment intent can
            // be reclaimed safely after the wallet balance changes.
            throw new BizException(409, "ORDER_WALLET_INSUFFICIENT");
        }
        BigDecimal balanceAfter = fullyDiscounted ? null : wallet.usdtAvailable().subtract(order.amountUsdt());
        if (!fullyDiscounted) {
            if (mapper.debitDevelopmentWallet(userId, order.amountUsdt(), wallet.version()) != 1) {
                throw new BizException(409, "ORDER_WALLET_CONFLICT");
            }
            if (mapper.insertDevelopmentPurchaseLedger(
                    orderNo, userId, order.amountUsdt(), balanceAfter) != 1) {
                throw new BizException(409, "ORDER_WALLET_LEDGER_CONFLICT");
            }
        }
        if (mapper.markDevelopmentOrderActivated(orderNo, userId, paymentNo) != 1) {
            throw new BizException(409, "ORDER_STATE_CONFLICT");
        }
        if (mapper.insertDevelopmentPayment(orderNo, userId, paymentNo, order.amountUsdt()) != 1) {
            throw new BizException(409, "PAYMENT_RECORD_CONFLICT");
        }
        if ("BUNDLE".equals(orderType)) {
            for (AppOrderCommandMapper.DevelopmentPaymentItem item : paymentItems) {
                for (int unitIndex = 0; unitIndex < item.quantity(); unitIndex++) {
                    String instanceNo = "NEX-ORD-" + sha256(orderNo + "|" + item.sortOrder()
                            + "|" + item.productId() + "|" + unitIndex)
                            .substring(0, 24).toUpperCase(Locale.ROOT);
                    if (mapper.insertWalletDevice(
                            orderNo, userId, item.productId(), instanceNo, unitIndex) != 1) {
                        throw new BizException(409, "DEVICE_ACTIVATION_CONFLICT");
                    }
                    publishDevelopmentDeviceActivated(userId, instanceNo, paymentRail);
                }
            }
        } else {
            for (int unitIndex = 0; unitIndex < order.quantity(); unitIndex++) {
                String instanceNo = "NEX-ORD-" + sha256(orderNo + "|" + unitIndex)
                        .substring(0, 24).toUpperCase(Locale.ROOT);
                if (mapper.insertDevelopmentDevice(orderNo, userId, instanceNo, unitIndex) != 1) {
                    throw new BizException(409, "DEVICE_ACTIVATION_CONFLICT");
                }
                publishDevelopmentDeviceActivated(userId, instanceNo, paymentRail);
            }
        }
        if (mapper.insertDevelopmentOrderHistory(
                orderNo, "placed", "activated", paymentRail + "-PAY-" + paymentNo) != 1) {
            throw new BizException(409, "ORDER_HISTORY_CONFLICT");
        }
        publishDevelopmentCheckoutCompleted(userId, order);
        Map<String, Object> auditDetail = new LinkedHashMap<>();
        auditDetail.put("paymentNo", paymentNo);
        auditDetail.put("quantity", order.quantity());
        auditDetail.put("amountUsdt", order.amountUsdt());
        if (balanceAfter != null) auditDetail.put("walletBalanceAfterUsdt", balanceAfter);
        auditDetail.put("paymentRail", paymentRail);
        auditDetail.put("canonical", true);
        audit.recordRequired(AuditLogWriteRequest.builder()
                .action(fullyDiscounted ? "APP_ORDER_VOUCHER_SETTLEMENT" : "APP_ORDER_WALLET_PAYMENT")
                .resourceType("ORDER").resourceId(orderNo).bizNo(orderNo).userId(userId).actorId(userId)
                .actorType("USER").method("POST").path("/api/orders/" + orderNo + "/pay")
                .result("SUCCESS").riskLevel("LOW")
                .detail(auditDetail).build());
        return developmentPaymentReceipt(order, paymentNo, balanceAfter, paymentRail, false);
    }

    private boolean validWallet(AppOrderCommandMapper.DevelopmentWallet wallet) {
        return wallet != null && wallet.usdtAvailable() != null && wallet.usdtAvailable().signum() >= 0
                && wallet.version() != null && wallet.version() >= 0;
    }

    private void publishDevelopmentCheckoutCompleted(
            Long userId, AppOrderCommandMapper.DevelopmentPayOrder order) {
        if (outbox == null) return;
        Map<String, Object> attribution = mapper.attribution(userId);
        if (attribution == null || attribution.get("accountAgeMonths") == null
                || !StringUtils.hasText(String.valueOf(attribution.get("cohort")))) {
            throw new BizException(409, "CHECKOUT_COMPLETED_EVENT_ATTRIBUTION_UNAVAILABLE");
        }
        String phase = normalizePhase(attribution.get("phase"));
        int accountAgeMonths = Integer.parseInt(String.valueOf(attribution.get("accountAgeMonths")));
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("order_id", order.orderNo());
        detail.put("order_no", order.orderNo());
        detail.put("order_subtotal_usdt", order.amountUsdt());
        detail.put("amount_usdt", order.amountUsdt());
        outbox.publishUserEvent("ORDER", order.orderNo(), "checkout.completed",
                userId, phase, accountAgeMonths, String.valueOf(attribution.get("cohort")), detail);
    }

    private void publishDevelopmentDeviceActivated(Long userId, String instanceNo, String paymentRail) {
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
        detail.put("mode", paymentRail);
        detail.put("operator", "system:wallet-checkout");
        detail.put("reason", "NexGrid wallet checkout activated device");
        detail.put("ts", Instant.now().toString());
        outbox.publishUserEvent("DEVICE", String.valueOf(device.deviceId()), "admin.device_activated",
                userId, phase, accountAgeMonths, String.valueOf(attribution.get("cohort")), detail);
    }

    private String normalizePhase(Object raw) {
        String phase = raw == null ? "P1" : String.valueOf(raw).trim().toUpperCase(Locale.ROOT);
        if (phase.matches("[1-6]")) phase = "P" + phase;
        return phase.matches("P[1-6]") ? phase : "P1";
    }

    private boolean commercePaymentEmergencyStopped() {
        return KillSwitchState.enabled(
                Optional.ofNullable(mapper.emergencyValue(MAINTENANCE_KILLSWITCH_KEY)),
                Optional.ofNullable(mapper.emergencyValue(MAINTENANCE_LEGACY_KILLSWITCH_KEY)));
    }

    private ApiResult<Map<String, Object>> developmentPaymentReceipt(
            AppOrderCommandMapper.DevelopmentPayOrder order, String paymentNo,
            BigDecimal walletBalanceAfterUsdt, String paymentMethod, boolean idempotent) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderNo", order.orderNo());
        data.put("paymentNo", paymentNo);
        data.put("paymentStatus", "PAID");
        data.put("orderStatus", "COMPLETED");
        data.put("activationStatus", "ACTIVATED");
        data.put("canonicalStatus", "activated");
        data.put("amountUsdt", order.amountUsdt());
        data.put("walletBalanceAfterUsdt", walletBalanceAfterUsdt);
        data.put("paymentMethod", paymentMethod);
        data.put("idempotent", idempotent);
        data.put("source", "server");
        data.put("sourceEnvironment", "PRODUCTION");
        data.put("runId", "");
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
        if (mapper.countNonCancellableHdPaySessions(orderNo) > 0) {
            return ApiResult.fail(409, "HDPAY_ORDER_NOT_CANCELLABLE");
        }
        releasePendingReservation(order, userId, orderNo, false);
        audit.recordRequired(AuditLogWriteRequest.builder().action("APP_ORDER_CANCELLED").resourceType("ORDER")
                .resourceId(orderNo).bizNo(orderNo).userId(userId).actorId(userId).actorType("USER")
                .method("POST").path("/api/orders/" + orderNo + "/cancel").result("SUCCESS").riskLevel("LOW")
                .detail(Map.of("orderType", order.orderType(), "environment", "PRODUCTION")).build());
        return ApiResult.ok(Map.of("orderNo", orderNo, "orderStatus", "CANCELLED", "paymentStatus", "CANCELLED",
                "idempotent", false, "serverCanonical", true, "source", "server",
                "sourceEnvironment", "PRODUCTION", "runId", ""));
    }

    /** Scheduler entrypoint. Each invocation owns a transaction and rechecks all payment/state fences. */
    @Transactional(rollbackFor = Exception.class)
    public boolean expirePendingOrder(Long userId, String orderNo) {
        if (userId == null || userId < 1 || !StringUtils.hasText(orderNo)) return false;
        String normalized = orderNo.trim();
        AppOrderCommandMapper.OrderRow order = mapper.lockOrder(normalized);
        if (order == null || !userId.equals(order.userId())
                || !"PENDING_PAYMENT".equalsIgnoreCase(order.orderStatus())
                || !"PENDING".equalsIgnoreCase(order.paymentStatus())
                || mapper.countNonCancellableHdPaySessions(normalized) > 0) {
            return false;
        }
        releasePendingReservation(order, userId, normalized, true);
        String orderType = StringUtils.hasText(order.orderType()) ? order.orderType() : "SINGLE";
        audit.recordRequired(AuditLogWriteRequest.builder().action("APP_ORDER_EXPIRED").resourceType("ORDER")
                .resourceId(normalized).bizNo(normalized).userId(userId).actorId(0L).actorType("SYSTEM")
                .method("SCHEDULED").path("commerce.pending-order-expiry").result("SUCCESS").riskLevel("LOW")
                .detail(Map.of("orderType", orderType, "environment", "PRODUCTION")).build());
        return true;
    }

    private void releasePendingReservation(
            AppOrderCommandMapper.OrderRow order, Long userId, String orderNo, boolean expired) {
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
            if (Boolean.TRUE.equals(item.quotaReserved())) {
                AppOrderCommandMapper.QuotaState quota = mapper.lockLifetimeQuotaState(item.productNo());
                // A gate edit/reset/delete starts a new generation.  An old reservation
                // must never decrement that new configuration; it is nevertheless safe
                // to complete the cancellation and restore inventory.
                if (item.quotaGateGeneration() != null && quota != null
                        && item.quotaGateGeneration().equals(quota.quotaGateGeneration())) {
                    if (quota.quotaSold() == null || quota.quotaSold() < item.quantity()
                            || mapper.releaseLifetimeQuota(item.productNo(), item.quantity(),
                            item.quotaGateGeneration()) != 1) {
                        throw new BizException(409, "ORDER_PURCHASE_QUOTA_RETURN_CONFLICT");
                    }
                }
            }
        }
        List<AppOrderCommandMapper.VoucherGrantRow> usedVouchers =
                mapper.lockUsedVouchersForOrder(userId, orderNo);
        if (usedVouchers != null && usedVouchers.size() > 1) {
            throw new BizException(409, "ORDER_VOUCHER_SNAPSHOT_CONFLICT");
        }
        int terminalRows = expired
                ? mapper.expireOrder(orderNo, userId)
                : mapper.cancelOrder(orderNo, userId);
        if (terminalRows != 1) throw new BizException(409, "ORDER_STATE_CONFLICT");
        if (usedVouchers != null && usedVouchers.size() == 1
                && mapper.restoreVoucher(usedVouchers.get(0).grantId(), userId, orderNo) != 1) {
            throw new BizException(409, "ORDER_VOUCHER_RETURN_CONFLICT");
        }
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
