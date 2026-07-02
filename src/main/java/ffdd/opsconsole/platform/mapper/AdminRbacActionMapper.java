package ffdd.opsconsole.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.platform.infrastructure.AdminRbacActionEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface AdminRbacActionMapper extends BaseMapper<AdminRbacActionEntity> {
    @Update("""
            CREATE TABLE IF NOT EXISTS nx_admin_rbac_action (
              id BIGINT AUTO_INCREMENT PRIMARY KEY,
              action_id VARCHAR(96) NOT NULL,
              action_name VARCHAR(160) NOT NULL,
              domain_group VARCHAR(64) NOT NULL,
              sort_order INT NOT NULL DEFAULT 9999,
              status TINYINT NOT NULL DEFAULT 1,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_admin_rbac_action_code (action_id),
              KEY idx_admin_rbac_action_status_sort (status, sort_order)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createActionTable();

    @Insert("""
            INSERT INTO nx_admin_rbac_action (
              action_id, action_name, domain_group, sort_order, status, is_deleted
            ) VALUES (
              #{actionId}, #{actionName}, #{domainGroup}, #{sortOrder}, 1, 0
            )
            ON DUPLICATE KEY UPDATE
              action_name = VALUES(action_name),
              domain_group = VALUES(domain_group),
              sort_order = VALUES(sort_order),
              status = 1,
              updated_at = NOW(),
              is_deleted = 0
            """)
    int upsertAction(
            @Param("actionId") String actionId,
            @Param("actionName") String actionName,
            @Param("domainGroup") String domainGroup,
            @Param("sortOrder") int sortOrder);
}
