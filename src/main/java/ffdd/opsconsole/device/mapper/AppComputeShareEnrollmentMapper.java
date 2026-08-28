package ffdd.opsconsole.device.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.time.Instant;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AppComputeShareEnrollmentMapper extends BaseMapper<Object> {
    @Select("""
            SELECT COUNT(1)
              FROM nx_user
             WHERE id = #{userId} AND sandbox = 0 AND status = 'ACTIVE' AND is_deleted = 0
            """)
    int isProductionUser(@Param("userId") Long userId);

    @Select("""
            SELECT id
              FROM nx_user
             WHERE id = #{userId} AND sandbox = 0 AND status = 'ACTIVE' AND is_deleted = 0
             FOR UPDATE
            """)
    Long lockProductionUser(@Param("userId") Long userId);

    @Select("""
            SELECT COUNT(1)
              FROM nx_user_device
             WHERE user_id = #{userId} AND is_deleted = 0
               AND UPPER(ownership_status) = 'OWNED'
               AND UPPER(status) IN ('ACTIVE','ONLINE','BUSY','RUNNING')
               AND UPPER(COALESCE(NULLIF(device_type,''),'DEVICE')) <> 'SHARE'
               AND deactivated_at IS NULL AND pending_deactivate = 0
            """)
    int activeDeviceCount(@Param("userId") Long userId);

    @Select("""
            SELECT COUNT(1)
              FROM nx_compute_share_enrollment
             WHERE user_id = #{userId} AND status = 'PENDING'
               AND expires_at > #{now} AND is_deleted = 0
            """)
    int activeEnrollmentCount(@Param("userId") Long userId, @Param("now") Instant now);

    @Select("""
            SELECT COALESCE((
                SELECT CAST(config_value AS UNSIGNED)
                  FROM nx_config_item
                 WHERE config_key = 'device.max_active_slots' AND status = 1 AND is_deleted = 0
                 LIMIT 1
            ), 3)
            """)
    int deviceSlotCap();

    @Insert("""
            INSERT INTO nx_compute_share_enrollment (
              enrollment_no, user_id, requested_gpu_model, pairing_code_hash, status,
              expires_at, row_version, created_at, updated_at, is_deleted
            ) VALUES (
              #{enrollmentNo}, #{userId}, #{requestedGpuModel}, #{pairingCodeHash}, 'PENDING',
              #{expiresAt}, 0, NOW(), NOW(), 0
            )
            """)
    int insertEnrollment(EnrollmentWrite write);

    @Select("""
            SELECT id, enrollment_no AS enrollmentNo, user_id AS userId,
                   requested_gpu_model AS requestedGpuModel, pairing_code_hash AS pairingCodeHash,
                   status, user_device_id AS deviceId, expires_at AS expiresAt, row_version AS rowVersion
              FROM nx_compute_share_enrollment
             WHERE enrollment_no = #{enrollmentNo} AND user_id = #{userId} AND is_deleted = 0
             LIMIT 1
            """)
    EnrollmentRow findEnrollment(@Param("enrollmentNo") String enrollmentNo, @Param("userId") Long userId);

    @Select("""
            SELECT id, enrollment_no AS enrollmentNo, user_id AS userId,
                   requested_gpu_model AS requestedGpuModel, pairing_code_hash AS pairingCodeHash,
                   status, user_device_id AS deviceId, expires_at AS expiresAt, row_version AS rowVersion
              FROM nx_compute_share_enrollment
             WHERE enrollment_no = #{enrollmentNo} AND is_deleted = 0
             LIMIT 1 FOR UPDATE
            """)
    EnrollmentRow lockEnrollment(@Param("enrollmentNo") String enrollmentNo);

    @Select("""
            SELECT id, user_id AS userId, instance_no AS instanceNo
              FROM nx_user_device
             WHERE instance_no = #{instanceNo} AND is_deleted = 0
             LIMIT 1
            """)
    CanonicalDeviceRow findCanonicalDevice(@Param("instanceNo") String instanceNo);

    @Insert("""
            INSERT INTO nx_user_device (
              user_id, source_order_no, product_id, product_code, product_tier,
              instance_no, name, device_type, generation, gpu_model, vram_total_gb,
              base_power_w, dc_location, price_usdt_snapshot, ownership_status,
              source_channel, status, hashrate, daily_usdt, daily_nex,
              last_seen_at, purchased_at, activated_at, pending_deactivate,
              row_version, created_at, updated_at, is_deleted
            ) VALUES (
              #{userId}, NULL, NULL, 'COMPUTE_SHARE', 'PC_GPU',
              #{instanceNo}, #{name}, 'PC_GPU', 1, #{gpuModel}, #{vramTotalGb},
              #{basePowerW}, 'LOCAL_EXECUTOR', 0, 'OWNED',
              'COMPUTE_SHARE', 'ACTIVE', 0, 0, 0,
              NOW(), NOW(), NOW(), 0,
              0, NOW(), NOW(), 0
            )
            """)
    int insertCanonicalDevice(CanonicalDeviceWrite write);

    @Update("""
            UPDATE nx_compute_share_enrollment
               SET status = 'CONNECTED', device_instance_no = #{instanceNo},
                   user_device_id = #{deviceId}, pairing_code_hash = NULL,
                   claimed_at = NOW(), row_version = row_version + 1, updated_at = NOW()
             WHERE id = #{id} AND status = 'PENDING' AND row_version = #{expectedVersion}
               AND expires_at > NOW() AND is_deleted = 0
            """)
    int completeEnrollment(@Param("id") Long id,
                           @Param("expectedVersion") Long expectedVersion,
                           @Param("instanceNo") String instanceNo,
                           @Param("deviceId") Long deviceId);

    record EnrollmentWrite(String enrollmentNo, Long userId, String requestedGpuModel,
                           String pairingCodeHash, Instant expiresAt) { }

    record EnrollmentRow(Long id, String enrollmentNo, Long userId, String requestedGpuModel,
                         String pairingCodeHash, String status, Long deviceId,
                         Instant expiresAt, Long rowVersion) { }

    record CanonicalDeviceWrite(Long userId, String instanceNo, String name, String gpuModel,
                                Integer vramTotalGb, BigDecimal basePowerW) { }

    record CanonicalDeviceRow(Long id, Long userId, String instanceNo) { }
}
