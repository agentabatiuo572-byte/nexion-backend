package ffdd.opsconsole.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.platform.infrastructure.AdminSecurityBaselineEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface AdminSecurityBaselineMapper extends BaseMapper<AdminSecurityBaselineEntity> {
    @Update("""
            CREATE TABLE IF NOT EXISTS nx_admin_security_baseline (
              id BIGINT AUTO_INCREMENT PRIMARY KEY,
              baseline_key VARCHAR(64) NOT NULL,
              label VARCHAR(128) NOT NULL,
              description VARCHAR(512) NOT NULL DEFAULT '',
              baseline_value VARCHAR(128) NOT NULL,
              locked TINYINT NOT NULL DEFAULT 0,
              sort_order INT NOT NULL DEFAULT 9999,
              status TINYINT NOT NULL DEFAULT 1,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_admin_security_baseline_key (baseline_key),
              KEY idx_admin_security_baseline_status_sort (status, sort_order)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createSecurityBaselineTable();

    @Select("""
            SELECT *
              FROM nx_admin_security_baseline
             WHERE baseline_key = #{baselineKey}
               AND status = 1
               AND is_deleted = 0
             LIMIT 1
            """)
    AdminSecurityBaselineEntity selectActiveByKey(@Param("baselineKey") String baselineKey);

    @Insert("""
            INSERT INTO nx_admin_security_baseline (
              baseline_key, label, description, baseline_value, locked, sort_order, status, is_deleted
            ) VALUES (
              #{baselineKey}, #{label}, #{description}, #{baselineValue}, #{locked}, #{sortOrder}, 1, 0
            )
            ON DUPLICATE KEY UPDATE
              label = VALUES(label),
              description = VALUES(description),
              baseline_value = VALUES(baseline_value),
              locked = VALUES(locked),
              sort_order = VALUES(sort_order),
              status = 1,
              updated_at = NOW(),
              is_deleted = 0
            """)
    int upsertBaseline(
            @Param("baselineKey") String baselineKey,
            @Param("label") String label,
            @Param("description") String description,
            @Param("baselineValue") String baselineValue,
            @Param("locked") int locked,
            @Param("sortOrder") int sortOrder);

    @Update("""
            UPDATE nx_admin_security_baseline
               SET baseline_value = #{baselineValue},
                   updated_at = NOW()
             WHERE baseline_key = #{baselineKey}
               AND status = 1
               AND is_deleted = 0
            """)
    int upsertValue(@Param("baselineKey") String baselineKey, @Param("baselineValue") String baselineValue);
}
