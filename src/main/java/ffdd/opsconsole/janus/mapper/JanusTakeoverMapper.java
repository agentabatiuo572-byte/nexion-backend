package ffdd.opsconsole.janus.mapper;

import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@SuppressWarnings("MybatisPlusBaseMapper")
public interface JanusTakeoverMapper {
    String COLUMNS = """
            sid,phase,command_id AS commandId,command_type AS commandType,
            command_version AS commandVersion,delivery_attempts AS deliveryAttempts,
            expected_target_id AS expectedTargetId,expected_target_version AS expectedTargetVersion,
            expected_target_catalog_version AS expectedTargetCatalogVersion,
            actual_target_id AS actualTargetId,actual_target_version AS actualTargetVersion,
            actual_target_catalog_version AS actualTargetCatalogVersion,
            device_applied_version AS deviceAppliedVersion,device_app_version AS deviceAppVersion,
            handoff_receipt AS handoffReceipt,cause_request_id AS causeRequestId,
            cause_audit_id AS causeAuditId,cause_decision_id AS causeDecisionId,
            failure_code AS failureCode,failure_class AS failureClass,failure_phase AS failurePhase,
            failure_message AS failureMessage,reconciliation_id AS reconciliationId,
            CAST(UNIX_TIMESTAMP(reconciliation_requested_at)*1000 AS UNSIGNED) AS reconciliationRequestedAt,
            CAST(UNIX_TIMESTAMP(reconciled_at)*1000 AS UNSIGNED) AS reconciledAt,
            CAST(UNIX_TIMESTAMP(requested_at)*1000 AS UNSIGNED) AS requestedAt,
            CAST(UNIX_TIMESTAMP(acknowledged_at)*1000 AS UNSIGNED) AS acknowledgedAt,
            row_version AS rowVersion
            """;

    @Select("SELECT " + COLUMNS + " FROM nx_janus_takeover_execution WHERE sid=#{sid}")
    Map<String,Object> find(@Param("sid") String sid);

    @Select("SELECT " + COLUMNS + " FROM nx_janus_takeover_execution WHERE sid=#{sid} FOR UPDATE")
    Map<String,Object> findForUpdate(@Param("sid") String sid);

    @Select("SELECT COUNT(1) FROM nx_janus_device WHERE sid=#{sid} AND user_id=#{userId} AND device_id=#{deviceId}")
    int owns(@Param("userId") long userId,@Param("sid") String sid,@Param("deviceId") String deviceId);

    @Insert("""
            INSERT INTO nx_janus_takeover_execution(
              sid,phase,command_id,command_type,command_version,delivery_attempts,
              expected_target_id,expected_target_version,expected_target_catalog_version,
              cause_request_id,cause_decision_id,requested_at,row_version)
            VALUES(#{sid},'COMMAND_PENDING_ACK',#{commandId},'ACTIVATE',1,1,
              #{targetId},#{targetVersion},#{catalogVersion},#{causeRequestId},#{causeDecisionId},NOW(3),0)
            ON DUPLICATE KEY UPDATE phase='COMMAND_PENDING_ACK',command_id=VALUES(command_id),
              command_type='ACTIVATE',command_version=command_version+1,delivery_attempts=1,
              expected_target_id=VALUES(expected_target_id),expected_target_version=VALUES(expected_target_version),
              expected_target_catalog_version=VALUES(expected_target_catalog_version),
              actual_target_id=NULL,actual_target_version=NULL,actual_target_catalog_version=NULL,
              device_applied_version=NULL,device_app_version=NULL,handoff_receipt=NULL,
              failure_code=NULL,failure_class=NULL,
              failure_phase=NULL,failure_message=NULL,cause_request_id=VALUES(cause_request_id),
              cause_decision_id=VALUES(cause_decision_id),requested_at=NOW(3),acknowledged_at=NULL,
              reconciliation_id=NULL,reconciliation_requested_at=NULL,reconciled_at=NULL,row_version=row_version+1
            """)
    int activate(@Param("sid") String sid,@Param("commandId") String commandId,
                 @Param("targetId") String targetId,@Param("targetVersion") Integer targetVersion,
                 @Param("catalogVersion") Long catalogVersion,@Param("causeRequestId") String causeRequestId,
                 @Param("causeDecisionId") String causeDecisionId);

    @Update("""
            UPDATE nx_janus_takeover_execution SET phase=#{phase},command_id=#{commandId},command_type=#{commandType},
              command_version=command_version+1,delivery_attempts=1,
              expected_target_id=#{targetId},expected_target_version=#{targetVersion},
              expected_target_catalog_version=#{catalogVersion},actual_target_id=NULL,actual_target_version=NULL,
              actual_target_catalog_version=NULL,device_applied_version=NULL,device_app_version=NULL,handoff_receipt=NULL,
              failure_code=NULL,failure_class=NULL,failure_phase=NULL,failure_message=NULL,
              cause_request_id=#{causeRequestId},requested_at=NOW(3),acknowledged_at=NULL,
              reconciliation_id=NULL,reconciliation_requested_at=NULL,reconciled_at=NULL,row_version=row_version+1
            WHERE sid=#{sid} AND row_version=#{expectedVersion}
            """)
    int replaceCommand(@Param("sid") String sid,@Param("expectedVersion") long expectedVersion,
                       @Param("phase") String phase,@Param("commandId") String commandId,
                       @Param("commandType") String commandType,@Param("targetId") String targetId,
                       @Param("targetVersion") Integer targetVersion,@Param("catalogVersion") Long catalogVersion,
                       @Param("causeRequestId") String causeRequestId);

    @Update("""
            UPDATE nx_janus_takeover_execution SET delivery_attempts=delivery_attempts+1,requested_at=NOW(3),
              row_version=row_version+1 WHERE sid=#{sid} AND row_version=#{expectedVersion}
              AND phase='REVOKE_PENDING_ACK' AND command_type='REVOKE'
            """)
    int resendRevoke(@Param("sid") String sid,@Param("expectedVersion") long expectedVersion);

    @Update("""
            UPDATE nx_janus_takeover_execution SET phase=#{toPhase},actual_target_id=COALESCE(#{actualTargetId},actual_target_id),
              actual_target_version=COALESCE(#{actualTargetVersion},actual_target_version),
              actual_target_catalog_version=COALESCE(#{actualTargetCatalogVersion},actual_target_catalog_version),
              device_applied_version=COALESCE(#{deviceAppliedVersion},device_applied_version),
              device_app_version=COALESCE(#{deviceAppVersion},device_app_version),
              handoff_receipt=COALESCE(#{handoffReceipt},handoff_receipt),
              acknowledged_at=CASE WHEN acknowledged_at IS NULL AND #{toPhase}<>'COMMAND_PENDING_ACK' THEN NOW(3) ELSE acknowledged_at END,
              failure_code=#{failureCode},failure_class=#{failureClass},
              failure_phase=CASE WHEN #{failureClass} IS NULL THEN NULL ELSE #{fromPhase} END,
              failure_message=#{failureMessage},row_version=row_version+1
            WHERE sid=#{sid} AND row_version=#{expectedVersion} AND command_id=#{commandId}
              AND command_version=#{commandVersion} AND phase=#{fromPhase}
            """)
    int progress(@Param("sid") String sid,@Param("expectedVersion") long expectedVersion,
                 @Param("commandId") String commandId,@Param("commandVersion") long commandVersion,
                 @Param("fromPhase") String fromPhase,@Param("toPhase") String toPhase,
                 @Param("actualTargetId") String actualTargetId,@Param("actualTargetVersion") Integer actualTargetVersion,
                 @Param("actualTargetCatalogVersion") Long actualTargetCatalogVersion,
                 @Param("deviceAppliedVersion") Long deviceAppliedVersion,
                 @Param("deviceAppVersion") String deviceAppVersion,@Param("handoffReceipt") String handoffReceipt,
                 @Param("failureCode") String failureCode,
                 @Param("failureClass") String failureClass,@Param("failureMessage") String failureMessage);

    @Update("""
            UPDATE nx_janus_takeover_execution SET reconciliation_id=#{reconciliationId},
              reconciliation_requested_at=NOW(3),reconciled_at=NULL,row_version=row_version+1
            WHERE sid=#{sid} AND row_version=#{expectedVersion}
            """)
    int requestReconciliation(@Param("sid") String sid,@Param("expectedVersion") long expectedVersion,
                              @Param("reconciliationId") String reconciliationId);

    @Update("""
            UPDATE nx_janus_takeover_execution SET actual_target_id=#{actualTargetId},
              actual_target_version=#{actualTargetVersion},actual_target_catalog_version=#{actualTargetCatalogVersion},
              device_applied_version=#{deviceAppliedVersion},device_app_version=#{deviceAppVersion},
              handoff_receipt=#{handoffReceipt},
              reconciled_at=NOW(3),row_version=row_version+1
            WHERE sid=#{sid} AND reconciliation_id=#{reconciliationId} AND reconciled_at IS NULL
              AND command_id=#{commandId} AND command_version=#{commandVersion}
            """)
    int reconcile(@Param("sid") String sid,@Param("reconciliationId") String reconciliationId,
                  @Param("commandId") String commandId,@Param("commandVersion") long commandVersion,
                  @Param("actualTargetId") String actualTargetId,@Param("actualTargetVersion") Integer actualTargetVersion,
                  @Param("actualTargetCatalogVersion") Long actualTargetCatalogVersion,
                  @Param("deviceAppliedVersion") Long deviceAppliedVersion,
                  @Param("deviceAppVersion") String deviceAppVersion,@Param("handoffReceipt") String handoffReceipt);
}
