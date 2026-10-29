package ffdd.opsconsole.device.web;

import ffdd.opsconsole.device.application.TestCloudExecutorService;
import ffdd.opsconsole.emergency.web.GeoBlockEnforcementFilter;
import ffdd.opsconsole.shared.exception.BizException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.*;
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

@Configuration(proxyBeanMethods=false)
@RequiredArgsConstructor
public class TestCloudExecutorSecurityConfig {
    private final TestCloudExecutorService service;
    @Bean @Order(0)
    SecurityFilterChain testCloudExecutorChain(HttpSecurity http) throws Exception {
        return http.securityMatcher("/api/test/cloud-executors/**").csrf(csrf->csrf.disable())
                .sessionManagement(session->session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors->errors.authenticationEntryPoint((req,res,ex)->error(res,401,"TEST_CLOUD_AUTH_INVALID"))
                        .accessDeniedHandler((req,res,ex)->error(res,403,"TEST_CLOUD_FORBIDDEN")))
                .authorizeHttpRequests(auth->auth.requestMatchers(HttpMethod.POST,"/api/test/cloud-executors/v1/next-task",
                        "/api/test/cloud-executors/v1/heartbeat","/api/test/cloud-executors/v1/tasks/*/heartbeat",
                        "/api/test/cloud-executors/v1/tasks/*/complete").hasRole("TEST_CLOUD_EXECUTOR")
                        .requestMatchers(HttpMethod.GET,"/api/test/cloud-executors/v1/tasks/*/receipt","/api/test/cloud-executors/v1/instances").hasRole("TEST_CLOUD_EXECUTOR")
                        .anyRequest().denyAll())
                .addFilterBefore(new CloudAuthenticationFilter(service),UsernamePasswordAuthenticationFilter.class).build();
    }
    static final class CloudAuthenticationFilter extends OncePerRequestFilter {
        private final TestCloudExecutorService service;
        CloudAuthenticationFilter(TestCloudExecutorService service) { this.service=service; }
        protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws IOException,ServletException {
            SecurityContextHolder.clearContext();
            try {
                service.requireEnabled();
                if(!GeoBlockEnforcementFilter.isDirectLoopback(request)) throw new BizException(403,"TEST_CLOUD_LOCAL_ONLY");
                String executorId=request.getHeader("X-UVEL-Executor-Id");
                if(request.getRequestURI().equals("/api/test/cloud-executors/v1/instances") && executorId==null) executorId="fleet";
                var identity=service.authenticate(request.getHeader("Authorization"),executorId);
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(identity,null,
                        List.of(new SimpleGrantedAuthority("ROLE_TEST_CLOUD_EXECUTOR"))));
                chain.doFilter(request,response);
            } catch(BizException failure) { error(response,failure.getCode(),failure.getMessage()); }
            finally { SecurityContextHolder.clearContext(); }
        }
    }
    private static void error(HttpServletResponse response,int code,String message) throws IOException {
        response.setStatus(code); response.setCharacterEncoding("UTF-8"); response.setContentType("application/json");
        response.getWriter().write("{\"code\":"+code+",\"message\":\""+message+"\",\"data\":null}");
    }
}
