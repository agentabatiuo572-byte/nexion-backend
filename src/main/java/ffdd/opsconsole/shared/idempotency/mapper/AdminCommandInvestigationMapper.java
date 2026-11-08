package ffdd.opsconsole.shared.idempotency.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.shared.audit.AuditLogRecord;
import ffdd.opsconsole.shared.idempotency.AdminCommandInvestigationEntity;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyRecordEntity;
import java.util.List;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface AdminCommandInvestigationMapper extends BaseMapper<AdminCommandInvestigationEntity> {
    @Select("""
            SELECT * FROM nx_admin_idempotency_record
             WHERE BINARY idempotency_key=BINARY #{key} AND origin_method=#{method}
               AND BINARY origin_path=BINARY #{path} AND is_deleted=0
               AND (origin_admin_id=#{actor} OR #{investigator}=TRUE)
             ORDER BY id ASC
            """)
    List<AdminIdempotencyRecordEntity> findOriginals(@Param("key") String key, @Param("method") String method,
            @Param("path") String path, @Param("actor") long actor, @Param("investigator") boolean investigator);

    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("""
            SELECT * FROM nx_admin_idempotency_record
             WHERE scope=#{scope} AND BINARY idempotency_key=BINARY #{key} AND is_deleted=0
             LIMIT 1 FOR UPDATE
            """)
    AdminIdempotencyRecordEntity lockOriginal(@Param("scope") String scope, @Param("key") String key);

    @Select("""
            SELECT * FROM nx_admin_command_investigation
             WHERE original_record_id=#{recordId} AND is_deleted=0 ORDER BY id DESC LIMIT 1
            """)
    AdminCommandInvestigationEntity latest(@Param("recordId") long recordId);

    @Update("""
            UPDATE nx_admin_idempotency_record SET status=#{status}, updated_at=NOW()
             WHERE id=#{id} AND request_hash=#{requestHash} AND is_deleted=0
               AND status IN ('UNKNOWN','SUCCEEDED','FAILED')
            """)
    int conclude(@Param("id") long id, @Param("requestHash") String requestHash, @Param("status") String status);

    /** A supplied audit ID is only a locator: original identity and successful fact are checked on the server. */
    @Select("""
            SELECT id, action, resource_type AS resourceType, resource_id AS resourceId,
                   actor_id AS actorId, method, path, detail_json AS detailJson, created_at AS createdAt
              FROM nx_audit_log
             WHERE id=#{auditId} AND is_deleted=0 AND result='SUCCESS' AND actor_type='ADMIN'
               AND actor_id=#{original.originAdminId}
               AND method=#{original.originMethod} AND BINARY path=BINARY #{original.originPath}
               AND created_at >= #{original.createdAt}
               AND BINARY JSON_UNQUOTE(JSON_EXTRACT(detail_json,'$.idempotencyKey'))=BINARY #{original.idempotencyKey}
             LIMIT 1
            """)
    AuditLogRecord successEvidence(@Param("auditId") long auditId,
                                  @Param("original") AdminIdempotencyRecordEntity original);
}
