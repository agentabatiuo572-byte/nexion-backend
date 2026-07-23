package ffdd.opsconsole.finance.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ffdd.opsconsole.finance.application.D2WithdrawalAuthorization;
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
@Import({SecurityConfig.class, D2WithdrawalAuthorization.class})
@ContextConfiguration(classes = {
        OpsFinanceController.class,
        SecurityConfig.class,
        D2WithdrawalAuthorization.class
})
class OpsFinanceControllerD2SecurityTest {
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
            chain.doFilter(
                    invocation.getArgument(0, jakarta.servlet.ServletRequest.class),
                    invocation.getArgument(1, jakarta.servlet.ServletResponse.class));
            return null;
        }).when(jwtAuthenticationFilter).doFilter(any(), any(), any());
        doAnswer(invocation -> {
            var chain = invocation.getArgument(2, jakarta.servlet.FilterChain.class);
            chain.doFilter(
                    invocation.getArgument(0, jakarta.servlet.ServletRequest.class),
                    invocation.getArgument(1, jakarta.servlet.ServletResponse.class));
            return null;
        }).when(adminRbacAuthorizationFilter).doFilter(any(), any(), any());
    }

    @Test
    void readOnlyD2OperatorCannotApproveWithdrawalOverHttp() throws Exception {
        mockMvc.perform(post("/api/admin/finance/withdrawals/WD-SECURITY-001/review")
                        .with(user("auditor").authorities(() -> "finance_d2_read"))
                        .header("Idempotency-Key", "d2-security-approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "action":"APPROVE",
                                  "operator":"auditor",
                                  "reason":"negative authorization contract",
                                  "addressVerified":true
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("ACCESS_DENIED"));
    }
}
