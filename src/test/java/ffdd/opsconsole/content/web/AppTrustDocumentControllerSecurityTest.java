package ffdd.opsconsole.content.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ffdd.opsconsole.content.application.TrustDocumentService;
import ffdd.opsconsole.content.domain.TrustDocumentsView;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.security.AdminRbacAuthorizationFilter;
import ffdd.opsconsole.shared.security.JwtAuthenticationFilter;
import ffdd.opsconsole.shared.security.SecurityConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

@WebMvcTest(AppTrustDocumentController.class)
@ActiveProfiles("dev")
@Import(SecurityConfig.class)
@ContextConfiguration(classes = {AppTrustDocumentController.class, SecurityConfig.class})
class AppTrustDocumentControllerSecurityTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TrustDocumentService service;

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
    void documentListAndFilesArePublicWithoutToken() throws Exception {
        when(service.published()).thenReturn(new TrustDocumentsView(
                new TrustDocumentsView.Profile("UVEL AI LTD.", "New York, USA", "New York, NY 10065", "8040896", "261008000079", "50000000"),
                List.of(new TrustDocumentsView.Document("whitepaper", "DEFAULT", "v1.3", "English", null, null,
                        "uvel-whitepaper-v1.3-en.pdf", 5, 21, "a".repeat(64), null, null, null))));
        when(service.publicFile("msb")).thenReturn(new TrustDocumentService.Content("fincen-msb.pdf", "%PDF-".getBytes()));

        mockMvc.perform(get("/api/content/trust/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profile.legalName").value("UVEL AI LTD."))
                .andExpect(jsonPath("$.data.documents[0].slot").value("whitepaper"));
        mockMvc.perform(get("/api/content/trust/documents/msb/file"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Disposition", "inline; filename=\"fincen-msb.pdf\""))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Cache-Control", "max-age=300, public"));
        when(service.page("whitepaper", 2)).thenReturn(new byte[] {(byte) 0xFF, (byte) 0xD8, 1});
        mockMvc.perform(get("/api/content/trust/documents/whitepaper/pages/2"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentType(MediaType.IMAGE_JPEG));
    }
}
