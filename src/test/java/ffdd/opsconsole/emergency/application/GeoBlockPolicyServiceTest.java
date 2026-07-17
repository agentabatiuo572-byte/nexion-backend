package ffdd.opsconsole.emergency.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.emergency.domain.EmergencyControlRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GeoBlockPolicyServiceTest {
    private final EmergencyControlRepository repository = mock(EmergencyControlRepository.class);
    private final GeoBlockPolicyService service = new GeoBlockPolicyService(repository);

    @Test
    void blockedCountryIsRejectedBeforeLogin() {
        when(repository.geoCountryPolicies()).thenReturn(List.of(
                Map.of("cc", "VE", "status", "blocked")));

        GeoBlockDecision decision = service.evaluate("VE", "POST", "/auth/users/login");

        assertThat(decision.blocked()).isTrue();
        assertThat(decision.code()).isEqualTo("GEO_BLOCKED");
        assertThat(decision.policyStatus()).isEqualTo("blocked");
    }

    @Test
    void limitedCountryCanLoginButCannotUseFundsEndpoint() {
        when(repository.geoCountryPolicies()).thenReturn(List.of(
                Map.of("cc", "IR", "status", "limited")));
        when(repository.geoEndpointCatalogs()).thenReturn(List.of());
        when(repository.geoEndpointPolicies()).thenReturn(List.of());

        assertThat(service.evaluate("IR", "POST", "/auth/users/login").blocked()).isFalse();
        assertThat(service.evaluate("IR", "POST", "/api/app/wallet/withdrawals").code())
                .isEqualTo("GEO_LIMITED");
        assertThat(service.evaluate("IR", "POST", "/api/finance/swap").code()).isEqualTo("GEO_LIMITED");
        assertThat(service.evaluate("IR", "POST", "/api/market/genesis/orders").code()).isEqualTo("GEO_LIMITED");
        assertThat(service.evaluate("IR", "POST", "/api/content/learning/courses/course-1/complete").code())
                .isEqualTo("GEO_LIMITED");
        assertThat(service.evaluate("IR", "POST", "/api/content/learning/courses/course-1/quiz").code())
                .isEqualTo("GEO_LIMITED");
        assertThat(service.evaluate("IR", "POST", "/api/content/learning/courses/course-1/start").blocked()).isFalse();
        assertThat(service.evaluate("IR", "POST", "/api/profile/finance-note").blocked()).isFalse();
        assertThat(service.evaluate("IR", "GET", "/api/app/wallet/withdrawals").blocked()).isFalse();
    }

    @Test
    void endpointSpecificCountryIsRejectedOnlyOnMatchingEndpoint() {
        when(repository.geoCountryPolicies()).thenReturn(List.of());
        when(repository.geoEndpointCatalogs()).thenReturn(List.of(Map.of(
                "endpointKey", "janus",
                "endpointPath", "/api/app/janus",
                "status", "ACTIVE")));
        when(repository.geoEndpointPolicies()).thenReturn(List.of(Map.of(
                "endpointKey", "janus",
                "countryCode", "KP",
                "source", "explicit")));

        assertThat(service.evaluate("KP", "GET", "/api/app/janus/sessions").code())
                .isEqualTo("GEO_ENDPOINT_BLOCKED");
        assertThat(service.evaluate("KP", "GET", "/api/content/learning").blocked()).isFalse();
    }
}
