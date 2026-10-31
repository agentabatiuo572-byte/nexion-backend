package ffdd.opsconsole.device.mapper;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.*;

@Mapper
public interface TestCloudRequestMapper {
    @Select("""
        SELECT id,trace_id traceId,detail_json detailJson,created_at createdAt FROM nx_audit_log
         WHERE service_name='nexion-backend' AND action='TEST_CLOUD_STABILITY_RECOVERY_APPROVED'
          AND resource_type='COMPUTE_TASK' AND resource_id=#{taskNo} AND biz_no=#{taskNo} AND user_id=#{ownerId}
          AND actor_type='SYSTEM' AND actor_username='test:task-stability-20261031'
          AND result='SUCCESS' AND risk_level='HIGH' AND is_deleted=0 ORDER BY id FOR UPDATE
        """)
    java.util.List<LegacyReview> stabilityReviews(@Param("taskNo") String taskNo,@Param("ownerId") long ownerId);
    record LegacyReview(long id,String traceId,String detailJson,LocalDateTime createdAt) { }
    @Select("""
        SELECT COUNT(*) FROM nx_test_cloud_request r JOIN nx_test_cloud_execution e ON e.task_no=r.task_no
          AND e.executor_id=r.executor_id AND e.next_key_hash=r.key_hash
         WHERE r.executor_id=#{executor} AND r.operation='NEXT' AND r.state='COMMITTED' AND r.task_no=#{taskNo}
           AND JSON_UNQUOTE(JSON_EXTRACT(r.response_json,'$.requestCreatedTask'))='true'
        """)
    int durableNext(@Param("executor") String executor,@Param("taskNo") String taskNo);
    @Insert("""
        INSERT IGNORE INTO nx_test_cloud_stability_recovery_use(review_id,task_no,executor_id,key_hash,original_body_hash,recovery_body_hash,termination_authorized,used_at)
        VALUES(#{review},#{taskNo},#{executor},#{keyHash},#{originalHash},#{bodyHash},#{terminalAuthority},#{now})
        """)
    int useStabilityReview(@Param("review") long review,@Param("taskNo") String taskNo,@Param("executor") String executor,
                          @Param("keyHash") String keyHash,@Param("originalHash") String originalHash,@Param("bodyHash") String bodyHash,
                          @Param("terminalAuthority") boolean terminalAuthority,@Param("now") LocalDateTime now);
    @Select("""
        SELECT COUNT(*) FROM nx_test_cloud_stability_recovery_use WHERE review_id=#{review} AND task_no=#{taskNo}
          AND executor_id=#{executor} AND key_hash=#{keyHash} AND original_body_hash=#{originalHash} AND recovery_body_hash=#{bodyHash}
        """)
    int sameStabilityUse(@Param("review") long review,@Param("taskNo") String taskNo,@Param("executor") String executor,
                         @Param("keyHash") String keyHash,@Param("originalHash") String originalHash,@Param("bodyHash") String bodyHash);
    @Select("SELECT COUNT(*) FROM nx_test_cloud_stability_recovery_use WHERE task_no=#{taskNo}")
    int stabilityUsed(@Param("taskNo") String taskNo);
    @Update("""
        UPDATE nx_test_cloud_request r JOIN nx_test_cloud_stability_recovery_use u ON u.task_no=r.task_no
         AND u.executor_id=r.executor_id AND u.key_hash=r.key_hash AND u.recovery_body_hash=r.body_hash
        SET r.recovery_used=1,r.reviewed_termination=u.termination_authorized
        WHERE r.executor_id=#{executor} AND r.operation='COMPLETE' AND r.key_hash=#{keyHash}
        """)
    int markStabilityAttempt(@Param("executor") String executor,@Param("keyHash") String keyHash);
    @Select("SELECT id FROM nx_test_cloud_resource_guard WHERE id=1 FOR UPDATE")
    int lockResourceGuard();
    @Select("""
        SELECT COUNT(*) FROM nx_user_device WHERE is_deleted=0 AND source_environment='PRODUCTION' AND run_id=''
          AND UPPER(ownership_status)='OWNED' AND UPPER(device_type) NOT IN ('PHONE','MOBILE')
          AND activated_at IS NOT NULL AND deactivated_at IS NULL
          AND UPPER(status) IN ('ACTIVE','ONLINE','BUSY','RUNNING','OFFLINE') FOR UPDATE
        """)
    int activeResourceExecutors();
    String COLUMNS="executor_id executorId,operation,key_hash keyHash,body_hash bodyHash,owner_id ownerId,device_id deviceId,"
            +"instance_no instanceNo,task_config_id taskConfigId,task_no taskNo,state,generation,recovery_token recoveryToken,"
            +"recovery_used recoveryUsed,proof_timestamp proofTimestamp,proof_validated proofValidated,reviewed_termination reviewedTermination,response_json responseJson,updated_at updatedAt";
    record Request(String executorId,String operation,String keyHash,String bodyHash,long ownerId,long deviceId,
                   String instanceNo,String taskConfigId,String taskNo,String state,long generation,String recoveryToken,
                   boolean recoveryUsed,Long proofTimestamp,boolean proofValidated,boolean reviewedTermination,String responseJson,LocalDateTime updatedAt) { }
    @Update("""
        UPDATE nx_test_cloud_request SET proof_validated=1 WHERE executor_id=#{r.executorId}
          AND operation=#{r.operation} AND key_hash=#{r.keyHash} AND body_hash=#{r.bodyHash}
          AND state='PROCESSING' AND generation=#{r.generation}
        """)
    int validateProof(@Param("r") Request request);
    @Select("SELECT "+COLUMNS+" FROM nx_test_cloud_request WHERE executor_id=#{executor} AND operation=#{operation} AND key_hash=#{keyHash}")
    Request read(@Param("executor") String executor,@Param("operation") String operation,@Param("keyHash") String keyHash);
    @Select("SELECT "+COLUMNS+" FROM nx_test_cloud_request WHERE executor_id=#{executor} AND operation=#{operation} AND key_hash=#{keyHash} FOR UPDATE")
    Request lock(@Param("executor") String executor,@Param("operation") String operation,@Param("keyHash") String keyHash);
    @Insert("""
        INSERT IGNORE INTO nx_test_cloud_request(executor_id,operation,key_hash,body_hash,owner_id,device_id,instance_no,
          task_config_id,task_no,state,generation,recovery_used,proof_timestamp,updated_at)
        VALUES(#{r.executorId},#{r.operation},#{r.keyHash},#{r.bodyHash},#{r.ownerId},#{r.deviceId},#{r.instanceNo},
          #{r.taskConfigId},#{r.taskNo},'PROCESSING',1,0,#{r.proofTimestamp},#{r.updatedAt})
        """)
    int insert(@Param("r") Request request);
    @Update("""
        UPDATE nx_test_cloud_request SET state='COMMITTED',task_no=#{taskNo},response_json=#{response},updated_at=#{now}
        WHERE executor_id=#{r.executorId} AND operation=#{r.operation} AND key_hash=#{r.keyHash}
          AND body_hash=#{r.bodyHash} AND state='PROCESSING' AND generation=#{r.generation}
        """)
    int commit(@Param("r") Request request,@Param("taskNo") String taskNo,@Param("response") String response,@Param("now") LocalDateTime now);
    @Update("""
        UPDATE nx_test_cloud_request SET state=#{state},recovery_token=#{token},updated_at=#{now}
        WHERE executor_id=#{r.executorId} AND operation=#{r.operation} AND key_hash=#{r.keyHash}
          AND body_hash=#{r.bodyHash} AND state='PROCESSING' AND generation=#{r.generation}
        """)
    int outcome(@Param("r") Request request,@Param("state") String state,@Param("token") String token,@Param("now") LocalDateTime now);
    @Update("""
        UPDATE nx_test_cloud_request SET state='PROCESSING',generation=generation+1,recovery_used=1,updated_at=#{now}
        WHERE executor_id=#{r.executorId} AND operation=#{r.operation} AND key_hash=#{r.keyHash}
          AND body_hash=#{r.bodyHash} AND state='ROLLED_BACK' AND generation=#{r.generation}
          AND recovery_used=0 AND recovery_token=#{token}
        """)
    int recover(@Param("r") Request request,@Param("token") String token,@Param("now") LocalDateTime now);
    @Update("""
        UPDATE nx_test_cloud_request SET generation=generation+1,recovery_used=1,response_json=#{response},updated_at=#{now}
        WHERE executor_id=#{r.executorId} AND operation=#{r.operation} AND key_hash=#{r.keyHash}
          AND body_hash=#{r.bodyHash} AND state='ROLLED_BACK' AND generation=#{r.generation}
          AND recovery_token=#{token} AND response_json IS NULL
        """)
    int terminate(@Param("r") Request request,@Param("token") String token,@Param("response") String response,@Param("now") LocalDateTime now);
}
