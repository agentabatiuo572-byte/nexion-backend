package ffdd.opsconsole.shared.security;


import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final AdminRbacAuthorizationFilter adminRbacAuthorizationFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/admin/auth/login",
                                "/auth/login",
                                "/auth/register",
                                "/auth/users/login",
                                "/auth/users/referrals/**",
                                "/auth/users/register/**",
                                "/auth/users/register",
                                "/commerce/app/store/**",
                                "/commerce/app/price-index",
                                "/commerce/app/payment-options",
                                "/openapi/v1/**")
                        .permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                // SSE 端点（GET /api/admin/content/conversations/stream）的 query-token 头化：
                // 浏览器原生 EventSource 不能自定义请求头，若客户端用 ?token=xxx 直连后端，
                // 此 shim 把 query token 转成 Authorization: Bearer，再交给后续 JwtAuthenticationFilter 校验。
                // 注册顺序:必须先注册 JwtAuthenticationFilter(锚点),再用它作锚点注册 shim;
                // 执行顺序仍是 shim 头化 → JWT 校验(shim 在 JWT 之前)。仅对 stream 路径生效,不影响其它请求。
                .addFilterBefore(new SseTokenShimFilter(), JwtAuthenticationFilter.class)
                .addFilterAfter(adminRbacAuthorizationFilter, JwtAuthenticationFilter.class)
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of(
                "http://localhost:*",
                "http://127.0.0.1:*",
                "http://[::1]:*",
                "http://10.*:*",
                "http://172.16.*:*",
                "http://172.17.*:*",
                "http://172.18.*:*",
                "http://172.19.*:*",
                "http://172.20.*:*",
                "http://172.21.*:*",
                "http://172.22.*:*",
                "http://172.23.*:*",
                "http://172.24.*:*",
                "http://172.25.*:*",
                "http://172.26.*:*",
                "http://172.27.*:*",
                "http://172.28.*:*",
                "http://172.29.*:*",
                "http://172.30.*:*",
                "http://172.31.*:*",
                "http://192.168.*:*"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setExposedHeaders(List.of("Authorization"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /**
     * SSE 端点的 query-token 头化过滤器。
     *
     * <p>背景：浏览器原生 EventSource 不支持自定义请求头；同源 cookie 经 Next route 透传 Authorization
     * 已能覆盖管理台默认场景。但若客户端改用直连后端 + ?token=xxx（例如跨进程 / 调试 / 反向代理拓扑），
     * JwtAuthenticationFilter 只读 Authorization 头、读不到 query token → SecurityContext 空 → RBAC 401。
     *
     * <p>本过滤器仅对 GET /api/admin/content/conversations/stream 生效：当请求未带 Authorization 头
     * 且 query 带 token 参数时，用 HttpServletRequestWrapper 把 token 头化为 Authorization: Bearer，
     * 后续 JwtAuthenticationFilter / AdminRbacAuthorizationFilter 完全无需改动。
     */
    static final class SseTokenShimFilter extends OncePerRequestFilter {
        private static final String STREAM_PATH = "/api/admin/content/conversations/stream";
        private static final String AUTHORIZATION = "Authorization";
        private static final String BEARER_PREFIX = "Bearer ";

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            HttpServletRequest forwarded = request;
            if ("GET".equals(request.getMethod())
                    && STREAM_PATH.equals(request.getRequestURI())
                    && !StringUtils.hasText(request.getHeader(AUTHORIZATION))) {
                String token = request.getParameter("token");
                if (StringUtils.hasText(token)) {
                    forwarded = new BearerHeaderWrapper(request, BEARER_PREFIX + token);
                }
            }
            chain.doFilter(forwarded, response);
        }

        /** 仅覆盖 Authorization 头读取；其余 header / 参数 / 路径信息透传。 */
        private static final class BearerHeaderWrapper extends HttpServletRequestWrapper {
            @SuppressWarnings("ArchitectureConfigField") // 请求包装器状态，不是 Spring 配置值。
            private final String authorization;

            BearerHeaderWrapper(HttpServletRequest request, String authorization) {
                super(request);
                this.authorization = authorization;
            }

            @Override
            public String getHeader(String name) {
                if (AUTHORIZATION.equalsIgnoreCase(name)) {
                    return authorization;
                }
                return super.getHeader(name);
            }

            @Override
            public Enumeration<String> getHeaders(String name) {
                if (AUTHORIZATION.equalsIgnoreCase(name)) {
                    return Collections.enumeration(List.of(authorization));
                }
                return super.getHeaders(name);
            }

            @Override
            public Enumeration<String> getHeaderNames() {
                Set<String> names = new HashSet<>();
                Enumeration<String> original = super.getHeaderNames();
                while (original != null && original.hasMoreElements()) {
                    names.add(original.nextElement());
                }
                names.add(AUTHORIZATION);
                return Collections.enumeration(names);
            }
        }
    }
}
