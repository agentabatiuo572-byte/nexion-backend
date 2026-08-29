package ffdd.opsconsole.shared.security;


import ffdd.opsconsole.platform.application.AuthenticatedPrincipalRateLimitFilter;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.developer.web.DeveloperApiKeyAuthenticationFilter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final AdminRbacAuthorizationFilter adminRbacAuthorizationFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ImpersonationReadOnlyEnforcementFilter impersonationReadOnlyEnforcementFilter,
            ObjectProvider<DeveloperApiKeyAuthenticationFilter> developerApiKeyAuthenticationFilterProvider,
            ObjectProvider<AuthenticatedPrincipalRateLimitFilter> principalRateLimitFilterProvider,
            ObjectProvider<UserBlocklistEnforcementFilter> userBlocklistFilterProvider) throws Exception {
        UserBlocklistEnforcementFilter userBlocklistEnforcementFilter = userBlocklistFilterProvider.getIfAvailable(
                () -> new UserBlocklistEnforcementFilter(userId -> false));
        AuthenticatedPrincipalRateLimitFilter authenticatedPrincipalRateLimitFilter =
                principalRateLimitFilterProvider.getIfAvailable(
                        AuthenticatedPrincipalRateLimitFilter::disabledForSlice);
        DeveloperApiKeyAuthenticationFilter developerApiKeyAuthenticationFilter = developerApiKeyAuthenticationFilterProvider.getIfAvailable();
        HttpSecurity configured = http.csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) ->
                                writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, "AUTH_REQUIRED"))
                        .accessDeniedHandler((request, response, exception) ->
                                writeJsonError(response, HttpServletResponse.SC_FORBIDDEN, "ACCESS_DENIED")))
                .authorizeHttpRequests(auth -> auth
                        // The original REQUEST dispatch is authenticated before a StreamingResponseBody
                        // starts. ASYNC is a container-managed continuation of that authorized request;
                        // re-authorizing it after the response is committed produces a false 403/error dispatch.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/config/platform").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/config/referral-rewards").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/public/referrals/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/config/task-pricing", "/api/config/phone-tiers").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/config/staking/pools").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/config/v-ranks").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/config/v-rank-policy", "/api/developer/docs", "/api/legal/terms/current").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/config/commission/rates").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/config/exchange/caps", "/api/config/market/nex", "/api/config/market/external", "/api/market/nex").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/genesis/state").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/config/repurchase").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/content/trust/sections/current", "/api/content/how-it-works/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/content/i18n", "/api/content/i18n/**", "/i18n", "/i18n/**").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/openapi/v1/topups/card/admission",
                                "/openapi/v1/topups/card/settlements",
                                "/openapi/v1/topups/card/failures",
                                "/openapi/v1/topups/card/chargebacks",
                                "/openapi/v1/topups/provider-statements",
                                "/openapi/v1/withdrawals/cregis/callbacks/payout")
                        .permitAll()
                        .requestMatchers(
                                "/api/admin/auth/login",
                                "/api/admin/auth/mfa/verify",
                                "/auth/login",
                                "/auth/register",
                                "/auth/users/login",
                                "/auth/users/login/2fa",
                                "/auth/users/login/otp/send",
                                "/auth/users/login/otp/verify",
                                "/auth/users/oauth/development/passkey/challenge",
                                "/auth/users/oauth/sandbox/challenge",
                                "/auth/users/oauth/exchange",
                                "/auth/users/refresh",
                                "/auth/users/logout",
                                "/auth/users/password-reset/complete",
                                "/auth/users/referrals/**",
                                "/auth/users/register/**",
                                "/auth/users/register",
                                "/commerce/app/store/**",
                                "/commerce/app/price-index",
                                "/commerce/app/payment-options")
                        .permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        // WebMvc slice tests and deployments that do not expose developer APIs
        // intentionally have no API-key filter bean. Do not add a null filter.
        if (developerApiKeyAuthenticationFilter != null) {
            configured.addFilterAfter(developerApiKeyAuthenticationFilter, JwtAuthenticationFilter.class);
        }
        return configured
                .addFilterAfter(authenticatedPrincipalRateLimitFilter, JwtAuthenticationFilter.class)
                .addFilterAfter(impersonationReadOnlyEnforcementFilter, AuthenticatedPrincipalRateLimitFilter.class)
                .addFilterAfter(userBlocklistEnforcementFilter, ImpersonationReadOnlyEnforcementFilter.class)
                .addFilterAfter(adminRbacAuthorizationFilter, UserBlocklistEnforcementFilter.class)
                .build();
    }

    @Bean
    public ImpersonationReadOnlyEnforcementFilter impersonationReadOnlyEnforcementFilter() {
        return new ImpersonationReadOnlyEnforcementFilter();
    }

    @Bean
    @ConditionalOnBean({PlatformConfigFacade.class, StringRedisTemplate.class})
    public AuthenticatedPrincipalRateLimitFilter authenticatedPrincipalRateLimitFilter(
            PlatformConfigFacade config, StringRedisTemplate redis) {
        return new AuthenticatedPrincipalRateLimitFilter(config, redis);
    }

    @Bean
    public FilterRegistrationBean<AuthenticatedPrincipalRateLimitFilter> authenticatedPrincipalRateLimitServletRegistration(
            ObjectProvider<AuthenticatedPrincipalRateLimitFilter> filterProvider) {
        AuthenticatedPrincipalRateLimitFilter filter = filterProvider.getIfAvailable(
                AuthenticatedPrincipalRateLimitFilter::disabledForSlice);
        FilterRegistrationBean<AuthenticatedPrincipalRateLimitFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    static void writeJsonError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":" + status + ",\"message\":\"" + message + "\",\"data\":null}");
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

}
