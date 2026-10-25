package ffdd.opsconsole.device.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import org.apache.ibatis.annotations.*;

@Mapper
public interface AppDeviceRankMapper extends BaseMapper<Object> {
    @Select("SELECT sandbox FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0")
    Integer userSandbox(@Param("userId") long userId);

    // OnboardingCalibrationMapper writes its calibrated TOPS directly into d.hashrate.
    @Select("""
            SELECT d.id deviceId, d.hashrate deviceTops
              FROM nx_phone_binding b
              JOIN nx_user_device d ON d.id=b.user_device_id AND d.user_id=b.user_id
               AND d.source_environment=b.source_environment AND BINARY d.run_id=BINARY b.run_id
             WHERE b.user_id=#{userId} AND b.source_environment=#{sourceEnvironment} AND BINARY b.run_id=BINARY #{runId}
               AND d.is_deleted=0 AND d.product_code='phone' AND d.device_type IN ('MOBILE','PHONE')
               AND d.ownership_status='OWNED' AND d.status IN ('ACTIVE','ONLINE','BUSY','RUNNING')
               AND d.activated_at IS NOT NULL AND d.deactivated_at IS NULL AND d.pending_deactivate=0
            """)
    Phone currentPhone(@Param("userId") long userId, @Param("sourceEnvironment") String sourceEnvironment, @Param("runId") String runId);

    record Phone(Long deviceId, BigDecimal deviceTops) { }
}
