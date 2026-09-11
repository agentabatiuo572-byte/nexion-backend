package ffdd.opsconsole.market.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.growth.application.H3WeeklyParticipationObservationService;
import ffdd.opsconsole.market.application.AppGenesisService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.RequestBody;

class AppGenesisControllerBuyContractTest {
    @Test
    void buyBindsRequiredExpectedPriceBodyAndForwardsItUnchanged() throws Exception {
        AppGenesisService service = mock(AppGenesisService.class);
        AppGenesisController controller = new AppGenesisController(service,
                mock(H3WeeklyParticipationObservationService.class));
        Authentication authentication = mock(Authentication.class);
        AppGenesisService.BuyRequest request =
                new AppGenesisService.BuyRequest(new BigDecimal("120.123456"));
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getPrincipal()).thenReturn("42");
        when(authentication.getDetails()).thenReturn(Map.of("subjectType", "USER"));
        when(service.buyListing(42L, "holding-42", "buy-key", request))
                .thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.buy("holding-42", request, "buy-key", authentication).getCode()).isZero();
        verify(service).buyListing(42L, "holding-42", "buy-key", request);

        Method buy = AppGenesisController.class.getMethod("buy", String.class,
                AppGenesisService.BuyRequest.class, String.class, Authentication.class);
        assertThat(buy.getParameters()[1].isAnnotationPresent(RequestBody.class)).isTrue();
    }
}
