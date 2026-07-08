package ffdd.opsconsole.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.platform.infrastructure.AuditObjectLockEntity;
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
            SELECT * FROM nx_audit_object_lock
            WHERE ticket_id = #{ticketId} AND is_deleted = 0 LIMIT 1
            """)
    AuditObjectLockEntity selectByTicketId(@Param("ticketId") String ticketId);
}
