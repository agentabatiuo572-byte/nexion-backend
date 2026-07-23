package ffdd.opsconsole.finance.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ffdd.opsconsole.finance.application.D5WithdrawalAuthorization;
import ffdd.opsconsole.finance.application.OpsFinanceService;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.security.AdminRbacAuthorizationFilter;
import ffdd.opsconsole.shared.security.JwtAuthenticationFilter;
import ffdd.opsconsole.shared.security.SecurityConfig;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(OpsWithdrawalLimitsController.class)
@Import({SecurityConfig.class, D5WithdrawalAuthorization.class})
@ContextConfiguration(classes = {OpsWithdrawalLimitsController.class, SecurityConfig.class, D5WithdrawalAuthorization.class})
class OpsWithdrawalLimitsControllerTest {
    @Autowired private MockMvc mockMvc;
    @MockBean private OpsFinanceService financeService;
    @MockBean private JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean private AdminRbacAuthorizationFilter adminRbacAuthorizationFilter;

    @BeforeEach
    void continueFilters() throws Exception {
        doAnswer(invocation -> {
            var chain = invocation.getArgument(2, jakarta.servlet.FilterChain.class);
            chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(jwtAuthenticationFilter).doFilter(any(), any(), any());
        doAnswer(invocation -> {
            var chain = invocation.getArgument(2, jakarta.servlet.FilterChain.class);
            chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(adminRbacAuthorizationFilter).doFilter(any(), any(), any());
    }

    @Test
    void canonicalGetRequiresD5Read() throws Exception {
        when(financeService.withdrawalLimits()).thenReturn(ApiResult.ok(Map.of("version", 1L)));
        mockMvc.perform(get("/api/admin/withdraw/limits")
                        .with(user("auditor").authorities(() -> "finance_d5_read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1));
        mockMvc.perform(get("/api/admin/withdraw/limits")
                        .with(user("support").authorities(() -> "finance_d2_read")))
                .andExpect(status().isForbidden());
    }

    @Test
    void phaseFieldReturnsHttp422AndH1Redirect() throws Exception {
        when(financeService.updateWithdrawalLimits(anyString(), any())).thenReturn(
                new ApiResult<>(422, "PHASE_PARAM_READONLY", Map.of("redirect", "/admin/phase/h1")));
        mockMvc.perform(put("/api/admin/withdraw/limits")
                        .with(user("risk").authorities(() -> "finance_d5_read"))
                        .header("Idempotency-Key", "d5-phase-readonly")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cooldownDays\":null}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PHASE_PARAM_READONLY"))
                .andExpect(jsonPath("$.redirect").value("/admin/phase/h1"));
    }

    @Test
    void missingReasonUsesHttp400() throws Exception {
        when(financeService.updateWithdrawalLimits(anyString(), any()))
                .thenReturn(new ApiResult<>(422, "REASON_REQUIRED", null));
        mockMvc.perform(put("/api/admin/withdraw/limits")
                        .with(user("lead").authorities(() -> "finance_d5_daily_limit_write"))
                        .header("Idempotency-Key", "d5-reason-required")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1,\"dailyLimitCount\":3}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
    }
}
