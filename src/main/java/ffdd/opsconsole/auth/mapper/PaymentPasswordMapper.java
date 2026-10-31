package ffdd.opsconsole.auth.mapper;

import java.time.Instant;
import java.util.Map;
import org.apache.ibatis.annotations.*;

@SuppressWarnings("MybatisPlusBaseMapper")
public interface PaymentPasswordMapper {
    @Select("<script>SELECT * FROM nx_payment_password WHERE user_id=#{userId}<if test='lock'> FOR UPDATE</if></script>")
    Map<String,Object> credential(@Param("userId") long userId,@Param("lock") boolean lock);
    @Insert("INSERT IGNORE INTO nx_payment_password(user_id) VALUES(#{userId})")
    int ensure(long userId);
    @Select("<script>SELECT id,country_code,phone FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0<if test='lock'> FOR UPDATE</if></script>")
    Map<String,Object> user(@Param("userId") long userId,@Param("lock") boolean lock);
    @Select("SELECT COUNT(*) FROM nx_payment_password_attempt WHERE user_id=#{userId} AND counts_failure=1 AND result_message='PAYMENT_PASSWORD_INCORRECT' AND occurred_at>=#{since}")
    int failureCount(@Param("userId") long userId,@Param("since") long since);
    @Update("UPDATE nx_payment_password SET password_hash=#{passwordHash},pepper_version=#{pepperVersion},credential_version=credential_version+1,changed_at=#{now},failure_reset_at=#{now},locked_until=NULL,recovery_phone_hash=#{phoneHash} WHERE user_id=#{userId}")
    int writePassword(@Param("userId") long userId,@Param("passwordHash") String passwordHash,@Param("pepperVersion") String pepperVersion,@Param("now") long now,@Param("phoneHash") String phoneHash);
    @Insert("INSERT IGNORE INTO nx_payment_command(user_id,kind,command_key,request_hash,created_at,updated_at) VALUES(#{userId},#{kind},#{key},#{digest},#{now},#{now})")
    int insertCommand(@Param("userId") long userId,@Param("kind") String kind,@Param("key") String key,@Param("digest") String digest,@Param("now") long now);
    @Select("SELECT request_hash,status,response_json FROM nx_admin_idempotency_record WHERE scope=#{scope} AND idempotency_key=#{key} AND is_deleted=0 ORDER BY id DESC LIMIT 1")
    Map<String,Object> legacy(@Param("scope") String scope,@Param("key") String key);
    @Update("UPDATE nx_payment_command SET status=#{status},response_json=#{response} WHERE user_id=#{userId} AND kind=#{kind} AND command_key=#{key} AND response_json IS NULL")
    int importCommand(@Param("userId") long userId,@Param("kind") String kind,@Param("key") String key,@Param("status") String status,@Param("response") String response);
    @Select("<script>SELECT * FROM nx_payment_command WHERE user_id=#{userId} AND kind=#{kind} AND command_key=#{key} FOR UPDATE<if test='nowait'> NOWAIT</if></script>")
    Map<String,Object> command(@Param("userId") long userId,@Param("kind") String kind,@Param("key") String key,@Param("nowait") boolean nowait);
    @Update("UPDATE nx_payment_command SET status=#{status},response_json=#{response},updated_at=#{now} WHERE user_id=#{userId} AND kind=#{kind} AND command_key=#{key}")
    int finishCommand(@Param("userId") long userId,@Param("kind") String kind,@Param("key") String key,@Param("status") String status,@Param("response") String response,@Param("now") long now);
    @Select("SELECT * FROM nx_payment_password_attempt WHERE user_id=#{userId} AND attempt_no=#{attempt}")
    Map<String,Object> attempt(@Param("userId") long userId,@Param("attempt") String attempt);
    @Update("UPDATE nx_payment_password SET failure_reset_at=#{now},locked_until=NULL WHERE user_id=#{userId}")
    int clearFailures(@Param("userId") long userId,@Param("now") long now);
    @Update("UPDATE nx_payment_password_attempt SET counts_failure=0 WHERE user_id=#{userId} AND counts_failure=1")
    int clearAttemptFailures(long userId);
    @Insert("INSERT INTO nx_payment_password_attempt(user_id,attempt_no,binding_hash,result_code,result_message,state_json,occurred_at,purpose) VALUES(#{userId},#{attempt},#{binding},#{code},#{message},'{}',#{now},#{purpose})")
    int insertAttempt(@Param("userId") long userId,@Param("attempt") String attempt,@Param("binding") String binding,@Param("code") int code,@Param("message") String message,@Param("now") long now,@Param("purpose") String purpose);
    @Update("UPDATE nx_payment_password SET locked_until=#{until} WHERE user_id=#{userId}")
    int lockCredential(@Param("userId") long userId,@Param("until") long until);
    @Update("UPDATE nx_payment_password_attempt SET result_code=#{code},state_json=#{state} WHERE user_id=#{userId} AND attempt_no=#{attempt}")
    int finishAttempt(@Param("userId") long userId,@Param("attempt") String attempt,@Param("code") int code,@Param("state") String state);
    @Update("UPDATE nx_payment_password_attempt SET result_message='BUSINESS_REJECTED' WHERE user_id=#{userId} AND attempt_no=#{attempt} AND result_code=0")
    int rejectBusiness(@Param("userId") long userId,@Param("attempt") String attempt);
    @Select("SELECT kind FROM nx_payment_command WHERE user_id=#{userId} AND command_key=#{key} AND kind IN ('setup','change','reset') ORDER BY created_at DESC LIMIT 1")
    Map<String,Object> securityCommand(@Param("userId") long userId,@Param("key") String key);
    @Insert("INSERT INTO nx_verified_phone(user_id,phone_hash,verified_at) VALUES(#{userId},#{phoneHash},#{now}) ON DUPLICATE KEY UPDATE phone_hash=VALUES(phone_hash),verified_at=VALUES(verified_at)")
    int recordPhone(@Param("userId") long userId,@Param("phoneHash") String phoneHash,@Param("now") long now);
    @Update("UPDATE nx_payment_password SET recovery_phone_hash=#{phoneHash} WHERE user_id=#{userId} AND recovery_phone_hash IS NOT NULL")
    int transferPhone(@Param("userId") long userId,@Param("phoneHash") String phoneHash);
    @Select("SELECT phone_hash FROM nx_verified_phone WHERE user_id=#{userId}")
    Map<String,Object> verifiedPhone(long userId);
    @Select("SELECT COUNT(*) FROM nx_payment_password_reset WHERE user_id=#{userId} AND created_at>#{since}")
    int countResetSince(@Param("userId") long userId,@Param("since") long since);
    @Update("UPDATE nx_payment_password_reset SET consumed_at=#{now} WHERE user_id=#{userId} AND consumed_at IS NULL")
    int expireResets(@Param("userId") long userId,@Param("now") long now);
    @Insert("INSERT INTO nx_payment_password_reset(challenge_no,user_id,session_hash,phone_hash,credential_version,code_hash,expires_at,created_at) VALUES(#{challenge},#{userId},#{sessionHash},#{phoneHash},#{version},#{codeHash},#{expires},#{now})")
    int insertReset(@Param("challenge") String challenge,@Param("userId") long userId,@Param("sessionHash") String sessionHash,@Param("phoneHash") String phoneHash,@Param("version") long version,@Param("codeHash") String codeHash,@Param("expires") long expires,@Param("now") long now);
    @Select("SELECT * FROM nx_payment_password_reset WHERE challenge_no=#{challenge} AND user_id=#{userId} FOR UPDATE")
    Map<String,Object> resetChallenge(@Param("userId") long userId,@Param("challenge") String challenge);
    @Update("UPDATE nx_payment_password_reset SET attempt_count=attempt_count+1 WHERE challenge_no=#{challenge}")
    int incrementReset(String challenge);
    @Update("UPDATE nx_payment_password_reset SET verified_at=#{now},proof_hash=#{proofHash},proof_expires_at=#{expires} WHERE challenge_no=#{challenge}")
    int verifyReset(@Param("challenge") String challenge,@Param("now") long now,@Param("proofHash") String proofHash,@Param("expires") long expires);
    @Select("SELECT * FROM nx_payment_password_reset WHERE user_id=#{userId} AND proof_hash=#{proofHash} FOR UPDATE")
    Map<String,Object> resetProof(@Param("userId") long userId,@Param("proofHash") String proofHash);
    @Update("UPDATE nx_payment_password_reset SET consumed_at=#{now} WHERE challenge_no=#{challenge}")
    int consumeReset(@Param("challenge") String challenge,@Param("now") long now);
    @Select("<script>SELECT * FROM nx_payment_password_policy WHERE id=1<if test='lock'> FOR UPDATE</if></script>")
    Map<String,Object> policy(@Param("lock") boolean lock);
    @Insert("INSERT INTO nx_payment_security_record(record_no,user_id,reason,started_at,locked_until,failed_attempts,window_minutes) VALUES(#{record},#{userId},'支付密码连续错误达到限制',#{now},#{until},#{failures},#{window})")
    int insertSecurityRecord(@Param("record") String record,@Param("userId") long userId,@Param("now") long now,@Param("until") long until,@Param("failures") int failures,@Param("window") int window);
    @Update("UPDATE nx_payment_security_record SET state=CASE WHEN locked_until IS NOT NULL AND locked_until<=#{now} THEN 'EXPIRED' ELSE 'RELEASED' END,version=version+1 WHERE user_id=#{userId} AND state='ACTIVE' AND type='PAYMENT_PASSWORD_LOCK'")
    int closeSecurityRecords(@Param("userId") long userId,@Param("now") long now);
    @Insert("INSERT INTO nx_payment_security_audit(audit_no,record_no,user_id,occurred_at,operator,action,reason) SELECT CONCAT('PINAUDIT-',REPLACE(UUID(),'-','')),record_no,user_id,#{now},CONCAT('user:',user_id),'PAYMENT_PASSWORD_CREDENTIAL_CHANGED','用户成功修改或重置支付密码，原钱包支付锁定结束' FROM nx_payment_security_record WHERE user_id=#{userId} AND state='ACTIVE' AND type='PAYMENT_PASSWORD_LOCK' AND locked_until>#{now}")
    int credentialUnlockAudit(@Param("userId") long userId,@Param("now") long now);
    static Instant instant(Object value) { return value==null ? null : Instant.ofEpochMilli(((Number)value).longValue()); }
    static long number(Map<String,Object> row,String field) { return ((Number)row.get(field)).longValue(); }
}
