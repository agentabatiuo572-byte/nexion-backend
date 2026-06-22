package ffdd.opsconsole.shared.security.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.shared.security.infrastructure.UserSessionEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AuthSessionMapper extends BaseMapper<UserSessionEntity> {
    @Select("""
            SELECT COUNT(1)
              FROM nx_user_session
             WHERE refresh_token_id = #{sessionId}
               AND user_id = #{userId}
               AND revoked_at IS NULL
               AND expires_at > NOW()
               AND is_deleted = 0
            """)
    int countActiveUserSession(@Param("sessionId") String sessionId, @Param("userId") Long userId);
}
