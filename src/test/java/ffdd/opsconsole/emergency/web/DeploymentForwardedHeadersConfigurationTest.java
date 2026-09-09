package ffdd.opsconsole.emergency.web;

import static org.assertj.core.api.Assertions.assertThat;
import ffdd.opsconsole.platform.application.PlatformGlobalRateLimitFilter;
import ffdd.opsconsole.shared.audit.AuditTraceFilter;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.Order;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.util.concurrent.atomic.AtomicBoolean;

class DeploymentForwardedHeadersConfigurationTest {
    @Test
    void socketPeerIsValidatedBeforeForwardingAndRateAndAuditSeeTheValidatedClient() {
        var config = new DeploymentForwardedHeadersConfiguration();
        int forwarding = config.deploymentForwardedHeadersRegistration(
                config.forwardedHeaderFilter(new GeoBlockEnforcementProperties())).getOrder();
        assertThat(GeoBlockEnforcementFilter.class.getAnnotation(Order.class).value()).isLessThan(forwarding);
        assertThat(PlatformGlobalRateLimitFilter.class.getAnnotation(Order.class).value()).isGreaterThan(forwarding);
        assertThat(AuditTraceFilter.class.getAnnotation(Order.class).value()).isGreaterThan(forwarding);
    }

    @Test
    void rejectsForgedForwardingOnAdminAndHealthPathsBeforeAnyDownstreamFilter() throws Exception {
        var filter = new DeploymentForwardedHeadersConfiguration().forwardedHeaderFilter(new GeoBlockEnforcementProperties());
        for (String path : new String[]{"/api/admin/auth/session", "/actuator/health", "/auth/users/login"}) {
            var request = new MockHttpServletRequest("GET", path);
            request.setRemoteAddr("203.0.113.44");
            request.addHeader("X-Forwarded-For", "127.0.0.1");
            request.addHeader("Forwarded", "for=127.0.0.1;proto=https");
            var response = new MockHttpServletResponse();
            var called = new AtomicBoolean();
            filter.doFilter(request, response, (req, res) -> called.set(true));
            assertThat(response.getStatus()).isEqualTo(403);
            assertThat(called).isFalse();
        }
    }

    @Test
    void forwardsTrustedIngressButAllowsHeaderlessLoopbackHealthChecks() throws Exception {
        var filter = new DeploymentForwardedHeadersConfiguration().forwardedHeaderFilter(new GeoBlockEnforcementProperties());
        for (boolean headers : new boolean[]{true, false}) {
            var request = new MockHttpServletRequest("GET", "/api/admin/auth/session");
            request.setRemoteAddr("127.0.0.1");
            if (headers) request.addHeader("X-Forwarded-For", "203.0.113.44");
            var called = new AtomicBoolean();
            filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
                assertThat(req.getRemoteAddr()).isEqualTo(headers ? "203.0.113.44" : "127.0.0.1");
                called.set(true);
            });
            assertThat(called).isTrue();
        }
    }
}
