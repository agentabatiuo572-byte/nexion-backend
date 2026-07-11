package ffdd.opsconsole.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.auth.infrastructure.AdminRoleRelationEntity;
import ffdd.opsconsole.auth.dto.AdminLoginResponse;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface AdminRoleRelationMapper extends BaseMapper<AdminRoleRelationEntity> {
    @Update("""
            UPDATE nx_admin_role_relation
            SET is_deleted = 1, updated_at = NOW()
            WHERE admin_id = #{adminId}
              AND role_id NOT IN (
                  SELECT id
                  FROM nx_admin_role
                  WHERE role_code = #{roleCode}
                    AND status = 1
                    AND is_deleted = 0
              )
            """)
    int disableOtherPrimaryRoles(@Param("adminId") Long adminId, @Param("roleCode") String roleCode);

    @Insert("""
            INSERT INTO nx_admin_role_relation (admin_id, role_id)
            SELECT #{adminId}, id
            FROM nx_admin_role
            WHERE role_code = #{roleCode}
              AND status = 1
              AND is_deleted = 0
            LIMIT 1
            ON DUPLICATE KEY UPDATE is_deleted = 0, updated_at = NOW()
            """)
    int ensurePrimaryRole(@Param("adminId") Long adminId, @Param("roleCode") String roleCode);

    @Select("""
            SELECT r.role_code
              FROM nx_admin_role_relation rr
              JOIN nx_admin_role r
                ON r.id = rr.role_id
               AND r.status = 1
               AND r.is_deleted = 0
             WHERE rr.admin_id = #{adminId}
               AND rr.is_deleted = 0
             ORDER BY rr.updated_at DESC, rr.id DESC
             LIMIT 1
            """)
    String activeRoleCode(@Param("adminId") Long adminId);

    @Select("""
            SELECT m.menu_code
              FROM nx_admin_role_relation rr
              JOIN nx_admin_role r
                ON r.id = rr.role_id
               AND r.status = 1
               AND r.is_deleted = 0
              JOIN nx_admin_role_menu rm
                ON rm.role_id = r.id
               AND rm.is_deleted = 0
              JOIN nx_admin_menu m
                ON m.id = rm.menu_id
               AND m.status = 1
               AND m.is_deleted = 0
             WHERE rr.admin_id = #{adminId}
               AND rr.is_deleted = 0
             GROUP BY m.menu_code
             ORDER BY MIN(m.sort_order), MIN(m.id)
            """)
    List<String> selectActiveMenuCodes(@Param("adminId") Long adminId);

    /**
     * Only returns active A7 nodes reachable through this admin's active A6 role grants.
     * The session never exposes the full menu catalog to a role that was not granted it.
     */
    @Select("""
            SELECT m.menu_code AS menuCode,
                   COALESCE(NULLIF(m.menu_name_zh, ''), NULLIF(m.menu_name, ''), m.menu_code) AS menuName,
                   m.route_path AS routePath,
                   p.menu_code AS parentCode,
                   m.sort_order AS sortOrder
              FROM nx_admin_role_relation rr
              JOIN nx_admin_role r
                ON r.id = rr.role_id
               AND r.status = 1
               AND r.is_deleted = 0
              JOIN nx_admin_role_menu rm
                ON rm.role_id = r.id
               AND rm.is_deleted = 0
              JOIN nx_admin_menu m
                ON m.id = rm.menu_id
               AND m.status = 1
               AND m.is_deleted = 0
              LEFT JOIN nx_admin_menu p
                ON p.id = m.parent_id
               AND p.status = 1
               AND p.is_deleted = 0
             WHERE rr.admin_id = #{adminId}
               AND rr.is_deleted = 0
             GROUP BY m.menu_code, m.menu_name_zh, m.menu_name, m.route_path,
                      p.menu_code, m.sort_order, m.id
             ORDER BY m.sort_order, m.id
            """)
    List<AdminLoginResponse.EffectiveMenuNode> selectActiveMenuNodes(@Param("adminId") Long adminId);

    /** Super-admin navigation is independent of role bindings, but still excludes disabled/deleted A7 nodes. */
    @Select("""
            SELECT m.menu_code AS menuCode,
                   COALESCE(NULLIF(m.menu_name_zh, ''), NULLIF(m.menu_name, ''), m.menu_code) AS menuName,
                   m.route_path AS routePath,
                   p.menu_code AS parentCode,
                   m.sort_order AS sortOrder
              FROM nx_admin_menu m
              LEFT JOIN nx_admin_menu p
                ON p.id = m.parent_id
               AND p.status = 1
               AND p.is_deleted = 0
             WHERE m.status = 1
               AND m.is_deleted = 0
             ORDER BY m.sort_order, m.id
            """)
    List<AdminLoginResponse.EffectiveMenuNode> selectAllActiveMenuNodes();

    /** 查角色下所有 admin（A6 改角色绑定后精准 evict 用）。 */
    @Select("""
            SELECT DISTINCT admin_id
              FROM nx_admin_role_relation
             WHERE role_id = #{roleId}
               AND is_deleted = 0
            """)
    List<Long> selectAdminIdsByRole(@Param("roleId") Long roleId);

    /** 删除角色时级联软删该角色下所有 admin↔role 关联。 */
    @Update("""
            UPDATE nx_admin_role_relation
               SET is_deleted = 1, updated_at = NOW()
             WHERE role_id = #{roleId}
               AND is_deleted = 0
            """)
    int disableRelationsByRole(@Param("roleId") Long roleId);
}
