package ffdd.opsconsole.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.auth.infrastructure.AdminEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AdminMapper extends BaseMapper<AdminEntity> {
    @Select("SELECT id FROM nx_admin WHERE username=#{username} LIMIT 1")
    Long findIdByUsername(@Param("username") String username);
}
