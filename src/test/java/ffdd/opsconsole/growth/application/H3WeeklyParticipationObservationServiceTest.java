package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.finance.application.FundsSandboxProfileGuard;
import ffdd.opsconsole.market.mapper.AppGenesisMapper;
import ffdd.opsconsole.shared.canonical.mapper.CanonicalStateMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class H3WeeklyParticipationObservationServiceTest {
    private final CanonicalStateMapper storefront = org.mockito.Mockito.mock(CanonicalStateMapper.class);
    private final AppGenesisMapper genesis = org.mockito.Mockito.mock(AppGenesisMapper.class);
    private final H3WeeklyParticipationEvaluator evaluator = org.mockito.Mockito.mock(H3WeeklyParticipationEvaluator.class);
    private final FundsSandboxProfileGuard sandbox = org.mockito.Mockito.mock(FundsSandboxProfileGuard.class);

    @Test
    void productionDetailObservationUsesAuthenticatedAccountAndCanonicalProduct() {
        when(sandbox.isStrictProductionRuntime()).thenReturn(true);
        when(storefront.activeUserEnvironment(42L)).thenReturn(0);
        // The canonical visible-detail predicate deliberately has no stock condition.
        when(storefront.findVisibleStorefrontProduct("NX-A")).thenReturn(1L);

        var result = service(new MockEnvironment()).observeStorefrontProductDetail(42L, "NX-A");

        assertThat(result.getCode()).isZero();
        verify(evaluator).recordStorefrontProductDetail(42L, "NX-A");
    }

    @Test
    void zeroStockButVisibleProductIsStillAnEligibleDetailObservation() {
        when(sandbox.isStrictProductionRuntime()).thenReturn(true);
        when(storefront.activeUserEnvironment(42L)).thenReturn(0);
        when(storefront.findVisibleStorefrontProduct("HD-PRO-1U")).thenReturn(9L);

        assertThat(service(new MockEnvironment()).observeStorefrontProductDetail(42L, "HD-PRO-1U").getCode()).isZero();

        verify(evaluator).recordStorefrontProductDetail(42L, "HD-PRO-1U");
    }
    @Test
    void unknownStorefrontProductDoesNotCreateParticipation() {
        when(sandbox.isStrictProductionRuntime()).thenReturn(true);
        when(storefront.activeUserEnvironment(42L)).thenReturn(0);
        when(storefront.findVisibleStorefrontProduct("MISSING")).thenReturn(null);

        var result = service(new MockEnvironment()).observeStorefrontProductDetail(42L, "MISSING");

        assertThat(result.getCode()).isEqualTo(404);
        verify(evaluator, never()).recordStorefrontProductDetail(42L, "MISSING");
    }

    @Test
    void sandboxGenesisAccountCannotCreateProductionParticipation() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        when(genesis.userSandbox(42L)).thenReturn(1);

        assertThatThrownBy(() -> service(environment).observeGenesisSecondaryMarket(42L))
                .hasMessage("H3_GENESIS_PRODUCTION_USER_REQUIRED");
        verify(evaluator, never()).recordGenesisSecondaryMarketView(42L);
    }

    private H3WeeklyParticipationObservationService service(MockEnvironment environment) {
        return new H3WeeklyParticipationObservationService(storefront, genesis, evaluator, sandbox, environment);
    }
}
