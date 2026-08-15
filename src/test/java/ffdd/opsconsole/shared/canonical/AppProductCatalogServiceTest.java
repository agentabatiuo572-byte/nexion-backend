package ffdd.opsconsole.shared.canonical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.device.mapper.AppTradeinMapper;
import ffdd.opsconsole.commerce.mapper.CommerceAcceptanceSandboxMapper;
import ffdd.opsconsole.commerce.application.CommerceAcceptanceRun;
import ffdd.opsconsole.finance.application.FundsSandboxProfileGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AppProductCatalogServiceTest {
    private final AppTradeinMapper mapper = mock(AppTradeinMapper.class);
    private final CommerceAcceptanceSandboxMapper sandboxMapper = mock(CommerceAcceptanceSandboxMapper.class);
    private final FundsSandboxProfileGuard sandboxGuard = mock(FundsSandboxProfileGuard.class);
    private final StorefrontProductReleasePolicy releasePolicy = mock(StorefrontProductReleasePolicy.class);
    private final AppProductCatalogService service = new AppProductCatalogService(
            mapper, sandboxMapper, sandboxGuard, new CommerceAcceptanceRun("test-run-0001"),
            new ObjectMapper(), releasePolicy);

    AppProductCatalogServiceTest() {
        when(releasePolicy.evaluate(any(), any()))
                .thenReturn(StorefrontProductReleasePolicy.Decision.open(null));
    }

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
            assertThat(product.get("available")).isEqualTo(true);
            assertThat(product.get("unlocksAtPhase")).isNull();
        });
    }

    @Test
    void catalogProjectsTheServerReleaseDecisionInsteadOfDatabasePhaseIds() {
        when(mapper.listPurchasableCatalogTargets()).thenReturn(List.of(target(
                "stellarbox-pro-v2", "NexionBox Pro v2", "Pro", new BigDecimal("2639"), 3)));
        when(releasePolicy.evaluate("stellarbox-pro-v2", null)).thenReturn(
                StorefrontProductReleasePolicy.Decision.closed("E1_PHASE_NOT_REACHED", "52"));

        @SuppressWarnings("unchecked")
        Map<String, Object> product =
                (Map<String, Object>) ((List<?>) service.catalog().getData().get("products")).get(0);

        assertThat(product).containsEntry("available", false)
                .containsEntry("releaseState", "E1_PHASE_NOT_REACHED")
                .containsEntry("releasePhaseId", "52")
                .containsEntry("unlocksAtPhase", null);
    }

    @Test
    void catalogKeepsAnEmptyPurchasableInventoryHonestInsteadOfResurfacingAdminOnlySkus() {
        when(mapper.listPurchasableCatalogTargets()).thenReturn(List.of());

        Map<String, Object> data = service.catalog().getData();

        assertThat(data.get("source")).isEqualTo("nx_product");
        assertThat(data.get("products")).isEqualTo(List.of());
    }

    @Test
    void sandboxUserReceivesOnlyTheIsolatedCatalogSnapshot() {
        when(sandboxGuard.isLocalSandboxEnabled()).thenReturn(true);
        when(sandboxMapper.isSandboxUser(42L)).thenReturn(true);
        when(sandboxMapper.listEligibleCatalogSeeds()).thenReturn(List.of());
        when(sandboxMapper.listSandboxCatalog("test-run-0001")).thenReturn(List.of(new CommerceAcceptanceSandboxMapper.SandboxCatalogProduct(
                8L, "stellarbox-pro-v2", "NexionBox Pro v2", "Pro", new BigDecimal("2639"), 3, 2,
                "RTX 5090", 256, new BigDecimal("20"), new BigDecimal("2"), new BigDecimal("2"),
                "", null, null, 0L, null)));

        Map<String, Object> data = service.catalog(42L).getData();

        assertThat(data.get("source")).isEqualTo("mock");
        assertThat(data.get("catalogSource")).isEqualTo("nx_commerce_sandbox_catalog");
        assertThat(data.get("sourceEnvironment")).isEqualTo("SANDBOX");
        assertThat(data.get("runId")).isEqualTo("test-run-0001");
        assertThat((List<?>) data.get("products")).singleElement().satisfies(item ->
                assertThat(((Map<?, ?>) item).get("id")).isEqualTo("stellarbox-pro-v2"));
        verify(sandboxMapper).pruneCatalog("test-run-0001");
        org.mockito.Mockito.verifyNoInteractions(mapper);
    }

    @Test
    void catalogProjectsE1PresentationMetadataButNeverProjectsAnUnenforcedPurchaseGate() {
        when(mapper.listPurchasableCatalogTargets()).thenReturn(List.of(new AppTradeinMapper.CatalogTargetProduct(
                "stellarbox-pro-v2", "NexionBox Pro v2", "Pro", new BigDecimal("2639"), 3, "SERVER", 2,
                "RTX 5090", 256, new BigDecimal("20"), new BigDecimal("2"), new BigDecimal("2"),
                null, null, 0, null, null, "2200W", "[\"Managed hosting\"]",
                new BigDecimal("1080"), new BigDecimal("56000"), new BigDecimal("24"), new BigDecimal("12"),
                "Flagship AI", "{\"rankMin\":3,\"mode\":\"all\",\"enforce\":true}")));

        @SuppressWarnings("unchecked")
        Map<String, Object> product = (Map<String, Object>) ((List<?>) service.catalog().getData().get("products")).get(0);

        assertThat(product.get("power")).isEqualTo("2200W");
        assertThat(product.get("features")).isEqualTo(List.of("Managed hosting"));
        @SuppressWarnings("unchecked")
        Map<String, Object> ai = (Map<String, Object>) product.get("ai");
        assertThat(ai).containsEntry("unlocks", "Flagship AI");
        assertThat(product).containsEntry("purchaseGate", null);
    }

    @Test
    void sandboxCatalogRefreshesExistingRowsFromTheCanonicalProductBeforeReading() {
        when(sandboxGuard.isLocalSandboxEnabled()).thenReturn(true);
        when(sandboxMapper.isSandboxUser(42L)).thenReturn(true);
        var seed = new CommerceAcceptanceSandboxMapper.CatalogSeed(
                8L, "stellarbox-s1", "NexionBox S1", "Entry", new BigDecimal("1319"), 7, 4,
                "SERVER", "1", "RTX 4090", 24, new BigDecimal("10"), new BigDecimal("1"),
                new BigDecimal("2"), "", null, null, "test-run-0001");
        when(sandboxMapper.listEligibleCatalogSeeds()).thenReturn(List.of(seed));
        when(sandboxMapper.listSandboxCatalog("test-run-0001")).thenReturn(List.of());

        service.catalog(42L);

        verify(sandboxMapper).upsertCatalog(seed);
        verify(sandboxMapper).pruneCatalog("test-run-0001");
    }

    @Test
    void controlledProfileRejectsNonSandboxUserInsteadOfReadingProductionCatalog() {
        when(sandboxGuard.isLocalSandboxEnabled()).thenReturn(true);
        when(sandboxMapper.isSandboxUser(42L)).thenReturn(false);

        var result = service.catalog(42L);

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).isEqualTo("COMMERCE_SANDBOX_USER_REQUIRED");
        org.mockito.Mockito.verifyNoInteractions(mapper);
    }

    private AppTradeinMapper.CatalogTargetProduct target(
            String productNo, String name, String tier, BigDecimal price, int stock) {
        return new AppTradeinMapper.CatalogTargetProduct(
                productNo, name, tier, price, stock, "SERVER", 2,
                "RTX 5090", 256, new BigDecimal("20"), new BigDecimal("2"), new BigDecimal("2"),
                null, null, 0, null, null);
    }
}
