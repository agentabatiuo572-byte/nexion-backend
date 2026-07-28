package ffdd.opsconsole.shared.canonical;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.device.domain.DeviceCatalogRepository;
import ffdd.opsconsole.device.domain.DevicePurchaseGateView;
import ffdd.opsconsole.device.domain.DeviceSkuView;
import ffdd.opsconsole.device.dto.DeviceSkuQueryRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
public class AppProductCatalogService {
    private static final Set<String> TIERS = Set.of("Entry", "Pro", "Flagship", "Share");
    private static final Set<String> LIFECYCLES = Set.of("active", "legacy");

    private final DeviceCatalogRepository catalogRepository;

    public ApiResult<Map<String, Object>> catalog() {
        try {
            PageResult<DeviceSkuView> page =
                    catalogRepository.pageSkus(new DeviceSkuQueryRequest("on", null, 1L, 500L));
            if (page == null || page.getRecords() == null) {
                return ApiResult.fail(500, "PRODUCT_CATALOG_INVALID");
            }
            List<Map<String, Object>> products = new ArrayList<>();
            LocalDateTime revision = null;
            for (DeviceSkuView sku : page.getRecords()) {
                if (sku == null || !"on".equals(sku.status())) {
                    return ApiResult.fail(500, "PRODUCT_CATALOG_INVALID");
                }
                products.add(product(sku));
                if (sku.updatedAt() != null && (revision == null || sku.updatedAt().isAfter(revision))) {
                    revision = sku.updatedAt();
                }
            }
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("source", "nx_admin_device_sku");
            response.put("revision", revision == null ? null : revision.toString());
            response.put("products", products);
            return ApiResult.ok(response);
        } catch (RuntimeException ex) {
            return ApiResult.fail(500, "PRODUCT_CATALOG_INVALID");
        }
    }

    private Map<String, Object> product(DeviceSkuView sku) {
        if (!StringUtils.hasText(sku.skuId())
                || !StringUtils.hasText(sku.name())
                || !TIERS.contains(sku.tier())
                || sku.price() == null
                || sku.price().signum() <= 0
                || sku.dailyEarn() == null
                || sku.dailyEarn().signum() < 0
                || sku.dailyEarnNex() == null
                || sku.dailyEarnNex().signum() < 0) {
            throw new IllegalArgumentException("invalid product catalog row");
        }
        String lifecycle = StringUtils.hasText(sku.lifecycle()) ? sku.lifecycle() : "active";
        if (!LIFECYCLES.contains(lifecycle)) {
            throw new IllegalArgumentException("invalid product lifecycle");
        }
        Long stock = stock(sku.stock());

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", sku.skuId());
        item.put("name", sku.name());
        item.put("tier", sku.tier());
        item.put("tagline", text(sku.tagline()));
        item.put("badge", nullableText(sku.badge()));
        item.put("gpu", text(sku.gpu()));
        item.put("vram", text(sku.vram()));
        item.put("hashRate", nullableText(sku.hashRate()));
        item.put("power", nullableText(sku.power()));
        item.put("dailyEarn", sku.dailyEarn());
        item.put("dailyEarnNEX", sku.dailyEarnNex());
        item.put("price", sku.price());
        item.put("sold", sku.sold() == null ? 0L : Math.max(0L, sku.sold()));
        item.put("stock", stock);
        item.put("features", sku.features() == null ? List.of() : sku.features());
        item.put("ai", ai(sku));
        item.put("status", lifecycle);
        item.put("unlocksAtPhase", nullableText(sku.unlockPhase()));
        item.put("purchaseGate", purchaseGate(sku.purchaseGate()));
        return item;
    }

    private Long stock(String value) {
        if (!StringUtils.hasText(value) || "∞".equals(value.trim())) return null;
        long parsed = Long.parseLong(value.trim());
        if (parsed < 0) throw new IllegalArgumentException("invalid product stock");
        return parsed;
    }

    private Map<String, Object> ai(DeviceSkuView sku) {
        if (sku.aiImageGenPerMin() == null
                && sku.aiLlmTokensPerSec() == null
                && sku.aiVideoMinPerHour() == null
                && sku.aiFineTuneMins() == null
                && !StringUtils.hasText(sku.aiUnlocks())) {
            return null;
        }
        Map<String, Object> ai = new LinkedHashMap<>();
        ai.put("imageGenPerMin", sku.aiImageGenPerMin());
        ai.put("llmTokensPerSec", sku.aiLlmTokensPerSec());
        ai.put("videoMinPerHour", sku.aiVideoMinPerHour());
        ai.put("fineTuneMins", sku.aiFineTuneMins());
        ai.put("unlocks", nullableText(sku.aiUnlocks()));
        return ai;
    }

    private Map<String, Object> purchaseGate(DevicePurchaseGateView gate) {
        if (gate == null) return null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rankMin", gate.rankMin());
        result.put("activeDirectMin", gate.activeDirectMin());
        result.put("teamVolumeMin", gate.teamVolumeMin());
        result.put("mode", gate.mode());
        result.put("quotaCap", gate.quotaCap());
        result.put("quotaSold", gate.quotaSold());
        result.put("quotaPeriod", gate.quotaPeriod());
        result.put("enforce", gate.enforce());
        return result;
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }

    private String nullableText(String value) {
        String normalized = text(value);
        return normalized.isEmpty() ? null : normalized;
    }
}
