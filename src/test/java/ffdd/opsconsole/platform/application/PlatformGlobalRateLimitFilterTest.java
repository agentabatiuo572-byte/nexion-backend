package ffdd.opsconsole.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import jakarta.servlet.FilterChain;
import java.util.Optional;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class PlatformGlobalRateLimitFilterTest {
    private PlatformGlobalRateLimitFilter filter;
    private ValueOperations<String, String> values;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        SecurityContextHolder.clearContext();
        PlatformConfigFacade config = mock(PlatformConfigFacade.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(config.activeValue(PlatformGlobalRateLimitFilter.CONFIG_KEY)).thenReturn(Optional.of("100"));
        when(redis.opsForValue()).thenReturn(values);
        when(redis.expire(any(String.class), any())).thenReturn(true);
        filter = new PlatformGlobalRateLimitFilter(config, redis);
    }

    @Test
    void anonymousGeneralFloodDoesNotConsumeAdminAuthOrProviderCallbackBudgets() throws Exception {
        when(values.increment(any(String.class))).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return key.contains(":admin-auth:") || key.contains(":provider-callback:") ? 1L : 101L;
        });

        MockHttpServletResponse generalResponse = execute("GET", "/api/public/catalog");
        MockHttpServletResponse authResponse = execute("POST", "/api/admin/auth/login");
        MockHttpServletResponse callbackResponse = execute("POST", "/api/payments/providers/cregis/callback");

        assertThat(generalResponse.getStatus()).isEqualTo(429);
        assertThat(authResponse.getStatus()).isEqualTo(200);
        assertThat(callbackResponse.getStatus()).isEqualTo(200);
    }

    @Test
    void apiHealthProbeUsesIndependentBudget() throws Exception {
        when(values.increment(any(String.class))).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return key.contains(":health:") ? 1L : 101L;
        });

        MockHttpServletResponse response = execute("GET", "/api/admin/janus/health");

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void legacyAuthRouteUsesTheIndependentAuthenticationBudget() throws Exception {
        when(values.increment(any(String.class))).thenAnswer(invocation ->
                invocation.<String>getArgument(0).contains(":admin-auth:") ? 1L : 101L);

        assertThat(execute("POST", "/auth/login").getStatus()).isEqualTo(200);
    }

    @Test
    void rotatingUnauthenticatedBearerValuesStillShareTheIpRouteBucket() throws Exception {
        Set<String> keys = new LinkedHashSet<>();
        when(values.increment(any(String.class))).thenAnswer(invocation -> {
            keys.add(invocation.getArgument(0));
            return 1L;
        });

        executeWithBearer("GET", "/api/public/catalog", "Bearer random-a");
        executeWithBearer("GET", "/api/public/catalog", "Bearer random-b");

        assertThat(keys).hasSize(2);
        assertThat(keys).anyMatch(key -> key.contains(":ip-") && !key.contains("random"));
        assertThat(keys).noneMatch(key -> key.contains(":subject-"));
    }

    private MockHttpServletResponse execute(String method, String path) throws Exception {
        return executeWithBearer(method, path, null);
    }

    private MockHttpServletResponse executeWithBearer(String method, String path, String authorization) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr("203.0.113.9");
        if (authorization != null) request.addHeader("Authorization", authorization);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();
        FilterChain chain = (req, res) -> invoked.set(true);
        filter.doFilter(request, response, chain);
        if (invoked.get() && response.getStatus() == 200) response.setStatus(200);
        return response;
    }
}
