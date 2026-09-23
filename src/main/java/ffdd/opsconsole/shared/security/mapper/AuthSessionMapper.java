package ffdd.opsconsole.shared.security.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.shared.security.infrastructure.UserSessionEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.util.List;

public interface AuthSessionMapper extends BaseMapper<UserSessionEntity> {
    @Select("""
            SELECT * FROM nx_user_session
             WHERE user_id=#{userId}
               AND session_chain_id=(SELECT session_chain_id FROM nx_user_session
                     WHERE user_id=#{userId} AND refresh_token_id=#{currentSessionId} AND is_deleted=0 LIMIT 1)
               AND revoked_at IS NULL AND expires_at>NOW() AND is_deleted=0
               AND COALESCE(last_active_at,updated_at,created_at)>DATE_SUB(NOW(),INTERVAL #{idleDays} DAY)
             ORDER BY id DESC LIMIT 1
            """)
    UserSessionEntity currentUserSession(@Param("userId") Long userId,
            @Param("currentSessionId") String currentSessionId, @Param("idleDays") int idleDays);

    @Select("""
            SELECT * FROM nx_user_session
             WHERE user_id=#{userId}
               AND session_chain_id<>(SELECT session_chain_id FROM nx_user_session
                     WHERE user_id=#{userId} AND refresh_token_id=#{currentSessionId} AND is_deleted=0 LIMIT 1)
               AND revoked_at IS NULL AND expires_at>NOW() AND is_deleted=0
               AND COALESCE(last_active_at,updated_at,created_at)>DATE_SUB(NOW(),INTERVAL #{idleDays} DAY)
               AND (#{beforeId} IS NULL OR id < #{beforeId})
             ORDER BY id DESC LIMIT 21
            """)
    List<UserSessionEntity> pageOtherUserSessions(@Param("userId") Long userId,
            @Param("currentSessionId") String currentSessionId, @Param("idleDays") int idleDays,
            @Param("beforeId") Long beforeId);

    @Select("""
            SELECT COUNT(1) FROM nx_user_session current_session
              JOIN nx_user_session target_session
                ON target_session.user_id=current_session.user_id
               AND target_session.session_chain_id=current_session.session_chain_id
             WHERE current_session.user_id=#{userId}
               AND current_session.refresh_token_id=#{currentSessionId}
               AND target_session.refresh_token_id=#{targetSessionId}
               AND current_session.is_deleted=0 AND target_session.is_deleted=0
            """)
    int countSameUserSessionChain(@Param("userId") Long userId,
            @Param("currentSessionId") String currentSessionId,
            @Param("targetSessionId") String targetSessionId);

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

    // Access requests already in flight when another H5 tab rotates the shared
    // cookie may still carry the previous bearer. The grace is bounded and
    // requires a live successor in the same chain; logout/reuse revokes it.
    @Update("""
            UPDATE nx_user_session live
              JOIN nx_user_session issued
                ON issued.session_chain_id=live.session_chain_id
               AND issued.user_id=live.user_id
               SET live.last_active_at=NOW(),live.updated_at=NOW()
             WHERE issued.refresh_token_id=#{sessionId}
               AND issued.user_id=#{userId}
               AND issued.rotation_redeemed_at>DATE_SUB(NOW(),INTERVAL 10 SECOND)
               AND issued.rotated_to_id IS NOT NULL
               AND issued.is_deleted=0
               AND live.revoked_at IS NULL
               AND live.expires_at>NOW()
               AND COALESCE(live.last_active_at,live.created_at)>DATE_SUB(NOW(),INTERVAL #{idleDays} DAY)
               AND live.is_deleted=0
            """)
    int touchRecentlyRotatedUserSession(
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
            UPDATE nx_user_session live
              JOIN nx_user_session target
                ON target.session_chain_id=live.session_chain_id AND target.user_id=live.user_id
               SET live.revoked_at=NOW(),live.updated_at=NOW()
             WHERE target.user_id=#{userId} AND target.refresh_token_id=#{sessionId}
               AND target.is_deleted=0
               AND live.revoked_at IS NULL AND live.expires_at>NOW() AND live.is_deleted=0
            """)
    int revokeOwnedUserSession(@Param("userId") Long userId, @Param("sessionId") String sessionId);

    @Update("""
            UPDATE nx_user_session
               SET revoked_at=NOW(),updated_at=NOW()
             WHERE user_id=#{userId}
               AND session_chain_id<>(SELECT session_chain_id FROM (
                     SELECT session_chain_id FROM nx_user_session
                      WHERE user_id=#{userId} AND refresh_token_id=#{currentSessionId} AND is_deleted=0 LIMIT 1
                   ) current_chain)
               AND revoked_at IS NULL AND expires_at>NOW() AND is_deleted=0
            """)
    int revokeOtherUserSessions(
            @Param("userId") Long userId,
            @Param("currentSessionId") String currentSessionId);

    @Update("""
            UPDATE nx_user_session
               SET revoked_at=COALESCE(revoked_at,NOW()),updated_at=NOW()
             WHERE user_id=#{userId} AND is_deleted=0
            """)
    int revokeAllUserSessions(@Param("userId") Long userId);
}
