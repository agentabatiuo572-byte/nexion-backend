package ffdd.opsconsole.device.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.device.infrastructure.TestCloudRequestEntity;
import java.time.LocalDateTime;
import java.util.Collection;
import org.apache.ibatis.annotations.*;

@Mapper
public interface TestCloudRequestMapper extends BaseMapper<TestCloudRequestEntity> {
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
    String RESOURCE_EXECUTOR_KIND="""
          is_deleted=0 AND source_environment='PRODUCTION' AND run_id=''
          AND UPPER(device_type) NOT IN ('PHONE','MOBILE')
        """;
    String RESOURCE_EXECUTOR_BASE=RESOURCE_EXECUTOR_KIND+" AND UPPER(ownership_status)='OWNED' AND activated_at IS NOT NULL";
    String RESOURCE_EXECUTOR_CURRENT="deactivated_at IS NULL AND UPPER(status) IN ('ACTIVE','ONLINE','BUSY','RUNNING','OFFLINE')";
    @Select("SELECT COUNT(*) FROM nx_user_device WHERE "+RESOURCE_EXECUTOR_BASE+" AND "+RESOURCE_EXECUTOR_CURRENT+" FOR UPDATE")
    int activeResourceExecutors();
    @Select("SELECT COUNT(*) FROM nx_user_device WHERE id=#{deviceId} AND "+RESOURCE_EXECUTOR_BASE+" AND "+RESOURCE_EXECUTOR_CURRENT+" FOR UPDATE")
    int resourceExecutorCount(@Param("deviceId") long deviceId);
    @Select("SELECT CASE WHEN "+RESOURCE_EXECUTOR_BASE+" AND NOT COALESCE(("+RESOURCE_EXECUTOR_CURRENT+"),0) THEN 1 ELSE 0 END FROM nx_user_device WHERE id=#{deviceId} FOR UPDATE")
    int restoreResourceDelta(@Param("deviceId") long deviceId);
    @Select("SELECT CASE WHEN "+RESOURCE_EXECUTOR_KIND+" AND NOT COALESCE(("+RESOURCE_EXECUTOR_BASE+" AND "+RESOURCE_EXECUTOR_CURRENT+"),0) THEN 1 ELSE 0 END FROM nx_user_device WHERE id=#{deviceId} FOR UPDATE")
    int activationResourceDelta(@Param("deviceId") long deviceId);
    String V1_PHYSICAL_FROM="""
        FROM nx_user_device d LEFT JOIN (
          SELECT r.device_id FROM nx_test_cloud_request r WHERE r.state IN ('PROCESSING','UNKNOWN')
        """+" AND NOT EXISTS ("+LegacyZeroCreditResolutionSql.RESOLVED_REQUEST+")"+"""
          UNION
          SELECT user_device_id device_id FROM nx_compute_task
           WHERE is_deleted=0 AND source_environment='PRODUCTION' AND status IN ('CLAIMED','RUNNING')
             AND task_no NOT LIKE 'CV2-%'
        ) legacy ON legacy.device_id=d.id
        """;
    String NOT_LOGICAL_DEVICE="""
        <if test='logicalDevices != null and !logicalDevices.isEmpty()'>
          AND d.id NOT IN <foreach collection='logicalDevices' item='logicalDevice' open='(' separator=',' close=')'>#{logicalDevice}</foreach>
        </if>
        """;
    /** Logical slots do not reserve a model; unresolved v1 work still reserves its original physical device. */
    @Select("<script>SELECT COUNT(*) "+V1_PHYSICAL_FROM+" WHERE "+RESOURCE_EXECUTOR_KIND
            +" AND (legacy.device_id IS NOT NULL OR ("+RESOURCE_EXECUTOR_BASE+" AND "+RESOURCE_EXECUTOR_CURRENT+NOT_LOGICAL_DEVICE+")) FOR UPDATE</script>")
    int activeV1ResourceExecutors(@Param("logicalDevices") Collection<Long> logicalDevices);
    /** Replacement cannot credit a logical slot or an unresolved legacy execution that remains reserved. */
    @Select("<script>SELECT COUNT(*) "+V1_PHYSICAL_FROM+" WHERE d.id=#{deviceId} AND "+RESOURCE_EXECUTOR_BASE
            +" AND "+RESOURCE_EXECUTOR_CURRENT+" AND legacy.device_id IS NULL "+NOT_LOGICAL_DEVICE+" FOR UPDATE</script>")
    int v1ResourceExecutorCount(@Param("deviceId") long deviceId,@Param("logicalDevices") Collection<Long> logicalDevices);
    // Execution admission is separate from activation promises. A bare logical task never ran a model.
    // saveResult and settlement commit atomically. Read their completed facts, never re-audit historical artifacts here.
    String UNCLOSED_MANIFEST="""
        FROM nx_test_cloud_execution e
        WHERE CASE WHEN e.result_hash IS NOT NULL THEN NOT EXISTS (
          SELECT 1 FROM nx_compute_task t JOIN nx_compute_receipt receipt ON receipt.task_no=t.task_no
           AND BINARY receipt.task_no=BINARY t.task_no
           AND receipt.user_id=t.user_id AND receipt.user_device_id=t.user_device_id
           AND receipt.source_environment='PRODUCTION' AND receipt.is_deleted=0
           AND receipt.earning_status IN ('CREDITED','SETTLED','SUCCESS')
          WHERE t.task_no=CONVERT(e.task_no USING utf8mb4) COLLATE utf8mb4_0900_ai_ci
           AND BINARY t.task_no=BINARY e.task_no AND t.user_id=e.owner_id AND t.user_device_id=e.device_id
           AND BINARY t.task_config_id=BINARY e.task_config_id AND t.is_deleted=0 AND t.source_environment='PRODUCTION'
           AND t.status='COMPLETED' AND t.completed_at IS NOT NULL
           AND e.complete_key_hash IS NOT NULL AND e.complete_body_hash IS NOT NULL
        ) ELSE NOT EXISTS (
          SELECT 1 FROM nx_test_cloud_request r JOIN nx_compute_task t ON t.task_no=r.task_no AND BINARY t.task_no=BINARY r.task_no
           AND t.user_id=r.owner_id AND t.user_device_id=r.device_id AND BINARY t.task_config_id=BINARY r.task_config_id
           AND t.is_deleted=0 AND t.source_environment='PRODUCTION' AND t.status IN ('EXPIRED','CANCELLED')
          WHERE r.task_no=CONVERT(e.task_no USING utf8mb4) COLLATE utf8mb4_0900_ai_ci
           AND BINARY r.task_no=BINARY e.task_no AND r.owner_id=e.owner_id AND r.device_id=e.device_id
           AND BINARY r.executor_id=BINARY e.executor_id AND BINARY r.instance_no=BINARY e.instance_no
           AND BINARY r.task_config_id=BINARY e.task_config_id AND r.operation='COMPLETE' AND r.state='ROLLED_BACK'
           AND r.recovery_used=1 AND r.generation>1 AND (r.proof_validated=1 OR r.reviewed_termination=1)
           AND JSON_UNQUOTE(JSON_EXTRACT(r.response_json,'$.state'))='ROLLED_BACK'
           AND BINARY JSON_UNQUOTE(JSON_EXTRACT(r.response_json,'$.taskNo'))=BINARY e.task_no
           AND JSON_UNQUOTE(JSON_EXTRACT(r.response_json,'$.taskStatus'))=t.status
           AND JSON_UNQUOTE(JSON_EXTRACT(r.response_json,'$.executionGeneration'))=CAST(r.generation AS CHAR)
           AND JSON_UNQUOTE(JSON_EXTRACT(r.response_json,'$.recoveryAllowed'))='false')
        AND NOT EXISTS (
          SELECT 1 FROM nx_test_cloud_request r WHERE r.task_no=CONVERT(e.task_no USING utf8mb4) COLLATE utf8mb4_0900_ai_ci
           AND BINARY r.task_no=BINARY e.task_no
           AND r.owner_id=e.owner_id AND r.device_id=e.device_id AND BINARY r.executor_id=BINARY e.executor_id
           AND BINARY r.instance_no=BINARY e.instance_no AND BINARY r.task_config_id=BINARY e.task_config_id
        """+" AND EXISTS ("+LegacyZeroCreditResolutionSql.RESOLVED_REQUEST+")) END";
    String ACTUAL_V1_WORK="""
        SELECT r.device_id FROM nx_test_cloud_request r WHERE r.state IN ('PROCESSING','UNKNOWN')
        """+" AND NOT EXISTS ("+LegacyZeroCreditResolutionSql.RESOLVED_REQUEST+") UNION SELECT e.device_id "+UNCLOSED_MANIFEST;
    /** Current read under the resource mutex + READ_COMMITTED; never lock another request behind its owner. */
    @Select("SELECT COUNT(*) FROM ("+ACTUAL_V1_WORK+") actual_v1")
    int actualV1Executions();
    @Select("SELECT COUNT(*) "+UNCLOSED_MANIFEST+" AND e.device_id=#{deviceId}")
    int unclosedManifests(@Param("deviceId") long deviceId);
    @Select("SELECT COUNT(*) FROM nx_test_cloud_request r WHERE r.device_id=#{deviceId} AND r.state IN ('PROCESSING','UNKNOWN') AND NOT EXISTS ("+LegacyZeroCreditResolutionSql.RESOLVED_REQUEST+")")
    int uncertainDeviceRequests(@Param("deviceId") long deviceId);
    @Select("""
        SELECT COUNT(*) FROM nx_test_cloud_execution e JOIN nx_compute_task t ON t.task_no=CONVERT(e.task_no USING utf8mb4) COLLATE utf8mb4_0900_ai_ci
         AND BINARY t.task_no=BINARY e.task_no
         AND t.user_id=e.owner_id AND t.user_device_id=e.device_id AND BINARY t.task_config_id=BINARY e.task_config_id
        WHERE e.owner_id=#{ownerId} AND e.device_id=#{deviceId} AND BINARY e.instance_no=BINARY #{instanceNo}
         AND BINARY e.executor_id=BINARY #{executorId} AND BINARY e.task_config_id=BINARY #{taskConfigId}
         AND e.next_key_hash=#{keyHash} AND t.status IN ('CLAIMED','RUNNING') AND t.source_environment='PRODUCTION'
         AND t.is_deleted=0 AND t.lease_expires_at>#{now} AND t.proof_expires_at>#{now}
         AND e.result_hash IS NULL AND e.result_artifact IS NULL AND e.complete_key_hash IS NULL AND e.complete_body_hash IS NULL
        """)
    int sameLiveNext(@Param("ownerId") long ownerId,@Param("deviceId") long deviceId,@Param("instanceNo") String instanceNo,
                     @Param("executorId") String executorId,@Param("taskConfigId") String taskConfigId,@Param("keyHash") String keyHash,@Param("now") LocalDateTime now);
    @Select("SELECT id FROM nx_user WHERE id=#{ownerId} AND is_deleted=0 FOR UPDATE")
    Long lockExecutionOwner(@Param("ownerId") long ownerId);
    @Select("SELECT id FROM nx_user_device WHERE id=#{deviceId} AND user_id=#{ownerId} AND BINARY instance_no=BINARY #{instanceNo} AND is_deleted=0 AND source_environment='PRODUCTION' FOR UPDATE")
    Long lockExecutionDevice(@Param("ownerId") long ownerId,@Param("deviceId") long deviceId,@Param("instanceNo") String instanceNo);
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
    int insertIntent(@Param("r") Request request);
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
