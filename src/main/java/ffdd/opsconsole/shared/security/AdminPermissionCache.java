package ffdd.opsconsole.shared.security;

import ffdd.opsconsole.auth.mapper.AdminRolePermissionMapper;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 管理端账号权限的 Redis 缓存（经典 RBAC）。
 *
 * <p>权威数据源为 MySQL（{@code nx_admin_permission} 等，由 {@code scripts/rbac-classic-seed/*.sql} 灌注）；
 * 这里做按账号的 Redis 缓存：鉴权时从 Redis 读，miss 回源 MySQL 并回填，Redis 不可用时降级直查 MySQL。
 * 权限/角色变更（A6/A7 配置面）后调 {@link #evict(Long)} 即时失效，无需等 TTL 或重登。
 *
 * <p>Key: {@code rbac:v2:admin:perms:{adminId}}，Redis Set 存权限码（每码一个 member，免序列化），TTL 30min 兜底。
 * super_admin 在 DB 已绑定全 265 细点（{@code role_permission} seed），不再需要旧 PERM_* 桥接。
 */
@Component
@RequiredArgsConstructor
public class AdminPermissionCache {
    private static final String KEY_PREFIX = "rbac:v2:admin:perms:";
    private static final Duration TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redisTemplate;
    private final AdminRolePermissionMapper permissionMapper;

    /** 读取账号权限码：Redis 命中直接返回；miss/异常回源 MySQL 并回填。 */
    public Set<String> getPermissionCodes(Long adminId) {
        if (adminId == null) {
            return Set.of();
        }
        String key = KEY_PREFIX + adminId;
        try {
            Set<String> cached = redisTemplate.opsForSet().members(key);
            if (cached != null && !cached.isEmpty()) {
                return cached;
            }
        } catch (RuntimeException ex) {
            // Redis 异常：降级直查 MySQL（不回填，避免反复写挂掉的 Redis）
            return loadFromDb(adminId);
        }
        Set<String> codes = loadFromDb(adminId);
        if (!codes.isEmpty()) {
            try {
                redisTemplate.opsForSet().add(key, codes.toArray(String[]::new));
                redisTemplate.expire(key, TTL);
            } catch (RuntimeException ignored) {
                // 回填失败不影响本次鉴权（下次请求再回源）
            }
        }
        return codes;
    }

    /** 失效单个账号权限缓存（A6 账号角色变更时调）。 */
    public void evict(Long adminId) {
        if (adminId == null) {
            return;
        }
        redisTemplate.delete(KEY_PREFIX + adminId);
    }

    /** 失效全部账号权限缓存（角色权限批量变更兜底）。 */
    public void evictAll() {
        Set<String> keys = redisTemplate.keys(KEY_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    private Set<String> loadFromDb(Long adminId) {
        return new LinkedHashSet<>(permissionMapper.selectActivePermissionCodes(adminId));
    }
}
