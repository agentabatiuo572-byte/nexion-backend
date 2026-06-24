package ffdd.opsconsole.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.platform.infrastructure.AuditOperationHistoryEntity;
import org.apache.ibatis.annotations.Update;

public interface AuditOperationHistoryMapper extends BaseMapper<AuditOperationHistoryEntity> {
    @Update("""
            CREATE TABLE IF NOT EXISTS nx_audit_operation_history (
              id BIGINT AUTO_INCREMENT PRIMARY KEY,
              operation_id VARCHAR(64) NOT NULL,
              action VARCHAR(160) NOT NULL,
              status VARCHAR(32) NOT NULL,
              chain_text VARCHAR(255) NOT NULL,
              time_label VARCHAR(32) NOT NULL,
              note VARCHAR(512) NOT NULL,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_audit_operation_history_no (operation_id),
              KEY idx_audit_operation_history_status (status, created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createHistoryTable();
}
