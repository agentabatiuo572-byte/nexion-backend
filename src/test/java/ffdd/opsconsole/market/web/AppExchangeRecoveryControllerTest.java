package ffdd.opsconsole.market.web;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ffdd.opsconsole.market.application.AppExchangeService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AppExchangeRecoveryControllerTest {
    private final AppExchangeService service = mock(AppExchangeService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AppExchangeController(service)).build();

    @Test
    void routeUsesAuthenticatedUserAndOriginalKeyIgnoringSpoofedOwner() throws Exception {
        var auth = new UsernamePasswordAuthenticationToken("7", null, List.of());
        auth.setDetails(Map.of("subjectType", "USER"));
        var request = new AppExchangeService.SwapRequest("USDT_TO_NEX", new BigDecimal("20.00"), true);
        when(service.recovery(7L, "original-key", request)).thenReturn(ApiResult.ok(Map.of("status", "NOT_FOUND")));
        mvc.perform(get("/api/exchange/recovery").principal(auth).header("Idempotency-Key", "original-key")
                        .param("direction", "USDT_TO_NEX").param("fromAmount", "20.00").param("queueIfCapped", "true")
                        .param("userId", "8").param("runId", "spoofed-run"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("NOT_FOUND"));
        verify(service).recovery(7L, "original-key", request);
        verifyNoMoreInteractions(service);
    }

    @Test
    void anonymousAdminMissingSubjectAndUnauthenticatedUserCannotReadReceipt() throws Exception {
        var admin = new UsernamePasswordAuthenticationToken("7", null, List.of());
        admin.setDetails(Map.of("subjectType", "ADMIN"));
        var missingSubject = new UsernamePasswordAuthenticationToken("7", null, List.of());
        var unauthenticated = new UsernamePasswordAuthenticationToken("7", null);
        unauthenticated.setDetails(Map.of("subjectType", "USER"));
        var invalidId = new UsernamePasswordAuthenticationToken("-7", null, List.of());
        invalidId.setDetails(Map.of("subjectType", "USER"));
        for (var auth : java.util.Arrays.asList(null, admin, missingSubject, unauthenticated, invalidId)) {
            var request = get("/api/exchange/recovery").header("Idempotency-Key", "original-key")
                    .param("direction", "USDT_TO_NEX").param("fromAmount", "20");
            if (auth != null) request.principal(auth);
            mvc.perform(request)
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(403));
        }
        verifyNoInteractions(service);
    }
}
