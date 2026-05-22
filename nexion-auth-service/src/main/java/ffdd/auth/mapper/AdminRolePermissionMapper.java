package ffdd.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.auth.domain.AdminRolePermission;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface AdminRolePermissionMapper extends BaseMapper<AdminRolePermission> {
    @Select("""
            SELECT rp.permission_id
            FROM admin_role_permission rp
            JOIN admin_permission p ON p.id = rp.permission_id
            WHERE rp.role_id = #{roleId}
              AND rp.is_deleted = 0
              AND p.is_deleted = 0
              AND p.status = 1
              AND p.resource_type = 'API'
            """)
    List<Long> selectActivePermissionIdsByRoleId(@Param("roleId") Long roleId);

    @Select("""
            <script>
            SELECT DISTINCT p.permission_code
            FROM admin_role_permission rp
            JOIN admin_permission p ON p.id = rp.permission_id
            WHERE rp.role_id IN
            <foreach collection="roleIds" item="roleId" open="(" separator="," close=")">
              #{roleId}
            </foreach>
              AND rp.is_deleted = 0
              AND p.is_deleted = 0
              AND p.status = 1
              AND p.resource_type = 'API'
            </script>
            """)
    List<String> selectActivePermissionCodesByRoleIds(@Param("roleIds") List<Long> roleIds);

    @Select("SELECT id, role_id, permission_id, created_at, updated_at, is_deleted FROM admin_role_permission WHERE role_id = #{roleId}")
    List<AdminRolePermission> selectAllByRoleId(@Param("roleId") Long roleId);

    @Select("SELECT COUNT(1) FROM admin_role_permission WHERE role_id = #{roleId} AND is_deleted = 0")
    long countActiveByRoleId(@Param("roleId") Long roleId);

    @Select("SELECT COUNT(1) FROM admin_role_permission WHERE permission_id = #{permissionId} AND is_deleted = 0")
    long countActiveByPermissionId(@Param("permissionId") Long permissionId);

    @Select("""
            SELECT r.role_name
            FROM admin_role_permission rp
            JOIN admin_role r ON r.id = rp.role_id
            WHERE rp.permission_id = #{permissionId}
              AND rp.is_deleted = 0
              AND r.is_deleted = 0
            ORDER BY r.id
            """)
    List<String> selectActiveRoleNamesByPermissionId(@Param("permissionId") Long permissionId);

    @Select("""
            SELECT p.permission_name
            FROM admin_role_permission rp
            JOIN admin_permission p ON p.id = rp.permission_id
            WHERE rp.role_id = #{roleId}
              AND rp.is_deleted = 0
              AND p.is_deleted = 0
            ORDER BY p.id
            """)
    List<String> selectActivePermissionNamesByRoleId(@Param("roleId") Long roleId);

    @Update("UPDATE admin_role_permission SET is_deleted = #{isDeleted}, updated_at = NOW() WHERE id = #{id}")
    int updateDeletedById(@Param("id") Long id, @Param("isDeleted") Integer isDeleted);

    @Insert("""
            INSERT INTO admin_role_permission (role_id, permission_id, created_at, updated_at, is_deleted)
            VALUES (#{roleId}, #{permissionId}, NOW(), NOW(), 0)
            ON DUPLICATE KEY UPDATE is_deleted = 0, updated_at = NOW()
            """)
    int upsertActive(@Param("roleId") Long roleId, @Param("permissionId") Long permissionId);
}
