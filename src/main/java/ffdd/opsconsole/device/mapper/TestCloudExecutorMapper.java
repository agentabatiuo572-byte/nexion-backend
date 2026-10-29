package ffdd.opsconsole.device.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.*;

/** Narrow TEST-only persistence. Every mutation is called under the existing user/device/task locks. */
@Mapper
public interface TestCloudExecutorMapper extends BaseMapper<Object> {
    String COLUMNS = "task_no taskNo,executor_id executorId,owner_id ownerId,device_id deviceId,instance_no instanceNo,"
            + "task_config_id taskConfigId,next_key_hash nextKeyHash,claim_token claimToken,input_json inputJson,"
            + "input_hash inputHash,model_tag modelTag,model_digest modelDigest,proof_nonce proofNonce,"
            + "result_hash resultHash,result_artifact resultArtifact,complete_key_hash completeKeyHash,"
            + "complete_body_hash completeBodyHash,created_at createdAt";

    @Select("SELECT " + COLUMNS + " FROM nx_test_cloud_execution WHERE task_no=#{taskNo} FOR UPDATE")
    Execution lock(@Param("taskNo") String taskNo);
    @Select("SELECT " + COLUMNS + " FROM nx_test_cloud_execution WHERE executor_id=#{executor} AND next_key_hash=#{keyHash} FOR UPDATE")
    Execution byNextKey(@Param("executor") String executor, @Param("keyHash") String keyHash);

    @Insert("""
        INSERT INTO nx_test_cloud_execution(task_no,executor_id,owner_id,device_id,instance_no,task_config_id,
          next_key_hash,claim_token,input_json,input_hash,model_tag,model_digest,proof_nonce,created_at)
        VALUES(#{e.taskNo},#{e.executorId},#{e.ownerId},#{e.deviceId},#{e.instanceNo},#{e.taskConfigId},
          #{e.nextKeyHash},#{e.claimToken},#{e.inputJson},#{e.inputHash},#{e.modelTag},#{e.modelDigest},#{e.proofNonce},#{e.createdAt})
        """)
    int insert(@Param("e") Execution e);

    @Update("""
        UPDATE nx_test_cloud_execution SET result_hash=#{hash},result_artifact=#{artifact},
          complete_key_hash=#{keyHash},complete_body_hash=#{bodyHash}
         WHERE task_no=#{taskNo} AND result_hash IS NULL AND result_artifact IS NULL
        """)
    int saveResult(@Param("taskNo") String taskNo,@Param("hash") String hash,@Param("artifact") byte[] artifact,
                   @Param("keyHash") String keyHash,@Param("bodyHash") String bodyHash);

    @Select("""
        SELECT COUNT(*) FROM nx_user_device WHERE id=#{deviceId} AND user_id=#{ownerId} AND instance_no=#{instanceNo}
          AND pending_deactivate=0 AND is_deleted=0 AND source_environment='PRODUCTION' AND run_id=''
          AND source_channel='ORDER' AND ownership_status='OWNED' AND activated_at IS NOT NULL AND deactivated_at IS NULL
        """)
    int eligibleBinding(@Param("ownerId") long ownerId,@Param("deviceId") long deviceId,@Param("instanceNo") String instanceNo);

    @Update("""
        UPDATE nx_compute_task t SET t.status='CANCELLED',t.last_error='TEST_CLOUD_UNEXECUTED_REPLACED',t.updated_at=#{now}
         WHERE t.user_id=#{ownerId} AND t.user_device_id=#{deviceId} AND t.task_no=#{taskNo}
           AND t.task_config_id=#{config} AND t.model_name='BGE-M3' AND t.client_name='UVEL App'
           AND t.status IN ('CLAIMED','RUNNING') AND t.source_environment='PRODUCTION' AND t.is_deleted=0
           AND t.proof_consumed_at IS NULL AND t.completed_at IS NULL AND COALESCE(t.last_error,'')=''
           AND NOT EXISTS(SELECT 1 FROM nx_test_cloud_execution e WHERE e.task_no=t.task_no)
           AND NOT EXISTS(SELECT 1 FROM nx_compute_receipt r WHERE r.task_no=t.task_no)
           AND NOT EXISTS(SELECT 1 FROM nx_wallet_ledger l WHERE l.biz_no=t.task_no)
           AND NOT EXISTS(SELECT 1 FROM nx_audit_log a WHERE a.resource_id=t.task_no
                            AND a.action<>'TASK_ASSIGNMENT_CLAIMED')
           AND NOT EXISTS(SELECT 1 FROM nx_user_device_runtime r WHERE r.user_device_id=t.user_device_id
                            AND (COALESCE(r.agent_version,'')<>'' OR COALESCE(r.client_name,'') NOT IN ('','UVEL App')))
        """)
    int cancelUnexecuted(@Param("ownerId") long ownerId,@Param("deviceId") long deviceId,@Param("taskNo") String taskNo,
                         @Param("config") String config,@Param("now") LocalDateTime now);

    @Insert("""
        INSERT IGNORE INTO nx_user_device_runtime(user_device_id,online_status,client_name,agent_version,heartbeat_at,
          created_at,updated_at,is_deleted) VALUES(#{deviceId},'OFFLINE','UVEL TEST BGE-M3',#{marker},#{now},#{now},#{now},0)
        """)
    int ensureRuntime(@Param("deviceId") long deviceId,@Param("marker") String marker,@Param("now") LocalDateTime now);

    @Update("""
        UPDATE nx_user_device_runtime SET online_status='ONLINE',client_name='UVEL TEST BGE-M3',agent_version=#{marker},
          active_task_no=#{taskNo},heartbeat_at=#{now},updated_at=#{now}
         WHERE user_device_id=#{deviceId} AND is_deleted=0 AND COALESCE(paused_reason,'')=''
           AND online_status IN ('ONLINE','OFFLINE')
           AND (COALESCE(agent_version,'')='' OR agent_version=#{marker})
           AND (COALESCE(active_task_no,'')='' OR active_task_no=#{taskNo})
        """)
    int heartbeat(@Param("deviceId") long deviceId,@Param("taskNo") String taskNo,@Param("marker") String marker,
                  @Param("now") LocalDateTime now);

    @Update("""
        UPDATE nx_compute_task SET task_name='UVEL TEST BGE-M3 inference',model_name='TEST_BGE_M3_V1',
          client_name='UVEL TEST BGE-M3',worker_ack_at=#{now},updated_at=#{now}
         WHERE task_no=#{taskNo} AND user_id=#{ownerId} AND user_device_id=#{deviceId}
           AND task_config_id=#{config} AND model_name='BGE-M3' AND status='RUNNING'
           AND source_environment='PRODUCTION' AND is_deleted=0 AND proof_consumed_at IS NULL AND completed_at IS NULL
        """)
    int acknowledge(@Param("ownerId") long ownerId,@Param("deviceId") long deviceId,@Param("taskNo") String taskNo,
                    @Param("config") String config,@Param("now") LocalDateTime now);

    @Update("""
        UPDATE nx_user_device_runtime r JOIN nx_user_device d ON d.id=r.user_device_id
           SET r.online_status='OFFLINE',r.updated_at=#{now}
         WHERE d.id=#{deviceId} AND d.user_id=#{ownerId} AND d.instance_no=#{instanceNo}
           AND d.source_environment='PRODUCTION' AND d.run_id='' AND d.is_deleted=0
           AND r.agent_version=#{marker} AND r.client_name='UVEL TEST BGE-M3'
           AND r.heartbeat_at<#{cutoff} AND r.is_deleted=0 AND COALESCE(r.paused_reason,'')=''
           AND r.online_status='ONLINE'
        """)
    int expireHeartbeat(@Param("ownerId") long ownerId,@Param("deviceId") long deviceId,@Param("instanceNo") String instanceNo,@Param("marker") String marker,
                        @Param("now") LocalDateTime now,@Param("cutoff") LocalDateTime cutoff);

    record Execution(String taskNo,String executorId,long ownerId,long deviceId,String instanceNo,String taskConfigId,
                     String nextKeyHash,String claimToken,String inputJson,String inputHash,String modelTag,String modelDigest,
                     String proofNonce,String resultHash,byte[] resultArtifact,String completeKeyHash,String completeBodyHash,
                     LocalDateTime createdAt) { }
}
