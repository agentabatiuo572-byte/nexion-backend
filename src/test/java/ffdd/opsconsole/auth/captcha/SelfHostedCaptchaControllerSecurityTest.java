package ffdd.opsconsole.auth.captcha;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ffdd.opsconsole.shared.api.ApiResult;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SelfHostedCaptchaController.class)
@ActiveProfiles("dev")
@Import(SecurityConfig.class)
@ContextConfiguration(classes = {SelfHostedCaptchaController.class, SecurityConfig.class})
class SelfHostedCaptchaControllerSecurityTest {
    @Autowired private MockMvc mockMvc;
    @MockBean private SelfHostedCaptchaService captcha;
    @MockBean private JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean private AdminRbacAuthorizationFilter adminRbacAuthorizationFilter;

    @BeforeEach
    void continueAuthenticationFilters() throws Exception {
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
    void publicChallengeUsesContainerPeerAndDoesNotReadSpoofedForwardingHeaders() throws Exception {
        when(captcha.createChallenge(any(), eq("203.0.113.16"))).thenReturn(ApiResult.ok(
                new SelfHostedCaptchaChallengeResponse("A".repeat(43), "data:image/png;base64,AA==",
                        "data:image/png;base64,AA==", 320, 160, 48, 48, 40, 120)));

        mockMvc.perform(post("/api/auth/captcha/challenge")
                        .with(request -> { request.setRemoteAddr("203.0.113.16"); return request; })
                        .header("X-Forwarded-For", "127.0.0.1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"scene\":\"REGISTER\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));

        verify(captcha).createChallenge(eq(new SelfHostedCaptchaChallengeRequest("REGISTER")), eq("203.0.113.16"));
    }
}
