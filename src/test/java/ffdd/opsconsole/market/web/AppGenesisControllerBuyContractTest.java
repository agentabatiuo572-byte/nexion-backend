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
    void recoveryIsReadOnlyAndRequiresANormalUserSubject() throws Exception {
        AppGenesisService service = mock(AppGenesisService.class);
        AppGenesisController controller = new AppGenesisController(service,
                mock(H3WeeklyParticipationObservationService.class));
        assertThat(controller.commandStatus("holding-42", "list", "key", BigDecimal.TEN, null).getCode()).isEqualTo(403);
        Authentication authentication = mock(Authentication.class);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getPrincipal()).thenReturn("42");
        when(authentication.getDetails()).thenReturn(Map.of("subjectType", "ADMIN"));
        assertThat(controller.commandStatus("holding-42", "list", "key", BigDecimal.TEN, authentication).getCode()).isEqualTo(403);
        org.mockito.Mockito.verifyNoInteractions(service);
        when(authentication.getDetails()).thenReturn(Map.of("subjectType", "USER"));
        when(service.commandStatus(42L, "list", "holding-42", "key", BigDecimal.TEN))
                .thenReturn(ApiResult.ok(Map.of("status", "SUCCEEDED")));
        assertThat(controller.commandStatus("holding-42", "list", "key", BigDecimal.TEN, authentication).getCode()).isZero();
        verify(service).commandStatus(42L, "list", "holding-42", "key", BigDecimal.TEN);
        Method method = AppGenesisController.class.getMethod("commandStatus", String.class, String.class, String.class,
                BigDecimal.class, Authentication.class);
        assertThat(method.isAnnotationPresent(org.springframework.web.bind.annotation.GetMapping.class)).isTrue();
    }

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
