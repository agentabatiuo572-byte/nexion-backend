package ffdd.opsconsole.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.platform.infrastructure.AuditConfirmCategoryEntity;
import org.apache.ibatis.annotations.Update;

public interface AuditConfirmCategoryMapper extends BaseMapper<AuditConfirmCategoryEntity> {
    @Update("""
            CREATE TABLE IF NOT EXISTS nx_audit_confirm_category (
              id BIGINT AUTO_INCREMENT PRIMARY KEY,
              category_name VARCHAR(80) NOT NULL,
              examples VARCHAR(512) NOT NULL,
              role_gate VARCHAR(255) NOT NULL,
              sort_order INT NOT NULL DEFAULT 0,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_audit_confirm_category_name (category_name),
              KEY idx_audit_confirm_category_order (sort_order)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createConfirmCategoryTable();
}
