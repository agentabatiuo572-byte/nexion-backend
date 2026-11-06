package ffdd.opsconsole.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.platform.infrastructure.AuditObjectLockEntity;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface AuditObjectLockMapper extends BaseMapper<AuditObjectLockEntity> {
    @Update("""
            CREATE TABLE IF NOT EXISTS nx_audit_object_lock (
              id BIGINT AUTO_INCREMENT PRIMARY KEY,
              ticket_id VARCHAR(64) NOT NULL,
              target_domain VARCHAR(4) NOT NULL,
              target_type VARCHAR(64) NOT NULL,
              target_id VARCHAR(128) NOT NULL,
              operator VARCHAR(128) NULL,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_target (target_domain, target_type, target_id),
              KEY idx_lock_ticket (ticket_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createLockTable();

    @Select("""
            SELECT COUNT(*) FROM nx_audit_object_lock
            WHERE target_domain = #{domain} AND target_type = #{type}
              AND target_id = #{targetId} AND is_deleted = 0
            """)
    int countActiveByTarget(@Param("domain") String domain, @Param("type") String type, @Param("targetId") String targetId);

    @Select("""
            SELECT COUNT(*) FROM nx_audit_object_lock
            WHERE target_domain = #{domain} AND target_type = #{type} AND target_id = #{targetId}
              AND is_deleted = 0 AND ticket_id <> #{ticketId}
            """)
    int countActiveByOtherTicket(@Param("domain") String domain, @Param("type") String type,
                                 @Param("targetId") String targetId, @Param("ticketId") String ticketId);

    @Select("""
            <script>
            SELECT l.target_id, t.command_json, t.operator_name, t.created_at
            FROM nx_audit_object_lock l
            LEFT JOIN nx_audit_operation_ticket t ON t.operation_id = l.ticket_id
              AND t.is_deleted = 0 AND t.status = 'pending'
              AND BINARY t.operator_name = BINARY l.operator
            WHERE l.target_domain = #{domain} AND l.target_type = #{type} AND l.is_deleted = 0
              AND l.target_id IN
              <foreach collection="targetIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<PendingLock> selectPendingLocks(@Param("domain") String domain, @Param("type") String type,
                                        @Param("targetIds") Collection<String> targetIds);

    record PendingLock(String targetId, String commandJson, String operatorName, LocalDateTime createdAt) {}

    @Select("""
            SELECT target_id FROM nx_audit_object_lock
            WHERE target_domain = #{domain} AND target_type = #{type} AND is_deleted = 0
            ORDER BY target_id
            """)
    java.util.List<String> selectActiveTargetIds(@Param("domain") String domain, @Param("type") String type);

    @Select("""
            SELECT * FROM nx_audit_object_lock
            WHERE ticket_id = #{ticketId} AND is_deleted = 0 LIMIT 1
            """)
    AuditObjectLockEntity selectByTicketId(@Param("ticketId") String ticketId);

    @Select("""
            SELECT * FROM nx_audit_object_lock
            WHERE ticket_id = #{ticketId} AND is_deleted = 0
            """)
    java.util.List<AuditObjectLockEntity> selectActiveByTicketId(@Param("ticketId") String ticketId);
}
