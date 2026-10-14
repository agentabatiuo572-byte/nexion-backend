package ffdd.opsconsole.shared.security;

import ffdd.opsconsole.auth.mapper.AdminRolePermissionMapper;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 管理端账号权限读取（经典 RBAC）；保留旧缓存清理接口兼容既有调用。
 *
 * <p>权威数据源为 MySQL（{@code nx_admin_permission} 等，由 {@code scripts/rbac-classic-seed/*.sql} 灌注）；
 * 每次鉴权直接读取当前有效权限，不从 Redis 读取或回填，避免角色变更前的旧读取晚回填。
 * 数据库读取失败向调用方传播，不回退到旧授权。
 *
 * <p>旧 Key: {@code rbac:v2:admin:perms:{adminId}}，{@link #evict(Long)} / {@link #evictAll()} 仅清理遗留键。
 * super_admin 在 DB 已绑定全 265 细点（{@code role_permission} seed），不再需要旧 PERM_* 桥接。
 */
@Component
@RequiredArgsConstructor
public class AdminPermissionCache {
    private static final String KEY_PREFIX = "rbac:v2:admin:perms:";

    private final StringRedisTemplate redisTemplate;
    private final AdminRolePermissionMapper permissionMapper;

    /** 每次读取数据库中的有效权限；未知账号没有权限，读取异常不使用旧值。 */
    public Set<String> getPermissionCodes(Long adminId) {
        if (adminId == null) {
            return Set.of();
        }
        return loadFromDb(adminId);
    }

    /** 清理单个账号的遗留权限缓存键（兼容既有角色变更调用）。 */
    public void evict(Long adminId) {
        if (adminId == null) {
            return;
        }
        redisTemplate.delete(KEY_PREFIX + adminId);
    }

    /** 清理全部遗留权限缓存键（兼容既有初始化调用）。 */
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
