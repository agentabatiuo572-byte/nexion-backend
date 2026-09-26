package ffdd.opsconsole.device.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import ffdd.opsconsole.shared.canonical.StorefrontProductPublishGate;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class AppProductCatalogMetadataSqlContractTest {
    @Test
    void appCatalogUsesProductTruthAndOnlyJoinsE1ForPresentationMetadata() throws Exception {
        Method method = AppTradeinMapper.class.getMethod("listPurchasableCatalogTargets");
        String sql = String.join("\n", method.getAnnotation(Select.class).value());

        assertThat(sql)
                .contains("FROM nx_product p")
                .contains("LEFT JOIN nx_admin_device_sku s")
                .contains("s.features_json AS featuresJson")
                .contains("NULL AS purchaseGateJson")
                .doesNotContain("s.purchase_gate_json AS purchaseGateJson");
    }

    @Test
    void signedSkuImageRequiresTheCurrentPublishedProductAndExactMediaPair() throws Exception {
        Method method = AppTradeinMapper.class.getMethod("currentStorefrontImage",
                String.class, String.class, String.class);
        String sql = String.join("\n", method.getAnnotation(Select.class).value());

        assertThat(sql).contains("p.product_no=#{productNo}", "p.store_visible=1",
                "BINARY s.image_asset_id = BINARY #{assetId}",
                "BINARY s.image_object_key = BINARY #{objectKey}",
                StorefrontProductPublishGate.PUBLISHABLE_SQL);
    }
}
