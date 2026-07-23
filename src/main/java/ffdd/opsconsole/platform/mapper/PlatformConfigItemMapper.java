package ffdd.opsconsole.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.platform.infrastructure.PlatformConfigItemEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.Map;

public interface PlatformConfigItemMapper extends BaseMapper<PlatformConfigItemEntity> {
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
                   COALESCE(TIMESTAMPDIFF(SECOND, MIN(created_at), CURRENT_TIMESTAMP), 0) AS oldest_seconds
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
