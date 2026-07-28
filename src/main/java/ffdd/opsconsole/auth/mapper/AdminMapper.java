package ffdd.opsconsole.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.auth.infrastructure.AdminEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface AdminMapper extends BaseMapper<AdminEntity> {
    @Select("SELECT id FROM nx_admin WHERE username=#{username} LIMIT 1")
    Long findIdByUsername(@Param("username") String username);

    @Select("SELECT * FROM nx_admin WHERE id=#{adminId} AND is_deleted=0 FOR UPDATE")
    AdminEntity selectActiveForUpdate(@Param("adminId") Long adminId);

    @Update("UPDATE nx_admin SET updated_at=NOW() WHERE id=#{adminId} AND is_deleted=0")
    int touchVersion(@Param("adminId") Long adminId);
}
