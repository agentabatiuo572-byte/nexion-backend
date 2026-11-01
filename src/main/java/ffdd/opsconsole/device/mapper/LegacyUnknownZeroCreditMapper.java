package ffdd.opsconsole.device.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

@Mapper
public interface LegacyUnknownZeroCreditMapper extends BaseMapper<Object> {
    @Select("SELECT id FROM nx_test_cloud_resource_guard WHERE id=1 FOR UPDATE")
    Integer lockResourceGuard();
    @Select("SELECT id FROM nx_user WHERE id=#{owner} AND status='ACTIVE' AND sandbox=0 AND is_deleted=0 FOR UPDATE")
    Long lockOwner(@Param("owner")long owner);
    @Select("SELECT id,user_id ownerId,instance_no instanceNo,status,pending_deactivate pendingDeactivate,row_version rowVersion "
            +"FROM nx_user_device WHERE id=#{device} AND user_id=#{owner} AND source_environment='PRODUCTION' "
            +"AND run_id='' AND ownership_status='OWNED' AND is_deleted=0 FOR UPDATE")
    Map<String,Object> lockDevice(@Param("owner")long owner,@Param("device")long device);

    String EVIDENCE="""
        SELECT r.id requestId,r.executor_id executorId,r.operation,r.key_hash requestKeyHash,r.body_hash requestBodyHash,
          r.owner_id ownerId,r.device_id deviceId,r.instance_no instanceNo,r.task_config_id taskConfigId,r.task_no taskNo,
          r.state requestState,r.generation requestGeneration,r.recovery_used recoveryUsed,r.proof_validated proofValidated,
          r.response_json responseJson,t.status taskStatus,t.user_id taskOwner,t.user_device_id taskDevice,
          t.task_config_id taskConfig,t.source_environment sourceEnvironment,t.is_deleted taskDeleted,
          t.proof_consumed_at proofConsumedAt,t.completed_at completedAt,t.model_name modelName,t.client_name clientName,
          e.executor_id executionExecutor,e.owner_id executionOwner,e.device_id executionDevice,
          e.instance_no executionInstance,e.task_config_id executionConfig,e.result_hash resultHash,
          (e.result_artifact IS NOT NULL) hasResultArtifact,e.complete_key_hash completeKeyHash,e.complete_body_hash completeBodyHash,
          e.input_hash inputHash,e.model_tag modelTag,e.model_digest modelDigest,e.next_key_hash nextKeyHash
        FROM nx_test_cloud_request r JOIN nx_compute_task t ON BINARY t.task_no=BINARY r.task_no
        JOIN nx_test_cloud_execution e ON BINARY e.task_no=BINARY r.task_no
        JOIN nx_user_device d ON d.id=r.device_id AND d.user_id=r.owner_id
          AND BINARY d.instance_no=BINARY r.instance_no AND d.source_environment='PRODUCTION' AND d.run_id=''
          AND d.ownership_status='OWNED' AND d.is_deleted=0
        JOIN nx_user u ON u.id=d.user_id AND u.status='ACTIVE' AND u.sandbox=0 AND u.is_deleted=0
        WHERE r.device_id=#{device} AND r.owner_id=#{owner} AND BINARY r.task_no=BINARY #{task}
          AND r.operation='COMPLETE'
        """;
    @Select(EVIDENCE)
    List<Map<String,Object>> evidence(@Param("owner")long owner,@Param("device")long device,@Param("task")String task);
    @Select(EVIDENCE+" FOR UPDATE")
    List<Map<String,Object>> lockEvidence(@Param("owner")long owner,@Param("device")long device,@Param("task")String task);
    @Select("SELECT user_device_id deviceId,active_task_no activeTaskNo,agent_version agentVersion,client_name clientName "
            +"FROM nx_user_device_runtime WHERE user_device_id=#{device} AND is_deleted=0 FOR UPDATE")
    Map<String,Object> lockRuntime(@Param("device")long device);
    @Select("SELECT task_no FROM nx_compute_task WHERE user_id=#{owner} AND user_device_id=#{device} "
            +"AND source_environment='PRODUCTION' AND is_deleted=0 AND status IN ('CLAIMED','RUNNING') FOR UPDATE")
    List<String> lockActiveTasks(@Param("owner")long owner,@Param("device")long device);

    @Select("SELECT id FROM nx_compute_receipt WHERE BINARY task_no=BINARY #{task} OR BINARY receipt_no=BINARY #{receipt} FOR UPDATE")
    List<Long> receipts(@Param("task")String task,@Param("receipt")String receipt);
    @Select("SELECT id FROM nx_earning_event WHERE BINARY event_no=BINARY CONCAT('EARN-',#{task}) "
            +"OR BINARY receipt_no=BINARY #{receipt} FOR UPDATE")
    List<Long> earnings(@Param("task")String task,@Param("receipt")String receipt);
    @Select("SELECT id FROM nx_wallet_ledger WHERE BINARY biz_no=BINARY #{task} OR BINARY biz_no=BINARY #{receipt} FOR UPDATE")
    List<Long> ledgers(@Param("task")String task,@Param("receipt")String receipt);
    // A different real completed task may legitimately own this device's UTC daily UVT ledger.
    @Select("""
        SELECT l.id FROM nx_wallet_ledger l
         WHERE l.user_id=#{owner} AND l.biz_no LIKE CONCAT('CLOUD_SHARE_DAILY:',#{device},':%')
           AND NOT EXISTS (SELECT 1 FROM nx_compute_receipt other JOIN nx_compute_task ot ON ot.task_no=other.task_no
             WHERE other.user_id=#{owner} AND other.user_device_id=#{device} AND BINARY other.task_no<>BINARY #{task}
               AND other.source_environment='PRODUCTION' AND ot.source_environment='PRODUCTION'
               AND l.asset='UVT' AND l.direction='IN' AND l.status='SUCCESS'
               AND other.reward_nex>0 AND other.reward_nex=l.amount AND ot.status='COMPLETED'
               AND other.earning_status IN ('CREDITED','SETTLED','SUCCESS')
               AND l.biz_no=CONCAT('CLOUD_SHARE_DAILY:',#{device},':',DATE(DATE_SUB(other.completed_at,INTERVAL 8 HOUR))))
         FOR UPDATE
        """)
    List<Long> unattributedDailyLedgers(@Param("owner")long owner,@Param("device")long device,@Param("task")String task);
    @Select("SELECT id FROM nx_support_fund_operation WHERE BINARY op_key=BINARY #{fundKey} "
            +"OR (source_table='nx_compute_receipt' AND BINARY source_key=BINARY #{receipt}) FOR UPDATE")
    List<Long> fundOperations(@Param("fundKey")String fundKey,@Param("receipt")String receipt);
    @Select("SELECT id FROM nx_event_outbox WHERE BINARY aggregate_id=BINARY #{task} "
            +"AND event_type IN ('task.completed','earnings.credited') FOR UPDATE")
    List<Long> completionOutbox(@Param("task")String task);
    @Select("SELECT id FROM nx_audit_log WHERE (BINARY resource_id=BINARY #{task} OR BINARY biz_no=BINARY #{receipt}) "
            +"AND action IN ('TASK_ASSIGNMENT_COMPLETED','TEST_CLOUD_RESULT_VERIFIED') FOR UPDATE")
    List<Long> completionAudit(@Param("task")String task,@Param("receipt")String receipt);
    @Select("SELECT id FROM nx_audit_log_archive WHERE (BINARY JSON_UNQUOTE(JSON_EXTRACT(cold_payload,'$.resourceId'))=BINARY #{task} "
            +"OR BINARY JSON_UNQUOTE(JSON_EXTRACT(cold_payload,'$.bizNo'))=BINARY #{receipt}) "
            +"AND JSON_UNQUOTE(JSON_EXTRACT(cold_payload,'$.action')) IN ('TASK_ASSIGNMENT_COMPLETED','TEST_CLOUD_RESULT_VERIFIED') FOR UPDATE")
    List<Long> archivedCompletionAudit(@Param("task")String task,@Param("receipt")String receipt);

    @Update("""
        UPDATE nx_compute_task SET status='CANCELLED',proof_consumed_at=#{now},
          last_error='UNKNOWN_ABANDONED_NO_CREDIT',updated_at=#{now}
         WHERE BINARY task_no=BINARY #{task} AND user_id=#{owner} AND user_device_id=#{device}
           AND source_environment='PRODUCTION' AND is_deleted=0 AND status IN ('CLAIMED','RUNNING','EXPIRED','CANCELLED')
           AND proof_consumed_at IS NULL AND completed_at IS NULL
        """)
    int cancelTask(@Param("owner")long owner,@Param("device")long device,@Param("task")String task,@Param("now")LocalDateTime now);
    @Update("""
        UPDATE nx_user_device_runtime SET active_task_no=NULL,online_status='OFFLINE',
          paused_reason='UNKNOWN_ABANDONED_NO_CREDIT',updated_at=#{now}
         WHERE user_device_id=#{device} AND BINARY active_task_no=BINARY #{task}
           AND BINARY agent_version=BINARY #{marker} AND client_name='UVEL TEST BGE-M3' AND is_deleted=0
        """)
    int clearRuntime(@Param("device")long device,@Param("task")String task,@Param("marker")String marker,@Param("now")LocalDateTime now);

    @Select("SELECT id resolutionId,incident_case_id caseId,task_no taskNo,device_id deviceId,outcome,task_status taskStatus,"
            +"a2_operation_id a2OperationId,resolved_at resolvedAt,deployment_scope deploymentScope,request_id requestId,"
            +"executor_id executorId,operation,request_key_hash requestKeyHash,request_body_hash requestBodyHash,owner_id ownerId,"
            +"instance_no instanceNo,task_config_id taskConfigId,request_generation requestGeneration,case_set_sha256 caseSetSha256 "
            +"FROM nx_test_cloud_zero_credit_resolution "
            +"WHERE BINARY incident_case_id=BINARY #{caseId}")
    Map<String,Object> resolution(@Param("caseId")String caseId);
    @Select("SELECT COUNT(*) FROM nx_test_cloud_request r WHERE r.id=#{requestId} AND r.state IN ('PROCESSING','UNKNOWN') "
            +"AND r.generation=1 AND r.recovery_used=0 AND r.response_json IS NULL AND EXISTS ("
            +LegacyZeroCreditResolutionSql.RESOLVED_REQUEST+") AND EXISTS (SELECT 1 FROM nx_compute_task t "
            +"WHERE BINARY t.task_no=BINARY r.task_no AND t.proof_consumed_at IS NOT NULL) "
            +"AND EXISTS (SELECT 1 FROM nx_test_cloud_execution e WHERE BINARY e.task_no=BINARY r.task_no "
            +"AND e.owner_id=r.owner_id AND e.device_id=r.device_id AND BINARY e.executor_id=BINARY r.executor_id "
            +"AND BINARY e.instance_no=BINARY r.instance_no AND BINARY e.task_config_id=BINARY r.task_config_id "
            +"AND BINARY e.input_hash=BINARY #{inputHash} AND BINARY e.model_tag=BINARY #{modelTag} "
            +"AND BINARY e.model_digest=BINARY #{modelDigest} AND BINARY e.next_key_hash=BINARY #{nextKeyHash} "
            +"AND e.result_hash IS NULL AND e.result_artifact IS NULL AND e.complete_key_hash IS NULL AND e.complete_body_hash IS NULL)")
    int validPrior(@Param("requestId")long requestId,@Param("inputHash")String inputHash,@Param("modelTag")String modelTag,
            @Param("modelDigest")String modelDigest,@Param("nextKeyHash")String nextKeyHash);
    @Insert("""
        INSERT INTO nx_test_cloud_zero_credit_resolution(incident_case_id,deployment_scope,request_id,executor_id,operation,
          request_key_hash,request_body_hash,owner_id,device_id,instance_no,task_config_id,task_no,request_generation,
          case_set_sha256,quiescence_sha256,backend_instance_id,a2_operation_id,outcome,task_status,snapshot_json,resolved_at)
        VALUES(#{caseId},'TEST',#{requestId},#{executorId},'COMPLETE',#{requestKeyHash},#{requestBodyHash},#{ownerId},#{deviceId},
          #{instanceNo},#{taskConfigId},#{taskNo},#{requestGeneration},#{caseSetSha256},#{quiescenceSha256},#{backendInstanceId},
          #{a2OperationId},'ZERO_CREDIT_CANCELLED','CANCELLED',#{snapshotJson},#{now})
        """)
    int insertResolution(Map<String,Object> values);
}
