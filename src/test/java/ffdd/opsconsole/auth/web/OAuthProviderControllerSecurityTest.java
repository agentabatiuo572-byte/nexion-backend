package ffdd.opsconsole.auth.web;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import ffdd.opsconsole.auth.application.*;
import ffdd.opsconsole.shared.security.*;
import jakarta.servlet.http.Cookie;
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

@WebMvcTest(OAuthProviderController.class)
@ActiveProfiles("dev")
@Import(SecurityConfig.class)
@ContextConfiguration(classes = {OAuthProviderController.class, SecurityConfig.class})
class OAuthProviderControllerSecurityTest {
    @Autowired MockMvc mvc;
    @MockBean OAuthProviderFlowService flows;
    @MockBean OAuthProviderClient providers;
    @MockBean AppUserRefreshCookieService cookies;
    @MockBean JwtAuthenticationFilter jwt;
    @MockBean AdminRbacAuthorizationFilter rbac;
    @BeforeEach void filters() throws Exception {
        doAnswer(i -> { i.getArgument(2, jakarta.servlet.FilterChain.class).doFilter(i.getArgument(0), i.getArgument(1)); return null; })
                .when(jwt).doFilter(any(), any(), any());
        doAnswer(i -> { i.getArgument(2, jakarta.servlet.FilterChain.class).doFilter(i.getArgument(0), i.getArgument(1)); return null; })
                .when(rbac).doFilter(any(), any(), any());
    }
    @Test void appleFormPostTraversesRealCorsAndSecurityChainWithoutBroadeningOtherRoutes() throws Exception {
        String state = "s".repeat(43), receipt = "r".repeat(43), id = "OIDC-" + "a".repeat(32);
        when(flows.callback("APPLE", state, "browser", "code", null))
                .thenReturn(new OAuthProviderFlowService.Callback(id, receipt));
        when(providers.h5Origin()).thenReturn("https://app.example.test");
        mvc.perform(post("/auth/users/oauth/provider/callback/APPLE").secure(true)
                .header("Origin", "https://appleid.apple.com").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .cookie(new Cookie("__Host-uvel-oauth-" + state, "browser")).param("state", state).param("code", "code"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("window.opener.postMessage")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("flowKey"))));
        mvc.perform(post("/auth/users/oauth/provider/callback/APPLE").header("Origin", "https://evil.example.test"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/auth/users/oauth/provider/link").header("Origin", "https://appleid.apple.com")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
    }
    @Test void anonymousBindingAndAdjacentPathsRemainProtected() throws Exception {
        mvc.perform(post("/auth/users/oauth/provider/link").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/auth/users/oauth/provider/linked")).andExpect(status().isUnauthorized());
        mvc.perform(get("/auth/users/oauth/provider/available/extra")).andExpect(status().isUnauthorized());
        verifyNoInteractions(flows);
    }
    @Test void callbackDoesNotRenderAnyUntrustedProviderParameters() throws Exception {
        mvc.perform(get("/auth/users/oauth/provider/callback/GOOGLE").param("state", "invalid")
                .param("error", "<script>alert(1)</script>"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("alert(1)"))));
    }
}
