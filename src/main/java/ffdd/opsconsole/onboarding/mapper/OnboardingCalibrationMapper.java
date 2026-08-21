package ffdd.opsconsole.onboarding.mapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Persistence boundary for the authenticated user's onboarding calibration. */
@Mapper
// Statement-only SQL boundary for calibration plus versioned configuration.
@SuppressWarnings("MybatisPlusBaseMapper")
public interface OnboardingCalibrationMapper {
    @Select("SELECT COALESCE(sandbox,0) FROM nx_user "
            + "WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0 LIMIT 1")
    Integer userSandbox(@Param("userId") Long userId);

    @Select("SELECT COALESCE(sandbox,0) FROM nx_user "
            + "WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0 LIMIT 1 FOR UPDATE")
    Integer lockUserSandbox(@Param("userId") Long userId);

    @Select("""
            SELECT user_id userId,device_id deviceId,user_device_id userDeviceId,signal_json signalJson,derived_json derivedJson,
                   comparison_json comparisonJson,source,server_canonical serverCanonical,config_revision configRevision,
                   row_version rowVersion,idempotency_key idempotencyKey,request_hash requestHash,
                   activation_status activationStatus,activation_idempotency_key activationIdempotencyKey,
                   activation_request_hash activationRequestHash,source_environment sourceEnvironment,run_id runId
              FROM nx_onboarding_calibration
             WHERE user_id=#{userId} AND device_id=#{deviceId}
               AND source_environment='PRODUCTION' AND run_id='' AND is_deleted=0
             LIMIT 1 FOR UPDATE
            """)
    CalibrationRow findForUpdate(@Param("userId") Long userId, @Param("deviceId") String deviceId);

    @Select("""
            SELECT user_id userId,device_id deviceId,user_device_id userDeviceId,signal_json signalJson,derived_json derivedJson,
                   comparison_json comparisonJson,source,server_canonical serverCanonical,config_revision configRevision,
                   row_version rowVersion,idempotency_key idempotencyKey,request_hash requestHash,
                   activation_status activationStatus,activation_idempotency_key activationIdempotencyKey,
                   activation_request_hash activationRequestHash,source_environment sourceEnvironment,run_id runId
              FROM nx_onboarding_calibration
             WHERE user_id=#{userId} AND device_id=#{deviceId}
               AND source_environment=#{sourceEnvironment} AND run_id=#{runId} AND is_deleted=0
             LIMIT 1 FOR UPDATE
            """)
    CalibrationRow findForUpdateScoped(@Param("userId") Long userId, @Param("deviceId") String deviceId,
                                       @Param("sourceEnvironment") String sourceEnvironment,
                                       @Param("runId") String runId);

    @Select("""
            SELECT user_id userId,device_id deviceId,user_device_id userDeviceId,signal_json signalJson,derived_json derivedJson,
                   comparison_json comparisonJson,source,server_canonical serverCanonical,config_revision configRevision,
                   row_version rowVersion,idempotency_key idempotencyKey,request_hash requestHash,
                   activation_status activationStatus,activation_idempotency_key activationIdempotencyKey,
                   activation_request_hash activationRequestHash,source_environment sourceEnvironment,run_id runId
              FROM nx_onboarding_calibration
             WHERE user_id=#{userId} AND device_id=#{deviceId}
               AND source_environment='PRODUCTION' AND run_id='' AND is_deleted=0
             LIMIT 1
            """)
    CalibrationRow find(@Param("userId") Long userId, @Param("deviceId") String deviceId);

    @Select("""
            SELECT user_id userId,device_id deviceId,user_device_id userDeviceId,signal_json signalJson,derived_json derivedJson,
                   comparison_json comparisonJson,source,server_canonical serverCanonical,config_revision configRevision,
                   row_version rowVersion,idempotency_key idempotencyKey,request_hash requestHash,
                   activation_status activationStatus,activation_idempotency_key activationIdempotencyKey,
                   activation_request_hash activationRequestHash,source_environment sourceEnvironment,run_id runId
              FROM nx_onboarding_calibration
             WHERE user_id=#{userId} AND device_id=#{deviceId}
               AND source_environment=#{sourceEnvironment} AND run_id=#{runId} AND is_deleted=0
             LIMIT 1
            """)
    CalibrationRow findScoped(@Param("userId") Long userId, @Param("deviceId") String deviceId,
                              @Param("sourceEnvironment") String sourceEnvironment,
                              @Param("runId") String runId);

    @Insert("""
            INSERT INTO nx_onboarding_calibration
              (user_id,device_id,signal_json,derived_json,comparison_json,source,server_canonical,source_environment,run_id,
               config_revision,row_version,idempotency_key,request_hash,activation_status,created_at,updated_at,is_deleted)
            VALUES (#{row.userId},#{row.deviceId},#{row.signalJson},#{row.derivedJson},#{row.comparisonJson},
                    'server',1,'PRODUCTION','',#{row.configRevision},0,#{row.idempotencyKey},#{row.requestHash},'CALIBRATED',NOW(6),NOW(6),0)
            ON DUPLICATE KEY UPDATE
              signal_json=IF(is_deleted=1,VALUES(signal_json),signal_json),
              derived_json=IF(is_deleted=1,VALUES(derived_json),derived_json),
              comparison_json=IF(is_deleted=1,VALUES(comparison_json),comparison_json),
              source=IF(is_deleted=1,'server',source),
              server_canonical=IF(is_deleted=1,1,server_canonical),
              config_revision=IF(is_deleted=1,VALUES(config_revision),config_revision),
              row_version=IF(is_deleted=1,0,row_version),
              idempotency_key=IF(is_deleted=1,VALUES(idempotency_key),idempotency_key),
              request_hash=IF(is_deleted=1,VALUES(request_hash),request_hash),
              activation_status=IF(is_deleted=1,'CALIBRATED',activation_status),
              activation_idempotency_key=IF(is_deleted=1,NULL,activation_idempotency_key),
              activation_request_hash=IF(is_deleted=1,NULL,activation_request_hash),
              updated_at=IF(is_deleted=1,NOW(6),updated_at),
              is_deleted=0
            """)
    int insert(@Param("row") CalibrationWrite row);

    @Insert("""
            INSERT INTO nx_onboarding_calibration
              (user_id,device_id,signal_json,derived_json,comparison_json,source,server_canonical,source_environment,run_id,
               config_revision,row_version,idempotency_key,request_hash,activation_status,created_at,updated_at,is_deleted)
            VALUES (#{row.userId},#{row.deviceId},#{row.signalJson},#{row.derivedJson},#{row.comparisonJson},
                    'server',1,#{row.sourceEnvironment},#{row.runId},#{row.configRevision},0,#{row.idempotencyKey},
                    #{row.requestHash},'CALIBRATED',NOW(6),NOW(6),0)
            ON DUPLICATE KEY UPDATE
              signal_json=IF(is_deleted=1,VALUES(signal_json),signal_json),
              derived_json=IF(is_deleted=1,VALUES(derived_json),derived_json),
              comparison_json=IF(is_deleted=1,VALUES(comparison_json),comparison_json),
              source=IF(is_deleted=1,'server',source),
              server_canonical=IF(is_deleted=1,1,server_canonical),
              config_revision=IF(is_deleted=1,VALUES(config_revision),config_revision),
              row_version=IF(is_deleted=1,0,row_version),
              idempotency_key=IF(is_deleted=1,VALUES(idempotency_key),idempotency_key),
              request_hash=IF(is_deleted=1,VALUES(request_hash),request_hash),
              activation_status=IF(is_deleted=1,'CALIBRATED',activation_status),
              activation_idempotency_key=IF(is_deleted=1,NULL,activation_idempotency_key),
              activation_request_hash=IF(is_deleted=1,NULL,activation_request_hash),
              updated_at=IF(is_deleted=1,NOW(6),updated_at),
              is_deleted=0
            """)
    int insertScoped(@Param("row") CalibrationWrite row);

    @Insert("""
            INSERT INTO nx_onboarding_calibration
              (user_id,device_id,signal_json,derived_json,comparison_json,source,server_canonical,source_environment,run_id,
               config_revision,row_version,idempotency_key,request_hash,activation_status,
               activation_idempotency_key,activation_request_hash,created_at,updated_at,is_deleted)
            VALUES (#{row.userId},#{row.deviceId},JSON_OBJECT(),JSON_OBJECT(),JSON_ARRAY(),
                    'server',1,#{row.sourceEnvironment},#{row.runId},0,0,#{row.calibrationIdempotencyKey},
                    #{row.calibrationRequestHash},'DEFERRED',#{row.activationIdempotencyKey},
                    #{row.activationRequestHash},NOW(6),NOW(6),0)
            ON DUPLICATE KEY UPDATE
              signal_json=IF(is_deleted=1,JSON_OBJECT(),signal_json),
              derived_json=IF(is_deleted=1,JSON_OBJECT(),derived_json),
              comparison_json=IF(is_deleted=1,JSON_ARRAY(),comparison_json),
              source=IF(is_deleted=1,'server',source),
              server_canonical=IF(is_deleted=1,1,server_canonical),
              source_environment=IF(is_deleted=1,VALUES(source_environment),source_environment),
              run_id=IF(is_deleted=1,VALUES(run_id),run_id),
              user_device_id=IF(is_deleted=1,NULL,user_device_id),
              config_revision=IF(is_deleted=1,0,config_revision),
              row_version=IF(is_deleted=1,0,row_version),
              idempotency_key=IF(is_deleted=1,VALUES(idempotency_key),idempotency_key),
              request_hash=IF(is_deleted=1,VALUES(request_hash),request_hash),
              activation_status=IF(is_deleted=1,'DEFERRED',activation_status),
              activation_idempotency_key=IF(is_deleted=1,VALUES(activation_idempotency_key),activation_idempotency_key),
              activation_request_hash=IF(is_deleted=1,VALUES(activation_request_hash),activation_request_hash),
              updated_at=IF(is_deleted=1,NOW(6),updated_at),
              is_deleted=0
            """)
    int insertDeferred(@Param("row") DeferredWrite row);

    @Update("""
             UPDATE nx_onboarding_calibration
               SET signal_json=#{signalJson},derived_json=#{derivedJson},comparison_json=#{comparisonJson},
                   config_revision=#{configRevision},row_version=row_version+1,
                   idempotency_key=#{idempotencyKey},request_hash=#{requestHash},
                   activation_status='CALIBRATED',activation_idempotency_key=NULL,activation_request_hash=NULL,
                   updated_at=NOW(6)
             WHERE user_id=#{userId} AND device_id=#{deviceId} AND source_environment='PRODUCTION' AND run_id=''
               AND row_version=#{expectedVersion} AND is_deleted=0
            """)
    int update(@Param("userId") Long userId, @Param("deviceId") String deviceId,
               @Param("expectedVersion") Long expectedVersion, @Param("signalJson") String signalJson,
               @Param("derivedJson") String derivedJson, @Param("comparisonJson") String comparisonJson,
               @Param("configRevision") Long configRevision, @Param("idempotencyKey") String idempotencyKey,
               @Param("requestHash") String requestHash);

    @Update("""
            UPDATE nx_onboarding_calibration
               SET signal_json=#{signalJson},derived_json=#{derivedJson},comparison_json=#{comparisonJson},
                   config_revision=#{configRevision},row_version=row_version+1,
                   idempotency_key=#{idempotencyKey},request_hash=#{requestHash},
                   activation_status='CALIBRATED',activation_idempotency_key=NULL,activation_request_hash=NULL,
                   updated_at=NOW(6)
             WHERE user_id=#{userId} AND device_id=#{deviceId}
               AND source_environment=#{sourceEnvironment} AND run_id=#{runId}
               AND row_version=#{expectedVersion} AND is_deleted=0
            """)
    int updateScoped(@Param("userId") Long userId, @Param("deviceId") String deviceId,
                     @Param("sourceEnvironment") String sourceEnvironment, @Param("runId") String runId,
                     @Param("expectedVersion") Long expectedVersion, @Param("signalJson") String signalJson,
                     @Param("derivedJson") String derivedJson, @Param("comparisonJson") String comparisonJson,
                     @Param("configRevision") Long configRevision, @Param("idempotencyKey") String idempotencyKey,
                     @Param("requestHash") String requestHash);

    @Update("""
            UPDATE nx_onboarding_calibration
               SET activation_status=#{status},user_device_id=#{userDeviceId},activation_idempotency_key=#{idempotencyKey},
                   activation_request_hash=#{requestHash},row_version=row_version+1,updated_at=NOW(6)
             WHERE user_id=#{userId} AND device_id=#{deviceId}
               AND source_environment='PRODUCTION' AND run_id=''
               AND row_version=#{expectedVersion} AND is_deleted=0
            """)
    int updateActivation(@Param("userId") Long userId, @Param("deviceId") String deviceId,
                          @Param("expectedVersion") Long expectedVersion, @Param("userDeviceId") Long userDeviceId,
                          @Param("status") String status,
                          @Param("idempotencyKey") String idempotencyKey, @Param("requestHash") String requestHash);

    @Update("""
            UPDATE nx_onboarding_calibration
               SET activation_status=#{status},user_device_id=#{userDeviceId},activation_idempotency_key=#{idempotencyKey},
                   activation_request_hash=#{requestHash},row_version=row_version+1,updated_at=NOW(6)
             WHERE user_id=#{userId} AND device_id=#{deviceId}
               AND source_environment=#{sourceEnvironment} AND run_id=#{runId}
               AND row_version=#{expectedVersion} AND is_deleted=0
            """)
    int updateActivationScoped(@Param("userId") Long userId, @Param("deviceId") String deviceId,
                               @Param("sourceEnvironment") String sourceEnvironment, @Param("runId") String runId,
                               @Param("expectedVersion") Long expectedVersion, @Param("userDeviceId") Long userDeviceId,
                               @Param("status") String status,
                               @Param("idempotencyKey") String idempotencyKey, @Param("requestHash") String requestHash);

    @Insert("""
            INSERT INTO nx_user_device(
              user_id,source_order_no,product_id,product_code,product_tier,instance_no,name,device_type,generation,
              gpu_model,vram_total_gb,base_power_w,dc_location,price_usdt_snapshot,ownership_status,source_channel,
              source_environment,run_id,status,hashrate,daily_usdt,daily_nex,last_seen_at,purchased_at,activated_at,deactivated_at,
              pending_deactivate,row_version,created_at,updated_at,is_deleted)
            VALUES(#{userId},#{instanceNo},NULL,'phone',#{productTier},#{instanceNo},'Your phone','MOBILE',1,
              #{gpuModel},#{vramTotalGb},0,'User device',0,'OWNED','ONBOARDING',
              #{sourceEnvironment},#{runId},'ACTIVE',#{tops},#{dailyUsdt},#{dailyNex},NOW(6),NOW(6),NOW(6),NULL,0,0,NOW(6),NOW(6),0)
            ON DUPLICATE KEY UPDATE
              user_id=IF(user_id=VALUES(user_id) AND source_environment=VALUES(source_environment)
                  AND run_id=VALUES(run_id),user_id,NULL),source_order_no=VALUES(source_order_no),
              product_code='phone',product_tier=VALUES(product_tier),name='Your phone',device_type='MOBILE',
              gpu_model=VALUES(gpu_model),vram_total_gb=VALUES(vram_total_gb),base_power_w=0,
              dc_location='User device',price_usdt_snapshot=0,ownership_status='OWNED',source_channel='ONBOARDING',
              status='ACTIVE',hashrate=VALUES(hashrate),daily_usdt=VALUES(daily_usdt),daily_nex=VALUES(daily_nex),
              last_seen_at=NOW(6),purchased_at=COALESCE(purchased_at,NOW(6)),activated_at=NOW(6),deactivated_at=NULL,
              pending_deactivate=0,row_version=row_version+1,updated_at=NOW(6),is_deleted=0
            """)
    int upsertPhoneDevice(@Param("userId") Long userId, @Param("instanceNo") String instanceNo,
                          @Param("productTier") String productTier, @Param("gpuModel") String gpuModel,
                          @Param("vramTotalGb") Integer vramTotalGb, @Param("tops") BigDecimal tops,
                          @Param("dailyUsdt") BigDecimal dailyUsdt, @Param("dailyNex") BigDecimal dailyNex,
                          @Param("sourceEnvironment") String sourceEnvironment, @Param("runId") String runId);

    @Select("SELECT id FROM nx_user_device WHERE user_id=#{userId} AND instance_no=#{instanceNo} "
            + "AND source_channel='ONBOARDING' AND source_environment=#{sourceEnvironment} AND run_id=#{runId} "
            + "AND is_deleted=0 LIMIT 1 FOR UPDATE")
    Long phoneDeviceId(@Param("userId") Long userId, @Param("instanceNo") String instanceNo,
                       @Param("sourceEnvironment") String sourceEnvironment, @Param("runId") String runId);

    @Update("""
            UPDATE nx_user_device
               SET status='DEACTIVATED',activated_at=NULL,deactivated_at=NOW(6),pending_deactivate=0,
                   row_version=row_version+1,updated_at=NOW(6)
             WHERE user_id=#{userId} AND source_channel='ONBOARDING'
               AND source_environment=#{sourceEnvironment} AND run_id=#{runId}
               AND UPPER(device_type) IN ('MOBILE','PHONE') AND is_deleted=0
            """)
    int deactivateScopedPhoneDevices(@Param("userId") Long userId,
                                     @Param("sourceEnvironment") String sourceEnvironment,
                                     @Param("runId") String runId);

    @Update("""
            UPDATE nx_user_device
               SET status='DEACTIVATED',activated_at=NULL,deactivated_at=NOW(6),pending_deactivate=0,
                   row_version=row_version+1,updated_at=NOW(6)
             WHERE user_id=#{userId} AND id<>#{keepUserDeviceId} AND source_channel='ONBOARDING'
               AND source_environment=#{sourceEnvironment} AND run_id=#{runId}
               AND UPPER(device_type) IN ('MOBILE','PHONE') AND activated_at IS NOT NULL AND is_deleted=0
            """)
    int deactivateOtherPhoneDevices(@Param("userId") Long userId,
                                    @Param("keepUserDeviceId") Long keepUserDeviceId,
                                    @Param("sourceEnvironment") String sourceEnvironment,
                                    @Param("runId") String runId);

    @Update("""
            UPDATE nx_onboarding_calibration
               SET activation_status='DEFERRED',row_version=row_version+1,updated_at=NOW(6)
             WHERE user_id=#{userId} AND (user_device_id IS NULL OR user_device_id<>#{keepUserDeviceId})
               AND source_environment=#{sourceEnvironment} AND run_id=#{runId}
               AND activation_status='ACTIVE' AND is_deleted=0
            """)
    int deferOtherPhoneCalibrations(@Param("userId") Long userId,
                                    @Param("keepUserDeviceId") Long keepUserDeviceId,
                                    @Param("sourceEnvironment") String sourceEnvironment,
                                    @Param("runId") String runId);

    @Select("""
            SELECT tier,name,tops_min topsMin,tops_max topsMax,base_rate_usdt baseRateUsdt,
                   base_rate_nex baseRateNex,revision
              FROM nx_onboarding_phone_tier_config
             WHERE active=1 AND is_deleted=0 ORDER BY tier
            """)
    List<TierRow> activeTiers();

    @Select("""
            SELECT config_key configKey,label,daily_usdt dailyUsdt,daily_nex dailyNex,sort_order sortOrder,revision
              FROM nx_onboarding_yield_comparison_config
             WHERE active=1 AND is_deleted=0 ORDER BY sort_order,config_key
            """)
    List<ComparisonRow> activeComparisons();

    record CalibrationWrite(Long userId, String deviceId, String signalJson, String derivedJson,
                            String comparisonJson, Long configRevision, String idempotencyKey, String requestHash,
                            String sourceEnvironment, String runId) {
        public CalibrationWrite(Long userId, String deviceId, String signalJson, String derivedJson,
                                String comparisonJson, Long configRevision, String idempotencyKey, String requestHash) {
            this(userId, deviceId, signalJson, derivedJson, comparisonJson, configRevision, idempotencyKey,
                    requestHash, "PRODUCTION", "");
        }
    }

    record DeferredWrite(Long userId, String deviceId, String calibrationIdempotencyKey,
                         String calibrationRequestHash, String activationIdempotencyKey,
                         String activationRequestHash, String sourceEnvironment, String runId) { }

    record CalibrationRow(Long userId, String deviceId, Long userDeviceId, String signalJson, String derivedJson,
                          String comparisonJson, String source, Boolean serverCanonical, Long configRevision,
                          Long rowVersion, String idempotencyKey, String requestHash,
                          String activationStatus, String activationIdempotencyKey, String activationRequestHash,
                          String sourceEnvironment, String runId) {
        public CalibrationRow(Long userId, String deviceId, String signalJson, String derivedJson,
                              String comparisonJson, String source, Boolean serverCanonical, Long configRevision,
                              Long rowVersion, String idempotencyKey, String requestHash) {
            this(userId, deviceId, null, signalJson, derivedJson, comparisonJson, source, serverCanonical, configRevision,
                    rowVersion, idempotencyKey, requestHash, "CALIBRATED", null, null, "PRODUCTION", "");
        }

        public CalibrationRow(Long userId, String deviceId, String signalJson, String derivedJson,
                              String comparisonJson, String source, Boolean serverCanonical, Long configRevision,
                              Long rowVersion, String idempotencyKey, String requestHash,
                              String sourceEnvironment, String runId) {
            this(userId, deviceId, null, signalJson, derivedJson, comparisonJson, source, serverCanonical, configRevision,
                    rowVersion, idempotencyKey, requestHash, "CALIBRATED", null, null, sourceEnvironment, runId);
        }
    }

    record TierRow(Integer tier, String name, Integer topsMin, Integer topsMax, BigDecimal baseRateUsdt,
                   BigDecimal baseRateNex, Long revision) { }

    record ComparisonRow(String configKey, String label, BigDecimal dailyUsdt, BigDecimal dailyNex,
                         Integer sortOrder, Long revision) { }
}
