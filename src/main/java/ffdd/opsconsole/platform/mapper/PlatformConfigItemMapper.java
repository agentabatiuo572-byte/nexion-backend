package ffdd.opsconsole.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.platform.infrastructure.PlatformConfigItemEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;
import java.util.Map;

public interface PlatformConfigItemMapper extends BaseMapper<PlatformConfigItemEntity> {
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
