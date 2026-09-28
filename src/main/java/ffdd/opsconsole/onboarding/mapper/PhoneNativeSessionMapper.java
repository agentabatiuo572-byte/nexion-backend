package ffdd.opsconsole.onboarding.mapper;

import org.apache.ibatis.annotations.*;

@Mapper
@SuppressWarnings("MybatisPlusBaseMapper")
public interface PhoneNativeSessionMapper {
    @Select("SELECT id FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0 FOR UPDATE")
    Long lockUser(@Param("userId") long userId);
    @Select("SELECT execution_installation_id FROM nx_phone_binding WHERE user_id=#{userId} "
            + "AND source_environment='PRODUCTION' AND run_id='' FOR UPDATE")
    String executionInstallation(@Param("userId") long userId);
    @Select("""
        SELECT d.device_type deviceType,b.installation_id installationId
          FROM nx_user_device d LEFT JOIN nx_phone_binding b
            ON b.user_id=d.user_id AND b.user_device_id=d.id AND b.source_environment='PRODUCTION' AND b.run_id=''
         WHERE d.user_id=#{userId} AND d.id=#{deviceId} AND d.is_deleted=0
           AND d.source_environment='PRODUCTION' AND d.run_id=''
        """)
    DeviceBinding deviceBinding(@Param("userId") long userId,@Param("deviceId") long deviceId);
    @Select("""
        SELECT user_device_id FROM nx_compute_task WHERE user_id=#{userId} AND task_no=#{taskNo}
          AND is_deleted=0 AND source_environment='PRODUCTION' LIMIT 1
        """)
    Long taskDevice(@Param("userId") long userId,@Param("taskNo") String taskNo);
    record DeviceBinding(String deviceType,String installationId) { }
    @Insert("""
        INSERT INTO nx_phone_native_session(user_id,session_id,installation_id,nonce,payload,challenge_expires_at,verified_until)
        VALUES(#{userId},#{sessionId},#{deviceId},#{nonce},#{payload},#{expiresAt},0)
        ON DUPLICATE KEY UPDATE installation_id=VALUES(installation_id),nonce=VALUES(nonce),payload=VALUES(payload),
          challenge_expires_at=VALUES(challenge_expires_at),verified_until=0
        """)
    int challenge(@Param("userId") long userId,@Param("sessionId") String sessionId,@Param("deviceId") String deviceId,
                  @Param("nonce") String nonce,@Param("payload") String payload,@Param("expiresAt") long expiresAt);
    @Select("""
        SELECT installation_id installationId,nonce,payload,challenge_expires_at challengeExpiresAt,verified_until verifiedUntil
          FROM nx_phone_native_session WHERE user_id=#{userId} AND session_id=#{sessionId} FOR UPDATE
        """)
    Session lock(@Param("userId") long userId,@Param("sessionId") String sessionId);
    @Select("""
        SELECT installation_id installationId,nonce,payload,challenge_expires_at challengeExpiresAt,verified_until verifiedUntil
          FROM nx_phone_native_session WHERE user_id=#{userId} AND session_id=#{sessionId}
        """)
    Session read(@Param("userId") long userId,@Param("sessionId") String sessionId);
    @Select("SELECT key_hash FROM nx_phone_installation_key WHERE user_id=#{userId} AND installation_id=#{deviceId} FOR UPDATE")
    String knownKey(@Param("userId") long userId,@Param("deviceId") String deviceId);
    @Insert("""
        INSERT INTO nx_phone_installation_key(user_id,installation_id,key_hash) VALUES(#{userId},#{deviceId},#{keyHash})
        ON DUPLICATE KEY UPDATE key_hash=key_hash
        """)
    int saveKey(@Param("userId") long userId,@Param("deviceId") String deviceId,@Param("keyHash") String keyHash);
    @Update("""
        UPDATE nx_phone_native_session SET verified_until=#{until},nonce='',payload='',challenge_expires_at=0
        WHERE user_id=#{userId} AND session_id=#{sessionId} AND nonce=#{nonce}
        """)
    int verify(@Param("userId") long userId,@Param("sessionId") String sessionId,@Param("nonce") String nonce,@Param("until") long until);
    record Session(String installationId,String nonce,String payload,long challengeExpiresAt,long verifiedUntil) { }
}
