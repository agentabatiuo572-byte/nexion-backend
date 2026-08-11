package ffdd.opsconsole.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import jakarta.servlet.FilterChain;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class AuthenticatedPrincipalRateLimitFilterTest {
    private final PlatformConfigFacade config = mock(PlatformConfigFacade.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final AuthenticatedPrincipalRateLimitFilter filter =
            new AuthenticatedPrincipalRateLimitFilter(config, redis);

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        when(config.activeValue(PlatformGlobalRateLimitFilter.CONFIG_KEY)).thenReturn(Optional.of("100"));
        when(redis.opsForValue()).thenReturn(values);
        when(redis.expire(any(String.class), any())).thenReturn(true);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatedRequestCreatesSubjectRouteBucketAfterJwt() throws Exception {
        List<String> keys = new ArrayList<>();
        when(values.increment(any(String.class))).thenAnswer(invocation -> {
            keys.add(invocation.getArgument(0));
            return 1L;
        });
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin-42", null, List.of()));

        MockHttpServletResponse response = execute("/api/admin/platform/config");

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(keys).hasSize(1);
        assertThat(keys.get(0)).contains("ops:principal-rate:subject-");
    }

    @Test
    void anonymousRequestAddsNoPrincipalBucketAndReliesOnThePreAuthIpGate() throws Exception {
        MockHttpServletResponse response = execute("/api/public/catalog");

        assertThat(response.getStatus()).isEqualTo(200);
        verify(values, never()).increment(any(String.class));
    }

    @Test
    void securityChainRegistersPrincipalLimiterStrictlyAfterJwt() throws Exception {
        String security = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/shared/security/SecurityConfig.java"));
        assertThat(security).contains(
                ".addFilterAfter(authenticatedPrincipalRateLimitFilter, JwtAuthenticationFilter.class)",
                ".addFilterAfter(impersonationReadOnlyEnforcementFilter, AuthenticatedPrincipalRateLimitFilter.class)");
    }

    private MockHttpServletResponse execute(String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();
        FilterChain chain = (req, res) -> invoked.set(true);
        filter.doFilter(request, response, chain);
        if (invoked.get()) response.setStatus(200);
        return response;
    }
}
