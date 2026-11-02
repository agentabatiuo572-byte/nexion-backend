package ffdd.opsconsole.device.mapper;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Rule cycles use the existing paid delivery contract without TEST executor credentials. */
@Mapper
public interface RuleCycleAdmissionMapper {
    @Select("SELECT user_id FROM nx_user_device WHERE id=#{deviceId} AND is_deleted=0")
    Long owner(@Param("deviceId")long deviceId);

    @Select("SELECT d.user_id " + TestCloudExecutorMapper.PAID_FLEET_FROM + """
            AND d.id=#{deviceId} AND d.user_id=#{userId}
            AND UPPER(d.status) IN ('ACTIVE','ONLINE','BUSY','RUNNING')
            AND (#{completing} OR d.pending_deactivate=0)
            FOR UPDATE OF o,d
            """)
    Long lockPaidOwner(@Param("deviceId")long deviceId,@Param("userId")long userId,
            @Param("completing")boolean completing,@Param("cutoff")LocalDateTime cutoff,
            @Param("tradeinCutoff")LocalDateTime tradeinCutoff,@Param("legacyEnabled")boolean legacyEnabled);
}
