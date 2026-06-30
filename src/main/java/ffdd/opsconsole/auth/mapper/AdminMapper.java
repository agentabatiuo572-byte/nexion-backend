package ffdd.opsconsole.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.auth.infrastructure.AdminEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AdminMapper extends BaseMapper<AdminEntity> {
    @Select("""
            SELECT config_value
            FROM nx_config_item
            WHERE config_key = CONCAT('a1.account.', #{adminId}, '.role')
              AND status = 1
              AND is_deleted = 0
            LIMIT 1
            """)
    String selectA1Role(@Param("adminId") Long adminId);
}
