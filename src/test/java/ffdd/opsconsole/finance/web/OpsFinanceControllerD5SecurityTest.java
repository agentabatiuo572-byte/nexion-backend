package ffdd.opsconsole.finance.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ffdd.opsconsole.finance.application.D2WithdrawalAuthorization;
import ffdd.opsconsole.finance.application.D5WithdrawalAuthorization;
import ffdd.opsconsole.finance.application.OpsFinanceService;
import ffdd.opsconsole.shared.security.AdminRbacAuthorizationFilter;
import ffdd.opsconsole.shared.security.JwtAuthenticationFilter;
import ffdd.opsconsole.shared.security.SecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(OpsFinanceController.class)
@Import({SecurityConfig.class, D2WithdrawalAuthorization.class, D5WithdrawalAuthorization.class})
@ContextConfiguration(classes = {
        OpsFinanceController.class,
        SecurityConfig.class,
        D2WithdrawalAuthorization.class,
        D5WithdrawalAuthorization.class
})
class OpsFinanceControllerD5SecurityTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OpsFinanceService financeService;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockBean
    private AdminRbacAuthorizationFilter adminRbacAuthorizationFilter;

    @BeforeEach
    void letAuthenticationFiltersContinue() throws Exception {
        doAnswer(invocation -> {
            var chain = invocation.getArgument(2, jakarta.servlet.FilterChain.class);
            chain.doFilter(invocation.getArgument(0, jakarta.servlet.ServletRequest.class),
                    invocation.getArgument(1, jakarta.servlet.ServletResponse.class));
            return null;
        }).when(jwtAuthenticationFilter).doFilter(any(), any(), any());
        doAnswer(invocation -> {
            var chain = invocation.getArgument(2, jakarta.servlet.FilterChain.class);
            chain.doFilter(invocation.getArgument(0, jakarta.servlet.ServletRequest.class),
                    invocation.getArgument(1, jakarta.servlet.ServletResponse.class));
            return null;
        }).when(adminRbacAuthorizationFilter).doFilter(any(), any(), any());
    }

    @Test
    void authenticatedLegacyPatchIsGoneAndCannotReachTheOldWriteService() throws Exception {
        mockMvc.perform(patch("/api/admin/finance/withdrawal-params")
                        .with(user("daily-only").authorities(
                                () -> "finance_d5_read",
                                () -> "finance_d5_daily_limit_write"))
                        .header("Idempotency-Key", "d5-security-nex-offset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "key":"nexFeeOffsetRate",
                                  "value":"0.50",
                                  "operator":"daily-only",
                                  "reason":"negative exact permission contract"
                                }
                                """))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("LEGACY_D5_WRITE_DISABLED"))
                .andExpect(jsonPath("$.redirect").value("/api/admin/withdraw/limits"));

        verifyNoInteractions(financeService);
    }
}
