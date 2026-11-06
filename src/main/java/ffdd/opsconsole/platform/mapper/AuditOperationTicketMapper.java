package ffdd.opsconsole.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.platform.infrastructure.AuditOperationTicketEntity;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface AuditOperationTicketMapper extends BaseMapper<AuditOperationTicketEntity> {
    @Update("""
            CREATE TABLE IF NOT EXISTS nx_audit_operation_ticket (
              id BIGINT AUTO_INCREMENT PRIMARY KEY,
              operation_id VARCHAR(64) NOT NULL,
              action VARCHAR(160) NOT NULL,
              object_text VARCHAR(255) NOT NULL,
              before_value VARCHAR(128) NOT NULL,
              after_value VARCHAR(128) NOT NULL,
              operator_name VARCHAR(128) NOT NULL,
              operator_role VARCHAR(32) NOT NULL,
              operation_type VARCHAR(32) NOT NULL,
              amplifies TINYINT NOT NULL DEFAULT 0,
              sos TINYINT NOT NULL DEFAULT 0,
              time_label VARCHAR(32) NOT NULL,
              mine TINYINT NOT NULL DEFAULT 0,
              role_gate VARCHAR(255) NOT NULL,
              reason VARCHAR(512) NOT NULL,
              status VARCHAR(32) NOT NULL DEFAULT 'pending',
              decision_reason VARCHAR(512) NULL,
              decided_at DATETIME NULL,
              command_json MEDIUMTEXT NULL COMMENT '结构化回放指令 {domain,op,params}',
              source_domain VARCHAR(8) NOT NULL DEFAULT 'A',
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_audit_operation_ticket_no (operation_id),
              KEY idx_audit_operation_ticket_status (status, created_at),
              KEY idx_audit_operation_ticket_type (operation_type, status),
              KEY idx_audit_operation_ticket_domain (source_domain, status, created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createTicketTable();

    @Select("""
            SELECT *
            FROM nx_audit_operation_ticket
            WHERE operation_id = #{operationId}
              AND is_deleted = 0
            LIMIT 1
            """)
    AuditOperationTicketEntity selectActiveByOperationId(@Param("operationId") String operationId);

    /** Serializes terminal decisions and clears pre-lock reads before rechecking authorization. */
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("""
            SELECT *
            FROM nx_audit_operation_ticket
            WHERE operation_id = #{operationId}
              AND is_deleted = 0
            LIMIT 1
            FOR UPDATE
            """)
    AuditOperationTicketEntity selectActiveByOperationIdForUpdate(@Param("operationId") String operationId);

    /** A negative maker ID signals conflicting authenticated origins across the hot and cold audit. */
    @Select("""
            <script>
            SELECT operationId, IF(MIN(actorId)=MAX(actorId), MIN(actorId), -1) AS makerId
              FROM (
                SELECT resource_id AS operationId, actor_id AS actorId
                  FROM nx_audit_log
                 WHERE is_deleted=0 AND resource_type='A2_OPERATION' AND result='SUCCESS'
                   AND actor_type='ADMIN' AND actor_id &gt; 0
                   AND action IN ('A2_OPERATION_PROPOSED','A2_OPERATION_EXECUTED')
                   AND resource_id IN
                   <foreach collection="operationIds" item="id" open="(" separator="," close=")">#{id}</foreach>
                UNION ALL
                SELECT JSON_UNQUOTE(JSON_EXTRACT(cold_payload,'$.resourceId')) AS operationId,
                       CAST(JSON_UNQUOTE(JSON_EXTRACT(cold_payload,'$.actorId')) AS SIGNED) AS actorId
                  FROM nx_audit_log_archive
                 WHERE content_sha256 IS NOT NULL
                   AND JSON_UNQUOTE(JSON_EXTRACT(cold_payload,'$.resourceType'))='A2_OPERATION'
                   AND JSON_UNQUOTE(JSON_EXTRACT(cold_payload,'$.result'))='SUCCESS'
                   AND JSON_UNQUOTE(JSON_EXTRACT(cold_payload,'$.actorType'))='ADMIN'
                   AND JSON_TYPE(JSON_EXTRACT(cold_payload,'$.actorId')) IN ('INTEGER','UNSIGNED INTEGER')
                   AND CAST(JSON_UNQUOTE(JSON_EXTRACT(cold_payload,'$.actorId')) AS SIGNED) &gt; 0
                   AND JSON_UNQUOTE(JSON_EXTRACT(cold_payload,'$.action')) IN ('A2_OPERATION_PROPOSED','A2_OPERATION_EXECUTED')
                   AND JSON_UNQUOTE(JSON_EXTRACT(cold_payload,'$.resourceId')) IN
                   <foreach collection="operationIds" item="id" open="(" separator="," close=")">#{id}</foreach>
              ) origins
             GROUP BY operationId
            </script>
            """)
    List<MakerAudit> selectMakerAudits(@Param("operationIds") Collection<String> operationIds);

    record MakerAudit(String operationId, Long makerId) {}
}
