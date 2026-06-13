package ffdd.compute.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.compute.domain.UserDeviceRuntime;
import org.apache.ibatis.annotations.Insert;

public interface UserDeviceRuntimeMapper extends BaseMapper<UserDeviceRuntime> {
    @Insert("""
            INSERT INTO nx_user_device_runtime (
              user_device_id, online_status, region, country, city, latitude, longitude,
              gpu_usage, gpu_temp_c, gpu_power_w, vram_used_gb, battery_level,
              is_charging, network_reachable, thermal_state, paused_reason,
              active_task_no, client_name, heartbeat_at, agent_version, is_deleted
            ) VALUES (
              #{userDeviceId}, #{onlineStatus}, #{region}, #{country}, #{city}, #{latitude}, #{longitude},
              #{gpuUsage}, #{gpuTempC}, #{gpuPowerW}, #{vramUsedGb}, #{batteryLevel},
              #{isCharging}, #{networkReachable}, #{thermalState}, #{pausedReason},
              #{activeTaskNo}, #{clientName}, #{heartbeatAt}, #{agentVersion}, 0
            )
            ON DUPLICATE KEY UPDATE
              online_status = VALUES(online_status),
              region = VALUES(region),
              country = VALUES(country),
              city = VALUES(city),
              latitude = VALUES(latitude),
              longitude = VALUES(longitude),
              gpu_usage = VALUES(gpu_usage),
              gpu_temp_c = VALUES(gpu_temp_c),
              gpu_power_w = VALUES(gpu_power_w),
              vram_used_gb = VALUES(vram_used_gb),
              battery_level = VALUES(battery_level),
              is_charging = VALUES(is_charging),
              network_reachable = VALUES(network_reachable),
              thermal_state = VALUES(thermal_state),
              paused_reason = VALUES(paused_reason),
              active_task_no = VALUES(active_task_no),
              client_name = VALUES(client_name),
              heartbeat_at = VALUES(heartbeat_at),
              agent_version = VALUES(agent_version),
              updated_at = CURRENT_TIMESTAMP,
              is_deleted = 0
            """)
    int upsert(UserDeviceRuntime runtime);
}
