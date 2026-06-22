package ffdd.opsconsole.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.auth.infrastructure.AdminRolePermissionEntity;
import java.util.List;
import org.apache.ibatis.annotations.Select;

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
}
