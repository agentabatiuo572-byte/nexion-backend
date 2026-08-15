package ffdd.opsconsole.shared.canonical;

import ffdd.opsconsole.finance.application.FundsSandboxProfileGuard;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.canonical.mapper.AppBundleOrderMapper;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class AppBundleOrderService {
    private static final int MIN_ITEMS = 2;
    private static final int MAX_ITEMS = 8;
    private final AppBundleOrderMapper mapper;
    private final AdminIdempotencyService idempotency;
    private final EventOutboxService outbox;
    private final FundsSandboxProfileGuard profileGuard;
    private final StorefrontPurchaseGatePolicy purchaseGatePolicy;

    @Transactional
    public ApiResult<Map<String, Object>> create(Long userId, List<String> productNos, String idempotencyKey) {
        if (profileGuard.isLocalSandboxEnabled()) {
            return ApiResult.fail(409, "BUNDLE_CHECKOUT_SANDBOX_UNSUPPORTED");
        }
        AppBundleOrderMapper.UserLock user = mapper.lockUser(userId);
        if (user == null) return ApiResult.fail(404, "USER_NOT_FOUND");
        if (user.sandbox()) return ApiResult.fail(403, "COMMERCE_SANDBOX_USER_FORBIDDEN");
        List<String> normalized = normalizeProducts(productNos);
        if (normalized == null) return ApiResult.fail(422, "BUNDLE_PRODUCTS_INVALID");
        return executeOnce(userId, normalized, idempotencyKey, () -> createOnce(userId, normalized));
    }

    private ApiResult<Map<String, Object>> createOnce(Long userId, List<String> productNos) {
        List<AppBundleOrderMapper.ProductRow> rows = mapper.lockProducts(productNos);
        if (rows == null || rows.size() != productNos.size()) return ApiResult.fail(409, "BUNDLE_PRODUCT_NOT_AVAILABLE");
        List<AppBundleOrderMapper.ProductRow> products = rows.stream()
                .sorted(Comparator.comparing(AppBundleOrderMapper.ProductRow::productNo)).toList();
        if (products.stream().anyMatch(row -> row.stock() == null || row.stock() < 1
                || row.priceUsdt() == null || row.priceUsdt().signum() <= 0 || !StringUtils.hasText(row.name()))) {
            return ApiResult.fail(409, "BUNDLE_PRODUCT_NOT_AVAILABLE");
        }
        if (products.stream().anyMatch(row -> StringUtils.hasText(row.purchaseGateJson()))) {
            AppBundleOrderMapper.PurchaseFacts facts = mapper.purchaseFacts(userId);
            if (facts == null) return ApiResult.fail(409, "PURCHASE_GATE_FACTS_UNAVAILABLE");
            StorefrontPurchaseGatePolicy.Facts gateFacts = new StorefrontPurchaseGatePolicy.Facts(
                    Math.max(0, facts.rank() == null ? 0 : facts.rank()), Math.max(0, facts.activeDirect() == null ? 0 : facts.activeDirect()),
                    facts.teamVolumeUsd() == null ? BigDecimal.ZERO : facts.teamVolumeUsd());
            if (products.stream().anyMatch(row -> !purchaseGatePolicy.evaluate(row.purchaseGateJson(), gateFacts).allowed())) {
                return ApiResult.fail(409, "PURCHASE_GATE_BLOCKED");
            }
        }
        int itemCount = products.size();
        int cap = Math.max(1, mapper.deviceSlotCap());
        int active = Math.max(0, mapper.activeDeviceCount(userId));
        int reserved = Math.max(0, mapper.reservedDeviceOrderCount(userId));
        if ((long) active + reserved + itemCount > cap) return ApiResult.fail(409, "CAPACITY_REPLACEMENT_REQUIRED");
        AppBundleOrderMapper.Attribution attribution = mapper.attribution(userId);
        if (attribution == null || attribution.accountAgeMonths() == null || !StringUtils.hasText(attribution.cohort())) {
            throw new BizException(409, "USER_EVENT_ATTRIBUTION_UNAVAILABLE");
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
            if (mapper.insertBundleItem(orderNo, products.get(index), index) != 1) {
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
                "idSource", "server"));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ApiResult<Map<String, Object>> executeOnce(Long userId, List<String> productNos, String key,
                                                        Supplier<ApiResult<Map<String, Object>>> action) {
        String material = userId + "|" + String.join(",", productNos);
        return (ApiResult<Map<String, Object>>) (ApiResult) idempotency.execute(
                "APP:BUNDLE_ORDER_CREATE:USER:" + userId, key, sha256(material), ApiResult.class, (Supplier) action);
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

    private BigDecimal discountRate(int itemCount) {
        if (itemCount >= 4) return new BigDecimal("0.12");
        if (itemCount == 3) return new BigDecimal("0.08");
        return new BigDecimal("0.05");
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
