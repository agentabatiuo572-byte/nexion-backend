package ffdd.opsconsole.shared.canonical;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.device.domain.DeviceSkuSpecifications;
import ffdd.opsconsole.device.domain.ProductInventoryMode;
import ffdd.opsconsole.device.mapper.AppTradeinMapper;
import ffdd.opsconsole.device.mapper.AppTradeinMapper.CatalogTargetProduct;
import ffdd.opsconsole.shared.canonical.StorefrontProductReleasePolicy.Decision;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Chooses a purchasable representative for each raw storefront series. */
@Service
@RequiredArgsConstructor
public class StorefrontSeriesRepresentatives {
    private static final Set<String> TIERS = Set.of("Entry", "Pro", "Flagship", "Share");
    private static final Comparator<CatalogTargetProduct> REPRESENTATIVE_ORDER =
            Comparator.comparing(CatalogTargetProduct::priceUsdt)
                    .thenComparing(CatalogTargetProduct::createdAt, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(CatalogTargetProduct::productId, Comparator.nullsLast(Comparator.naturalOrder()));

    private final AppTradeinMapper tradeinMapper;
    private final StorefrontProductReleasePolicy productReleasePolicy;
    private final ObjectMapper objectMapper;

    public Map<String, String> currentProductNos() {
        Map<String, String> productNos = new LinkedHashMap<>();
        currentRepresentatives().forEach((series, target) -> productNos.put(series, target.productNo()));
        return productNos;
    }

    public Map<String, Integer> currentVramGb() {
        Map<String, Integer> vramBySeries = new LinkedHashMap<>();
        currentRepresentatives().forEach((series, target) -> {
            if (target.vramTotalGb() != null && target.vramTotalGb() > 0) {
                vramBySeries.put(series, target.vramTotalGb());
            }
        });
        return vramBySeries;
    }

    private Map<String, CatalogTargetProduct> currentRepresentatives() {
        List<CatalogTargetProduct> targets = tradeinMapper.listPurchasableCatalogTargets();
        if (targets == null || targets.isEmpty()) return Map.of();
        List<CatalogTargetProduct> renderable = targets.stream().filter(this::rendersInFormalCatalog).toList();
        return select(renderable, releaseDecisions(renderable, productReleasePolicy));
    }

    static Map<String, Decision> releaseDecisions(List<CatalogTargetProduct> targets,
                                                   StorefrontProductReleasePolicy productReleasePolicy) {
        Map<String, String> phases = new LinkedHashMap<>();
        for (CatalogTargetProduct target : targets) {
            phases.put(target.productNo(), target.unlockPhase());
        }
        return productReleasePolicy.evaluateBatch(phases);
    }

    static Map<String, CatalogTargetProduct> select(List<CatalogTargetProduct> renderable, Map<String, Decision> releases) {
        Map<String, CatalogTargetProduct> winners = new LinkedHashMap<>();
        for (CatalogTargetProduct target : renderable) {
            if (StringUtils.hasText(target.seriesCode())
                    && purchaseStatus(target, releases.get(target.productNo())).canPurchase()) {
                winners.merge(target.seriesCode(), target,
                        (left, right) -> REPRESENTATIVE_ORDER.compare(left, right) <= 0 ? left : right);
            }
        }
        return winners;
    }

    private boolean rendersInFormalCatalog(CatalogTargetProduct target) {
        if (target == null || target.liveActivityVisible() == null || !StringUtils.hasText(target.seriesCode())
                || !hasValidRenderData(target, StorefrontDisplaySales.total(target.sold(), target.displaySoldBase()),
                        canonicalProductType(target.deviceType(), target.tier()))) return false;
        try {
            features(objectMapper, target.featuresJson());
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    static boolean hasValidRenderData(CatalogTargetProduct target, Number sold, String productType) {
        if (target == null) return false;
        ProductInventoryMode mode = ProductInventoryMode.parse(target.inventoryMode());
        return StringUtils.hasText(target.productNo())
                && StringUtils.hasText(target.name())
                && target.tier() != null && TIERS.contains(target.tier())
                && target.priceUsdt() != null && target.priceUsdt().signum() > 0
                && mode != null
                && (mode == ProductInventoryMode.UNLIMITED ? "SHARE".equals(productType)
                        : target.stock() != null && target.stock() >= 0)
                && target.dailyUsdt() != null && target.dailyUsdt().signum() >= 0
                && target.dailyNex() != null && target.dailyNex().signum() >= 0
                && target.computePoints() != null && target.computePoints() >= 0
                && sold != null && sold.longValue() >= 0;
    }

    static PurchaseStatus purchaseStatus(CatalogTargetProduct target, Decision release) {
        boolean specsComplete = "Share".equals(target.tier()) || DeviceSkuSpecifications.hasPurchaseSpecifications(
                target.gpuModel(), target.vramTotalGb(), target.power());
        boolean outOfStock = !ProductInventoryMode.isUnlimited(target.inventoryMode()) && Integer.valueOf(0).equals(target.stock());
        boolean available = release != null && release.available() && specsComplete;
        String blockedReason = !specsComplete ? "PRODUCT_SPECS_UNAVAILABLE"
                : outOfStock ? "PRODUCT_OUT_OF_STOCK" : null;
        return new PurchaseStatus(available, blockedReason);
    }

    record PurchaseStatus(boolean available, String blockedReason) {
        boolean canPurchase() {
            return available && blockedReason == null;
        }
    }

    static String canonicalProductType(String raw, String tier) {
        if (StringUtils.hasText(raw)) {
            String normalized = raw.trim().toUpperCase(Locale.ROOT);
            if ("DEVICE".equals(normalized) || "SHARE".equals(normalized)) return normalized;
        }
        // Compatibility for old isolated-catalog fixtures only.
        return "Share".equals(tier) ? "SHARE" : "DEVICE";
    }

    static List<String> features(ObjectMapper objectMapper, String json) {
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
}
