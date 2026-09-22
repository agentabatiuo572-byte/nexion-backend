package ffdd.opsconsole.content.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ffdd.opsconsole.content.application.AppRiskDisclosureService;
import ffdd.opsconsole.content.domain.AppRiskDisclosureView;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.security.AdminRbacAuthorizationFilter;
import ffdd.opsconsole.shared.security.JwtAuthenticationFilter;
import ffdd.opsconsole.shared.security.SecurityConfig;
import java.util.List;
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

@WebMvcTest(AppRiskDisclosureController.class)
@ActiveProfiles("dev")
@Import(SecurityConfig.class)
@ContextConfiguration(classes = {AppRiskDisclosureController.class, SecurityConfig.class})
class AppRiskDisclosureControllerSecurityTest {
    @Autowired private MockMvc mockMvc;
    @MockBean private AppRiskDisclosureService service;
    @MockBean private JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean private AdminRbacAuthorizationFilter adminRbacAuthorizationFilter;

    @BeforeEach
    void passThroughAuthenticationFilters() throws Exception {
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
    void onlyPublicReadIsAnonymous() throws Exception {
        when(service.publicCurrent("VN")).thenReturn(ApiResult.ok(new AppRiskDisclosureView(
                "server", "PRODUCTION", "SBV", "Vietnam", "v13", "zh+vi+en", "2026-07-12",
                false, null, List.of(), null, null, 5)));

        mockMvc.perform(get("/api/legal/risk-disclosure/public/current").param("country", "VN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.version").value("v13"))
                .andExpect(jsonPath("$.data.acknowledgmentToken").isEmpty());
        mockMvc.perform(get("/api/legal/risk-disclosure/current"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/legal/risk-disclosure/acknowledgment")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
