package ffdd.opsconsole.device.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
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
}
