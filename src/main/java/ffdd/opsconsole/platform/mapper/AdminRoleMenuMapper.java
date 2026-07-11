package ffdd.opsconsole.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.platform.infrastructure.AdminRoleMenuEntity;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 角色↔菜单 绑定 mapper（A6 菜单授权）。仿 AdminRolePermissionMapper 白名单同步风格。 */
public interface AdminRoleMenuMapper extends BaseMapper<AdminRoleMenuEntity> {

    @Select("SELECT menu_id FROM nx_admin_role_menu WHERE role_id = #{roleId} AND is_deleted = 0")
    List<Long> selectActiveMenuIdsByRole(Long roleId);

    @Update("""
            <script>
            UPDATE nx_admin_role_menu
               SET is_deleted = 0, updated_at = NOW()
             WHERE role_id = #{roleId}
               AND is_deleted = 1
               AND menu_id IN
               <foreach collection="menuIds" item="mid" open="(" separator="," close=")">
                 #{mid}
               </foreach>
            </script>
            """)
    int restoreRoleMenus(@Param("roleId") Long roleId, @Param("menuIds") Collection<Long> menuIds);

    /** 批量补插缺失绑定（NOT EXISTS 防重）。调用方须保证 menuIds 非空。 */
    @org.apache.ibatis.annotations.Insert("""
            <script>
            INSERT INTO nx_admin_role_menu (role_id, menu_id, is_deleted)
            SELECT #{roleId}, v.menu_id, 0
              FROM (
                <foreach collection="menuIds" item="mid" separator="UNION ALL">
                  SELECT #{mid} AS menu_id
                </foreach>
              ) v
             WHERE NOT EXISTS (
                   SELECT 1 FROM nx_admin_role_menu rm
                    WHERE rm.role_id = #{roleId}
                      AND rm.menu_id = v.menu_id
             )
            </script>
            """)
    int insertMissingRoleMenus(@Param("roleId") Long roleId, @Param("menuIds") Collection<Long> menuIds);

    /** 白名单外软删。调用方须保证 allowedMenuIds 非空（空则用 disableAllRoleMenus）。 */
    @Update("""
            <script>
            UPDATE nx_admin_role_menu
               SET is_deleted = 1, updated_at = NOW()
             WHERE role_id = #{roleId}
               AND is_deleted = 0
               AND menu_id NOT IN
               <foreach collection="allowedMenuIds" item="mid" open="(" separator="," close=")">
                 #{mid}
               </foreach>
            </script>
            """)
    int disableRoleMenusExcept(@Param("roleId") Long roleId, @Param("allowedMenuIds") Collection<Long> allowedMenuIds);

    @Update("UPDATE nx_admin_role_menu SET is_deleted = 1, updated_at = NOW() WHERE role_id = #{roleId} AND is_deleted = 0")
    int disableAllRoleMenus(Long roleId);

    /** 菜单删除时级联软删所有引用该菜单的绑定。 */
    @Update("UPDATE nx_admin_role_menu SET is_deleted = 1, updated_at = NOW() WHERE menu_id = #{menuId} AND is_deleted = 0")
    int softDeleteByMenuId(Long menuId);
}
