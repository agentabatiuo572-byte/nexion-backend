package ffdd.opsconsole.shared.canonical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.device.mapper.AppTradeinMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AppProductCatalogServiceTest {
    private final AppTradeinMapper mapper = mock(AppTradeinMapper.class);
    private final AppProductCatalogService service = new AppProductCatalogService(mapper);

    @Test
    void catalogPublishesOnlyTargetsAcceptedByTheSameTradeinQuoteAndSubmitPath() {
        when(mapper.listPurchasableCatalogTargets()).thenReturn(List.of(target(
                "stellarbox-pro-v2", "NexionBox Pro v2", "Pro", new BigDecimal("2639"), 3)));

        Map<String, Object> data = service.catalog().getData();

        assertThat(data.get("source")).isEqualTo("nx_product");
        assertThat((List<?>) data.get("products")).singleElement().satisfies(item -> {
            Map<?, ?> product = (Map<?, ?>) item;
            assertThat(product.get("id")).isEqualTo("stellarbox-pro-v2");
            assertThat(product.get("name")).isEqualTo("NexionBox Pro v2");
            assertThat(product.get("tier")).isEqualTo("Pro");
            assertThat(product.get("price")).isEqualTo(new BigDecimal("2639"));
            assertThat(product.get("stock")).isEqualTo(3);
            assertThat(product.get("status")).isEqualTo("active");
            assertThat(product.get("unlocksAtPhase")).isNull();
        });
    }

    @Test
    void catalogKeepsAnEmptyPurchasableInventoryHonestInsteadOfResurfacingAdminOnlySkus() {
        when(mapper.listPurchasableCatalogTargets()).thenReturn(List.of());

        Map<String, Object> data = service.catalog().getData();

        assertThat(data.get("source")).isEqualTo("nx_product");
        assertThat(data.get("products")).isEqualTo(List.of());
    }

    private AppTradeinMapper.CatalogTargetProduct target(
            String productNo, String name, String tier, BigDecimal price, int stock) {
        return new AppTradeinMapper.CatalogTargetProduct(
                productNo, name, tier, price, stock, "SERVER", 2,
                "RTX 5090", 256, new BigDecimal("20"), new BigDecimal("2"), new BigDecimal("2"),
                null, null, 0, null, null);
    }
}
