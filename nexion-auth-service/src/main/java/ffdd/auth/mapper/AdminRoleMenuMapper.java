package ffdd.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.auth.domain.AdminRoleMenu;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface AdminRoleMenuMapper extends BaseMapper<AdminRoleMenu> {
    @Select("SELECT menu_id FROM admin_role_menu WHERE role_id = #{roleId} AND is_deleted = 0")
    List<Long> selectActiveMenuIdsByRoleId(@Param("roleId") Long roleId);

    @Select("""
            <script>
            SELECT DISTINCT rm.menu_id
            FROM admin_role_menu rm
            WHERE rm.role_id IN
            <foreach collection="roleIds" item="roleId" open="(" separator="," close=")">
              #{roleId}
            </foreach>
              AND rm.is_deleted = 0
            </script>
            """)
    List<Long> selectActiveMenuIdsByRoleIds(@Param("roleIds") List<Long> roleIds);

    @Select("SELECT id, role_id, menu_id, created_at, updated_at, is_deleted FROM admin_role_menu WHERE role_id = #{roleId}")
    List<AdminRoleMenu> selectAllByRoleId(@Param("roleId") Long roleId);

    @Select("SELECT COUNT(1) FROM admin_role_menu WHERE role_id = #{roleId} AND is_deleted = 0")
    long countActiveByRoleId(@Param("roleId") Long roleId);

    @Select("SELECT COUNT(1) FROM admin_role_menu WHERE menu_id = #{menuId} AND is_deleted = 0")
    long countActiveByMenuId(@Param("menuId") Long menuId);

    @Select("""
            SELECT r.role_name
            FROM admin_role_menu rm
            JOIN admin_role r ON r.id = rm.role_id
            WHERE rm.menu_id = #{menuId}
              AND rm.is_deleted = 0
              AND r.is_deleted = 0
            ORDER BY r.id
            """)
    List<String> selectActiveRoleNamesByMenuId(@Param("menuId") Long menuId);

    @Select("""
            SELECT m.menu_name
            FROM admin_role_menu rm
            JOIN admin_menu m ON m.id = rm.menu_id
            WHERE rm.role_id = #{roleId}
              AND rm.is_deleted = 0
              AND m.is_deleted = 0
            ORDER BY m.sort_order, m.id
            """)
    List<String> selectActiveMenuNamesByRoleId(@Param("roleId") Long roleId);

    @Update("UPDATE admin_role_menu SET is_deleted = #{isDeleted}, updated_at = NOW() WHERE id = #{id}")
    int updateDeletedById(@Param("id") Long id, @Param("isDeleted") Integer isDeleted);

    @Insert("""
            INSERT INTO admin_role_menu (role_id, menu_id, created_at, updated_at, is_deleted)
            VALUES (#{roleId}, #{menuId}, NOW(), NOW(), 0)
            ON DUPLICATE KEY UPDATE is_deleted = 0, updated_at = NOW()
            """)
    int upsertActive(@Param("roleId") Long roleId, @Param("menuId") Long menuId);
}
