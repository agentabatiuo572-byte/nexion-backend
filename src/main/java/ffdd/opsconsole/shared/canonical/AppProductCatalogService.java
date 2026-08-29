package ffdd.opsconsole.shared.canonical;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.device.mapper.AppTradeinMapper;
import ffdd.opsconsole.device.domain.ProductInventoryMode;
import ffdd.opsconsole.device.domain.DeviceSkuSpecifications;
import ffdd.opsconsole.commerce.mapper.CommerceAcceptanceSandboxMapper;
import ffdd.opsconsole.finance.application.FundsSandboxProfileGuard;
import ffdd.opsconsole.commerce.application.CommerceAcceptanceRun;
import ffdd.opsconsole.shared.api.ApiResult;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

/**
 * App purchase catalogue. It reads the same nx_product truth used by the quote,
 * order-submit and PC E1 catalogue paths. E1-only presentation metadata remains
 * in the administrative extension table and cannot override purchase truth.
 */
@ApplicationService
@RequiredArgsConstructor
@Slf4j
public class AppProductCatalogService {
    private static final Set<String> TIERS = Set.of("Entry", "Pro", "Flagship", "Share");

    private final AppTradeinMapper tradeinMapper;
    private final CommerceAcceptanceSandboxMapper commerceAcceptanceSandboxMapper;
    private final FundsSandboxProfileGuard fundsSandboxProfileGuard;
    private final CommerceAcceptanceRun commerceAcceptanceRun;
    private final ObjectMapper objectMapper;
    private final StorefrontProductReleasePolicy productReleasePolicy;

    public ApiResult<Map<String, Object>> catalog() {
        return catalog(null);
    }

    public ApiResult<Map<String, Object>> catalog(Long userId) {
        boolean developmentRuntime = false;
        if (!developmentRuntime && fundsSandboxProfileGuard.isLocalSandboxEnabled()) {
            if (userId == null || !commerceAcceptanceSandboxMapper.isSandboxUser(userId)) {
                return ApiResult.fail(403, "COMMERCE_SANDBOX_USER_REQUIRED");
            }
            return sandboxCatalog();
        }
        if (!developmentRuntime && !fundsSandboxProfileGuard.isStrictProductionRuntime()) {
            return ApiResult.fail(503, "COMMERCE_SANDBOX_UNAVAILABLE");
        }
        ApiResult<Map<String, Object>> audienceFailure = canonicalAudienceFailure(userId, developmentRuntime);
        if (audienceFailure != null) return audienceFailure;
        try {
            List<AppTradeinMapper.CatalogTargetProduct> targets = tradeinMapper.listPurchasableCatalogTargets();
            if (targets == null) return ApiResult.fail(500, "PRODUCT_CATALOG_INVALID");
            List<Map<String, Object>> products = new ArrayList<>();
            LocalDateTime revision = null;
            for (AppTradeinMapper.CatalogTargetProduct target : targets) {
                products.add(product(target, productReleasePolicy.evaluate(target.productNo(), target.unlockPhase())));
                if (target.updatedAt() != null && (revision == null || target.updatedAt().isAfter(revision))) {
                    revision = target.updatedAt();
                }
            }
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("source", "nx_product");
            response.put("serverCanonical", true);
            response.put("sourceEnvironment", "PRODUCTION");
            response.put("runId", "");
            response.put("revision", revision == null ? null : revision.toString());
            response.put("products", products);
            return ApiResult.ok(response);
        } catch (RuntimeException ex) {
            return ApiResult.fail(500, "PRODUCT_CATALOG_INVALID");
        }
    }

    private ApiResult<Map<String, Object>> canonicalAudienceFailure(Long userId, boolean developmentRuntime) {
        if (userId == null || userId <= 0) {
            return ApiResult.fail(403, "PRODUCT_CATALOG_PRODUCTION_USER_REQUIRED");
        }
        if (!developmentRuntime) {
            return Integer.valueOf(0).equals(tradeinMapper.activeUserEnvironment(userId))
                    ? null
                    : ApiResult.fail(403, "PRODUCT_CATALOG_PRODUCTION_USER_REQUIRED");
        }
        return Integer.valueOf(1).equals(tradeinMapper.activeUserEnvironment(userId))
                ? null
                : ApiResult.fail(403, "PRODUCT_CATALOG_DEVELOPMENT_USER_REQUIRED");
    }

    private ApiResult<Map<String, Object>> sandboxCatalog() {
        try {
            String runId = commerceAcceptanceRun.requireRunId();
            List<CommerceAcceptanceSandboxMapper.CatalogSeed> seeds = commerceAcceptanceSandboxMapper.listEligibleCatalogSeeds();
            if (seeds == null) return ApiResult.fail(500, "COMMERCE_SANDBOX_CATALOG_INVALID");
            for (CommerceAcceptanceSandboxMapper.CatalogSeed seed : seeds) commerceAcceptanceSandboxMapper.upsertCatalog(new CommerceAcceptanceSandboxMapper.CatalogSeed(
                    seed.productId(), seed.productNo(), seed.name(), seed.tier(), seed.priceUsdt(), seed.stock(), seed.sold(), seed.deviceType(),
                    seed.generation(), seed.gpuModel(), seed.vramTotalGb(), seed.hashrate(), seed.dailyUsdt(), seed.dailyNex(), seed.tagline(), seed.badge(), seed.unlockPhase(),
                    seed.power(), seed.datacenter(), seed.uptime(), seed.warranty(), seed.phoneDailyEarn(), seed.phoneDailyEarnNex(),
                    seed.featuresJson(), seed.aiImageGenPerMin(), seed.aiLlmTokensPerSec(), seed.aiVideoMinPerHour(),
                    seed.aiFineTuneMins(), seed.aiUnlocks(), seed.purchaseGateJson(), seed.inventoryMode(), runId));
            commerceAcceptanceSandboxMapper.pruneCatalog(runId);
            List<CommerceAcceptanceSandboxMapper.SandboxCatalogProduct> targets = commerceAcceptanceSandboxMapper.listSandboxCatalog(runId);
            if (targets == null) return ApiResult.fail(500, "COMMERCE_SANDBOX_CATALOG_INVALID");
            List<Map<String, Object>> products = targets.stream().map(this::sandboxProduct).toList();
            LocalDateTime revision = targets.stream().map(CommerceAcceptanceSandboxMapper.SandboxCatalogProduct::updatedAt)
                    .filter(java.util.Objects::nonNull).max(LocalDateTime::compareTo).orElse(null);
            return ApiResult.ok(Map.of("source", "mock", "catalogSource", "nx_commerce_sandbox_catalog",
                    "revision", revision == null ? "" : revision.toString(), "products", products,
                    "sourceEnvironment", "SANDBOX", "runId", runId, "serverCanonical", true));
        } catch (RuntimeException ex) {
            log.warn("Sandbox storefront catalogue projection failed: type={}, message={}",
                    ex.getClass().getSimpleName(), ex.getMessage(), ex);
            return ApiResult.fail(500, "COMMERCE_SANDBOX_CATALOG_INVALID");
        }
    }

    private Map<String, Object> sandboxProduct(CommerceAcceptanceSandboxMapper.SandboxCatalogProduct target) {
        return product(new AppTradeinMapper.CatalogTargetProduct(target.productNo(), target.name(), target.tier(),
                target.priceUsdt(), target.stock(), target.productType(), 0, target.gpuModel(), target.vramTotalGb(),
                target.hashrate(), target.dailyUsdt(), target.dailyNex(), target.tagline(), target.badge(), target.sold(),
                target.unlockPhase(), target.updatedAt(), target.power(), target.datacenter(), target.uptime(), target.warranty(),
                target.phoneDailyEarn(), target.phoneDailyEarnNex(), target.featuresJson(),
                target.aiImageGenPerMin(), target.aiLlmTokensPerSec(), target.aiVideoMinPerHour(), target.aiFineTuneMins(), target.aiUnlocks(),
                target.purchaseGateJson(), target.inventoryMode()), productReleasePolicy.evaluate(target.productNo(), target.unlockPhase()));
    }

    private Map<String, Object> product(
            AppTradeinMapper.CatalogTargetProduct target,
            StorefrontProductReleasePolicy.Decision release) {
        String productType = canonicalProductType(target == null ? null : target.deviceType(), target == null ? null : target.tier());
        if (target == null
                || !StringUtils.hasText(target.productNo())
                || !StringUtils.hasText(target.name())
                || !TIERS.contains(target.tier())
                || target.priceUsdt() == null
                || target.priceUsdt().signum() <= 0
                || ProductInventoryMode.parse(target.inventoryMode()) == null
                || (ProductInventoryMode.isUnlimited(target.inventoryMode()) && !"SHARE".equals(productType))
                || (!ProductInventoryMode.isUnlimited(target.inventoryMode())
                    && (target.stock() == null || target.stock() < 0))
                || target.dailyUsdt() == null
                || target.dailyUsdt().signum() < 0
                || target.dailyNex() == null
                || target.dailyNex().signum() < 0
                || target.sold() == null
                || target.sold() < 0) {
            throw new IllegalArgumentException("invalid purchasable product catalog row");
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", target.productNo());
        item.put("name", target.name());
        item.put("tier", target.tier());
        item.put("tagline", text(target.tagline()));
        item.put("badge", nullableText(target.badge()));
        boolean share = "Share".equals(target.tier());
        boolean specsComplete = share || (StringUtils.hasText(target.gpuModel())
                && target.vramTotalGb() != null && target.vramTotalGb() > 0
                && StringUtils.hasText(target.power())
                && StringUtils.hasText(target.datacenter()));
        item.put("gpu", display(target.gpuModel()));
        item.put("vram", target.vramTotalGb() == null || target.vramTotalGb() <= 0
                ? "unavailable" : target.vramTotalGb() + "GB");
        item.put("hashRate", decimalText(target.hashrate()));
        item.put("power", display(target.power()));
        item.put("datacenter", display(target.datacenter()));
        item.put("uptime", DeviceSkuSpecifications.display(target.uptime()));
        item.put("warranty", DeviceSkuSpecifications.display(target.warranty()));
        item.put("phoneDailyEarn", DeviceSkuSpecifications.dailyDisplay(target.phoneDailyEarn(), "USDT/day"));
        item.put("phoneDailyEarnNEX", DeviceSkuSpecifications.dailyDisplay(target.phoneDailyEarnNex(), "NEX/day"));
        item.put("dailyEarn", target.dailyUsdt());
        item.put("dailyEarnNEX", target.dailyNex());
        item.put("price", target.priceUsdt());
        item.put("sold", target.sold());
        item.put("productType", productType);
        item.put("inventoryMode", target.inventoryMode());
        item.put("stock", ProductInventoryMode.isUnlimited(target.inventoryMode()) ? null : target.stock());
        item.put("features", features(target.featuresJson()));
        item.put("ai", ai(target));
        item.put("status", "active");
        item.put("available", release.available() && specsComplete);
        // Preserve the release policy's server decision verbatim. Specification
        // certification is an independent fail-closed purchase gate and must
        // never hide an E1 phase decision from the App/PC projections.
        item.put("releaseState", release.reason());
        item.put("releasePhaseId", nullableText(release.releasePhaseId()));
        // E1 database ids and H1 P1-P6 codes are different namespaces. Never
        // make the App compare those ids again; availability above is canonical.
        item.put("unlocksAtPhase", null);
        // Historical client-only gates are deliberately not projected until every quote/order
        // path enforces the same eligibility and quota rules on the server.
        item.put("purchaseGate", null);
        boolean outOfStock = !ProductInventoryMode.isUnlimited(target.inventoryMode()) && target.stock() == 0;
        item.put("purchaseBlocked", !specsComplete || outOfStock);
        item.put("purchaseBlockedReason", !specsComplete
                ? "PRODUCT_SPECS_UNAVAILABLE"
                : outOfStock ? "PRODUCT_OUT_OF_STOCK" : null);
        return item;
    }

    private String canonicalProductType(String raw, String tier) {
        if (StringUtils.hasText(raw)) {
            String normalized = raw.trim().toUpperCase(java.util.Locale.ROOT);
            if ("DEVICE".equals(normalized) || "SHARE".equals(normalized)) return normalized;
        }
        // Compatibility for old isolated-catalog fixtures only. Canonical nx_product rows
        // always project product_type explicitly through AppTradeinMapper.
        return "Share".equals(tier) ? "SHARE" : "DEVICE";
    }

    private List<String> features(String json) {
        if (!StringUtils.hasText(json)) return List.of();
        try {
            List<String> values = objectMapper.readValue(json, new TypeReference<>() { });
            if (values == null || values.stream().anyMatch(value -> value == null)) {
                throw new IllegalArgumentException("invalid product feature metadata");
            }
            return List.copyOf(values);
        } catch (Exception ex) {
            throw new IllegalArgumentException("invalid product feature metadata", ex);
        }
    }

    private Map<String, Object> ai(AppTradeinMapper.CatalogTargetProduct target) {
        if (target.aiImageGenPerMin() == null && target.aiLlmTokensPerSec() == null
                && target.aiVideoMinPerHour() == null && target.aiFineTuneMins() == null
                && !StringUtils.hasText(target.aiUnlocks())) {
            return null;
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("imageGenPerMin", target.aiImageGenPerMin());
        value.put("llmTokensPerSec", target.aiLlmTokensPerSec());
        value.put("videoMinPerHour", target.aiVideoMinPerHour());
        value.put("fineTuneMins", target.aiFineTuneMins());
        value.put("unlocks", nullableText(target.aiUnlocks()));
        return value;
    }

    private Map<String, Object> object(String json) {
        if (!StringUtils.hasText(json)) return null;
        try {
            Map<String, Object> value = objectMapper.readValue(json, new TypeReference<>() { });
            if (value == null) throw new IllegalArgumentException("invalid product object metadata");
            return value;
        } catch (Exception ex) {
            throw new IllegalArgumentException("invalid product object metadata", ex);
        }
    }

    private String text(String value) {
        return value == null ? "" : value;
    }

    private String display(String value) {
        return StringUtils.hasText(value) ? value.trim() : "unavailable";
    }

    private String nullableText(String value) {
        return StringUtils.hasText(value) ? value : null;
    }

    private String decimalText(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }
}
