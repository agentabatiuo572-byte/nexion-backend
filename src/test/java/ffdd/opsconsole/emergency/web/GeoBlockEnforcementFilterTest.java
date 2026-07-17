package ffdd.opsconsole.emergency.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.emergency.application.GeoBlockDecision;
import ffdd.opsconsole.emergency.application.GeoBlockPolicyService;
import ffdd.opsconsole.emergency.application.GeoEdgeHealthMonitor;
import ffdd.opsconsole.emergency.domain.EmergencyControlRepository;
import jakarta.servlet.FilterChain;
import java.util.Optional;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class GeoBlockEnforcementFilterTest {
    private final GeoBlockPolicyService policyService = mock(GeoBlockPolicyService.class);
    private final EmergencyControlRepository repository = mock(EmergencyControlRepository.class);
    private final GeoBlockEnforcementProperties properties = new GeoBlockEnforcementProperties();
    private final GeoEdgeHealthMonitor healthMonitor = new GeoEdgeHealthMonitor(java.time.Clock.systemUTC());
    private final GeoBlockEnforcementFilter filter =
            new GeoBlockEnforcementFilter(policyService, repository, properties, healthMonitor);

    @Test
    void trustedEdgeCountryIsEnforcedAndRecorded() throws Exception {
        when(repository.settingValue("emergency.geo.edgeJudgeSource"))
                .thenReturn(Optional.of("nexion-gateway"));
        when(policyService.evaluate("VE", "POST", "/auth/users/login"))
                .thenReturn(GeoBlockDecision.blocked("GEO_BLOCKED", "blocked", null));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/users/login");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Nexion-Edge-Country", "VE");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("GEO_BLOCKED");
        verify(repository).recordGeoBlockEvent("VE", "委内瑞拉", null, "nexion-gateway");
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void untrustedClientCannotSpoofTheCountryHeader() throws Exception {
        when(repository.settingValue("emergency.geo.edgeJudgeSource"))
                .thenReturn(Optional.of("nexion-gateway"));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/users/login");
        request.setRemoteAddr("198.51.100.10");
        request.addHeader("X-Nexion-Edge-Country", "US");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("GEO_EDGE_TRUST_REQUIRED");
        verify(policyService, never()).evaluate(anyString(), anyString(), anyString());
    }

    @Test
    void originalConnectionPeerFromTomcatIsUsedAfterForwardedAddressProcessing() throws Exception {
        when(repository.settingValue("emergency.geo.edgeJudgeSource"))
                .thenReturn(Optional.of("nexion-gateway"));
        when(policyService.evaluate("VE", "POST", "/commerce/app/orders"))
                .thenReturn(GeoBlockDecision.blocked("GEO_BLOCKED", "blocked", null));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/commerce/app/orders");
        request.setRemoteAddr("203.0.113.9");
        request.setAttribute("org.apache.catalina.AccessLog.RemoteAddr", "127.0.0.1");
        request.addHeader("X-Nexion-Edge-Country", "VE");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        verify(policyService).evaluate("VE", "POST", "/commerce/app/orders");
    }

    @Test
    void trustedProxyConfigurationSupportsCidrRanges() {
        properties.setTrustedProxyAddresses(List.of("198.51.100.0/24", "2001:db8::/32"));

        assertThat(properties.isTrustedProxy("198.51.100.42")).isTrue();
        assertThat(properties.isTrustedProxy("198.51.101.42")).isFalse();
        assertThat(properties.isTrustedProxy("2001:db8::2")).isTrue();
        assertThat(properties.isTrustedProxy("2001:db9::2")).isFalse();
    }

    @Test
    void missingCountryFailsClosedByDefaultEvenOnLoopback() throws Exception {
        when(repository.settingValue("emergency.geo.edgeJudgeSource"))
                .thenReturn(Optional.of("nexion-gateway"));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/users/login");
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("GEO_COUNTRY_UNRESOLVED");
        assertThat(healthMonitor.snapshot("nexion-gateway").failureCount()).isEqualTo(1);
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void activeTransitionFallsBackToThePreviousSourceWithoutAnEnforcementGap() throws Exception {
        when(repository.settingValue("emergency.geo.edgeJudgeSource"))
                .thenReturn(Optional.of("cloudflare"));
        when(repository.settingValue("emergency.geo.edgeJudgeFallbackUntilEpochMs"))
                .thenReturn(Optional.of(String.valueOf(System.currentTimeMillis() + 60_000)));
        when(repository.settingValue("emergency.geo.edgeJudgeFallbackSource"))
                .thenReturn(Optional.of("nexion-gateway"));
        when(policyService.evaluate("JP", "POST", "/auth/users/login"))
                .thenReturn(GeoBlockDecision.allowed());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/users/login");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Nexion-Edge-Country", "JP");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(policyService).evaluate("JP", "POST", "/auth/users/login");
        verify(chain).doFilter(request, response);
        assertThat(healthMonitor.snapshot("cloudflare").failureCount()).isEqualTo(1);
        assertThat(healthMonitor.snapshot("nexion-gateway").sampleCount()).isEqualTo(1);
    }

    @Test
    void missingCandidateHeaderIsCountedInTheCandidateFailureDenominator() throws Exception {
        when(repository.settingValue("emergency.geo.edgeJudgeSource"))
                .thenReturn(Optional.of("nexion-gateway"));
        when(policyService.evaluate("JP", "GET", "/api/profile"))
                .thenReturn(GeoBlockDecision.allowed());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/profile");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Nexion-Edge-Country", "JP");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, mock(FilterChain.class));

        assertThat(healthMonitor.snapshot("nexion-gateway").failureCount()).isZero();
        assertThat(healthMonitor.snapshot("cloudflare").sampleCount()).isEqualTo(1);
        assertThat(healthMonitor.snapshot("cloudflare").failureCount()).isEqualTo(1);
    }

    @Test
    void expiredFallbackDoesNotAcceptThePreviousSourceHeader() throws Exception {
        when(repository.settingValue("emergency.geo.edgeJudgeSource"))
                .thenReturn(Optional.of("cloudflare"));
        when(repository.settingValue("emergency.geo.edgeJudgeFallbackUntilEpochMs"))
                .thenReturn(Optional.of(String.valueOf(System.currentTimeMillis() - 1)));
        when(repository.settingValue("emergency.geo.edgeJudgeFallbackSource"))
                .thenReturn(Optional.of("nexion-gateway"));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/profile");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Nexion-Edge-Country", "JP");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("GEO_COUNTRY_UNRESOLVED");
        verify(chain, never()).doFilter(request, response);
        verify(policyService, never()).evaluate(anyString(), anyString(), anyString());
    }
}
