package ffdd.opsconsole.finance.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ffdd.opsconsole.finance.cregis.CregisSandboxService;
import ffdd.opsconsole.shared.security.AdminRbacAuthorizationFilter;
import ffdd.opsconsole.shared.security.JwtAuthenticationFilter;
import ffdd.opsconsole.shared.security.SecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CregisSandboxController.class)
@Import(SecurityConfig.class)
@ContextConfiguration(classes = {CregisSandboxController.class, SecurityConfig.class})
class CregisSandboxControllerSecurityTest {
    @Autowired private MockMvc mockMvc;
    @MockBean private CregisSandboxService service;
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
        when(service.overview()).thenReturn(new CregisSandboxService.SandboxOverview(
                "LOCAL_SANDBOX", true, false, false, "USDT-BEP20"));
        when(service.runProbe()).thenReturn(new CregisSandboxService.ProbeResult(
                "PASS", "USDT-BEP20", true, true, true, false, "AWAITING_AUDIT"));
    }

    @Test
    void anonymousAndUnrelatedUsersCannotReadOrRunProbe() throws Exception {
        mockMvc.perform(get("/api/admin/finance/cregis/sandbox"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/finance/cregis/sandbox")
                        .with(user("other").authorities(() -> "finance_d2_read")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/finance/cregis/sandbox/probes")
                        .with(user("reader").authorities(() -> "finance_d1_read")))
                .andExpect(status().isForbidden());
    }

    @Test
    void d1ReadAndConfigManageAuthoritiesAreSeparated() throws Exception {
        mockMvc.perform(get("/api/admin/finance/cregis/sandbox")
                        .with(user("reader").authorities(() -> "finance_d1_read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.productionReady").value(false));
        mockMvc.perform(post("/api/admin/finance/cregis/sandbox/probes")
                        .with(user("operator").authorities(() -> "finance_d1_bank_config_manage")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("PASS"))
                .andExpect(jsonPath("$.data.externalFundSideEffects").value(false));
    }
}
