package ffdd.opsconsole.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.auth.mapper.AdminRolePermissionMapper;
import ffdd.opsconsole.auth.mapper.AdminRoleRelationMapper;
import ffdd.opsconsole.shared.security.AdminPermissionCache;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

class M5ContentRbacCacheClosureInitializerTest {
    private static final long CONTENT_ADMIN_ID = 101L;
    private static final long SUPPORT_ADMIN_ID = 202L;
    private static final long RISK_ADMIN_ID = 303L;
    private static final String CONTENT_KEY = "rbac:v2:admin:perms:" + CONTENT_ADMIN_ID;
    private static final String SUPPORT_KEY = "rbac:v2:admin:perms:" + SUPPORT_ADMIN_ID;
    private static final String RISK_KEY = "rbac:v2:admin:perms:" + RISK_ADMIN_ID;

    @Test
    void startupClosureEvictsOnlyContentLegacyCacheThenNextAuthorizationUsesClosedM5Grant() {
        CacheFixture fixture = new CacheFixture();
        fixture.seed(CONTENT_ADMIN_ID, Set.of("service_m1_read", "service_m4_write", "service_m5_read"));
        fixture.seed(SUPPORT_ADMIN_ID, Set.of("service_m1_read", "service_m5_write"));
        fixture.seed(RISK_ADMIN_ID, Set.of("service_m1_read", "service_m4_read", "service_m5_read"));
        when(fixture.permissionMapper.selectActivePermissionCodes(CONTENT_ADMIN_ID))
                .thenReturn(List.of("service_m5_read", "service_m5_write"));

        M5ContentRbacCacheClosureInitializer initializer = fixture.initializer();
        initializer.enforceContentM5Closure();

        Set<String> contentAuthorities = fixture.cache.getPermissionCodes(CONTENT_ADMIN_ID);
        assertThat(hasAuthority(contentAuthorities, "service_m1_read")).isFalse();
        assertThat(hasAuthority(contentAuthorities, "service_m4_write")).isFalse();
        assertThat(hasAuthority(contentAuthorities, "service_m5_read")).isTrue();
        assertThat(hasAuthority(contentAuthorities, "service_m5_write")).isTrue();
        assertThat(fixture.cache.getPermissionCodes(SUPPORT_ADMIN_ID))
                .containsExactlyInAnyOrder("service_m1_read", "service_m5_write");
        assertThat(fixture.cache.getPermissionCodes(RISK_ADMIN_ID))
                .containsExactlyInAnyOrder("service_m1_read", "service_m4_read", "service_m5_read");
        verify(fixture.redisTemplate).delete(CONTENT_KEY);
        verify(fixture.redisTemplate, never()).delete(SUPPORT_KEY);
        verify(fixture.redisTemplate, never()).delete(RISK_KEY);
        verify(fixture.redisTemplate, never()).keys(anyString());
    }

    @Test
    void repeatedStartupClosureIsIdempotentAndStillNeverEvictsOtherRoleCaches() {
        CacheFixture fixture = new CacheFixture();
        fixture.seed(CONTENT_ADMIN_ID, Set.of("service_m1_read", "service_m5_read"));
        fixture.seed(SUPPORT_ADMIN_ID, Set.of("service_m1_read"));
        fixture.seed(RISK_ADMIN_ID, Set.of("service_m1_read"));
        when(fixture.permissionMapper.selectActivePermissionCodes(CONTENT_ADMIN_ID))
                .thenReturn(List.of("service_m5_read", "service_m5_write"));

        M5ContentRbacCacheClosureInitializer initializer = fixture.initializer();
        initializer.enforceContentM5Closure();
        Set<String> afterFirstStartup = fixture.cache.getPermissionCodes(CONTENT_ADMIN_ID);
        initializer.enforceContentM5Closure();
        Set<String> afterSecondStartup = fixture.cache.getPermissionCodes(CONTENT_ADMIN_ID);

        assertThat(afterSecondStartup).containsExactlyInAnyOrderElementsOf(afterFirstStartup);
        assertThat(afterSecondStartup).containsExactlyInAnyOrder("service_m5_read", "service_m5_write");
        verify(fixture.redisTemplate, org.mockito.Mockito.times(2)).delete(CONTENT_KEY);
        verify(fixture.redisTemplate, never()).delete(SUPPORT_KEY);
        verify(fixture.redisTemplate, never()).delete(RISK_KEY);
        verify(fixture.redisTemplate, never()).keys(anyString());
    }

    @Test
    void startupFailsClosedInsteadOfServingLegacyCacheWhenTheSqlClosureWasNotApplied() {
        CacheFixture fixture = new CacheFixture();
        fixture.seed(CONTENT_ADMIN_ID, Set.of("service_m1_read", "service_m5_read"));
        when(fixture.permissionMapper.isM5ContentRbacClosureApplied()).thenReturn(false);

        assertThatThrownBy(() -> fixture.initializer().enforceContentM5Closure())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("M5_CONTENT_RBAC_CLOSURE_REQUIRED");

        verify(fixture.redisTemplate, never()).delete(anyString());
        verify(fixture.roleRelationMapper, never()).selectAdminIdsByRoleCode("CONTENT");
    }

    private static boolean hasAuthority(Set<String> authorities, String requiredAuthority) {
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                CONTENT_ADMIN_ID,
                null,
                authorities.stream().map(SimpleGrantedAuthority::new).toList());
        AuthorizationManager<Object> manager = AuthorityAuthorizationManager.hasAuthority(requiredAuthority);
        AuthorizationDecision decision = manager.check(() -> authentication, new Object());
        return decision != null && decision.isGranted();
    }

    private static final class CacheFixture {
        private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        private final SetOperations<String, String> setOperations = mock(SetOperations.class);
        private final AdminRolePermissionMapper permissionMapper = mock(AdminRolePermissionMapper.class);
        private final AdminRoleRelationMapper roleRelationMapper = mock(AdminRoleRelationMapper.class);
        private final Map<String, Set<String>> rows = new LinkedHashMap<>();
        private final AdminPermissionCache cache = new AdminPermissionCache(redisTemplate, permissionMapper);

        private CacheFixture() {
            when(redisTemplate.opsForSet()).thenReturn(setOperations);
            when(setOperations.members(anyString())).thenAnswer(invocation ->
                    new LinkedHashSet<>(rows.getOrDefault(invocation.getArgument(0), Set.of())));
            when(redisTemplate.delete(anyString())).thenAnswer(invocation ->
                    rows.remove(invocation.getArgument(0)) != null);
            when(permissionMapper.isM5ContentRbacClosureApplied()).thenReturn(true);
            when(roleRelationMapper.selectAdminIdsByRoleCode("CONTENT")).thenReturn(List.of(CONTENT_ADMIN_ID));
        }

        private void seed(long adminId, Set<String> permissions) {
            rows.put("rbac:v2:admin:perms:" + adminId, new LinkedHashSet<>(permissions));
        }

        private M5ContentRbacCacheClosureInitializer initializer() {
            return new M5ContentRbacCacheClosureInitializer(permissionMapper, roleRelationMapper, cache);
        }
    }
}
