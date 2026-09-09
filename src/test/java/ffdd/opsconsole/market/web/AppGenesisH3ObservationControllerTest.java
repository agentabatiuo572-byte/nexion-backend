package ffdd.opsconsole.market.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.growth.application.H3WeeklyParticipationObservationService;
import ffdd.opsconsole.market.application.AppGenesisService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class AppGenesisH3ObservationControllerTest {
    private final AppGenesisService service = mock(AppGenesisService.class);
    private final H3WeeklyParticipationObservationService observation = mock(H3WeeklyParticipationObservationService.class);
    private final AppGenesisController controller = new AppGenesisController(service, observation);

    @Test
    void secondaryMarketObservationUsesOnlyTheAuthenticatedUser() {
        when(observation.observeGenesisSecondaryMarket(42L)).thenReturn(ApiResult.ok(Map.of("accepted", true)));

        ApiResult<Map<String, Object>> result = controller.observeSecondaryMarket(auth("42", "USER"));

        assertThat(result.getCode()).isZero();
        verify(observation).observeGenesisSecondaryMarket(42L);
    }

    @Test
    void secondaryMarketObservationRejectsNonUserBeforeAnyWriteBoundary() {
        ApiResult<Map<String, Object>> result = controller.observeSecondaryMarket(auth("7", "ADMIN"));

        assertThat(result.getCode()).isEqualTo(403);
        verify(observation, never()).observeGenesisSecondaryMarket(7L);
    }

    private static UsernamePasswordAuthenticationToken auth(String id, String subjectType) {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(id, "n/a", java.util.List.of());
        authentication.setDetails(Map.of("subjectType", subjectType));
        return authentication;
    }
}
