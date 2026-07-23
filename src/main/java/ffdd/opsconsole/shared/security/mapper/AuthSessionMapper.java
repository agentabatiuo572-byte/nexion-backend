package ffdd.opsconsole.shared.security.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.shared.security.infrastructure.UserSessionEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.util.List;

public interface AuthSessionMapper extends BaseMapper<UserSessionEntity> {
    @Select("SELECT * FROM nx_user_session WHERE refresh_token_id=#{refreshTokenId} AND is_deleted=0 LIMIT 1 FOR UPDATE")
    UserSessionEntity findRefreshForUpdate(@Param("refreshTokenId") String refreshTokenId);

    @Update("""
            UPDATE nx_user_session
               SET rotated_to_id=#{rotatedToId},rotation_redeemed_at=NOW(),revoked_at=NOW(),updated_at=NOW()
             WHERE id=#{id} AND rotation_redeemed_at IS NULL AND revoked_at IS NULL AND is_deleted=0
            """)
    int markRefreshRotated(
            @Param("id") Long id,
            @Param("rotatedToId") String rotatedToId);

    @Update("UPDATE nx_user_session SET revoked_at=COALESCE(revoked_at,NOW()),updated_at=NOW() WHERE session_chain_id=#{chainId} AND is_deleted=0")
    int revokeRefreshChain(@Param("chainId") String chainId);
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

    @Update("""
            UPDATE nx_user_session
               SET last_active_at=NOW(),updated_at=NOW()
             WHERE refresh_token_id=#{sessionId}
               AND user_id=#{userId}
               AND revoked_at IS NULL
               AND expires_at>NOW()
               AND COALESCE(last_active_at,created_at)>DATE_SUB(NOW(),INTERVAL #{idleDays} DAY)
               AND is_deleted=0
            """)
    int touchActiveUserSession(
            @Param("sessionId") String sessionId,
            @Param("userId") Long userId,
            @Param("idleDays") int idleDays);

    @Select("""
            SELECT *
              FROM nx_user_session
             WHERE user_id=#{userId}
               AND revoked_at IS NULL
               AND expires_at>NOW()
               AND COALESCE(last_active_at,updated_at,created_at)>DATE_SUB(NOW(),INTERVAL #{idleDays} DAY)
               AND is_deleted=0
             ORDER BY COALESCE(last_active_at,updated_at,created_at) DESC,id DESC
            """)
    List<UserSessionEntity> listActiveUserSessions(
            @Param("userId") Long userId,
            @Param("idleDays") int idleDays);

    @Update("""
            UPDATE nx_user_session
               SET revoked_at=NOW(),updated_at=NOW()
             WHERE user_id=#{userId}
               AND refresh_token_id=#{sessionId}
               AND revoked_at IS NULL
               AND expires_at>NOW()
               AND is_deleted=0
            """)
    int revokeOwnedUserSession(@Param("userId") Long userId, @Param("sessionId") String sessionId);

    @Update("""
            UPDATE nx_user_session
               SET revoked_at=NOW(),updated_at=NOW()
             WHERE user_id=#{userId}
               AND refresh_token_id<>#{currentSessionId}
               AND revoked_at IS NULL
               AND expires_at>NOW()
               AND is_deleted=0
            """)
    int revokeOtherUserSessions(
            @Param("userId") Long userId,
            @Param("currentSessionId") String currentSessionId);
}
