package ffdd.opsconsole.shared.security.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.shared.security.infrastructure.UserSessionEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.util.List;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public interface AuthSessionMapper extends BaseMapper<UserSessionEntity> {
    record SessionSummary(String sessionGroup,String installationId,Long appSessionVersion,boolean executionSupported,
                          long sessionCount,java.time.LocalDateTime lastActiveAt) { }
    @Select("SELECT session_group sessionGroup,installation_id installationId,app_session_version appSessionVersion,"
            +"execution_supported executionSupported,COUNT(*) sessionCount,MAX(last_active_at) lastActiveAt FROM nx_user_session "
            +"WHERE user_id=#{userId} AND is_deleted=0 AND revoked_at IS NULL AND session_ended_reason IS NULL "
            +"AND expires_at>DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR) "
            +"AND COALESCE(last_active_at,created_at)>DATE_SUB(DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR),INTERVAL #{idleDays} DAY) "
            +"AND (session_group='H5' OR (session_group='APP' AND EXISTS(SELECT 1 FROM nx_app_session_state a "
            +"WHERE a.user_id=nx_user_session.user_id AND a.app_session_version=nx_user_session.app_session_version "
            +"AND a.session_chain_id=nx_user_session.session_chain_id AND a.installation_id=nx_user_session.installation_id "
            +"AND a.execution_supported=nx_user_session.execution_supported))) "
            +"GROUP BY session_group,installation_id,app_session_version,execution_supported ORDER BY session_group,installation_id")
    List<SessionSummary> adminSessionSummary(@Param("userId") long userId,@Param("idleDays") int idleDays);
    @Select("SELECT EXISTS(SELECT 1 FROM nx_user_security WHERE user_id=#{id} AND is_deleted=0 AND password_reset_required=1)")
    boolean passwordResetRequired(long id);
    @Select("SELECT id FROM nx_user WHERE id=#{userId} FOR UPDATE")
    @org.apache.ibatis.annotations.Options(flushCache = org.apache.ibatis.annotations.Options.FlushCachePolicy.TRUE)
    Long lockSecurityAccount(@Param("userId") long userId);

    @Update("UPDATE nx_user_session SET session_ended_reason=COALESCE(session_ended_reason,#{reason}) WHERE user_id=#{userId}")
    int endAllChains(@Param("userId") long userId, @Param("reason") String reason);

    @Select("SELECT COUNT(*) FROM nx_user_session WHERE user_id=#{userId} AND session_group='APP' "
            + "AND session_ended_reason IS NULL AND revoked_at IS NULL AND is_deleted=0 "
            + "AND session_chain_id<>(SELECT session_chain_id FROM nx_user_session WHERE refresh_token_id=#{sessionId} AND user_id=#{userId} LIMIT 1 FOR UPDATE) FOR UPDATE")
    int countAppOutsideChain(@Param("userId") long userId, @Param("sessionId") String sessionId);

    @Update("UPDATE nx_user_session SET session_ended_reason=COALESCE(session_ended_reason,'SECURITY_REVOKED') "
            + "WHERE user_id=#{userId} AND session_chain_id<>(SELECT session_chain_id FROM "
            + "(SELECT session_chain_id FROM nx_user_session WHERE refresh_token_id=#{sessionId} AND user_id=#{userId} LIMIT 1) current_chain)")
    int endOtherChains(@Param("userId") long userId, @Param("sessionId") String sessionId);

    @Select("SELECT id FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0 FOR UPDATE")
    @org.apache.ibatis.annotations.Options(flushCache = org.apache.ibatis.annotations.Options.FlushCachePolicy.TRUE)
    Long lockAccount(@Param("userId") long userId);

    @Select("SELECT * FROM nx_user_session WHERE refresh_token_id=#{sessionId} AND is_deleted=0")
    UserSessionEntity findSession(@Param("sessionId") String sessionId);

    @Select("SELECT * FROM nx_user_session WHERE refresh_token_id=#{sessionId} AND user_id=#{userId} AND is_deleted=0")
    UserSessionEntity findOwnedSession(@Param("sessionId") String sessionId, @Param("userId") long userId);

    @org.apache.ibatis.annotations.Insert("INSERT IGNORE INTO nx_app_session_state(user_id,app_session_version) VALUES(#{userId},0)")
    int initializeAppState(@Param("userId") long userId);

    @Select("SELECT app_session_version FROM nx_app_session_state WHERE user_id=#{userId} FOR UPDATE")
    Long lockAppVersion(@Param("userId") long userId);

    @Select("SELECT installation_id FROM nx_app_session_state WHERE user_id=#{userId} FOR UPDATE")
    String appInstallation(@Param("userId") long userId);

    @Update("""
        UPDATE nx_app_session_state SET app_session_version=#{version},session_chain_id=#{chainId},
          installation_id=#{installationId},execution_supported=#{executionSupported}
        WHERE user_id=#{userId}
        """)
    int advanceAppState(@Param("userId") long userId, @Param("version") long version,
            @Param("chainId") String chainId, @Param("installationId") String installationId,
            @Param("executionSupported") boolean executionSupported);

    @Update("""
        UPDATE nx_user_session SET revoked_at=COALESCE(revoked_at,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR)),
          session_ended_reason='APP_REPLACED',updated_at=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR)
        WHERE user_id=#{userId} AND session_group='APP' AND is_deleted=0
          AND session_ended_reason IS NULL
        """)
    int replaceAppSessions(@Param("userId") long userId);

    @Update("UPDATE nx_user_session SET session_ended_reason=#{reason} WHERE session_chain_id=#{chainId} AND session_ended_reason IS NULL")
    int endChain(@Param("chainId") String chainId, @Param("reason") String reason);

    String FIXED_CONTEXT = "(session_group='H5' OR (session_group='APP' AND EXISTS (SELECT 1 FROM nx_app_session_state a "
            + "WHERE a.user_id=nx_user_session.user_id AND a.app_session_version=nx_user_session.app_session_version "
            + "AND a.session_chain_id=nx_user_session.session_chain_id AND a.installation_id=nx_user_session.installation_id AND a.execution_supported=nx_user_session.execution_supported FOR UPDATE)))";

    @Select("SELECT COUNT(*) FROM nx_user_session WHERE refresh_token_id=#{sessionId} AND user_id=#{userId} "
            + "AND is_deleted=0 AND session_ended_reason IS NULL AND " + FIXED_CONTEXT + " FOR UPDATE")
    int countFixedContext(@Param("sessionId") String sessionId, @Param("userId") long userId);

    @Select("SELECT * FROM nx_user_session WHERE refresh_token_id=#{sessionId} AND user_id=#{userId} "
            + "AND is_deleted=0 AND session_ended_reason IS NULL AND " + FIXED_CONTEXT
            + " AND COALESCE(last_active_at,created_at)>DATE_SUB(DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR),INTERVAL #{idleDays} DAY)"
            + " AND ((revoked_at IS NULL AND expires_at>DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR)) "
            + "OR (rotation_redeemed_at>DATE_SUB(DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR),INTERVAL 10 SECOND) "
            + "AND EXISTS(SELECT 1 FROM nx_user_session live WHERE live.user_id=nx_user_session.user_id "
            + "AND live.session_chain_id=nx_user_session.session_chain_id AND live.revoked_at IS NULL "
            + "AND live.is_deleted=0 AND live.expires_at>DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR) FOR UPDATE))) FOR UPDATE")
    UserSessionEntity findAuthorizedSession(@Param("sessionId") String sessionId, @Param("userId") long userId, @Param("idleDays") int idleDays);

    @Select("SELECT COUNT(*) FROM nx_user_session WHERE user_id=#{userId} AND installation_id=#{installationId} "
            + "AND session_group='APP' AND execution_supported=1 AND revoked_at IS NULL AND is_deleted=0 "
            + "AND expires_at>DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR) "
            + "AND COALESCE(last_active_at,created_at)>DATE_SUB(DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR),INTERVAL #{idleDays} DAY) "
            + "AND session_ended_reason IS NULL AND " + FIXED_CONTEXT + " FOR UPDATE")
    int countExecutionOwner(@Param("userId") long userId, @Param("installationId") String installationId, @Param("idleDays") int idleDays);

    // nx_user_session DATETIME values use UTC+08; SQL session time_zone can differ on a host.
    @Select("""
            SELECT * FROM nx_user_session
             WHERE user_id=#{userId}
               AND session_chain_id=(SELECT session_chain_id FROM nx_user_session
                     WHERE user_id=#{userId} AND refresh_token_id=#{currentSessionId} AND is_deleted=0 LIMIT 1)
               AND revoked_at IS NULL AND expires_at>DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR) AND is_deleted=0
               AND COALESCE(last_active_at,updated_at,created_at)>DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR),INTERVAL #{idleDays} DAY)
             ORDER BY id DESC LIMIT 1
            """)
    UserSessionEntity currentUserSession(@Param("userId") Long userId,
            @Param("currentSessionId") String currentSessionId, @Param("idleDays") int idleDays);

    @Select("""
            SELECT * FROM nx_user_session
             WHERE user_id=#{userId}
               AND session_chain_id<>(SELECT session_chain_id FROM nx_user_session
                     WHERE user_id=#{userId} AND refresh_token_id=#{currentSessionId} AND is_deleted=0 LIMIT 1)
               AND revoked_at IS NULL AND expires_at>DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR) AND is_deleted=0
               AND COALESCE(last_active_at,updated_at,created_at)>DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR),INTERVAL #{idleDays} DAY)
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
               SET rotated_to_id=#{rotatedToId},rotation_redeemed_at=DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR),revoked_at=DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR),updated_at=DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)
             WHERE id=#{id} AND rotation_redeemed_at IS NULL AND revoked_at IS NULL AND is_deleted=0
            """)
    int markRefreshRotated(
            @Param("id") Long id,
            @Param("rotatedToId") String rotatedToId);

    @Update("UPDATE nx_user_session SET revoked_at=COALESCE(revoked_at,DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)),updated_at=DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR) WHERE session_chain_id=#{chainId} AND is_deleted=0")
    int revokeRefreshChain(@Param("chainId") String chainId);
    @Select("""
            SELECT COUNT(1)
              FROM nx_user_session
             WHERE refresh_token_id = #{sessionId}
               AND user_id = #{userId}
               AND revoked_at IS NULL
               AND expires_at > DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)
               AND is_deleted = 0
            """)
    int countActiveUserSession(@Param("sessionId") String sessionId, @Param("userId") Long userId);

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    default int touchActiveUserSession(String sessionId, Long userId, int idleDays) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Session activity requires a transaction");
        }
        if (lockAccount(userId) == null || countFixedContext(sessionId, userId) == 0) return 0;
        if (lockActiveUserSession(sessionId, userId) == 0) return 0;
        // Re-evaluate deadlines in a fresh statement after acquiring the unique-token row lock.
        return touchLockedActiveUserSession(sessionId, userId, idleDays);
    }

    @Update("""
            UPDATE nx_user_session SET updated_at=updated_at
             WHERE refresh_token_id=#{sessionId} AND user_id=#{userId}
               AND revoked_at IS NULL AND is_deleted=0
            """)
    int lockActiveUserSession(@Param("sessionId") String sessionId, @Param("userId") Long userId);

    @Update("""
            UPDATE nx_user_session
               SET last_active_at=DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR),updated_at=DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)
             WHERE refresh_token_id=#{sessionId}
               AND user_id=#{userId}
               AND revoked_at IS NULL
               AND expires_at>DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)
               AND COALESCE(last_active_at,created_at)>DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR),INTERVAL #{idleDays} DAY)
               AND is_deleted=0
            """)
    int touchLockedActiveUserSession(
            @Param("sessionId") String sessionId,
            @Param("userId") Long userId,
            @Param("idleDays") int idleDays);

    // Access requests already in flight when another H5 tab rotates the shared
    // cookie may still carry the previous bearer. The grace is bounded and
    // requires a live successor in the same chain; logout/reuse revokes it.
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    default int touchRecentlyRotatedUserSession(String sessionId, Long userId, int idleDays) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Session grace requires a transaction");
        }
        if (lockAccount(userId) == null || countFixedContext(sessionId, userId) == 0) return 0;
        UserSessionEntity issued = findRecentUserSessionRotation(sessionId, userId);
        if (issued == null) return 0;
        // Only the matching live row is locked. READ_COMMITTED releases nonmatching
        // UPDATE locks when a concurrent refresh retires a candidate.
        for (int attempt = 0; attempt < 3; attempt++) {
            Long liveId = findActiveUserSessionInChain(issued.getSessionChainId(), userId, idleDays);
            if (liveId == null) return 0;
            if (lockRotatedSuccessor(liveId, userId, issued.getSessionChainId()) == 0) continue;
            // UTC_TIMESTAMP is fixed at statement start. Acquire the live lock without
            // touching activity, then check expiry/idle in a fresh statement after waiting.
            int touched = touchRotatedSuccessor(liveId, userId, issued.getSessionChainId(),
                    issued.getRotationRedeemedAt(), idleDays);
            if (touched == 0) return 0; // Never relocate while holding a matched live lock.
            // UPDATE clears MyBatis's local cache. Recheck the issuer after any
            // lock wait, while revocation of the live row cannot commit.
            UserSessionEntity confirmed = findRecentUserSessionRotation(sessionId, userId);
            if (confirmed != null && java.util.Objects.equals(
                    issued.getSessionChainId(), confirmed.getSessionChainId())) return touched;
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return 0;
        }
        if (findRecentUserSessionRotation(sessionId, userId) == null) return 0;
        throw new ConcurrencyFailureException("Session rotation changed during authentication");
    }

    @Select("""
            SELECT * FROM nx_user_session
             WHERE refresh_token_id=#{sessionId} AND user_id=#{userId} AND is_deleted=0
               AND rotation_redeemed_at>DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR),INTERVAL 10 SECOND)
               AND rotated_to_id IS NOT NULL
            """)
    UserSessionEntity findRecentUserSessionRotation(@Param("sessionId") String sessionId, @Param("userId") Long userId);

    @Select("""
            SELECT id FROM nx_user_session
             WHERE session_chain_id=#{chainId} AND user_id=#{userId} AND revoked_at IS NULL AND is_deleted=0
               AND expires_at>DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)
               AND COALESCE(last_active_at,created_at)>DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR),INTERVAL #{idleDays} DAY)
             ORDER BY id DESC LIMIT 1
            """)
    Long findActiveUserSessionInChain(@Param("chainId") String chainId, @Param("userId") Long userId,
            @Param("idleDays") int idleDays);

    @Update("""
            UPDATE nx_user_session SET updated_at=updated_at
             WHERE id=#{id} AND user_id=#{userId} AND session_chain_id=#{chainId}
               AND revoked_at IS NULL AND is_deleted=0
            """)
    int lockRotatedSuccessor(@Param("id") Long id, @Param("userId") Long userId, @Param("chainId") String chainId);

    @Update("""
            UPDATE nx_user_session
               SET last_active_at=DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR),updated_at=DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)
             WHERE id=#{id} AND user_id=#{userId} AND session_chain_id=#{chainId}
               AND #{redeemedAt}>DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR),INTERVAL 10 SECOND)
               AND revoked_at IS NULL AND is_deleted=0
               AND expires_at>DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)
               AND COALESCE(last_active_at,created_at)>DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR),INTERVAL #{idleDays} DAY)
            """)
    int touchRotatedSuccessor(@Param("id") Long id, @Param("userId") Long userId, @Param("chainId") String chainId,
            @Param("redeemedAt") java.time.LocalDateTime redeemedAt, @Param("idleDays") int idleDays);

    @Select("""
            SELECT *
              FROM nx_user_session
             WHERE user_id=#{userId}
               AND revoked_at IS NULL
               AND expires_at>DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)
               AND COALESCE(last_active_at,updated_at,created_at)>DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR),INTERVAL #{idleDays} DAY)
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
               SET live.revoked_at=DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR),live.updated_at=DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)
             WHERE target.user_id=#{userId} AND target.refresh_token_id=#{sessionId}
               AND target.is_deleted=0
               AND live.revoked_at IS NULL AND live.expires_at>DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR) AND live.is_deleted=0
            """)
    int revokeOwnedUserSession(@Param("userId") Long userId, @Param("sessionId") String sessionId);

    @Update("""
            UPDATE nx_user_session
               SET revoked_at=DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR),updated_at=DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)
             WHERE user_id=#{userId}
               AND session_chain_id<>(SELECT session_chain_id FROM (
                     SELECT session_chain_id FROM nx_user_session
                      WHERE user_id=#{userId} AND refresh_token_id=#{currentSessionId} AND is_deleted=0 LIMIT 1
                   ) current_chain)
               AND revoked_at IS NULL AND expires_at>DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR) AND is_deleted=0
            """)
    int revokeOtherUserSessions(
            @Param("userId") Long userId,
            @Param("currentSessionId") String currentSessionId);

    @Update("""
            UPDATE nx_user_session
               SET revoked_at=COALESCE(revoked_at,DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)),updated_at=DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)
             WHERE user_id=#{userId} AND is_deleted=0
            """)
    int revokeAllUserSessions(@Param("userId") Long userId);
}
