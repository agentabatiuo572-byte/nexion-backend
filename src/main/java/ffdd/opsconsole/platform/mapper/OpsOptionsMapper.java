package ffdd.opsconsole.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.platform.infrastructure.AdminRoleOptionEntity;
import java.util.List;
import org.apache.ibatis.annotations.Select;

public interface OpsOptionsMapper extends BaseMapper<AdminRoleOptionEntity> {
    @Select("""
            SELECT COALESCE(NULLIF(dc_location, ''), 'UNASSIGNED') AS dcLocation
              FROM nx_user_device
             WHERE is_deleted = 0
             GROUP BY COALESCE(NULLIF(dc_location, ''), 'UNASSIGNED')
             ORDER BY dcLocation
             LIMIT 100
            """)
    List<String> datacenters();
}
