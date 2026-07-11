package ffdd.opsconsole.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.platform.infrastructure.AdminMenuEntity;
import java.util.List;
import org.apache.ibatis.annotations.Select;

/** A7 菜单管理 mapper。整树一次性查出，service 层内存构树（86 节点量级）。 */
public interface AdminMenuMapper extends BaseMapper<AdminMenuEntity> {

    @Select("SELECT * FROM nx_admin_menu WHERE is_deleted = 0 ORDER BY parent_id IS NULL DESC, parent_id, sort_order, id")
    List<AdminMenuEntity> selectAllActive();

    @Select("SELECT * FROM nx_admin_menu WHERE parent_id = #{parentId} AND is_deleted = 0")
    List<AdminMenuEntity> selectChildren(Long parentId);
}
