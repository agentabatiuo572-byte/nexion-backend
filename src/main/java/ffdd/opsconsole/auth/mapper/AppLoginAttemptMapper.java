package ffdd.opsconsole.auth.mapper;

import org.apache.ibatis.annotations.*;

@Mapper
@SuppressWarnings("MybatisPlusBaseMapper")
public interface AppLoginAttemptMapper {
    @Select("SELECT attempt_id,user_id,context_hash,recovery_hash,session_id FROM nx_app_login_attempt WHERE attempt_id=#{attemptId}")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    Attempt find(@Param("attemptId") String attemptId);

    @Select("SELECT attempt_id,user_id,context_hash,recovery_hash,session_id FROM nx_app_login_attempt WHERE attempt_id=#{attemptId} FOR UPDATE")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    Attempt findLocked(@Param("attemptId") String attemptId);

    @Insert("""
        INSERT INTO nx_app_login_attempt(attempt_id,user_id,context_hash,recovery_hash,session_id,created_at)
        VALUES(#{attemptId},#{userId},#{contextHash},#{recoveryHash},#{sessionId},UTC_TIMESTAMP(3))
        """)
    int insert(Attempt attempt);

    record Attempt(String attemptId, Long userId, String contextHash, String recoveryHash, String sessionId) { }
}
