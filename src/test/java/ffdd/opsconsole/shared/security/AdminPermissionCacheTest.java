package ffdd.opsconsole.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.auth.mapper.AdminRolePermissionMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

class AdminPermissionCacheTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final AdminRolePermissionMapper mapper = mock(AdminRolePermissionMapper.class);
    private final AdminPermissionCache permissions = new AdminPermissionCache(redis, mapper);

    @Test
    void eachReadSeesCurrentDatabaseGrantsIncludingRevocationWithoutRedisRefill() {
        when(mapper.selectActivePermissionCodes(1456L)).thenReturn(
                List.of("service_m1_read", "service_m1_write"), List.of("service_m1_read"), List.of());

        assertThat(permissions.getPermissionCodes(1456L)).containsExactly("service_m1_read", "service_m1_write");
        assertThat(permissions.getPermissionCodes(1456L)).containsExactly("service_m1_read");
        assertThat(permissions.getPermissionCodes(1456L)).isEmpty();

        verify(mapper, times(3)).selectActivePermissionCodes(1456L);
        verifyNoInteractions(redis);
    }

    @Test
    void legacyRedisGrantsAreNeverReadOrRefilled() {
        @SuppressWarnings("unchecked")
        SetOperations<String, String> legacy = mock(SetOperations.class);
        when(redis.opsForSet()).thenReturn(legacy);
        when(legacy.members("rbac:v2:admin:perms:1456")).thenReturn(Set.of("PERM_ADMIN_WRITE"));
        when(mapper.selectActivePermissionCodes(1456L)).thenReturn(List.of("service_m1_read"));

        assertThat(permissions.getPermissionCodes(1456L)).containsExactly("service_m1_read");

        verifyNoInteractions(redis, legacy);
    }

    @Test
    void databaseFailureDoesNotReusePreviouslyReadGrantsOrReadRedis() {
        when(mapper.selectActivePermissionCodes(1456L)).thenReturn(List.of("service_m1_write"))
                .thenThrow(new IllegalStateException("permission store unavailable"));
        assertThat(permissions.getPermissionCodes(1456L)).containsExactly("service_m1_write");

        assertThatThrownBy(() -> permissions.getPermissionCodes(1456L))
                .isInstanceOf(IllegalStateException.class).hasMessage("permission store unavailable");

        verifyNoInteractions(redis);
    }

    @Test
    void nullIdentityHasNoPermissionsAndDoesNotAccessEitherStore() {
        assertThat(permissions.getPermissionCodes(null)).isEmpty();
        verifyNoInteractions(redis, mapper);
    }

    @Test
    void legacyEvictionEntryPointsStillDeleteOnlyTheirExistingKeyNamespace() {
        when(redis.keys("rbac:v2:admin:perms:*")).thenReturn(Set.of("rbac:v2:admin:perms:1456"));
        permissions.evict(1456L);
        permissions.evictAll();
        verify(redis).delete("rbac:v2:admin:perms:1456");
        verify(redis).delete(Set.of("rbac:v2:admin:perms:1456"));
        verifyNoInteractions(mapper);
    }
}
