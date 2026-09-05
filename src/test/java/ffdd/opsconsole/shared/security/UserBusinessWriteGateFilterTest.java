package ffdd.opsconsole.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.terms.LegalTermsService;
import ffdd.opsconsole.content.terms.domain.LegalTermsCurrentView;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.user.mapper.UserOpsMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class UserBusinessWriteGateFilterTest {
    private final UserOpsMapper users = mock(UserOpsMapper.class);
    private final LegalTermsService terms = mock(LegalTermsService.class);
    private final UserBusinessWriteGateFilter filter = new UserBusinessWriteGateFilter(users, terms);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void blocksBusinessWriteUntilOnboardingCompletes() throws Exception {
        authenticateUser(42L);
        when(users.isOnboardingComplete(42L)).thenReturn(false);

        MockHttpServletResponse response = invoke("POST", "/api/orders");

        assertThat(response.getStatus()).isEqualTo(428);
        assertThat(response.getContentAsString()).contains("USER_ONBOARDING_REQUIRED");
        verify(terms, never()).current("en", "GLOBAL", 42L);
    }

    @Test
    void blocksBusinessWriteUntilCurrentTermsAreAcknowledged() throws Exception {
        authenticateUser(42L);
        when(users.isOnboardingComplete(42L)).thenReturn(true);
        when(users.activeUserLanguage(42L)).thenReturn("en");
        when(terms.current("en", "GLOBAL", 42L)).thenReturn(ApiResult.ok(current(false)));

        MockHttpServletResponse response = invoke("POST", "/api/tasks/assignments/claim");

        assertThat(response.getStatus()).isEqualTo(428);
        assertThat(response.getContentAsString()).contains("LEGAL_TERMS_ACK_REQUIRED");
    }

    @Test
    void permitsOnboardingAndTermsAcknowledgementWritesBeforeBusinessAccess() throws Exception {
        authenticateUser(42L);
        when(users.isOnboardingComplete(42L)).thenReturn(false);

        MockHttpServletResponse onboarding = invoke("POST", "/api/onboarding/calibrate");
        MockHttpServletResponse acknowledgement = invoke("POST", "/api/legal/terms/acknowledgment");

        assertThat(onboarding.getStatus()).isEqualTo(200);
        assertThat(acknowledgement.getStatus()).isEqualTo(200);
        verify(users, never()).isOnboardingComplete(42L);
    }

    @Test
    void permitsBusinessWriteOnlyAfterBothServerFactsPass() throws Exception {
        authenticateUser(42L);
        when(users.isOnboardingComplete(42L)).thenReturn(true);
        when(users.activeUserLanguage(42L)).thenReturn("en");
        when(terms.current("en", "GLOBAL", 42L)).thenReturn(ApiResult.ok(current(true)));

        MockHttpServletResponse response = invoke("POST", "/api/orders");

        assertThat(response.getStatus()).isEqualTo(200);
    }

    private MockHttpServletResponse invoke(String method, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private void authenticateUser(long id) {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(String.valueOf(id), null, List.of());
        authentication.setDetails(Map.of("subjectType", "USER"));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private LegalTermsCurrentView current(boolean acknowledged) {
        return new LegalTermsCurrentView("server", "PRODUCTION", "", "en", "en", "GLOBAL", "GLOBAL",
                "exact", "v1", LocalDateTime.now(), "Terms", "Summary", List.of(), acknowledged, null);
    }
}
