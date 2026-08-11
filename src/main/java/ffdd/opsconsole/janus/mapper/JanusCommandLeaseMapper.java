package ffdd.opsconsole.janus.mapper;

import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@SuppressWarnings("MybatisPlusBaseMapper")
public interface JanusCommandLeaseMapper {
    @Insert("""
            INSERT IGNORE INTO nx_janus_command_lease(
              device_id,command_id,command_version,executor_id,claim_nonce,lease_token,
              fencing_token,lease_until,created_at,updated_at)
            VALUES(#{deviceId},#{commandId},#{commandVersion},#{executorId},#{claimNonce},#{leaseToken},
              1,FROM_UNIXTIME(#{leaseUntil}/1000),NOW(3),NOW(3))
            """)
    int insert(@Param("deviceId") String deviceId, @Param("commandId") String commandId,
               @Param("commandVersion") long commandVersion, @Param("executorId") String executorId,
               @Param("claimNonce") String claimNonce, @Param("leaseToken") String leaseToken,
               @Param("leaseUntil") long leaseUntil);

    @Select("""
            SELECT device_id AS deviceId,command_id AS commandId,command_version AS commandVersion,
              executor_id AS executorId,claim_nonce AS claimNonce,lease_token AS leaseToken,
              fencing_token AS fencingToken,
              CAST(UNIX_TIMESTAMP(lease_until)*1000 AS UNSIGNED) AS leaseExpiresAt
            FROM nx_janus_command_lease
            WHERE device_id=#{deviceId} AND command_id=#{commandId} AND command_version=#{commandVersion}
            """)
    Map<String,Object> find(@Param("deviceId") String deviceId, @Param("commandId") String commandId,
                            @Param("commandVersion") long commandVersion);

    @Update("""
            UPDATE nx_janus_command_lease SET claim_nonce=#{claimNonce},lease_until=FROM_UNIXTIME(#{leaseUntil}/1000),
              updated_at=NOW(3)
            WHERE device_id=#{deviceId} AND command_id=#{commandId} AND command_version=#{commandVersion}
              AND executor_id=#{executorId} AND lease_token=#{leaseToken} AND fencing_token=#{fencingToken}
            """)
    int renew(@Param("deviceId") String deviceId, @Param("commandId") String commandId,
              @Param("commandVersion") long commandVersion, @Param("executorId") String executorId,
              @Param("leaseToken") String leaseToken, @Param("fencingToken") long fencingToken,
              @Param("claimNonce") String claimNonce, @Param("leaseUntil") long leaseUntil);

    @Update("""
            UPDATE nx_janus_command_lease SET executor_id=#{executorId},claim_nonce=#{claimNonce},
              lease_token=#{newLeaseToken},fencing_token=fencing_token+1,
              lease_until=FROM_UNIXTIME(#{leaseUntil}/1000),updated_at=NOW(3)
            WHERE device_id=#{deviceId} AND command_id=#{commandId} AND command_version=#{commandVersion}
              AND lease_token=#{oldLeaseToken} AND fencing_token=#{oldFencingToken}
              AND lease_until<FROM_UNIXTIME(#{now}/1000)
            """)
    int takeExpired(@Param("deviceId") String deviceId, @Param("commandId") String commandId,
                    @Param("commandVersion") long commandVersion, @Param("executorId") String executorId,
                    @Param("claimNonce") String claimNonce, @Param("newLeaseToken") String newLeaseToken,
                    @Param("oldLeaseToken") String oldLeaseToken, @Param("oldFencingToken") long oldFencingToken,
                    @Param("leaseUntil") long leaseUntil, @Param("now") long now);

    @Select("""
            SELECT device_id AS deviceId,command_id AS commandId,command_version AS commandVersion,
              executor_id AS executorId,claim_nonce AS claimNonce,lease_token AS leaseToken,
              fencing_token AS fencingToken,
              CAST(UNIX_TIMESTAMP(lease_until)*1000 AS UNSIGNED) AS leaseExpiresAt
            FROM nx_janus_command_lease WHERE lease_token=#{leaseToken}
            """)
    Map<String,Object> findByToken(@Param("leaseToken") String leaseToken);
}
