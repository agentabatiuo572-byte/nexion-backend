package ffdd.opsconsole.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.platform.dto.PermissionDictionaryView;
import ffdd.opsconsole.platform.infrastructure.AdminPermissionEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** A8 权限字典 mapper（只读）。platform 包首个分页 mapper：手动 count + limit/offset。 */
public interface AdminPermissionMapper extends BaseMapper<AdminPermissionEntity> {

    @Select("""
            <script>
            SELECT COUNT(*)
              FROM nx_admin_permission p
              LEFT JOIN nx_admin_menu page ON page.id = p.menu_id AND page.is_deleted = 0
              LEFT JOIN nx_admin_menu domain ON domain.id = page.parent_id AND domain.is_deleted = 0
             WHERE p.is_deleted = 0 AND p.status = 1 AND p.resource_type = 'API'
              <if test="keyword != null and keyword != ''">
                AND (p.permission_code LIKE CONCAT('%', #{keyword}, '%')
                  OR p.permission_name LIKE CONCAT('%', #{keyword}, '%'))
              </if>
              <if test="permType != null and permType != ''">
                AND p.perm_type = #{permType}
              </if>
              <if test="domain != null and domain != '' and domain != 'ALL' and domain != 'UNMAPPED'">
                AND domain.menu_code = #{domain}
              </if>
              <if test="domain == 'UNMAPPED'">
                AND domain.id IS NULL
              </if>
            </script>
            """)
    long countPermissions(@Param("keyword") String keyword,
                          @Param("domain") String domain,
                          @Param("permType") String permType);

    @Select("""
            <script>
            SELECT p.permission_code AS permissionCode,
                   p.permission_name AS permissionName,
                   p.perm_type AS permType,
                   p.menu_id AS menuId,
                   COALESCE(NULLIF(CONCAT_WS(' / ', domain.menu_name_zh, page.menu_name_zh), ''), '未归类') AS menuCodePath,
                   p.amplifies AS amplifies,
                   (SELECT COUNT(DISTINCT rp.role_id)
                      FROM nx_admin_role_permission rp
                      JOIN nx_admin_role r ON r.id = rp.role_id AND r.status = 1 AND r.is_deleted = 0
                     WHERE rp.permission_id = p.id AND rp.is_deleted = 0) AS boundRoleCount,
                   p.resource_path AS resourcePath
              FROM nx_admin_permission p
              LEFT JOIN nx_admin_menu page ON page.id = p.menu_id AND page.is_deleted = 0
              LEFT JOIN nx_admin_menu domain ON domain.id = page.parent_id AND domain.is_deleted = 0
             WHERE p.is_deleted = 0 AND p.status = 1 AND p.resource_type = 'API'
              <if test="keyword != null and keyword != ''">
                AND (p.permission_code LIKE CONCAT('%', #{keyword}, '%')
                  OR p.permission_name LIKE CONCAT('%', #{keyword}, '%'))
              </if>
              <if test="permType != null and permType != ''">
                AND p.perm_type = #{permType}
              </if>
              <if test="domain != null and domain != '' and domain != 'ALL' and domain != 'UNMAPPED'">
                AND domain.menu_code = #{domain}
              </if>
              <if test="domain == 'UNMAPPED'">
                AND domain.id IS NULL
              </if>
             ORDER BY p.permission_code
             LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<PermissionDictionaryView> pagePermissions(@Param("keyword") String keyword,
                                                   @Param("domain") String domain,
                                                   @Param("permType") String permType,
                                                   @Param("limit") int limit,
                                                   @Param("offset") int offset);

    @Select("""
            SELECT p.permission_code AS permissionCode,
                   p.permission_name AS permissionName,
                   p.perm_type AS permType,
                   p.menu_id AS menuId,
                   COALESCE(NULLIF(CONCAT_WS(' / ', domain.menu_name_zh, page.menu_name_zh), ''), '未归类') AS menuCodePath,
                   p.amplifies AS amplifies,
                   (SELECT COUNT(DISTINCT rp.role_id)
                      FROM nx_admin_role_permission rp
                      JOIN nx_admin_role r ON r.id = rp.role_id AND r.status = 1 AND r.is_deleted = 0
                     WHERE rp.permission_id = p.id AND rp.is_deleted = 0) AS boundRoleCount,
                   p.resource_path AS resourcePath
              FROM nx_admin_permission p
              LEFT JOIN nx_admin_menu page ON page.id = p.menu_id AND page.is_deleted = 0
              LEFT JOIN nx_admin_menu domain ON domain.id = page.parent_id AND domain.is_deleted = 0
             WHERE p.permission_code = #{permissionCode}
               AND p.is_deleted = 0
               AND p.status = 1
            """)
    PermissionDictionaryView selectPermissionDetail(@Param("permissionCode") String permissionCode);

    @Select("SELECT COUNT(*) FROM nx_admin_permission WHERE menu_id = #{menuId} AND status = 1 AND is_deleted = 0")
    long countActiveByMenuId(@Param("menuId") Long menuId);
}
