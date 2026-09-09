package ffdd.opsconsole.emergency.web;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.ForwardedHeaderFilter;

/** Deployment-only: validate the socket peer before exposing forwarded metadata. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "nexion.deployment.trusted-proxy-forwarding", havingValue = "true")
public class DeploymentForwardedHeadersConfiguration {
    @Bean
    public ForwardedHeaderFilter forwardedHeaderFilter(GeoBlockEnforcementProperties properties) {
        return new ForwardedHeaderFilter() {
            @Override
            protected boolean shouldNotFilter(HttpServletRequest request) {
                return false; // Trust verification includes admin, health and headerless requests.
            }

            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                    FilterChain chain) throws ServletException, IOException {
                // server.forward-headers-strategy=none preserves this actual connection peer.
                if (!properties.isTrustedProxy(request.getRemoteAddr())) {
                    response.sendError(403, "DEPLOYMENT_PROXY_TRUST_REQUIRED");
                    return;
                }
                super.doFilterInternal(request, response, chain);
            }
        };
    }

    @Bean
    public FilterRegistrationBean<ForwardedHeaderFilter> deploymentForwardedHeadersRegistration(
            ForwardedHeaderFilter forwardedHeaderFilter) {
        var registration = new FilterRegistrationBean<>(forwardedHeaderFilter);
        // Geo (+30) validates the real socket peer; rate/audit (+40) see the client.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 35);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
        return registration;
    }
}
