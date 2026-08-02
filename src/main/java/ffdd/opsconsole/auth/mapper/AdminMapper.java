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

    @Update("UPDATE nx_admin SET version=version+1, updated_at=NOW() WHERE id=#{adminId} AND is_deleted=0")
    int touchVersion(@Param("adminId") Long adminId);

    @Update("""
            UPDATE nx_admin
               SET status=#{status}, version=version+1, updated_at=NOW()
             WHERE id=#{adminId} AND version=#{expectedVersion} AND is_deleted=0
            """)
    int updateStatusIfVersion(
            @Param("adminId") Long adminId,
            @Param("expectedVersion") long expectedVersion,
            @Param("status") int status);

    @Update("""
            UPDATE nx_admin
               SET username=#{username}, nickname=#{nickname}, email=#{email}, version=version+1, updated_at=NOW()
             WHERE id=#{adminId} AND version=#{expectedVersion} AND is_deleted=0
            """)
    int updateProfileIfVersion(
            @Param("adminId") Long adminId,
            @Param("expectedVersion") long expectedVersion,
            @Param("username") String username,
            @Param("nickname") String nickname,
            @Param("email") String email);

    @Update("""
            UPDATE nx_admin
               SET super_admin=#{superAdmin}, version=version+1, updated_at=NOW()
             WHERE id=#{adminId} AND version=#{expectedVersion} AND is_deleted=0
            """)
    int updateRoleIfVersion(
            @Param("adminId") Long adminId,
            @Param("expectedVersion") long expectedVersion,
            @Param("superAdmin") int superAdmin);

    @Update("""
            UPDATE nx_admin
               SET password_hash=#{passwordHash}, version=version+1, updated_at=NOW()
             WHERE id=#{adminId} AND version=#{expectedVersion} AND is_deleted=0
            """)
    int updatePasswordIfVersion(
            @Param("adminId") Long adminId,
            @Param("expectedVersion") long expectedVersion,
            @Param("passwordHash") String passwordHash);

    @Update("""
            UPDATE nx_admin
               SET version=version+1, updated_at=NOW()
             WHERE id=#{adminId} AND version=#{expectedVersion} AND is_deleted=0
            """)
    int incrementVersionIfVersion(@Param("adminId") Long adminId, @Param("expectedVersion") long expectedVersion);
}
