package ffdd.opsconsole.market.web;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ffdd.opsconsole.market.application.AppStakingService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AppStakingOpenStatusControllerTest {
    private final AppStakingService service = mock(AppStakingService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AppStakingController(service)).build();

    @Test
    void getUsesOnlyAuthenticatedUserIgnoringSpoofedScopeOwnerAndEnvironment() throws Exception {
        var auth = new UsernamePasswordAuthenticationToken("60723153007", null, List.of());
        auth.setDetails(Map.of("subjectType", "USER"));
        when(service.openStatus(60723153007L, "test-only-original-key", "usdt30d", "20"))
                .thenReturn(ApiResult.ok(Map.of("status", "FAILED")));
        mvc.perform(get("/api/stakes/open-status").principal(auth)
                .header("Idempotency-Key", "test-only-original-key").param("tierKey", "usdt30d").param("amountUsdt", "20")
                .param("userId", "8").param("scope", "spoofed").param("runId", "spoofed"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("FAILED"));
        verify(service).openStatus(60723153007L, "test-only-original-key", "usdt30d", "20");
        verifyNoMoreInteractions(service);
    }

    @Test
    void anonymousAdminMissingSubjectAndUnauthenticatedUserCannotQuery() throws Exception {
        var admin = new UsernamePasswordAuthenticationToken("7", null, List.of());
        admin.setDetails(Map.of("subjectType", "ADMIN"));
        var missing = new UsernamePasswordAuthenticationToken("7", null, List.of());
        var guest = new UsernamePasswordAuthenticationToken("7", null);
        guest.setDetails(Map.of("subjectType", "USER"));
        for (var auth : java.util.Arrays.asList(null, admin, missing, guest)) {
            var request = get("/api/stakes/open-status").header("Idempotency-Key", "key").param("tierKey", "usdt30d").param("amountUsdt", "20");
            if (auth != null) request.principal(auth);
            mvc.perform(request).andExpect(jsonPath("$.code").value(403));
        }
        verifyNoInteractions(service);
    }
}
