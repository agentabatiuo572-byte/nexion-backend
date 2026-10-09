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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class UserBusinessWriteGateFilterTest {
    private final UserOpsMapper users = mock(UserOpsMapper.class);
    private final LegalTermsService terms = mock(LegalTermsService.class);
    private final UserBusinessWriteGateFilter filter = new UserBusinessWriteGateFilter(users, terms);
    private final ch.qos.logback.classic.Logger logger =
            (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(UserBusinessWriteGateFilter.class);
    private final ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs =
            new ch.qos.logback.core.read.ListAppender<>();
    private ch.qos.logback.classic.Level previousLevel;

    @BeforeEach
    void captureDiagnostics() {
        previousLevel = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.WARN);
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
        logger.detachAppender(logs);
        logs.stop();
        logger.setLevel(previousLevel);
    }

    @Test
    void depositOnboarding503PreservesResponseBlocksDispatchAndLogsNoCause() throws Exception {
        authenticateUser(42L);
        when(users.isOnboardingComplete(42L)).thenThrow(
                new IllegalStateException("fixture-secret database-url synthetic-token"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(new MockHttpServletRequest("POST", "/api/app/deposits/vietqr/intents"), response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).isEqualTo(
                "{\"code\":503,\"message\":\"USER_ONBOARDING_STATE_UNAVAILABLE\",\"data\":null}");
        assertThat(chain.getRequest()).isNull();
        verify(terms, never()).current("en", "GLOBAL", 42L);
        assertSafeDiagnostic("USER_ONBOARDING_STATE_UNAVAILABLE");
    }

    @Test
    void depositLegal503PreservesResponseBlocksDispatchAndLogsOnlyGateEnum() throws Exception {
        authenticateUser(42L);
        when(users.isOnboardingComplete(42L)).thenReturn(true);
        when(users.activeUserLanguage(42L)).thenReturn("en");
        when(terms.current("en", "GLOBAL", 42L)).thenReturn(ApiResult.fail(503, "fixture-private-message"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(new MockHttpServletRequest("POST", "/api/app/deposits/vietqr/intents"), response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).isEqualTo(
                "{\"code\":503,\"message\":\"LEGAL_TERMS_UNAVAILABLE\",\"data\":null}");
        assertThat(chain.getRequest()).isNull();
        assertSafeDiagnostic("LEGAL_TERMS_UNAVAILABLE");
    }

    @ParameterizedTest
    @CsvSource({"GET,/api/app/deposits/vietqr/intents,200", "POST,/api/orders,503",
            "POST,/api/app/deposits/vietqr/intents/other,503"})
    void otherRoutesAndReadsDoNotEmitDeposit503Diagnostic(String method, String path, int status) throws Exception {
        authenticateUser(42L);
        when(users.isOnboardingComplete(42L)).thenThrow(new IllegalStateException("synthetic-cause"));
        assertThat(invoke(method, path).getStatus()).isEqualTo(status);
        assertThat(logs.list).isEmpty();
    }

    @Test
    void permittedDepositWriteStillDispatchesOnceWithoutFailureDiagnostic() throws Exception {
        authenticateUser(42L);
        when(users.isOnboardingComplete(42L)).thenReturn(true);
        when(users.activeUserLanguage(42L)).thenReturn("en");
        when(terms.current("en", "GLOBAL", 42L)).thenReturn(ApiResult.ok(current(true)));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/app/deposits/vietqr/intents");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
        verify(users).isOnboardingComplete(42L);
        verify(terms).current("en", "GLOBAL", 42L);
        assertThat(logs.list).isEmpty();
    }

    private void assertSafeDiagnostic(String code) {
        assertThat(logs.list).extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .containsExactly("event=VIETQR_INTENT_CREATE_UNAVAILABLE phase=USER_WRITE_GATE code=" + code);
        assertThat(logs.list).singleElement().satisfies(event -> assertThat(event.getThrowableProxy()).isNull());
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
    void notificationInteractionsRemainAvailableBeforeOnboarding() throws Exception {
        authenticateUser(42L);
        when(users.isOnboardingComplete(42L)).thenReturn(false);

        assertThat(invoke("POST", "/api/notifications/99/actions").getStatus()).isEqualTo(200);
        assertThat(invoke("POST", "/api/notifications/99/read").getStatus()).isEqualTo(200);
        assertThat(invoke("POST", "/api/notifications/read-all").getStatus()).isEqualTo(200);
        assertThat(invoke("DELETE", "/api/notifications/read").getStatus()).isEqualTo(200);
        assertThat(invoke("POST", "/api/notifications/99/other").getStatus()).isEqualTo(428);
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
