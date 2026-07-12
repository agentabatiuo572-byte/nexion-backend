package ffdd.opsconsole.content.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ffdd.opsconsole.content.application.OpsTrustDisclosureService;
import ffdd.opsconsole.content.domain.AppTrustSectionsView;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
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
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AppTrustSectionController.class)
@Import(SecurityConfig.class)
@ContextConfiguration(classes = {AppTrustSectionController.class, SecurityConfig.class})
class AppTrustSectionControllerSecurityTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OpsTrustDisclosureService service;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockBean
    private AdminRbacAuthorizationFilter adminRbacAuthorizationFilter;

    @MockBean
    private AuditLogService auditLogService;

    @BeforeEach
    void letAuthenticationFiltersContinueWithoutCreatingAuthentication() throws Exception {
        doAnswer(invocation -> {
            var request = invocation.getArgument(0, jakarta.servlet.ServletRequest.class);
            var response = invocation.getArgument(1, jakarta.servlet.ServletResponse.class);
            var chain = invocation.getArgument(2, jakarta.servlet.FilterChain.class);
            chain.doFilter(request, response);
            return null;
        }).when(jwtAuthenticationFilter).doFilter(any(), any(), any());
        doAnswer(invocation -> {
            var request = invocation.getArgument(0, jakarta.servlet.ServletRequest.class);
            var response = invocation.getArgument(1, jakarta.servlet.ServletResponse.class);
            var chain = invocation.getArgument(2, jakarta.servlet.FilterChain.class);
            chain.doFilter(request, response);
            return null;
        }).when(adminRbacAuthorizationFilter).doFilter(any(), any(), any());
    }

    @Test
    void currentPublishedSectionsIsPublicWithoutToken() throws Exception {
        when(service.publishedSections()).thenReturn(ApiResult.ok(new AppTrustSectionsView(List.of(
                new AppTrustSectionsView.Section(
                        "financials", "v1", "财务与储备数据", "指标卡片",
                        List.of(new AppTrustSectionsView.Field("reserveCoverage", "储备覆盖率", "100%")))))));

        mockMvc.perform(get("/api/content/trust/sections/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.sections[0].sectionKey").value("financials"))
                .andExpect(jsonPath("$.data.sections[0].version").value("v1"));
    }
}
