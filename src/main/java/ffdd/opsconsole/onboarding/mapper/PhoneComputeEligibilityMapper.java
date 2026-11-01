package ffdd.opsconsole.onboarding.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Read-only diagnosis of the account's phones; no session credentials are selected. */
@Mapper
@SuppressWarnings("MybatisPlusBaseMapper")
public interface PhoneComputeEligibilityMapper {
    @Select("""
        SELECT d.id deviceId,d.instance_no instanceNo,d.status,d.activated_at activatedAt,
               d.deactivated_at deactivatedAt,d.pending_deactivate pendingDeactivate,
               b.installation_id installationId,b.execution_installation_id executionInstallationId,
               EXISTS(SELECT 1 FROM nx_onboarding_calibration c WHERE c.user_id=d.user_id
                   AND c.user_device_id=d.id AND c.device_id=b.installation_id
                   AND c.activation_status='ACTIVE' AND c.source='server' AND c.server_canonical=1
                   AND c.source_environment='PRODUCTION' AND c.run_id='' AND c.is_deleted=0) calibrated,
               r.heartbeat_at heartbeatAt,r.network_reachable networkReachable,r.battery_level batteryLevel,
               r.paused_reason pausedReason
          FROM nx_user_device d JOIN nx_user u ON u.id=d.user_id
             AND u.status='ACTIVE' AND u.is_deleted=0 AND u.sandbox=0
          LEFT JOIN nx_phone_binding b ON b.user_id=d.user_id AND b.user_device_id=d.id
             AND b.source_environment='PRODUCTION' AND b.run_id=''
          LEFT JOIN nx_user_device_runtime r ON r.user_device_id=d.id AND r.is_deleted=0
         WHERE d.user_id=#{userId} AND d.is_deleted=0 AND d.source_environment='PRODUCTION' AND d.run_id=''
           AND UPPER(d.ownership_status)='OWNED' AND UPPER(d.device_type) IN ('PHONE','MOBILE')
         ORDER BY d.id
        """)
    List<PhoneState> phones(@Param("userId") long userId);

    record PhoneState(long deviceId,String instanceNo,String status,LocalDateTime activatedAt,
            LocalDateTime deactivatedAt,boolean pendingDeactivate,String installationId,String executionInstallationId,
            boolean calibrated,LocalDateTime heartbeatAt,Boolean networkReachable,Integer batteryLevel,String pausedReason) { }
}
