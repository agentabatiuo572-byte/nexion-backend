package ffdd.opsconsole.commerce.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.commerce.application.AppOrderCommandService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;

class AppOrderCommandControllerTest {
    @Test
    void paymentCommandUsesAuthenticatedUserAndIdempotencyKey() {
        AppOrderCommandService service = mock(AppOrderCommandService.class);
        AppOrderCommandController controller = new AppOrderCommandController(service);
        TestingAuthenticationToken authentication = new TestingAuthenticationToken("7", "ignored", List.of());
        authentication.setDetails(Map.of("subjectType", "USER"));
        var pin=new ffdd.opsconsole.auth.facade.PaymentAuthorizationFacade.Authorization("018290",1L,"attempt-1");
        when(service.pay(7L, "CSO-1", "payment-key",pin))
                .thenReturn(ApiResult.ok(Map.of("orderNo", "CSO-1", "paymentStatus", "PAID")));

        ApiResult<Map<String, Object>> result = controller.pay("CSO-1", "payment-key", pin,authentication);

        assertThat(result.getCode()).isZero();
        verify(service).pay(eq(7L), eq("CSO-1"), eq("payment-key"),eq(pin));
    }

    @Test
    void paymentCommandRejectsNonUserSubjectsBeforeCallingService() {
        AppOrderCommandService service = mock(AppOrderCommandService.class);
        AppOrderCommandController controller = new AppOrderCommandController(service);
        TestingAuthenticationToken authentication = new TestingAuthenticationToken("1", "ignored", List.of());
        authentication.setDetails(Map.of("subjectType", "ADMIN"));

        ApiResult<Map<String, Object>> result = controller.pay("CSO-1", "payment-key", null,authentication);

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).isEqualTo("USER_SUBJECT_REQUIRED");
    }
}
