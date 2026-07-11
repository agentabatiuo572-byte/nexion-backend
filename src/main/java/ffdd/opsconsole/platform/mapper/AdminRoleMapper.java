package ffdd.opsconsole.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.platform.infrastructure.AdminRoleEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** A6 角色管理 CRUD mapper。与 OpsOptionsMapper(下拉用 AdminRoleOptionEntity) 同表不同实体，互不交叉。 */
public interface AdminRoleMapper extends BaseMapper<AdminRoleEntity> {

    @Select("SELECT COUNT(*) FROM nx_admin_role WHERE role_code = #{roleCode} AND is_deleted = 0")
    boolean existsByRoleCode(@Param("roleCode") String roleCode);
}
