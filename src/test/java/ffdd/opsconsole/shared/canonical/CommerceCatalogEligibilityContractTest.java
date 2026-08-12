package ffdd.opsconsole.shared.canonical;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CommerceCatalogEligibilityContractTest {
    @Test
    void catalogAndOrdinaryOrderUseTheSameActiveVisiblePositiveStockAndPriceBoundary() throws Exception {
        String catalog = Files.readString(Path.of("src/main/java/ffdd/opsconsole/device/mapper/AppTradeinMapper.java"));
        String order = Files.readString(Path.of("src/main/java/ffdd/opsconsole/shared/canonical/mapper/CanonicalStateMapper.java"));

        assertThat(catalog).contains("store_visible=1", "UPPER(status) IN ('ACTIVE','ON_SALE')", "price_usdt>0", "stock>=1");
        assertThat(order).contains("COALESCE(store_visible, 1) = 1", "UPPER(status) IN ('ACTIVE', 'ON_SALE')",
                "price_usdt > 0 AND stock >= 1");
    }
}
