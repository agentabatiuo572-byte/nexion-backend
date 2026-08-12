package ffdd.opsconsole.shared.canonical;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.device.mapper.AppTradeinMapper;
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
import org.springframework.util.StringUtils;

/**
 * App purchase catalogue.  It intentionally reads the same nx_product truth
 * used by the quote and submit paths, rather than the administrative SKU
 * configuration surface.
 */
@ApplicationService
@RequiredArgsConstructor
public class AppProductCatalogService {
    private static final Set<String> TIERS = Set.of("Entry", "Pro", "Flagship", "Share");

    private final AppTradeinMapper tradeinMapper;
    private final CommerceAcceptanceSandboxMapper commerceAcceptanceSandboxMapper;
    private final FundsSandboxProfileGuard fundsSandboxProfileGuard;
    private final CommerceAcceptanceRun commerceAcceptanceRun;

    public ApiResult<Map<String, Object>> catalog() {
        return catalog(null);
    }

    public ApiResult<Map<String, Object>> catalog(Long userId) {
        if (fundsSandboxProfileGuard.isLocalSandboxEnabled()) {
            if (userId == null || !commerceAcceptanceSandboxMapper.isSandboxUser(userId)) {
                return ApiResult.fail(403, "COMMERCE_SANDBOX_USER_REQUIRED");
            }
            return sandboxCatalog();
        }
        try {
            List<AppTradeinMapper.CatalogTargetProduct> targets = tradeinMapper.listPurchasableCatalogTargets();
            if (targets == null) return ApiResult.fail(500, "PRODUCT_CATALOG_INVALID");
            List<Map<String, Object>> products = new ArrayList<>();
            LocalDateTime revision = null;
            for (AppTradeinMapper.CatalogTargetProduct target : targets) {
                products.add(product(target));
                if (target.updatedAt() != null && (revision == null || target.updatedAt().isAfter(revision))) {
                    revision = target.updatedAt();
                }
            }
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("source", "nx_product");
            response.put("revision", revision == null ? null : revision.toString());
            response.put("products", products);
            return ApiResult.ok(response);
        } catch (RuntimeException ex) {
            return ApiResult.fail(500, "PRODUCT_CATALOG_INVALID");
        }
    }

    private ApiResult<Map<String, Object>> sandboxCatalog() {
        try {
            String runId = commerceAcceptanceRun.requireRunId();
            List<CommerceAcceptanceSandboxMapper.CatalogSeed> seeds = commerceAcceptanceSandboxMapper.listEligibleCatalogSeeds();
            if (seeds == null) return ApiResult.fail(500, "COMMERCE_SANDBOX_CATALOG_INVALID");
            for (CommerceAcceptanceSandboxMapper.CatalogSeed seed : seeds) commerceAcceptanceSandboxMapper.insertCatalogIfAbsent(new CommerceAcceptanceSandboxMapper.CatalogSeed(
                    seed.productId(), seed.productNo(), seed.name(), seed.tier(), seed.priceUsdt(), seed.stock(), seed.sold(), seed.deviceType(),
                    seed.generation(), seed.gpuModel(), seed.vramTotalGb(), seed.hashrate(), seed.dailyUsdt(), seed.dailyNex(), seed.tagline(), seed.badge(), seed.unlockPhase(), runId));
            List<CommerceAcceptanceSandboxMapper.SandboxCatalogProduct> targets = commerceAcceptanceSandboxMapper.listSandboxCatalog(runId);
            if (targets == null) return ApiResult.fail(500, "COMMERCE_SANDBOX_CATALOG_INVALID");
            List<Map<String, Object>> products = targets.stream().map(this::sandboxProduct).toList();
            LocalDateTime revision = targets.stream().map(CommerceAcceptanceSandboxMapper.SandboxCatalogProduct::updatedAt)
                    .filter(java.util.Objects::nonNull).max(LocalDateTime::compareTo).orElse(null);
            return ApiResult.ok(Map.of("source", "mock", "catalogSource", "nx_commerce_sandbox_catalog",
                    "revision", revision == null ? "" : revision.toString(), "products", products,
                    "sourceEnvironment", "SANDBOX", "runId", runId));
        } catch (RuntimeException ex) {
            return ApiResult.fail(500, "COMMERCE_SANDBOX_CATALOG_INVALID");
        }
    }

    private Map<String, Object> sandboxProduct(CommerceAcceptanceSandboxMapper.SandboxCatalogProduct target) {
        return product(new AppTradeinMapper.CatalogTargetProduct(target.productNo(), target.name(), target.tier(),
                target.priceUsdt(), target.stock(), target.productNo(), 0, target.gpuModel(), target.vramTotalGb(),
                target.hashrate(), target.dailyUsdt(), target.dailyNex(), target.tagline(), target.badge(), target.sold(),
                target.unlockPhase(), target.updatedAt()));
    }

    private Map<String, Object> product(AppTradeinMapper.CatalogTargetProduct target) {
        if (target == null
                || !StringUtils.hasText(target.productNo())
                || !StringUtils.hasText(target.name())
                || !TIERS.contains(target.tier())
                || target.priceUsdt() == null
                || target.priceUsdt().signum() <= 0
                || target.stock() == null
                || target.stock() < 1
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
        item.put("gpu", text(target.gpuModel()));
        item.put("vram", target.vramTotalGb() == null ? "" : target.vramTotalGb() + "GB");
        item.put("hashRate", decimalText(target.hashrate()));
        item.put("power", "");
        item.put("dailyEarn", target.dailyUsdt());
        item.put("dailyEarnNEX", target.dailyNex());
        item.put("price", target.priceUsdt());
        item.put("sold", target.sold());
        item.put("stock", target.stock());
        item.put("features", List.of());
        item.put("ai", null);
        item.put("status", "active");
        item.put("unlocksAtPhase", nullableText(target.unlockPhase()));
        item.put("purchaseGate", null);
        return item;
    }

    private String text(String value) {
        return value == null ? "" : value;
    }

    private String nullableText(String value) {
        return StringUtils.hasText(value) ? value : null;
    }

    private String decimalText(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }
}
