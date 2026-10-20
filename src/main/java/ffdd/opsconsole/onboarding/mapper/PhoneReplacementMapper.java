package ffdd.opsconsole.onboarding.mapper;

import ffdd.opsconsole.common.boundary.PhoneReplacementContract.*;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/** Every writer first locks the account through the domain service. Scope is explicit on every query. */
@Mapper
@SuppressWarnings("MybatisPlusBaseMapper") // Command/fact records span tables; there is no single CRUD entity.
public interface PhoneReplacementMapper {
    @Select("SELECT COALESCE(sandbox,0) FROM nx_user WHERE id=#{userId} AND is_deleted=0")
    Integer adminUserSandbox(long userId);

    @Select("SELECT r.paused_reason FROM nx_user_device_runtime r JOIN nx_user_device d ON d.id=r.user_device_id "
            +"WHERE d.user_id=#{a.userId} AND d.id=#{deviceId} AND d.source_environment=#{a.sourceEnvironment} "
            +"AND d.run_id=#{a.runId} AND d.is_deleted=0 AND r.is_deleted=0")
    String executionPauseReason(@Param("a") Account account,@Param("deviceId") long deviceId);

    @Select("SELECT grant_id grantId,target_installation_id targetInstallationId,reason,operator_id operatorId,"
            +"JSON_UNQUOTE(JSON_EXTRACT(evidence_json,'$.reference')) evidenceRef,issue_operation_id issueOperationId,"
            +"consumed_operation_id consumedOperationId,consumed_at consumedAt FROM nx_phone_replacement_grant WHERE "
            +"user_id=#{a.userId} AND source_environment=#{a.sourceEnvironment} AND run_id=#{a.runId} "
            +"ORDER BY issued_at DESC,grant_id DESC LIMIT #{limit} OFFSET #{offset}")
    List<Map<String,Object>> grantMetadata(@Param("a") Account account,@Param("offset") int offset,@Param("limit") int limit);
    @Select("<script>SELECT resource_id resourceId,operation_id operationId,command_type commandType,status,audit_ref auditRef "
            +"FROM nx_phone_replacement_operation WHERE user_id=#{a.userId} AND source_environment=#{a.sourceEnvironment} "
            +"AND run_id=#{a.runId} AND resource_id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            +"ORDER BY submitted_at,operation_id</script>")
    List<Map<String,Object>> resourceOperations(@Param("a") Account account,@Param("ids") List<String> ids);

    String SCOPE = "user_id=#{a.userId} AND source_environment=#{a.sourceEnvironment} AND run_id=#{a.runId}";
    @Select("SELECT operation_id operationId,command_type commandType,status,submitted_at submittedAt "
            +"FROM nx_phone_replacement_operation WHERE "+SCOPE+" AND status='PENDING' ORDER BY submitted_at,operation_id LIMIT 100")
    List<Map<String,Object>> pendingOperations(@Param("a") Account account);
    String REQUEST_COLUMNS = """
        request_id requestId,request_no requestNo,user_id userId,source_environment sourceEnvironment,run_id runId,
        target_installation_id targetInstallationId,binding_version bindingVersion,reason,created_at createdAt,
        expires_at expiresAt,status,grant_id grantId,support_ref supportRef,support_link_status supportLinkStatus,
        close_reason closeReason,closed_at closedAt
        """;
    String GRANT_COLUMNS = """
        grant_id grantId,request_id requestId,user_id userId,source_environment sourceEnvironment,run_id runId,
        target_installation_id targetInstallationId,binding_version bindingVersion,waive_count waiveCount,
        waive_interval waiveInterval,issued_at issuedAt,expires_at expiresAt,status,reason,evidence_json evidenceJson,
        operator_id operatorId,consumed_operation_id consumedOperationId,consumed_at consumedAt,terminal_at terminalAt
        """;
    String OP_COLUMNS = """
        operation_id operationId,user_id userId,source_environment sourceEnvironment,run_id runId,
        actor_type actorType,actor_id actorId,command_type commandType,idempotency_key idempotencyKey,
        request_hash requestHash,status,submitted_at submittedAt,completed_at completedAt,
        original_result_json originalResultJson,resource_type resourceType,resource_id resourceId,audit_ref auditRef
        """;

    @Select("SELECT " + GRANT_COLUMNS + " FROM nx_phone_replacement_grant WHERE " + SCOPE +
            " ORDER BY issued_at DESC,grant_id DESC LIMIT #{limit} OFFSET #{offset}")
    List<Grant> grants(@Param("a") Account account, @Param("offset") int offset, @Param("limit") int limit);

    @Select("SELECT " + OP_COLUMNS + " FROM nx_phone_replacement_operation WHERE operation_id=#{id} AND actor_type='ADMIN'")
    Operation adminOperationContext(String id);
    @Select("SELECT " + OP_COLUMNS + " FROM nx_phone_replacement_operation WHERE actor_type='ADMIN' AND actor_id=#{actorId} AND command_type=#{type} AND idempotency_key=#{key} AND source_environment=#{environment} AND run_id=#{run}")
    Operation adminOperationContextByKey(@Param("actorId") long actorId, @Param("type") String type, @Param("key") String key,
                                        @Param("environment") String environment,@Param("run") String run);

    @Select("SELECT config_key configKey,config_value configValue FROM nx_config_item WHERE " +
            "config_key IN ('E.compute.phoneBinding.allowReplacement','E.compute.phoneBinding.minReplacementIntervalDays'," +
            "'E.compute.phoneBinding.replacementWindowDays','E.compute.phoneBinding.maxReplacementsInWindow'," +
            "'E.compute.phoneBinding.policyVersion') AND status=1 AND is_deleted=0 ORDER BY config_key")
    List<Config> policy();
    @Select("SELECT config_key configKey,config_value configValue FROM nx_config_item WHERE " +
            "config_key IN ('E.compute.phoneBinding.allowReplacement','E.compute.phoneBinding.minReplacementIntervalDays'," +
            "'E.compute.phoneBinding.replacementWindowDays','E.compute.phoneBinding.maxReplacementsInWindow'," +
            "'E.compute.phoneBinding.policyVersion') AND status=1 AND is_deleted=0 ORDER BY config_key FOR SHARE")
    List<Config> lockPolicy();
    @Select("SELECT config_key configKey,config_value configValue FROM nx_config_item WHERE " +
            "config_key IN ('E.compute.phoneBinding.allowReplacement','E.compute.phoneBinding.minReplacementIntervalDays'," +
            "'E.compute.phoneBinding.replacementWindowDays','E.compute.phoneBinding.maxReplacementsInWindow'," +
            "'E.compute.phoneBinding.policyVersion') AND status=1 AND is_deleted=0 ORDER BY config_key FOR UPDATE")
    List<Config> lockPublicationPolicy();
    record Config(String configKey, String configValue) { }

    @Select("SELECT status FROM nx_user WHERE id=#{userId} AND is_deleted=0 FOR UPDATE")
    String lockUser(long userId);
    @Select("SELECT installation_id installationId,user_device_id userDeviceId,hardware_key hardwareKey," +
            "CAST(UNIX_TIMESTAMP(changed_at)*1000 AS SIGNED) changedAt,version FROM nx_phone_binding WHERE " + SCOPE)
    Binding binding(@Param("a") Account account);
    @Select("SELECT installation_id installationId,user_device_id userDeviceId,hardware_key hardwareKey," +
            "CAST(UNIX_TIMESTAMP(changed_at)*1000 AS SIGNED) changedAt,version FROM nx_phone_binding WHERE " + SCOPE + " FOR UPDATE")
    Binding lockBinding(@Param("a") Account account);
    @Insert("INSERT IGNORE INTO nx_phone_replacement_history_state(user_id,source_environment,run_id) " +
            "VALUES(#{a.userId},#{a.sourceEnvironment},#{a.runId})")
    int ensureState(@Param("a") Account account);
    @Select("SELECT history_complete_from completeFrom,all_history_known allHistoryKnown," +
            "changed_at_verified changedAtVerified,evidence_ref evidenceRef FROM nx_phone_replacement_history_state WHERE " + SCOPE)
    State state(@Param("a") Account account);
    @Select("SELECT history_complete_from completeFrom,all_history_known allHistoryKnown," +
            "changed_at_verified changedAtVerified,evidence_ref evidenceRef FROM nx_phone_replacement_history_state WHERE " + SCOPE + " FOR SHARE")
    State currentState(@Param("a") Account account);
    record State(Long completeFrom, boolean allHistoryKnown, boolean changedAtVerified, String evidenceRef) { }
    @Update("UPDATE nx_phone_replacement_history_state SET changed_at_verified=1," +
            "all_history_known=IF(#{first},1,all_history_known)," +
            "history_complete_from=IF(#{first},#{now},history_complete_from) WHERE " + SCOPE)
    int markBindingTime(@Param("a") Account account, @Param("first") boolean first, @Param("now") long now);
    @Update("UPDATE nx_phone_replacement_history_state SET history_complete_from=#{from},evidence_ref=#{evidence} " +
            "WHERE " + SCOPE + " AND (history_complete_from IS NULL OR history_complete_from>#{from})")
    int establishHistory(@Param("a") Account account, @Param("from") long from, @Param("evidence") String evidence);

    @Select("SELECT committed_at FROM nx_phone_replacement_event WHERE " + SCOPE +
            " AND committed_at>#{after} AND committed_at<=#{now} ORDER BY committed_at,event_id")
    List<Long> eventTimes(@Param("a") Account account, @Param("after") long after, @Param("now") long now);
    @Select("SELECT committed_at FROM nx_phone_replacement_event WHERE " + SCOPE +
            " AND committed_at>#{after} AND committed_at<=#{now} ORDER BY committed_at,event_id FOR SHARE")
    List<Long> currentEventTimes(@Param("a") Account account, @Param("after") long after, @Param("now") long now);
    @Select("SELECT event_id eventId,operation_id operationId,old_binding_version oldBindingVersion," +
            "new_binding_version newBindingVersion,committed_at committedAt,policy_version policyVersion,grant_id grantId," +
            "source_kind sourceKind,source_id sourceId,(SELECT o.audit_ref FROM nx_phone_replacement_operation o " +
            "WHERE o.operation_id=nx_phone_replacement_event.operation_id AND o.user_id=nx_phone_replacement_event.user_id " +
            "AND o.source_environment=nx_phone_replacement_event.source_environment AND o.run_id=nx_phone_replacement_event.run_id) auditRef " +
            "FROM nx_phone_replacement_event WHERE " + SCOPE +
            " ORDER BY committed_at DESC,event_id DESC LIMIT #{limit} OFFSET #{offset}")
    List<Map<String,Object>> events(@Param("a") Account account, @Param("offset") int offset, @Param("limit") int limit);
    @Insert("""
        INSERT INTO nx_phone_replacement_event(event_id,user_id,source_environment,run_id,operation_id,
         old_installation_id,new_installation_id,old_user_device_id,new_user_device_id,old_binding_version,
         new_binding_version,committed_at,policy_version,grant_id,source_kind,source_id)
        VALUES(#{eventId},#{a.userId},#{a.sourceEnvironment},#{a.runId},#{operationId},#{old.installationId},
         #{fresh.installationId},#{old.userDeviceId},#{fresh.userDeviceId},#{old.version},#{fresh.version},
         #{now},#{policyVersion},#{grantId},#{sourceKind},#{sourceId})
        """)
    int insertEvent(@Param("a") Account account,@Param("eventId") String eventId,@Param("operationId") String operationId,
                    @Param("old") Binding oldBinding,@Param("fresh") Binding newBinding,@Param("now") long now,
                    @Param("policyVersion") long policyVersion,@Param("grantId") String grantId,
                    @Param("sourceKind") String sourceKind,@Param("sourceId") String sourceId);
    @Select("SELECT event_id FROM nx_phone_replacement_event WHERE " + SCOPE + " AND source_kind=#{kind} AND source_id=#{id} FOR SHARE")
    String eventBySource(@Param("a") Account account,@Param("kind") String kind,@Param("id") String id);

    @Select("SELECT " + OP_COLUMNS + " FROM nx_phone_replacement_operation WHERE " + SCOPE + " AND operation_id=#{id}")
    Operation operation(@Param("a") Account account,@Param("id") String id);
    @Select("SELECT " + OP_COLUMNS + " FROM nx_phone_replacement_operation WHERE " + SCOPE + " AND operation_id=#{id} FOR SHARE")
    Operation currentOperation(@Param("a") Account account,@Param("id") String id);
    @Select("SELECT " + OP_COLUMNS + " FROM nx_phone_replacement_operation WHERE source_environment=#{a.sourceEnvironment}" +
            " AND run_id=#{a.runId} AND actor_type=#{actor.type} AND actor_id=#{actor.id} AND command_type=#{type} AND idempotency_key=#{key}")
    Operation operationByKey(@Param("a") Account account,@Param("actor") Actor actor,
                            @Param("type") String commandType,@Param("key") String key);
    @Select("SELECT " + OP_COLUMNS + " FROM nx_phone_replacement_operation WHERE source_environment=#{a.sourceEnvironment}" +
            " AND run_id=#{a.runId} AND actor_type=#{actor.type} AND actor_id=#{actor.id} AND command_type=#{type} AND idempotency_key=#{key} FOR SHARE")
    Operation currentOperationByKey(@Param("a") Account account,@Param("actor") Actor actor,
                                   @Param("type") String commandType,@Param("key") String key);
    @Insert("""
        INSERT INTO nx_phone_replacement_operation(operation_id,user_id,source_environment,run_id,actor_type,actor_id,
          command_type,idempotency_key,request_hash,status,submitted_at)
        VALUES(#{id},#{a.userId},#{a.sourceEnvironment},#{a.runId},#{actor.type},#{actor.id},#{type},#{key},#{hash},'PENDING',#{now})
        """)
    int insertOperation(@Param("a") Account account,@Param("actor") Actor actor,@Param("id") String id,
                        @Param("type") String type,@Param("key") String key,@Param("hash") String hash,@Param("now") long now);
    @Update("UPDATE nx_phone_replacement_operation SET status=#{status},completed_at=#{now},original_result_json=#{json}," +
            "resource_type=#{resourceType},resource_id=#{resourceId},audit_ref=#{auditRef} WHERE " + SCOPE +
            " AND operation_id=#{id} AND status='PENDING'")
    int finishOperation(@Param("a") Account account,@Param("id") String id,@Param("status") String status,
                        @Param("now") long now,@Param("json") String json,@Param("resourceType") String resourceType,
                        @Param("resourceId") String resourceId,@Param("auditRef") String auditRef);

    @Select("SELECT " + REQUEST_COLUMNS + " FROM nx_phone_replacement_request WHERE " + SCOPE + " AND request_id=#{id}")
    Request request(@Param("a") Account account,@Param("id") String id);
    @Select("SELECT " + REQUEST_COLUMNS + " FROM nx_phone_replacement_request WHERE " + SCOPE + " AND request_id=#{id} FOR SHARE")
    Request currentRequest(@Param("a") Account account,@Param("id") String id);
    @Select("<script>SELECT " + REQUEST_COLUMNS + " FROM nx_phone_replacement_request WHERE " + SCOPE +
            "<if test='installation != null'> AND target_installation_id=#{installation}</if>" +
            " ORDER BY created_at DESC,request_id DESC LIMIT #{limit} OFFSET #{offset}</script>")
    List<Request> requests(@Param("a") Account account,@Param("installation") String installation,
                           @Param("offset") int offset,@Param("limit") int limit);
    @Select("<script>SELECT " + REQUEST_COLUMNS + " FROM nx_phone_replacement_request WHERE " + SCOPE +
            "<if test='installation != null'> AND target_installation_id=#{installation}</if>" +
            " ORDER BY created_at DESC,request_id DESC LIMIT #{limit} OFFSET #{offset} FOR SHARE</script>")
    List<Request> currentRequests(@Param("a") Account account,@Param("installation") String installation,
                                 @Param("offset") int offset,@Param("limit") int limit);
    @Select("SELECT " + REQUEST_COLUMNS + " FROM nx_phone_replacement_request WHERE " + SCOPE +
            " AND target_installation_id=#{installation} AND binding_version=#{version} AND status='PENDING'" +
            " ORDER BY created_at,request_id LIMIT 1")
    Request pendingRequest(@Param("a") Account account,@Param("installation") String installation,@Param("version") long version);
    @Select("SELECT " + REQUEST_COLUMNS + " FROM nx_phone_replacement_request WHERE " + SCOPE +
            " AND target_installation_id=#{installation} AND binding_version=#{version} AND status='PENDING'" +
            " ORDER BY created_at,request_id LIMIT 1 FOR SHARE")
    Request currentPendingRequest(@Param("a") Account account,@Param("installation") String installation,@Param("version") long version);
    @Insert("""
        INSERT INTO nx_phone_replacement_request(request_id,request_no,user_id,source_environment,run_id,
         target_installation_id,binding_version,reason,created_at,expires_at,status,create_operation_id)
        VALUES(#{id},#{no},#{a.userId},#{a.sourceEnvironment},#{a.runId},#{installation},#{version},
         #{reason},#{now},#{expires},'PENDING',#{operationId})
        """)
    int insertRequest(@Param("a") Account account,@Param("id") String id,@Param("no") String no,
                      @Param("installation") String installation,@Param("version") long version,@Param("reason") String reason,
                      @Param("now") long now,@Param("expires") long expires,@Param("operationId") String operationId);
    @Update("UPDATE nx_phone_replacement_request SET status=CASE WHEN binding_version<>#{version} THEN 'STALE' ELSE 'EXPIRED' END " +
            "WHERE " + SCOPE + " AND status='PENDING' AND (binding_version<>#{version} OR expires_at<=#{now})")
    int expireRequests(@Param("a") Account account,@Param("version") long version,@Param("now") long now);
    @Update("UPDATE nx_phone_replacement_request SET status=#{status},grant_id=#{grantId}," +
            "close_reason=#{reason},closed_at=#{closedAt} WHERE " + SCOPE + " AND request_id=#{id} AND status='PENDING'")
    int transitionRequest(@Param("a") Account account,@Param("id") String id,@Param("status") String status,
                          @Param("grantId") String grantId,@Param("reason") String reason,@Param("closedAt") Long closedAt);
    @Update("UPDATE nx_phone_replacement_request SET support_ref=#{ref},support_link_status='LINKED' " +
            "WHERE " + SCOPE + " AND request_id=#{id} AND status IN ('PENDING','GRANTED') " +
            "AND (support_ref IS NULL OR support_ref=#{ref})")
    int linkSupport(@Param("a") Account account,@Param("id") String id,@Param("ref") String supportRef);

    @Select("SELECT " + GRANT_COLUMNS + " FROM nx_phone_replacement_grant WHERE " + SCOPE + " AND grant_id=#{id}")
    Grant grant(@Param("a") Account account,@Param("id") String id);
    @Select("SELECT " + GRANT_COLUMNS + " FROM nx_phone_replacement_grant WHERE " + SCOPE + " AND grant_id=#{id} FOR SHARE")
    Grant currentGrant(@Param("a") Account account,@Param("id") String id);
    @Select("SELECT " + GRANT_COLUMNS + " FROM nx_phone_replacement_grant WHERE " + SCOPE +
            " AND status='ACTIVE' ORDER BY grant_id FOR UPDATE")
    List<Grant> lockActiveGrants(@Param("a") Account account);
    @Select("SELECT " + GRANT_COLUMNS + " FROM nx_phone_replacement_grant WHERE " + SCOPE +
            " AND status='ACTIVE' ORDER BY issued_at DESC,grant_id DESC")
    List<Grant> activeGrants(@Param("a") Account account);
    @Insert("""
        INSERT INTO nx_phone_replacement_grant(grant_id,request_id,user_id,source_environment,run_id,target_installation_id,
         binding_version,waive_count,waive_interval,issued_at,expires_at,status,reason,evidence_json,operator_id,issue_operation_id)
        VALUES(#{id},#{request.requestId},#{a.userId},#{a.sourceEnvironment},#{a.runId},#{request.targetInstallationId},
         #{request.bindingVersion},#{count},#{interval},#{now},#{expires},'ACTIVE',#{reason},#{evidence},#{actorId},#{operationId})
        """)
    int insertGrant(@Param("a") Account account,@Param("request") Request request,@Param("id") String id,
                    @Param("count") boolean waiveCount,@Param("interval") boolean waiveInterval,@Param("now") long now,
                    @Param("expires") long expires,@Param("reason") String reason,@Param("evidence") String evidenceJson,
                    @Param("actorId") long actorId,@Param("operationId") String operationId);
    @Update("UPDATE nx_phone_replacement_grant SET status=#{status},terminal_at=#{now}," +
            "consumed_operation_id=#{operationId},consumed_at=#{consumedAt} WHERE " + SCOPE +
            " AND grant_id=#{id} AND status='ACTIVE'")
    int transitionGrant(@Param("a") Account account,@Param("id") String id,@Param("status") String status,
                        @Param("now") long now,@Param("operationId") String consumedOperation,@Param("consumedAt") Long consumedAt);
}
