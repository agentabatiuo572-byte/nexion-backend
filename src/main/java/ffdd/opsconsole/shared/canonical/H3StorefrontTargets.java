package ffdd.opsconsole.shared.canonical;

import ffdd.opsconsole.device.mapper.AppTradeinMapper;
import ffdd.opsconsole.shared.capacity.MonthlyTargetProjection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** H3 targets use the same release and specification facts as the ordinary storefront. */
@Service
@RequiredArgsConstructor
public class H3StorefrontTargets {
    public static final String PRODUCT_DETAIL = "/pages/store/detail?id=";
    private final AppTradeinMapper catalog;
    private final StorefrontProductReleasePolicy releasePolicy;

    public List<Target> list() {
        var products = catalog.listPurchasableCatalogTargets();
        var releases = StorefrontSeriesRepresentatives.releaseDecisions(products, releasePolicy);
        return products.stream().filter(product -> product.liveActivityVisible() != null
                && product.productNo() != null && product.productNo().matches("[A-Za-z0-9._~-]{1,96}")
                && StorefrontSeriesRepresentatives.hasValidRenderData(product,
                        StorefrontDisplaySales.total(product.sold(), product.displaySoldBase()),
                        StorefrontSeriesRepresentatives.canonicalProductType(product.deviceType(), product.tier())))
                .map(product -> {
                    var release = releases.get(product.productNo());
                    boolean openable = release != null && release.available();
                    var status = StorefrontSeriesRepresentatives.purchaseStatus(product, release);
                    var dailyEarn = product.monthlyTargetUsdt() == null
                            ? product.dailyUsdt() : MonthlyTargetProjection.daily(product.monthlyTargetUsdt());
                    boolean roiEligible = openable && "S1".equals(product.seriesCode())
                            && !"Share".equals(product.tier())
                            && !"PRODUCT_SPECS_UNAVAILABLE".equals(status.blockedReason())
                            && dailyEarn != null && dailyEarn.signum() > 0;
                    return new Target(product.productNo(), product.name(), PRODUCT_DETAIL + product.productNo(),
                            openable, product.seriesCode(), roiEligible);
                }).toList();
    }

    public Target byProductNo(String productNo) {
        if (productNo == null || !productNo.matches("[A-Za-z0-9._~-]{1,96}")) return null;
        return list().stream().filter(target -> target.productNo().equals(productNo)).findFirst().orElse(null);
    }

    public record Target(String productNo, String name, String actionRoute, boolean openable,
                         String seriesCode, boolean roiEligible) { }
}
