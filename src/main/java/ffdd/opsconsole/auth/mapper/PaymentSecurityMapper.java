package ffdd.opsconsole.auth.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

@SuppressWarnings("MybatisPlusBaseMapper")
public interface PaymentSecurityMapper {
    String FILTER=" FROM nx_payment_security_record WHERE (#{userId} IS NULL OR user_id=#{userId}) AND (#{keyword}='' OR CAST(user_id AS CHAR)=#{keyword} OR record_no=#{keyword} OR EXISTS(SELECT 1 FROM nx_user u WHERE u.id=user_id AND u.is_deleted=0 AND (u.user_no=#{keyword} OR u.nickname LIKE CONCAT('%',#{keyword},'%') OR u.phone=#{keyword}))) AND (#{state}='' OR CASE WHEN state='ACTIVE' AND locked_until &lt;= #{now} THEN 'EXPIRED' ELSE state END=#{state}) AND (#{from} IS NULL OR started_at &gt;= #{from}) AND (#{to} IS NULL OR started_at &lt;= #{to})";
    @Select("<script>SELECT *"+FILTER+" ORDER BY started_at DESC,record_no LIMIT #{size} OFFSET #{offset}</script>")
    List<Map<String,Object>> records(@Param("userId") Long userId,@Param("keyword") String keyword,@Param("state") String state,@Param("from") Long from,@Param("to") Long to,@Param("now") long now,@Param("size") int size,@Param("offset") long offset);
    @Select("<script>SELECT COUNT(*)"+FILTER+"</script>")
    long count(@Param("userId") Long userId,@Param("keyword") String keyword,@Param("state") String state,@Param("from") Long from,@Param("to") Long to,@Param("now") long now);
    @Select("<script>SELECT * FROM nx_payment_security_record WHERE record_no=#{id}<if test='lock'> FOR UPDATE</if></script>")
    Map<String,Object> record(@Param("id") String id,@Param("lock") boolean lock);
    @Select("SELECT * FROM nx_payment_security_record WHERE user_id=#{userId} ORDER BY started_at DESC,record_no DESC LIMIT 1")
    Map<String,Object> latest(long userId);
    @Select("SELECT attempt_no,occurred_at,purpose,channel,result_code,result_message FROM nx_payment_password_attempt WHERE user_id=#{userId} ORDER BY occurred_at DESC,attempt_no LIMIT #{size} OFFSET #{offset}")
    List<Map<String,Object>> attempts(@Param("userId") long userId,@Param("size") int size,@Param("offset") long offset);
    @Select("SELECT COUNT(*) FROM nx_payment_password_attempt WHERE user_id=#{userId}")
    long attemptCount(long userId);
    @Select("SELECT * FROM nx_payment_security_audit WHERE record_no=#{id} ORDER BY occurred_at,audit_no")
    List<Map<String,Object>> audits(String id);
    @Update("UPDATE nx_payment_security_record SET state='RELEASED',version=version+1 WHERE record_no=#{id} AND user_id=#{userId} AND version=#{version} AND state='ACTIVE'")
    int release(@Param("id") String id,@Param("userId") long userId,@Param("version") long version);
    @Insert("INSERT INTO nx_payment_security_audit(audit_no,record_no,user_id,occurred_at,operator,action,reason) VALUES(#{id},#{record},#{userId},#{now},#{operator},#{action},#{reason})")
    int audit(@Param("id") String id,@Param("record") String record,@Param("userId") long userId,@Param("now") long now,@Param("operator") String operator,@Param("action") String action,@Param("reason") String reason);
    @Insert("INSERT IGNORE INTO nx_payment_security_admin_command(actor_id,surface,command_key,user_id,record_no,request_hash,created_at) VALUES(#{actor},#{surface},#{key},#{userId},#{record},#{hash},#{now})")
    int createCommand(@Param("actor") long actor,@Param("surface") String surface,@Param("key") String key,@Param("userId") long userId,@Param("record") String record,@Param("hash") String hash,@Param("now") long now);
    @Select("<script>SELECT * FROM nx_payment_security_admin_command WHERE actor_id=#{actor} AND surface=#{surface} AND command_key=#{key}<if test='lock'> FOR UPDATE</if></script>")
    Map<String,Object> command(@Param("actor") long actor,@Param("surface") String surface,@Param("key") String key,@Param("lock") boolean lock);
    @Update("UPDATE nx_payment_security_admin_command SET response_json=#{receipt} WHERE actor_id=#{actor} AND surface=#{surface} AND command_key=#{key}")
    int finish(@Param("actor") long actor,@Param("surface") String surface,@Param("key") String key,@Param("receipt") String receipt);
    @Update("UPDATE nx_payment_password_policy SET max_attempts=#{attempts},window_minutes=#{window},lock_minutes=#{lock},version=version+1,updated_at=#{now},updated_by=#{operator} WHERE id=1 AND version=#{version}")
    int policy(@Param("attempts") int attempts,@Param("window") int window,@Param("lock") int lock,@Param("version") long version,@Param("now") long now,@Param("operator") String operator);
}
