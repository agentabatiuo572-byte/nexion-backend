package ffdd.opsconsole.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.platform.infrastructure.AuditOperationTicketEntity;
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
              command_json TEXT NULL COMMENT '结构化回放指令 {domain,op,params}',
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_audit_operation_ticket_no (operation_id),
              KEY idx_audit_operation_ticket_status (status, created_at),
              KEY idx_audit_operation_ticket_type (operation_type, status)
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

    /** Serializes terminal decisions so a pending ticket can be replayed at most once. */
    @Select("""
            SELECT *
            FROM nx_audit_operation_ticket
            WHERE operation_id = #{operationId}
              AND is_deleted = 0
            LIMIT 1
            FOR UPDATE
            """)
    AuditOperationTicketEntity selectActiveByOperationIdForUpdate(@Param("operationId") String operationId);
}
