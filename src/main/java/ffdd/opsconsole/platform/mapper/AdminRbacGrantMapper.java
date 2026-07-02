package ffdd.opsconsole.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.platform.infrastructure.AdminRbacGrantEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface AdminRbacGrantMapper extends BaseMapper<AdminRbacGrantEntity> {
    @Update("""
            CREATE TABLE IF NOT EXISTS nx_admin_rbac_grant (
              id BIGINT AUTO_INCREMENT PRIMARY KEY,
              action_id VARCHAR(96) NOT NULL,
              role_key VARCHAR(64) NOT NULL,
              grant_value VARCHAR(8) NOT NULL,
              status TINYINT NOT NULL DEFAULT 1,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_admin_rbac_grant_action_role (action_id, role_key),
              KEY idx_admin_rbac_grant_role (role_key, status),
              CONSTRAINT fk_admin_rbac_grant_action
                FOREIGN KEY (action_id) REFERENCES nx_admin_rbac_action(action_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createGrantTable();

    @Insert("""
            INSERT INTO nx_admin_rbac_grant (
              action_id, role_key, grant_value, status, is_deleted
            ) VALUES (
              #{actionId}, #{roleKey}, #{grantValue}, 1, 0
            )
            ON DUPLICATE KEY UPDATE
              grant_value = VALUES(grant_value),
              status = 1,
              updated_at = NOW(),
              is_deleted = 0
            """)
    int upsertGrant(
            @Param("actionId") String actionId,
            @Param("roleKey") String roleKey,
            @Param("grantValue") String grantValue);
}
