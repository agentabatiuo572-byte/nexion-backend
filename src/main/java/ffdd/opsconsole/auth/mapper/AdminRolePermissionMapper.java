package ffdd.opsconsole.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.auth.infrastructure.AdminRolePermissionEntity;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface AdminRolePermissionMapper extends BaseMapper<AdminRolePermissionEntity> {

    @Select("""
            SELECT DISTINCT p.permission_code
            FROM nx_admin_role_relation rr
            JOIN nx_admin_role r
              ON r.id = rr.role_id
             AND r.status = 1
             AND r.is_deleted = 0
            JOIN nx_admin_role_permission rp
              ON rp.role_id = r.id
             AND rp.is_deleted = 0
            JOIN nx_admin_permission p
              ON p.id = rp.permission_id
             AND p.status = 1
             AND p.is_deleted = 0
             AND p.resource_type = 'API'
            WHERE rr.admin_id = #{adminId}
              AND rr.is_deleted = 0
            """)
    List<String> selectActivePermissionCodes(Long adminId);

    @Insert("""
            INSERT INTO nx_admin_role (
                role_code,
                role_name,
                remark,
                status,
                is_deleted
            )
            VALUES (
                #{roleCode},
                #{roleName},
                #{remark},
                1,
                0
            )
            ON DUPLICATE KEY UPDATE
                role_name = VALUES(role_name),
                remark = VALUES(remark),
                status = 1,
                is_deleted = 0,
                updated_at = NOW()
            """)
    int ensureRole(@Param("roleCode") String roleCode,
                   @Param("roleName") String roleName,
                   @Param("remark") String remark);

    @Update("""
            UPDATE nx_admin_role_relation supportRelation
            JOIN nx_admin_role support
              ON support.id = supportRelation.role_id
             AND support.role_code = #{supportRoleCode}
            JOIN nx_admin_role_relation legacyRelation
              ON legacyRelation.admin_id = supportRelation.admin_id
             AND legacyRelation.is_deleted = 0
            JOIN nx_admin_role legacy
              ON legacy.id = legacyRelation.role_id
             AND legacy.role_code = #{legacyRoleCode}
            SET supportRelation.is_deleted = 0,
                supportRelation.updated_at = NOW()
            WHERE supportRelation.is_deleted = 1
            """)
    int restoreSupportRelationsForLegacy(@Param("legacyRoleCode") String legacyRoleCode,
                                         @Param("supportRoleCode") String supportRoleCode);

    @Update("""
            UPDATE nx_admin_role_relation rr
            JOIN nx_admin_role legacy
              ON legacy.id = rr.role_id
             AND legacy.role_code = #{legacyRoleCode}
            JOIN nx_admin_role support
              ON support.role_code = #{supportRoleCode}
             AND support.status = 1
             AND support.is_deleted = 0
            LEFT JOIN nx_admin_role_relation existing
              ON existing.admin_id = rr.admin_id
             AND existing.role_id = support.id
            SET rr.role_id = support.id,
                rr.is_deleted = 0,
                rr.updated_at = NOW()
            WHERE rr.is_deleted = 0
              AND existing.id IS NULL
            """)
    int migrateRoleRelations(@Param("legacyRoleCode") String legacyRoleCode,
                             @Param("supportRoleCode") String supportRoleCode);

    @Update("""
            UPDATE nx_admin_role_relation rr
            JOIN nx_admin_role legacy
              ON legacy.id = rr.role_id
             AND legacy.role_code = #{legacyRoleCode}
            SET rr.is_deleted = 1,
                rr.updated_at = NOW()
            WHERE rr.is_deleted = 0
            """)
    int disableRoleRelations(@Param("legacyRoleCode") String legacyRoleCode);

    @Update("""
            UPDATE nx_admin_role
               SET status = 0,
                   is_deleted = 1,
                   updated_at = NOW()
             WHERE role_code = #{roleCode}
            """)
    int disableRole(@Param("roleCode") String roleCode);

    @Insert("""
            INSERT INTO nx_admin_permission (
                permission_code,
                permission_name,
                resource_type,
                resource_path,
                remark,
                status,
                is_deleted
            )
            VALUES (
                #{permissionCode},
                #{permissionName},
                'API',
                #{resourcePath},
                #{remark},
                1,
                0
            )
            ON DUPLICATE KEY UPDATE
                permission_name = VALUES(permission_name),
                resource_type = VALUES(resource_type),
                resource_path = VALUES(resource_path),
                remark = VALUES(remark),
                status = 1,
                is_deleted = 0,
                updated_at = NOW()
            """)
    int ensurePermission(@Param("permissionCode") String permissionCode,
                         @Param("permissionName") String permissionName,
                         @Param("resourcePath") String resourcePath,
                         @Param("remark") String remark);

    @Update("""
            UPDATE nx_admin_permission
               SET status = 0,
                   is_deleted = 1,
                   updated_at = NOW()
             WHERE permission_code = #{permissionCode}
            """)
    int disablePermission(@Param("permissionCode") String permissionCode);

    @Update("""
            UPDATE nx_admin_role_permission rp
            JOIN nx_admin_role r
              ON r.id = rp.role_id
             AND r.role_code = #{roleCode}
             AND r.status = 1
             AND r.is_deleted = 0
            JOIN nx_admin_permission p
              ON p.id = rp.permission_id
             AND p.permission_code = #{permissionCode}
             AND p.status = 1
             AND p.is_deleted = 0
            SET rp.is_deleted = 0,
                rp.updated_at = NOW()
            WHERE rp.is_deleted = 1
            """)
    int restoreRolePermission(@Param("roleCode") String roleCode,
                              @Param("permissionCode") String permissionCode);

    @Insert("""
            INSERT INTO nx_admin_role_permission (role_id, permission_id)
            SELECT r.id, p.id
              FROM nx_admin_role r
              JOIN nx_admin_permission p
                ON p.permission_code = #{permissionCode}
               AND p.status = 1
               AND p.is_deleted = 0
             WHERE r.role_code = #{roleCode}
               AND r.status = 1
               AND r.is_deleted = 0
               AND NOT EXISTS (
                   SELECT 1
                     FROM nx_admin_role_permission rp
                    WHERE rp.role_id = r.id
                      AND rp.permission_id = p.id
                      AND rp.is_deleted = 0
               )
            """)
    int insertMissingRolePermission(@Param("roleCode") String roleCode,
                                    @Param("permissionCode") String permissionCode);

    @Update("""
            UPDATE nx_admin_role_permission rp
            JOIN nx_admin_role r
              ON r.id = rp.role_id
             AND r.role_code = #{roleCode}
             AND r.is_deleted = 0
            JOIN nx_admin_permission p
              ON p.id = rp.permission_id
             AND p.permission_code = #{permissionCode}
             AND p.is_deleted = 0
            SET rp.is_deleted = 1,
                rp.updated_at = NOW()
            WHERE rp.is_deleted = 0
            """)
    int disableRolePermission(@Param("roleCode") String roleCode,
                              @Param("permissionCode") String permissionCode);

    @Update("""
            <script>
            UPDATE nx_admin_role_permission rp
            JOIN nx_admin_role r
              ON r.id = rp.role_id
             AND r.role_code = #{roleCode}
             AND r.is_deleted = 0
            JOIN nx_admin_permission p
              ON p.id = rp.permission_id
            SET rp.is_deleted = 1,
                rp.updated_at = NOW()
            WHERE rp.is_deleted = 0
              AND p.permission_code NOT IN
              <foreach collection="allowedPermissionCodes" item="permissionCode" open="(" separator="," close=")">
                #{permissionCode}
              </foreach>
            </script>
            """)
    int disableRolePermissionsExcept(@Param("roleCode") String roleCode,
                                     @Param("allowedPermissionCodes") Collection<String> allowedPermissionCodes);
}
