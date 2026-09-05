package ffdd.opsconsole.shared.canonical;

import ffdd.opsconsole.commerce.application.CommerceAcceptanceRun;
import ffdd.opsconsole.commerce.mapper.CommerceAcceptanceSandboxMapper;
import ffdd.opsconsole.finance.application.FundsSandboxProfileGuard;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.canonical.mapper.AppBundleOrderMapper;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.device.domain.ProductInventoryMode;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class AppBundleOrderService {
    private static final int MIN_ITEMS = 2;
    private static final int MAX_ITEMS = 8;
    private final AppBundleOrderMapper mapper;
    private final AdminIdempotencyService idempotency;
    private final EventOutboxService outbox;
    private final FundsSandboxProfileGuard profileGuard;
    private final StorefrontPurchaseGatePolicy purchaseGatePolicy;
    private final StorefrontProductReleasePolicy releasePolicy;
    private final CommerceAcceptanceSandboxMapper sandboxMapper;
    private final CommerceAcceptanceRun acceptanceRun;
    private final PlatformConfigFacade bundleConfig;

    @Autowired
    public AppBundleOrderService(
            AppBundleOrderMapper mapper,
            AdminIdempotencyService idempotency,
            EventOutboxService outbox,
            FundsSandboxProfileGuard profileGuard,
            StorefrontPurchaseGatePolicy purchaseGatePolicy,
            StorefrontProductReleasePolicy releasePolicy,
            CommerceAcceptanceSandboxMapper sandboxMapper,
            CommerceAcceptanceRun acceptanceRun,
            PlatformConfigFacade bundleConfig) {
        this.mapper = mapper;
        this.idempotency = idempotency;
        this.outbox = outbox;
        this.profileGuard = profileGuard;
        this.purchaseGatePolicy = purchaseGatePolicy;
        this.releasePolicy = releasePolicy;
        this.sandboxMapper = sandboxMapper;
        this.acceptanceRun = acceptanceRun;
        this.bundleConfig = bundleConfig;
    }

    AppBundleOrderService(
            AppBundleOrderMapper mapper,
            AdminIdempotencyService idempotency,
            EventOutboxService outbox,
            FundsSandboxProfileGuard profileGuard,
            StorefrontPurchaseGatePolicy purchaseGatePolicy,
            StorefrontProductReleasePolicy releasePolicy,
            CommerceAcceptanceSandboxMapper sandboxMapper,
            CommerceAcceptanceRun acceptanceRun) {
        this(mapper, idempotency, outbox, profileGuard, purchaseGatePolicy, releasePolicy,
                sandboxMapper, acceptanceRun, null);
    }

    @Transactional
    public ApiResult<Map<String, Object>> create(
            Long userId, List<String> productNos, Long expectedPolicyVersion, String idempotencyKey) {
        if (profileGuard.isLocalSandboxEnabled()) {
            AppBundleOrderMapper.UserLock user = mapper.lockUser(userId);
            if (user == null) return ApiResult.fail(404, "USER_NOT_FOUND");
            if (!user.sandbox()) return ApiResult.fail(403, "COMMERCE_SANDBOX_USER_REQUIRED");
            List<String> normalized = normalizeProducts(productNos);
            if (normalized == null) return ApiResult.fail(422, "BUNDLE_PRODUCTS_INVALID");
            if (sandboxMapper == null || acceptanceRun == null) {
                return ApiResult.fail(503, "COMMERCE_SANDBOX_UNAVAILABLE");
            }
            String runId = acceptanceRun.requireRunId();
            return executeSandboxOnce(userId, normalized, idempotencyKey, runId,
                    () -> createSandboxOnce(userId, normalized, runId));
        }
        if (!profileGuard.isStrictProductionRuntime()) {
            return ApiResult.fail(503, "COMMERCE_SANDBOX_UNAVAILABLE");
        }
        AppBundleOrderMapper.UserLock user = mapper.lockUser(userId);
        if (user == null) return ApiResult.fail(404, "USER_NOT_FOUND");
        if (user.sandbox()) return ApiResult.fail(403, "COMMERCE_SANDBOX_USER_FORBIDDEN");
        List<String> normalized = normalizeProducts(productNos);
        if (normalized == null) return ApiResult.fail(422, "BUNDLE_PRODUCTS_INVALID");
        return executeOnce(userId, normalized, idempotencyKey, "APP:BUNDLE_ORDER_CREATE:USER:",
                () -> createOnce(userId, normalized, expectedPolicyVersion));
    }

    /**
     * Sandbox bundles reserve only the run-scoped catalogue and create one
     * aggregate state-machine order with one inventory snapshot per product.
     * Payment, cancellation, expiry and refund therefore move the complete
     * bundle atomically and never write canonical product/order tables.
     */
    private ApiResult<Map<String, Object>> createSandboxOnce(Long userId, List<String> productNos, String runId) {
        List<CommerceAcceptanceSandboxMapper.SandboxCatalogProduct> products = new ArrayList<>();
        List<String> lockOrder = productNos.stream().sorted().toList();
        for (String productNo : lockOrder) {
            CommerceAcceptanceSandboxMapper.SandboxCatalogProduct product = sandboxMapper.lockSandboxCatalogProduct(
                    runId, null, productNo, 1);
            ProductInventoryMode inventoryMode = product == null ? null : ProductInventoryMode.parse(product.inventoryMode());
            if (product == null || product.priceUsdt() == null || product.priceUsdt().signum() <= 0
                    || inventoryMode == null
                    || (inventoryMode == ProductInventoryMode.UNLIMITED
                        && !"SHARE".equalsIgnoreCase(product.productType()))
                    || (inventoryMode == ProductInventoryMode.FINITE
                        && (product.stock() == null || product.stock() < 1))
                    || product.version() == null) {
                return ApiResult.fail(409, "COMMERCE_SANDBOX_PRODUCT_NOT_AVAILABLE");
            }
            String configurationBlock = AppCanonicalBoundaryService.storefrontConfigurationBlock(
                    product.productType(), product.inventoryMode(), product.gpuModel(), product.vramTotalGb(),
                    product.power(), product.datacenter());
            if (configurationBlock != null) return ApiResult.fail(409, configurationBlock);
            StorefrontProductReleasePolicy.Decision release = releasePolicy.evaluate(product.productNo(), product.unlockPhase());
            if (release == null || !release.available()) return ApiResult.fail(409, "BUNDLE_PRODUCT_NOT_RELEASED");
            products.add(product);
        }
        if (products.stream().anyMatch(row -> StringUtils.hasText(row.purchaseGateJson()))) {
            AppBundleOrderMapper.PurchaseFacts facts = mapper.purchaseFacts(userId);
            if (facts == null) return ApiResult.fail(409, "PURCHASE_GATE_FACTS_UNAVAILABLE");
            StorefrontPurchaseGatePolicy.Facts gateFacts = safeGateFacts(facts);
            if (gateFacts == null) return ApiResult.fail(409, "PURCHASE_GATE_FACTS_INVALID");
            if (products.stream().anyMatch(row -> !purchaseGatePolicy.evaluate(row.purchaseGateJson(), gateFacts).allowed())) {
                return ApiResult.fail(409, "COMMERCE_SANDBOX_PURCHASE_GATE_BLOCKED");
            }
        }
        BigDecimal subtotal = products.stream().map(CommerceAcceptanceSandboxMapper.SandboxCatalogProduct::priceUsdt)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(6, RoundingMode.DOWN);
        BigDecimal discount = subtotal.multiply(discountRate(products.size())).setScale(6, RoundingMode.DOWN);
        BigDecimal amount = subtotal.subtract(discount).setScale(6, RoundingMode.DOWN);
        String bundleNo = "BND-SBX-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        for (CommerceAcceptanceSandboxMapper.SandboxCatalogProduct product : products) {
            if (sandboxMapper.reserveSandboxCatalogStock(runId, product.productId(), product.version(), 1) != 1) {
                throw new BizException(409, "COMMERCE_SANDBOX_STOCK_CONFLICT");
            }
        }
        CommerceAcceptanceSandboxMapper.SandboxCatalogProduct first = products.get(0);
        if (sandboxMapper.insertSandboxOrder(new CommerceAcceptanceSandboxMapper.OrderWrite(
                bundleNo, userId, first.productId(), 1, amount, first.version(), runId,
                "BUNDLE", products.size())) != 1) {
            throw new BizException(409, "COMMERCE_SANDBOX_ORDER_CREATE_CONFLICT");
        }
        for (CommerceAcceptanceSandboxMapper.SandboxCatalogProduct product : products) {
            if (sandboxMapper.insertInventory(new CommerceAcceptanceSandboxMapper.InventoryWrite(
                    bundleNo, product.productId(), product.productNo(), product.priceUsdt(), 1, runId)) != 1) {
                throw new BizException(409, "COMMERCE_SANDBOX_ORDER_CREATE_CONFLICT");
            }
        }
        return ApiResult.ok(linked("orderNo", bundleNo, "orderNos", List.of(bundleNo), "orderType", "BUNDLE",
                "itemCount", products.size(), "productNos", productNos, "subtotalUsdt", subtotal,
                "discountRate", discountRate(products.size()), "discountUsdt", discount, "amountUsdt", amount,
                "paymentStatus", "PENDING", "orderStatus", "PENDING_PAYMENT", "idSource", "sandbox-server",
                "source", "mock", "sourceEnvironment", "SANDBOX", "runId", runId));
    }

    private ApiResult<Map<String, Object>> createOnce(
            Long userId, List<String> productNos, Long expectedPolicyVersion) {
        long currentPolicyVersion = lockDiscountPolicyVersion();
        if (expectedPolicyVersion == null || expectedPolicyVersion < 1
                || expectedPolicyVersion != currentPolicyVersion) {
            return ApiResult.fail(409, "BUNDLE_DISCOUNT_POLICY_STALE");
        }
        List<AppBundleOrderMapper.ProductRow> rows = mapper.lockProducts(productNos.stream().sorted().toList());
        if (rows == null || rows.size() != productNos.size()) return ApiResult.fail(409, "BUNDLE_PRODUCT_NOT_AVAILABLE");
        List<AppBundleOrderMapper.ProductRow> products = rows.stream()
                .sorted(Comparator.comparing(AppBundleOrderMapper.ProductRow::productNo)).toList();
        for (AppBundleOrderMapper.ProductRow product : products) {
            String configurationBlock = AppCanonicalBoundaryService.storefrontConfigurationBlock(
                    product.productType(), product.inventoryMode(), product.gpuModel(), product.vramTotalGb(),
                    product.power(), product.datacenter());
            if (configurationBlock != null) return ApiResult.fail(409, configurationBlock);
        }
        if (products.stream().anyMatch(row -> (!ProductInventoryMode.isUnlimited(row.inventoryMode())
                        && (row.stock() == null || row.stock() < 1))
                || row.priceUsdt() == null || row.priceUsdt().signum() <= 0 || !StringUtils.hasText(row.name()))) {
            return ApiResult.fail(409, "BUNDLE_PRODUCT_NOT_AVAILABLE");
        }
        if (products.stream().anyMatch(row -> {
            StorefrontProductReleasePolicy.Decision release = releasePolicy.evaluate(row.productNo(), row.unlockPhase());
            return release == null || !release.available();
        })) return ApiResult.fail(409, "BUNDLE_PRODUCT_NOT_RELEASED");
        if (products.stream().anyMatch(row -> StringUtils.hasText(row.purchaseGateJson()))) {
            AppBundleOrderMapper.PurchaseFacts facts = mapper.purchaseFacts(userId);
            if (facts == null) return ApiResult.fail(409, "PURCHASE_GATE_FACTS_UNAVAILABLE");
            StorefrontPurchaseGatePolicy.Facts gateFacts = safeGateFacts(facts);
            if (gateFacts == null) return ApiResult.fail(409, "PURCHASE_GATE_FACTS_INVALID");
            if (products.stream().anyMatch(row -> !purchaseGatePolicy.evaluate(row.purchaseGateJson(), gateFacts).allowed())) {
                return ApiResult.fail(409, "PURCHASE_GATE_BLOCKED");
            }
        }
        int itemCount = products.size();
        long physicalItemCount = products.stream()
                .filter(row -> !"SHARE".equalsIgnoreCase(row.productType())).count();
        int cap = Math.max(1, mapper.deviceSlotCap());
        int active = Math.max(0, mapper.activeDeviceCount(userId));
        int reserved = Math.max(0, mapper.reservedDeviceOrderCount(userId));
        if ((long) active + reserved + physicalItemCount > cap) {
            return ApiResult.fail(409, "CAPACITY_REPLACEMENT_REQUIRED");
        }
        AppBundleOrderMapper.Attribution attribution = mapper.attribution(userId);
        if (attribution == null || attribution.accountAgeMonths() == null || !StringUtils.hasText(attribution.cohort())) {
            throw new BizException(409, "USER_EVENT_ATTRIBUTION_UNAVAILABLE");
        }
        Map<String, QuotaReservation> quotaReservations = new LinkedHashMap<>();
        for (AppBundleOrderMapper.ProductRow product : products) {
            QuotaReservation quotaReservation = reserveCanonicalPurchaseQuota(userId, product.productNo(), 1);
            if (quotaReservation.reserved()) {
                quotaReservations.put(product.productNo(), quotaReservation);
            }
        }
        BigDecimal subtotal = products.stream().map(AppBundleOrderMapper.ProductRow::priceUsdt)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(6, RoundingMode.DOWN);
        BigDecimal discountRate = discountRate(itemCount);
        BigDecimal discount = subtotal.multiply(discountRate).setScale(6, RoundingMode.DOWN);
        BigDecimal amount = subtotal.subtract(discount).setScale(6, RoundingMode.DOWN);
        String orderNo = "BND-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        for (AppBundleOrderMapper.ProductRow product : products) {
            if (mapper.decrementStock(product.id()) != 1) throw new BizException(409, "BUNDLE_PRODUCT_STOCK_CONFLICT");
        }
        if (mapper.insertBundleOrder(userId, orderNo, products.get(0).id(), itemCount, subtotal, discount, amount) != 1) {
            throw new BizException(409, "BUNDLE_ORDER_CREATE_CONFLICT");
        }
        for (int index = 0; index < products.size(); index++) {
            AppBundleOrderMapper.ProductRow product = products.get(index);
            QuotaReservation quotaReservation = quotaReservations.get(product.productNo());
            if (mapper.insertBundleItem(orderNo, product, index,
                    quotaReservation != null, quotaReservation == null ? null : quotaReservation.gateGeneration()) != 1) {
                throw new BizException(409, "BUNDLE_ORDER_ITEM_CREATE_CONFLICT");
            }
        }
        outbox.publishUserEvent("ORDER", orderNo, "checkout.started", userId,
                normalizePhase(attribution.phase()), attribution.accountAgeMonths(), attribution.cohort(),
                linked("userId", userId, "orderId", orderNo, "orderType", "BUNDLE",
                        "productNos", products.stream().map(AppBundleOrderMapper.ProductRow::productNo).toList(),
                        "itemCount", itemCount, "amountUsdt", amount));
        return ApiResult.ok(linked("orderNo", orderNo, "orderType", "BUNDLE", "itemCount", itemCount,
                "productNos", products.stream().map(AppBundleOrderMapper.ProductRow::productNo).toList(),
                "subtotalUsdt", subtotal, "discountRate", discountRate, "discountUsdt", discount,
                "amountUsdt", amount, "paymentStatus", "PENDING", "orderStatus", "PENDING_PAYMENT",
                "idSource", "server", "policyVersion", currentPolicyVersion));
    }

    private StorefrontPurchaseGatePolicy.Facts safeGateFacts(AppBundleOrderMapper.PurchaseFacts facts) {
        try {
            return new StorefrontPurchaseGatePolicy.Facts(
                    facts.rank() == null ? 0 : facts.rank(),
                    facts.activeDirect() == null ? 0 : facts.activeDirect(),
                    facts.teamVolumeUsd() == null ? BigDecimal.ZERO : facts.teamVolumeUsd());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private QuotaReservation reserveCanonicalPurchaseQuota(Long userId, String productNo, int quantity) {
        String rawGate = mapper.purchaseGateJson(productNo);
        if (!StringUtils.hasText(rawGate)) return QuotaReservation.none();
        AppBundleOrderMapper.PurchaseFacts facts = mapper.purchaseFacts(userId);
        StorefrontPurchaseGatePolicy.Facts gateFacts = facts == null ? null : safeGateFacts(facts);
        if ((facts != null && gateFacts == null)
                || !purchaseGatePolicy.evaluate(rawGate, gateFacts).allowed()) {
            throw new BizException(409, "PURCHASE_GATE_BLOCKED");
        }
        boolean quotaReserved = purchaseGatePolicy.hasQuota(rawGate);
        if (quotaReserved) {
            if (mapper.consumePurchaseQuota(productNo, quantity) != 1) {
                throw new BizException(409, "PURCHASE_GATE_SOLD_OUT");
            }
            Long gateGeneration = mapper.lockPurchaseGateGeneration(productNo);
            if (gateGeneration == null || gateGeneration < 1) {
                throw new BizException(409, "PURCHASE_GATE_RESERVATION_UNAVAILABLE");
            }
            return new QuotaReservation(true, gateGeneration);
        }
        return QuotaReservation.none();
    }

    private record QuotaReservation(boolean reserved, Long gateGeneration) {
        static QuotaReservation none() {
            return new QuotaReservation(false, null);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ApiResult<Map<String, Object>> executeOnce(Long userId, List<String> productNos, String key,
                                                        String namespace, Supplier<ApiResult<Map<String, Object>>> action) {
        String material = userId + "|" + String.join(",", productNos);
        return (ApiResult<Map<String, Object>>) (ApiResult) idempotency.execute(
                namespace + userId, key, sha256(material), ApiResult.class, (Supplier) action);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ApiResult<Map<String, Object>> executeSandboxOnce(Long userId, List<String> productNos, String key,
                                                               String runId,
                                                               Supplier<ApiResult<Map<String, Object>>> action) {
        String material = "SANDBOX|" + runId + "|" + userId + "|" + String.join(",", productNos);
        return (ApiResult<Map<String, Object>>) (ApiResult) idempotency.execute(
                "APP:BUNDLE_ORDER_CREATE:SANDBOX:" + runId + ":USER:" + userId,
                key, sha256(material), ApiResult.class, (Supplier) action);
    }

    private List<String> normalizeProducts(List<String> productNos) {
        if (productNos == null || productNos.size() < MIN_ITEMS || productNos.size() > MAX_ITEMS) return null;
        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        for (String value : productNos) {
            if (!StringUtils.hasText(value)) return null;
            String normalized = value.trim();
            if (!normalized.matches("[A-Za-z0-9._-]{2,64}") || !distinct.add(normalized)) return null;
        }
        return new ArrayList<>(distinct);
    }

    public ApiResult<Map<String, Object>> discountPolicy() {
        BundleDiscountPolicy policy = currentDiscountPolicy();
        return ApiResult.ok(linked(
                "source", "server", "serverCanonical", true,
                "policyVersion", discountPolicyVersion(false),
                "tiers", List.of(
                        linked("minItems", 2, "rate", policy.twoItems()),
                        linked("minItems", 3, "rate", policy.threeItems()),
                        linked("minItems", 4, "rate", policy.fourPlusItems()))));
    }

    private BigDecimal discountRate(int itemCount) {
        return currentDiscountPolicy().rateFor(itemCount);
    }

    private BundleDiscountPolicy currentDiscountPolicy() {
        return bundleConfig == null
                ? BundleDiscountPolicy.testDefaults()
                : BundleDiscountPolicy.require(bundleConfig::activeValue);
    }

    private long lockDiscountPolicyVersion() {
        return discountPolicyVersion(true);
    }

    private long discountPolicyVersion(boolean lock) {
        if (bundleConfig == null) return 1L;
        try {
            String raw = (lock
                    ? bundleConfig.activeValueForUpdate(BundleDiscountPolicy.VERSION_KEY)
                    : bundleConfig.activeValue(BundleDiscountPolicy.VERSION_KEY))
                    .filter(StringUtils::hasText).orElseThrow().trim();
            long version = Long.parseLong(raw);
            if (version < 1) throw new NumberFormatException("non-positive version");
            return version;
        } catch (RuntimeException ex) {
            throw new BizException(503, "BUNDLE_DISCOUNT_POLICY_UNAVAILABLE");
        }
    }

    private String normalizePhase(String value) {
        String normalized = StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "P1";
        return normalized.matches("P[1-6]") ? normalized : "P1";
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte item : digest) hex.append(String.format("%02x", item));
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private Map<String, Object> linked(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]);
        return result;
    }
}
