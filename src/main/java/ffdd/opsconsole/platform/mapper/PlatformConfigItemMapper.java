package ffdd.opsconsole.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.platform.domain.PlatformConfigItem;
import ffdd.opsconsole.platform.infrastructure.PlatformConfigItemEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.util.List;
import java.util.Map;

public interface PlatformConfigItemMapper extends BaseMapper<PlatformConfigItemEntity> {
    @Select("""
            SELECT config_key, config_value FROM nx_config_item
             WHERE config_key IN ('auth.session.access_ttl_hours', 'auth.session.refresh_ttl_days',
                                  'auth.session.idle_ttl_days')
               AND status = 1 AND is_deleted = 0
            """)
    List<PlatformConfigItemEntity> selectSessionLifetimes();

    @Insert("""
            INSERT IGNORE INTO nx_config_item
                (config_key, config_value, value_type, config_group, visibility, remark,
                 status, created_at, updated_at, is_deleted)
            VALUES
                (#{configKey}, #{configValue}, #{valueType}, #{configGroup}, #{visibility}, #{remark},
                 #{status}, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
            """)
    int insertIfConfigKeyAbsent(
            @Param("configKey") String configKey,
            @Param("configValue") String configValue,
            @Param("valueType") String valueType,
            @Param("configGroup") String configGroup,
            @Param("visibility") String visibility,
            @Param("remark") String remark,
            @Param("status") Integer status);

    @Update("""
            UPDATE nx_config_item
               SET config_value = #{item.configValue}, value_type = #{item.valueType}, config_group = #{item.configGroup},
                   visibility = #{item.visibility}, remark = #{item.remark}, status = 1, is_deleted = 0,
                   updated_at = #{item.updatedAt}
             WHERE config_key = #{item.configKey} AND (status <> 1 OR is_deleted <> 0)
            """)
    int reactivateConfigIfInactive(@Param("item") PlatformConfigItem item);

    @Select("""
            SELECT id, config_key, config_value, value_type, config_group, visibility, remark,
                   status, created_at, updated_at, is_deleted
              FROM nx_config_item
             WHERE config_key = #{configKey}
               AND status = 1
               AND is_deleted = 0
             LIMIT 1
             FOR UPDATE
            """)
    PlatformConfigItemEntity selectActiveByKeyForUpdate(@Param("configKey") String configKey);

    @Select("""
            SELECT COUNT(*) AS backlog,
                   COALESCE(TIMESTAMPDIFF(SECOND, MIN(created_at), CURRENT_TIMESTAMP), 0) AS oldest_seconds,
                   COALESCE(SUM(CASE WHEN event_type = 'ADMIN_USER_PROFILE_VIEWED' AND NOT EXISTS (
                       SELECT 1 FROM nx_audit_log a
                        WHERE a.biz_no = CONCAT('C1-VIEW-', nx_event_outbox.event_id)
                          AND BINARY a.biz_no = BINARY CONCAT('C1-VIEW-', nx_event_outbox.event_id)
                          AND a.is_deleted = 0)
                       THEN 1 ELSE 0 END), 0) AS audit_link_unresolved
              FROM nx_event_outbox
             WHERE is_deleted = 0
               AND status IN ('PENDING', 'FAILED')
            """)
    Map<String, Object> selectA3EventBacklog();

    @Select("""
            SELECT COUNT(*)
              FROM nx_wallet_ledger
             WHERE is_deleted = 0
               AND created_at >= CURRENT_TIMESTAMP - INTERVAL 24 HOUR
            """)
    Long countA3LedgerEntries24h();

    @Select("SELECT UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000")
    Long selectA3DatabaseEpochMillis();
}
