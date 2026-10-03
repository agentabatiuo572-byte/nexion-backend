package ffdd.opsconsole.device.web;

import ffdd.opsconsole.device.application.TestComputeWorkerService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.emergency.web.GeoBlockEnforcementFilter;
import ffdd.opsconsole.shared.security.JwtAuthenticationFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
public class TestComputeWorkerSecurityConfig {
    private final TestComputeWorkerService worker;

    @Bean
    @Order(1)
    SecurityFilterChain testComputeWorkerChain(HttpSecurity http) throws Exception {
        return http.securityMatcher("/api/test/compute-workers/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, exception) ->
                        error(response, 401, "TEST_COMPUTE_WORKER_AUTH_INVALID"))
                        .accessDeniedHandler((request, response, exception) -> error(response, 403, "TEST_COMPUTE_WORKER_FORBIDDEN")))
                .authorizeHttpRequests(auth -> auth.requestMatchers(HttpMethod.POST,
                        "/api/test/compute-workers/v1/tasks/*/claim", "/api/test/compute-workers/v1/tasks/*/complete",
                        "/api/test/compute-workers/v1/tasks/*/release").hasRole("TEST_COMPUTE_WORKER")
                        .requestMatchers(HttpMethod.POST, "/api/test/compute-workers/v2/next-task",
                                "/api/test/compute-workers/v2/tasks/*/complete", "/api/test/compute-workers/v2/tasks/*/release")
                        .hasRole("TEST_COMPUTE_WORKER_CONTINUOUS")
                        .requestMatchers(HttpMethod.GET, "/api/test/compute-workers/v2/tasks/*/receipt").hasRole("TEST_COMPUTE_WORKER_CONTINUOUS")
                        .anyRequest().denyAll())
                .addFilterBefore(new WorkerAuthenticationFilter(worker), UsernamePasswordAuthenticationFilter.class).build();
    }

    /** Keep JWT in the ordinary SecurityFilterChain, never also as a global Servlet filter. */
    @Bean
    FilterRegistrationBean<JwtAuthenticationFilter> jwtAuthenticationServletRegistration(JwtAuthenticationFilter jwt) {
        var registration = new FilterRegistrationBean<>(jwt);
        registration.setEnabled(false);
        return registration;
    }

    private static final class WorkerAuthenticationFilter extends OncePerRequestFilter {
        private final TestComputeWorkerService worker;
        private WorkerAuthenticationFilter(TestComputeWorkerService worker) { this.worker = worker; }
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            // No inherited gateway, developer, USER or ADMIN identity is accepted in this namespace.
            SecurityContextHolder.clearContext();
            try {
                if (request.getRequestURI().startsWith("/api/test/compute-workers/v2/")) {
                    worker.requireContinuousEnabled();
                    if (!GeoBlockEnforcementFilter.isContinuousWorkerRoute(request)
                            || !GeoBlockEnforcementFilter.isDirectLoopback(request)) throw new BizException(403, "TEST_COMPUTE_WORKER_LOCAL_ONLY");
                    var identity = worker.authenticateContinuous(request.getHeader("Authorization"));
                    var auth = new UsernamePasswordAuthenticationToken(identity, null,
                            List.of(new SimpleGrantedAuthority("ROLE_TEST_COMPUTE_WORKER_CONTINUOUS")));
                    auth.setDetails(Map.of("subjectType", "TEST_COMPUTE_WORKER", "username", identity.getName()));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                    chain.doFilter(request, response);
                    return;
                }
                var grant = worker.authenticate(request.getHeader("Authorization"));
                var auth = new UsernamePasswordAuthenticationToken(grant, null,
                        List.of(new SimpleGrantedAuthority("ROLE_TEST_COMPUTE_WORKER")));
                auth.setDetails(Map.of("subjectType", "TEST_COMPUTE_WORKER", "username", grant.getName()));
                SecurityContextHolder.getContext().setAuthentication(auth);
                chain.doFilter(request, response);
            } catch (BizException failure) {
                error(response, failure.getCode(), failure.getMessage());
            } finally { SecurityContextHolder.clearContext(); }
        }
    }

    private static void error(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status); response.setCharacterEncoding("UTF-8"); response.setContentType("application/json");
        response.getWriter().write("{\"code\":" + status + ",\"message\":\"" + message + "\",\"data\":null}");
    }
}
