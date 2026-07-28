package ffdd.opsconsole.shared.canonical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.device.domain.DeviceCatalogRepository;
import ffdd.opsconsole.device.domain.DevicePurchaseGateView;
import ffdd.opsconsole.device.domain.DeviceSkuView;
import ffdd.opsconsole.shared.api.PageResult;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AppProductCatalogServiceTest {
    private final DeviceCatalogRepository repository = mock(DeviceCatalogRepository.class);
    private final AppProductCatalogService service = new AppProductCatalogService(repository);

    @Test
    void catalogUsesOnlyBackendOnSkusAndMapsAppContract() {
        DeviceSkuView sku = sku("server-sku", "on", "23", LocalDateTime.of(2026, 7, 27, 1, 2));
        when(repository.pageSkus(argThat(query ->
                "on".equals(query.status()) && query.pageNum() == 1L && query.pageSize() == 500L)))
                .thenReturn(new PageResult<>(1, 1, 500, List.of(sku)));

        Map<String, Object> data = service.catalog().getData();

        assertThat(data.get("source")).isEqualTo("nx_admin_device_sku");
        assertThat(data.get("revision")).isEqualTo("2026-07-27T01:02");
        assertThat((List<?>) data.get("products")).singleElement().satisfies(item -> {
            Map<?, ?> product = (Map<?, ?>) item;
            assertThat(product.get("id")).isEqualTo("server-sku");
            assertThat(product.get("stock")).isEqualTo(23L);
            assertThat(product.get("dailyEarnNEX")).isEqualTo(new BigDecimal("80"));
            assertThat(product.get("unlocksAtPhase")).isEqualTo("P3");
            assertThat(product.get("purchaseGate")).isInstanceOf(Map.class);
        });
    }

    @Test
    void malformedStockFailsClosedInsteadOfPublishingAFalseCatalog() {
        when(repository.pageSkus(argThat(query -> "on".equals(query.status()))))
                .thenReturn(new PageResult<>(1, 1, 500,
                        List.of(sku("bad-stock", "on", "not-a-number", LocalDateTime.now()))));

        assertThat(service.catalog().getCode()).isEqualTo(500);
        assertThat(service.catalog().getMessage()).isEqualTo("PRODUCT_CATALOG_INVALID");
    }

    @Test
    void unlimitedStockIsPublishedAsNullInsteadOfBreakingTheWholeCatalog() {
        when(repository.pageSkus(argThat(query -> "on".equals(query.status()))))
                .thenReturn(new PageResult<>(1, 1, 500,
                        List.of(sku("unlimited", "on", "∞", LocalDateTime.now()))));

        Map<String, Object> data = service.catalog().getData();
        Map<?, ?> product = (Map<?, ?>) ((List<?>) data.get("products")).get(0);

        assertThat(product.get("stock")).isNull();
    }

    private DeviceSkuView sku(String id, String status, String stock, LocalDateTime updatedAt) {
        return new DeviceSkuView(
                id, "Server SKU", "Pro", "Server canonical", "New",
                "8x GPU", "192GB", "2480 MH/s", "2400W", "Singapore",
                new BigDecimal("1199"), new BigDecimal("13"), new BigDecimal("80"),
                null, null, null, 10L, stock, new BigDecimal("4.9"), 20L,
                720L, 38000L, 12L, 20L, "TK-1", List.of("Feature A"),
                2, "active", null, null, "P3",
                new DevicePurchaseGateView(null, 5, null, "all", 1000, 977, "month", true),
                null, null, null, "new", status,
                LocalDateTime.of(2026, 7, 1, 0, 0), updatedAt);
    }
}
