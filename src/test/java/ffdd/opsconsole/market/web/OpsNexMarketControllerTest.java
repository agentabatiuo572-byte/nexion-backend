package ffdd.opsconsole.market.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.market.application.G2G3AdminCommandService;
import ffdd.opsconsole.market.application.G4AdminCommandService;
import ffdd.opsconsole.market.application.OpsNexMarketService;
import ffdd.opsconsole.market.application.GenesisCatalogService;
import ffdd.opsconsole.market.application.OpsRepurchaseAdminService;
import ffdd.opsconsole.market.dto.NexMarketAdvanceRequest;
import ffdd.opsconsole.market.dto.NexMarketCurveUpdateRequest;
import ffdd.opsconsole.market.dto.NexMarketValueUpdateRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;

class OpsNexMarketControllerTest {
    private final OpsNexMarketService marketService = mock(OpsNexMarketService.class);
    private final GenesisCatalogService genesisCatalogService = mock(GenesisCatalogService.class);
    private final OpsNexMarketController controller = new OpsNexMarketController(
            marketService, null, null, null, genesisCatalogService);

    @Test
    void curveDelegatesToService() {
        when(marketService.overview()).thenReturn(ApiResult.ok(Map.of("frames", List.of())));

        assertThat(controller.curve().getData()).containsKey("frames");

        verify(marketService).overview();
    }

    @Test
    void updateCurveDelegatesWithIdempotencyHeader() {
        NexMarketCurveUpdateRequest request = new NexMarketCurveUpdateRequest(List.of(), "reason", "superadmin");
        when(marketService.updateWeeklyCurve("idem-g3", request)).thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.updateCurve("idem-g3", request).getCode()).isZero();

        verify(marketService).updateWeeklyCurve("idem-g3", request);
    }

    @Test
    void advanceDelegatesWithIdempotencyHeader() {
        NexMarketAdvanceRequest request = new NexMarketAdvanceRequest("daily", "system");
        when(marketService.advanceCurrentFrame("idem-advance", request)).thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.advance("idem-advance", request).getData()).containsEntry("ok", true);

        verify(marketService).advanceCurrentFrame("idem-advance", request);
    }

    @Test
    void updateControlDelegatesWithIdempotencyHeader() {
        NexMarketValueUpdateRequest request = new NexMarketValueUpdateRequest("D3", "pin demo", "superadmin");
        when(marketService.updateControl("idem-control", "pin", request)).thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.updateControl("idem-control", "pin", request).getCode()).isZero();

        verify(marketService).updateControl("idem-control", "pin", request);
    }

    @Test
    void updateOverrideDelegatesWithIdempotencyHeader() {
        NexMarketValueUpdateRequest request = new NexMarketValueUpdateRequest("0.166", "manual override", "superadmin");
        when(marketService.updateOverride("idem-override", "currentPrice", request)).thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.updateOverride("idem-override", "currentPrice", request).getCode()).isZero();

        verify(marketService).updateOverride("idem-override", "currentPrice", request);
    }

    @Test
    void repurchaseDelegatesToService() {
        when(marketService.repurchaseOverview()).thenReturn(ApiResult.ok(Map.of("domain", "G7")));

        assertThat(controller.repurchase().getData()).containsEntry("domain", "G7");

        verify(marketService).repurchaseOverview();
    }

    @Test
    void updateRepurchaseParamDelegatesWithIdempotencyHeader() {
        NexMarketValueUpdateRequest request = new NexMarketValueUpdateRequest("40", "raise apy", "superadmin");
        when(marketService.updateRepurchaseParam("idem-g7", "apy", request)).thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.updateRepurchaseParam("idem-g7", "apy", request).getCode()).isZero();

        verify(marketService).updateRepurchaseParam("idem-g7", "apy", request);
    }

    @Test
    void genesisDelegatesToService() {
        when(marketService.genesisOverview(2, 20)).thenReturn(ApiResult.ok(Map.of("domain", "G4")));
        when(genesisCatalogService.enrich(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(controller.genesis(2, 20).getData()).containsEntry("domain", "G4");

        verify(marketService).genesisOverview(2, 20);
    }

    @Test
    void updateGenesisParamDelegatesWithIdempotencyHeader() {
        NexMarketValueUpdateRequest request = new NexMarketValueUpdateRequest("0.2", "raise dividend", "superadmin");
        when(marketService.updateGenesisParam("idem-g4", "dividend", request)).thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.updateGenesisParam("idem-g4", "dividend", request).getCode()).isZero();

        verify(marketService).updateGenesisParam("idem-g4", "dividend", request);
    }

    @Test
    void updateGenesisMarketStatusDelegatesWithIdempotencyHeader() {
        NexMarketValueUpdateRequest request = new NexMarketValueUpdateRequest("off", "securities risk", "superadmin");
        when(marketService.updateGenesisMarketStatus("idem-g4-switch", request)).thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.updateGenesisMarketStatus("idem-g4-switch", request).getCode()).isZero();

        verify(marketService).updateGenesisMarketStatus("idem-g4-switch", request);
    }

    @Test
    void initializeGenesisSeriesDelegatesWithIdempotencyHeaderAndReturnsRefreshedOverview() {
        GenesisCatalogService.SeriesBootstrapRequest request =
                new GenesisCatalogService.SeriesBootstrapRequest("GENESIS-2026", "Genesis 2026",
                        "initialize active series", "superadmin");
        when(genesisCatalogService.initializeSeries("idem-g4-series", request)).thenReturn(ApiResult.ok());
        when(marketService.genesisOverview()).thenReturn(ApiResult.ok(Map.of("domain", "G4")));
        when(genesisCatalogService.enrich(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(controller.initializeGenesisSeries("idem-g4-series", request).getData())
                .containsEntry("domain", "G4");

        verify(genesisCatalogService).initializeSeries("idem-g4-series", request);
        verify(marketService).genesisOverview();
    }

    @Test
    void initializeGenesisSeriesRejectsReadOnlyAuthorityThroughTheRealMethodSecurityProxy() {
        try (var context = new AnnotationConfigApplicationContext(SecurityFixture.class)) {
            var securedController = context.getBean(OpsNexMarketController.class);
            var securedCatalog = context.getBean(GenesisCatalogService.class);
            SecurityContextHolder.getContext().setAuthentication(
                    new TestingAuthenticationToken("operator", "unused", "finprod_g4_read"));

            assertThatThrownBy(() -> securedController.initializeGenesisSeries("idem-denied",
                    new GenesisCatalogService.SeriesBootstrapRequest(
                            "GENESIS-2026", "Genesis 2026", "initialize catalog safely", "operator")))
                    .isInstanceOf(AccessDeniedException.class);
            verifyNoInteractions(securedCatalog);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void priceWriterCanCreateFirstTierButCannotToggleGenesisMarketThroughRealMethodSecurity() {
        try (var context = new AnnotationConfigApplicationContext(SecurityFixture.class)) {
            var securedController = context.getBean(OpsNexMarketController.class);
            var securedCatalog = context.getBean(GenesisCatalogService.class);
            var securedMarket = context.getBean(OpsNexMarketService.class);
            SecurityContextHolder.getContext().setAuthentication(
                    new TestingAuthenticationToken("operator", "unused", "finprod_g4_price_write"));
            var tierRequest = new GenesisCatalogService.TierRequest(
                    1000, new BigDecimal("9999"), 7L, "create first quote tier", "operator");
            when(securedCatalog.createTier("idem-first-tier", tierRequest)).thenReturn(ApiResult.ok());
            when(securedMarket.genesisOverview()).thenReturn(ApiResult.ok(Map.of("domain", "G4")));
            when(securedCatalog.enrich(org.mockito.ArgumentMatchers.any()))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            assertThat(securedController.createGenesisTier("idem-first-tier", tierRequest).getCode()).isZero();
            assertThatThrownBy(() -> securedController.updateGenesisMarketOpenState("idem-toggle-denied",
                    new GenesisCatalogService.MarketStateRequest(
                            "open", "reopen after review", "operator", "default", 3L)))
                    .isInstanceOf(AccessDeniedException.class);

            verify(securedCatalog).createTier("idem-first-tier", tierRequest);
            verify(securedCatalog, org.mockito.Mockito.never()).updateMarketState(
                    org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Configuration
    @EnableMethodSecurity
    static class SecurityFixture {
        @Bean OpsNexMarketService marketService() { return mock(OpsNexMarketService.class); }
        @Bean G2G3AdminCommandService commandService() { return mock(G2G3AdminCommandService.class); }
        @Bean G4AdminCommandService g4CommandService() { return mock(G4AdminCommandService.class); }
        @Bean OpsRepurchaseAdminService repurchaseService() { return mock(OpsRepurchaseAdminService.class); }
        @Bean GenesisCatalogService genesisCatalogService() { return mock(GenesisCatalogService.class); }
        @Bean OpsNexMarketController controller(OpsNexMarketService marketService,
                                                G2G3AdminCommandService commandService,
                                                G4AdminCommandService g4CommandService,
                                                OpsRepurchaseAdminService repurchaseService,
                                                GenesisCatalogService genesisCatalogService) {
            return new OpsNexMarketController(marketService, commandService, g4CommandService,
                    repurchaseService, genesisCatalogService);
        }
    }

    @Test
    void rerunGenesisDividendBatchDelegatesWithIdempotencyHeader() {
        NexMarketValueUpdateRequest request = new NexMarketValueUpdateRequest("rerun", "retry failed rows", "superadmin");
        when(marketService.rerunGenesisDividendBatch("idem-g4-rerun", "GD-0611", request)).thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.rerunGenesisDividendBatch("idem-g4-rerun", "GD-0611", request).getCode()).isZero();

        verify(marketService).rerunGenesisDividendBatch("idem-g4-rerun", "GD-0611", request);
    }
}
