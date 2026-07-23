package ffdd.opsconsole.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.auth.infrastructure.AppUserSecurityEntity;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface AppUserSecurityMapper extends BaseMapper<AppUserSecurityEntity> {
    @Select("SELECT password_hash FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0 FOR UPDATE")
    String passwordHashForUpdate(@Param("userId") Long userId);

    @Select("SELECT COALESCE((SELECT two_factor_enabled FROM nx_user_security WHERE user_id=#{userId} AND is_deleted=0 LIMIT 1),0)=1")
    boolean twoFactorEnabled(@Param("userId") Long userId);

    @Select("SELECT password_changed_at FROM nx_user_security WHERE user_id=#{userId} AND is_deleted=0 LIMIT 1")
    LocalDateTime passwordChangedAt(@Param("userId") Long userId);

    @Update("UPDATE nx_user SET password_hash=#{passwordHash},updated_at=NOW() WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0")
    int updatePasswordHash(@Param("userId") Long userId, @Param("passwordHash") String passwordHash);

    @Insert("""
            INSERT INTO nx_user_security (
                user_id,two_factor_enabled,login_fail_count,password_reset_required,password_changed_at,
                created_at,updated_at,is_deleted
            ) VALUES (#{userId},0,0,0,NOW(),NOW(),NOW(),0)
            ON DUPLICATE KEY UPDATE password_changed_at=NOW(),password_reset_required=0,updated_at=NOW(),is_deleted=0
            """)
    int markPasswordChanged(@Param("userId") Long userId);

    @Insert("""
            INSERT INTO nx_user_security (
                user_id,two_factor_enabled,login_fail_count,password_reset_required,
                created_at,updated_at,is_deleted
            ) VALUES (#{userId},#{enabled},0,0,NOW(),NOW(),0)
            ON DUPLICATE KEY UPDATE two_factor_enabled=#{enabled},updated_at=NOW(),is_deleted=0
            """)
    int upsertTwoFactor(@Param("userId") Long userId, @Param("enabled") boolean enabled);
}
