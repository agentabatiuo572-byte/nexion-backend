package ffdd.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.auth.domain.AdminMenu;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AdminMenuMapper extends BaseMapper<AdminMenu> {
    @Select("SELECT COUNT(1) FROM admin_menu WHERE parent_id = #{parentId} AND is_deleted = 0")
    long countActiveChildrenByParentId(@Param("parentId") Long parentId);

    @Select("""
            SELECT menu_name
            FROM admin_menu
            WHERE parent_id = #{parentId}
              AND is_deleted = 0
            ORDER BY sort_order, id
            """)
    java.util.List<String> selectActiveChildNamesByParentId(@Param("parentId") Long parentId);
}
