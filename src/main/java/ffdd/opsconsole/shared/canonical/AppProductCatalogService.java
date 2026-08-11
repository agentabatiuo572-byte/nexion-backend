package ffdd.opsconsole.shared.canonical;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.device.mapper.AppTradeinMapper;
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

    public ApiResult<Map<String, Object>> catalog() {
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
