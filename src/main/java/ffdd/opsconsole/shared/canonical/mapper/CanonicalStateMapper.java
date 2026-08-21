package ffdd.opsconsole.shared.canonical.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.shared.canonical.infrastructure.CanonicalUserEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CanonicalStateMapper extends BaseMapper<CanonicalUserEntity> {
    @Select("""
            SELECT status
              FROM nx_trial_claim
             WHERE user_id = #{userId} AND is_deleted = 0
             ORDER BY id DESC
             LIMIT 1
            """)
    String findTrialState(@Param("userId") Long userId);

    @Select("""
            SELECT COALESCE((
                SELECT two_factor_enabled
                  FROM nx_user_security
                 WHERE user_id = #{userId} AND is_deleted = 0
                 LIMIT 1
            ), 0) = 1
            """)
    boolean twoFactorEnabled(@Param("userId") Long userId);

    @Select("""
            SELECT COALESCE((
                SELECT config_value
                  FROM nx_config_item
                 WHERE config_key = 'growth.phase.current' AND status = 1 AND is_deleted = 0
                 LIMIT 1
            ), 'P1')
            """)
    String currentPhase();

    @Select("SELECT id,sandbox FROM nx_user WHERE id = #{userId} AND status='ACTIVE' AND is_deleted = 0 FOR UPDATE")
    UserLock lockUser(@Param("userId") Long userId);

    record UserLock(Long id, boolean sandbox) { }

    @Select("""
            SELECT COALESCE((
                       SELECT config_value
                         FROM nx_config_item
                        WHERE config_key = 'growth.phase.current' AND status = 1 AND is_deleted = 0
                        LIMIT 1
                   ), 'P1') AS phase,
                   GREATEST(TIMESTAMPDIFF(MONTH, u.created_at, NOW()), 0) AS accountAgeMonths,
                   DATE_FORMAT(u.created_at, '%x-W%v') AS cohort
              FROM nx_user u
             WHERE u.id = #{userId} AND u.is_deleted = 0 AND COALESCE(u.sandbox,0)=0
             LIMIT 1
            """)
    UserEventAttribution userEventAttribution(@Param("userId") Long userId);

    @Select("""
            SELECT CAST(COALESCE(NULLIF(REPLACE(UPPER(u.v_rank),'V',''),''),'0') AS UNSIGNED) AS `rank`,
                   (SELECT COUNT(*) FROM nx_team_member tm JOIN nx_user child ON child.id=tm.member_user_id
                     WHERE tm.user_id=u.id AND tm.level=1 AND tm.is_deleted=0 AND child.sandbox=u.sandbox
                       AND child.status='ACTIVE' AND child.is_deleted=0) AS activeDirect,
                   (SELECT COALESCE(SUM(tm.volume),0) FROM nx_team_member tm JOIN nx_user member
                     ON member.id=tm.member_user_id AND member.sandbox=u.sandbox
                    WHERE tm.user_id=u.id AND tm.is_deleted=0 AND member.status='ACTIVE' AND member.is_deleted=0) AS teamVolumeUsd
              FROM nx_user u WHERE u.id=#{userId} AND u.status='ACTIVE' AND u.is_deleted=0 LIMIT 1
            """)
    PurchaseFacts purchaseFacts(@Param("userId") Long userId);

    @Select("""
            SELECT COUNT(1)
             FROM nx_user_device d
             JOIN nx_user u ON u.id = d.user_id AND COALESCE(u.sandbox,0)=0
             WHERE d.user_id = #{userId} AND d.is_deleted = 0
               AND d.source_environment='PRODUCTION' AND d.run_id=''
               AND UPPER(d.ownership_status) = 'OWNED'
               AND UPPER(d.status) IN ('ACTIVE','ONLINE','BUSY','RUNNING')
               AND d.deactivated_at IS NULL AND d.pending_deactivate = 0
            """)
    int activeDeviceCount(@Param("userId") Long userId);

    @Select("""
            SELECT COALESCE(SUM(quantity), 0)
              FROM nx_order o
              JOIN nx_user u ON u.id=o.user_id AND COALESCE(u.sandbox,0)=0
             WHERE o.user_id = #{userId} AND o.is_deleted = 0
               AND UPPER(o.order_status) IN ('PENDING_PAYMENT','PAID','PROCESSING','PROVISIONING')
               AND UPPER(COALESCE(o.activation_status, 'WAITING_PAYMENT')) NOT IN
                   ('ACTIVATED','REFUNDED','CANCELLED','PROVISIONING_FAILED')
            """)
    int reservedDeviceOrderCount(@Param("userId") Long userId);

    @Select("""
            SELECT COALESCE((
                SELECT CAST(config_value AS UNSIGNED)
                  FROM nx_config_item
                 WHERE config_key = 'device.max_active_slots' AND status = 1 AND is_deleted = 0
                 LIMIT 1
            ), 3)
            """)
    int deviceSlotCap();

    @Update("""
            UPDATE nx_user_device
               SET status = 'ACTIVE', activated_at = COALESCE(activated_at, NOW()), deactivated_at = NULL,
                   row_version = row_version + 1, updated_at = NOW()
             WHERE id = #{deviceId} AND user_id = #{userId} AND is_deleted = 0
               AND UPPER(ownership_status) = 'OWNED' AND UPPER(status) <> 'ACTIVE'
               AND row_version = #{expectedVersion}
               AND EXISTS (SELECT 1 FROM nx_user u
                            WHERE u.id = #{userId} AND COALESCE(u.sandbox,0)=0)
               AND (SELECT active_count FROM (
                    SELECT COUNT(1) AS active_count
                      FROM nx_user_device d
                      JOIN nx_user u ON u.id = d.user_id AND COALESCE(u.sandbox,0)=0
                     WHERE d.user_id = #{userId} AND d.is_deleted = 0
                       AND UPPER(d.ownership_status) = 'OWNED'
                       AND UPPER(d.status) IN ('ACTIVE','ONLINE','BUSY','RUNNING')
                       AND d.deactivated_at IS NULL AND d.pending_deactivate = 0
               ) active_snapshot) < #{slotCap}
            """)
    int activateOwnedDeviceCas(@Param("userId") Long userId,
                               @Param("deviceId") Long deviceId,
                               @Param("expectedVersion") Long expectedVersion,
                               @Param("slotCap") Integer slotCap);

    @Select("""
            SELECT d.id, d.user_id AS userId, d.instance_no AS instanceNo, d.status,
                   d.ownership_status AS ownershipStatus, d.row_version AS rowVersion,
                   d.pending_deactivate AS pendingDeactivate
              FROM nx_user_device d
              JOIN nx_user u ON u.id=d.user_id AND COALESCE(u.sandbox,0)=0
             WHERE d.id = #{deviceId} AND d.is_deleted = 0
             LIMIT 1
             FOR UPDATE
            """)
    UserDeviceCommandRow lockDeviceForUserCommand(@Param("deviceId") Long deviceId);

    @Select("""
            SELECT COUNT(1) FROM nx_compute_task
             WHERE user_id=#{userId} AND user_device_id=#{deviceId} AND is_deleted=0
               AND EXISTS (SELECT 1 FROM nx_user u WHERE u.id=#{userId} AND COALESCE(u.sandbox,0)=0)
               AND UPPER(COALESCE(source_environment,'PRODUCTION'))='PRODUCTION'
               AND UPPER(status) IN ('CLAIMED','RUNNING')
            """)
    boolean hasActiveTask(@Param("userId") Long userId, @Param("deviceId") Long deviceId);

    @Update("""
            UPDATE nx_user_device SET pending_deactivate=1,updated_at=NOW()
             WHERE id=#{deviceId} AND user_id=#{userId} AND is_deleted=0
               AND UPPER(ownership_status)='OWNED' AND UPPER(status)='ACTIVE'
               AND pending_deactivate=0 AND row_version=#{expectedVersion}
               AND EXISTS (SELECT 1 FROM nx_user u WHERE u.id=#{userId} AND COALESCE(u.sandbox,0)=0)
            """)
    int markDevicePendingDeactivate(@Param("userId") Long userId, @Param("deviceId") Long deviceId,
                                    @Param("expectedVersion") Long expectedVersion);

    @Update("""
            UPDATE nx_user_device SET status='DEACTIVATED',activated_at=NULL,deactivated_at=NOW(),
                   pending_deactivate=0,row_version=row_version+1,updated_at=NOW()
             WHERE id=#{deviceId} AND user_id=#{userId} AND is_deleted=0
               AND UPPER(ownership_status)='OWNED' AND UPPER(status)='ACTIVE'
               AND pending_deactivate=1
               AND EXISTS (SELECT 1 FROM nx_user u WHERE u.id=#{userId} AND COALESCE(u.sandbox,0)=0)
            """)
    int deactivatePendingDevice(@Param("userId") Long userId, @Param("deviceId") Long deviceId);

    @Update("""
            UPDATE nx_user_device
               SET status = 'DEACTIVATED', activated_at = NULL, deactivated_at = NOW(),
                   pending_deactivate = 0, row_version = row_version + 1, updated_at = NOW()
             WHERE id = #{deviceId} AND user_id = #{userId} AND is_deleted = 0
               AND UPPER(ownership_status) = 'OWNED' AND UPPER(status) = 'ACTIVE'
               AND row_version = #{expectedVersion}
               AND EXISTS (SELECT 1 FROM nx_user u WHERE u.id=#{userId} AND COALESCE(u.sandbox,0)=0)
            """)
    int deactivateOwnedDeviceCas(@Param("userId") Long userId,
                                 @Param("deviceId") Long deviceId,
                                 @Param("expectedVersion") Long expectedVersion);

    @Update("""
            UPDATE nx_user_device_runtime
               SET online_status = 'OFFLINE', paused_reason = 'USER_DEACTIVATED', updated_at = NOW()
             WHERE user_device_id = #{deviceId} AND is_deleted = 0
               AND EXISTS (SELECT 1 FROM nx_user_device d JOIN nx_user u ON u.id=d.user_id
                            WHERE d.id=#{deviceId} AND COALESCE(u.sandbox,0)=0)
            """)
    int markDeviceRuntimeDeactivated(@Param("deviceId") Long deviceId);

    @Select("""
            SELECT config_key AS configKey, config_value AS configValue
              FROM nx_compute_e3_config
             WHERE config_key IN (
                   'capacityBand1DeltaPct','capacityBand2DeltaPct','capacityBand3DeltaPct',
                   'stageEarlyEnd','stageMidEnd','cycleMonths','capacityFloorPct','capacitySubsidyDays',
                   'capacityApplyToPhone','capacityApplyToCloudShare','capacityApplyToPcGpu',
                   'capacityApplyToS1','capacityApplyToPro','capacityApplyToProV2',
                   'capacityApplyToRackP1','capacityApplyToRackP2',
                   'taskLockS1','taskLockPro','taskLockRack')
               AND is_deleted = 0
             ORDER BY config_key
            """)
    List<E3CapacityConfig> e3CapacityConfig();

    @Select("""
            SELECT COALESCE(SUM(daily_usdt), 0) AS dailyUsdt,
                   COALESCE(SUM(daily_nex), 0) AS dailyNex
              FROM nx_user_device d
              JOIN nx_user u ON u.id=d.user_id AND COALESCE(u.sandbox,0)=0
             WHERE d.user_id = #{userId} AND d.is_deleted = 0
               AND UPPER(d.status) = 'ACTIVE'
            """)
    DeviceEarnings deviceEarnings(@Param("userId") Long userId);

    @Select("""
            SELECT r.user_device_id AS deviceId,
                   COALESCE(SUM(r.reward_usdt), 0) AS todayEarningsUsdt,
                   COALESCE(SUM(r.reward_nex), 0) AS todayEarningsNex
              FROM nx_compute_receipt r
              JOIN nx_user u ON u.id=r.user_id AND COALESCE(u.sandbox,0)=0
             WHERE r.user_id = #{userId} AND r.is_deleted = 0
               AND COALESCE(r.source_environment, 'PRODUCTION') = 'PRODUCTION'
               AND UPPER(r.earning_status) IN ('POSTED','SUCCESS','SETTLED','CREDITED','PAID')
               AND r.completed_at >= #{start}
               AND r.completed_at < #{end}
             GROUP BY r.user_device_id
            """)
    List<DeviceRealizedToday> realizedToday(
            @Param("userId") Long userId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    @Select("""
            SELECT w.usdt_available AS usdtAvailable,
                   w.nex_available AS nexAvailable,
                   u.created_at AS joinedAt
              FROM nx_user u
              JOIN nx_user_wallet w ON w.user_id = u.id AND w.is_deleted = 0
             WHERE u.id = #{userId} AND u.is_deleted = 0 AND COALESCE(u.sandbox,0)=0
             LIMIT 1
            """)
    UserCanonicalProfile userCanonicalProfile(@Param("userId") Long userId);

    @Select("""
            SELECT d.id,
                   d.instance_no AS instanceNo,
                   d.name,
                   d.device_type AS deviceType,
                   d.product_code AS productCode,
                   d.status,
                   d.row_version AS rowVersion,
                   d.pending_deactivate AS pendingDeactivate,
                   d.activated_at AS activatedAt,
                   d.purchased_at AS purchasedAt,
                   d.daily_usdt AS dailyUsdt,
                   d.daily_nex AS dailyNex,
                   d.gpu_model AS gpuModel,
                   d.vram_total_gb AS vramTotalGb,
                   d.base_power_w AS basePowerW,
                   d.dc_location AS location,
                   COALESCE(NULLIF(CASE WHEN o.quantity > 0 THEN o.amount_usdt / o.quantity END, 0),
                            NULLIF(d.price_usdt_snapshot, 0), p.price_usdt, 0) AS actualPaidUsdt,
                   COALESCE((SELECT SUM(r.reward_usdt)
                              FROM nx_compute_receipt r
                              WHERE r.user_device_id = d.id AND r.is_deleted = 0
                                AND COALESCE(r.source_environment, 'PRODUCTION') = 'PRODUCTION'
                                AND UPPER(r.earning_status) IN
                                    ('POSTED','SUCCESS','SETTLED','CREDITED','PAID')), 0) AS cumulativeOutputUsdt
              FROM nx_user_device d
              JOIN nx_user u ON u.id=d.user_id AND COALESCE(u.sandbox,0)=0
              LEFT JOIN nx_product p
                ON p.id = d.product_id AND p.is_deleted = 0
              LEFT JOIN nx_order o
                ON o.order_no = d.source_order_no AND o.user_id = d.user_id
               AND o.payment_status = 'PAID' AND o.is_deleted = 0
             WHERE d.user_id = #{userId} AND d.is_deleted = 0
               AND UPPER(d.ownership_status) = 'OWNED'
             ORDER BY d.purchased_at ASC, d.id ASC
            """)
    List<OwnedDevice> ownedDevices(@Param("userId") Long userId);

    @Select("""
            SELECT 0 AS usdtAvailable,0 AS nexAvailable,u.created_at AS joinedAt
              FROM nx_user u
             WHERE u.id=#{userId} AND u.status='ACTIVE' AND u.is_deleted=0 AND u.sandbox=1
             LIMIT 1
            """)
    UserCanonicalProfile sandboxUserCanonicalProfile(@Param("userId") Long userId);

    @Select("""
            SELECT d.id,d.instance_no AS instanceNo,d.name,d.device_type AS deviceType,
                   d.product_code AS productCode,d.status,d.row_version AS rowVersion,
                   d.pending_deactivate AS pendingDeactivate,d.activated_at AS activatedAt,
                   d.purchased_at AS purchasedAt,d.daily_usdt AS dailyUsdt,d.daily_nex AS dailyNex,
                   d.gpu_model AS gpuModel,d.vram_total_gb AS vramTotalGb,d.base_power_w AS basePowerW,
                   d.dc_location AS location,0 AS actualPaidUsdt,0 AS cumulativeOutputUsdt
              FROM nx_user_device d
              JOIN nx_user u ON u.id=d.user_id AND u.status='ACTIVE' AND u.is_deleted=0 AND u.sandbox=1
             WHERE d.user_id=#{userId} AND d.source_environment='SANDBOX' AND d.run_id=#{runId}
               AND d.is_deleted=0 AND UPPER(d.ownership_status)='OWNED'
             ORDER BY d.purchased_at,d.id
            """)
    List<OwnedDevice> sandboxOwnedDevices(@Param("userId") Long userId, @Param("runId") String runId);

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_user_otp_challenge (
              id BIGINT AUTO_INCREMENT PRIMARY KEY,
              challenge_no VARCHAR(96) NOT NULL,
              user_id BIGINT NOT NULL,
              code_hash CHAR(64) NOT NULL,
              expires_at DATETIME NOT NULL,
              attempts INT NOT NULL DEFAULT 0,
              consumed_at DATETIME NULL,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_user_otp_challenge_no (challenge_no),
              KEY idx_user_otp_active (user_id, expires_at, consumed_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createOtpChallengeTable();

    @Update("""
            UPDATE nx_user_otp_challenge
               SET consumed_at = NOW(), attempts = attempts + 1, updated_at = NOW()
             WHERE challenge_no = #{challengeNo} AND user_id = #{userId}
               AND code_hash = SHA2(CONCAT(#{code}, ':', challenge_no), 256)
               AND consumed_at IS NULL AND expires_at >= NOW() AND attempts < 5 AND is_deleted = 0
            """)
    int consumeValidOtp(@Param("userId") Long userId,
                        @Param("challengeNo") String challengeNo,
                        @Param("code") String code);

    @Update("""
            UPDATE nx_user_otp_challenge
               SET attempts = attempts + 1, updated_at = NOW()
             WHERE challenge_no = #{challengeNo} AND user_id = #{userId}
               AND consumed_at IS NULL AND expires_at >= NOW() AND attempts < 5 AND is_deleted = 0
            """)
    int incrementOtpFailure(@Param("userId") Long userId, @Param("challengeNo") String challengeNo);

    @Select("""
            SELECT p.id, p.product_no AS productNo, p.price_usdt AS priceUsdt, p.stock, p.unlock_phase AS unlockPhase,
                   (SELECT s.purchase_gate_json FROM nx_admin_device_sku s
                     WHERE s.sku_id=p.product_no AND s.is_deleted=0 LIMIT 1) AS purchaseGateJson
              FROM nx_product p
             WHERE p.is_deleted = 0
               AND ((#{productId} IS NOT NULL AND p.id = #{productId})
                 OR (#{productNo} IS NOT NULL AND p.product_no = #{productNo}))
               AND UPPER(p.status) IN ('ACTIVE', 'ON_SALE')
               AND COALESCE(p.store_visible, 1) = 1
               AND p.price_usdt > 0 AND p.stock >= 1
             LIMIT 1 FOR UPDATE
            """)
    ProductStock lockProduct(@Param("productId") Long productId, @Param("productNo") String productNo);

    @Select("""
            SELECT p.id, p.product_no AS productNo, p.price_usdt AS priceUsdt, p.stock,
                   p.unlock_phase AS unlockPhase,
                   (SELECT s.purchase_gate_json FROM nx_admin_device_sku s
                     WHERE s.sku_id=p.product_no AND s.is_deleted=0 LIMIT 1) AS purchaseGateJson
              FROM nx_product p
             WHERE p.is_deleted=0
               AND ((#{productId} IS NOT NULL AND p.id=#{productId})
                 OR (#{productNo} IS NOT NULL AND p.product_no=#{productNo}))
               AND UPPER(p.status) IN ('ACTIVE','ON_SALE')
               AND COALESCE(p.store_visible,1)=1 AND p.price_usdt>0 AND p.stock>=1
             LIMIT 1
            """)
    ProductStock findPurchasableProduct(@Param("productId") Long productId,
                                        @Param("productNo") String productNo);

    @Select("""
            SELECT s.purchase_gate_json
              FROM nx_admin_device_sku s
             WHERE s.sku_id=#{productNo} AND s.is_deleted=0 LIMIT 1
            """)
    String purchaseGateJson(@Param("productNo") String productNo);

    /** Canonical, quantity-aware lifetime quota CAS for ordinary checkout. */
    @Update("""
            UPDATE nx_admin_device_sku
               SET purchase_gate_json=JSON_SET(purchase_gate_json,'$.quotaSold',
                   CAST(JSON_UNQUOTE(JSON_EXTRACT(purchase_gate_json,'$.quotaSold')) AS UNSIGNED)+#{quantity}),
                   updated_at=NOW()
             WHERE sku_id=#{productNo} AND is_deleted=0
               AND JSON_VALID(purchase_gate_json)=1
               AND JSON_UNQUOTE(JSON_EXTRACT(purchase_gate_json,'$.enforce'))='true'
               AND (JSON_EXTRACT(purchase_gate_json,'$.quotaPeriod') IS NULL
                    OR JSON_UNQUOTE(JSON_EXTRACT(purchase_gate_json,'$.quotaPeriod'))='lifetime')
               AND JSON_EXTRACT(purchase_gate_json,'$.quotaCap') IS NOT NULL
               AND JSON_EXTRACT(purchase_gate_json,'$.quotaSold') IS NOT NULL
               AND #{quantity} > 0
               AND CAST(JSON_UNQUOTE(JSON_EXTRACT(purchase_gate_json,'$.quotaSold')) AS UNSIGNED)+#{quantity}
                   <= CAST(JSON_UNQUOTE(JSON_EXTRACT(purchase_gate_json,'$.quotaCap')) AS UNSIGNED)
            """)
    int consumePurchaseQuota(@Param("productNo") String productNo, @Param("quantity") Integer quantity);

    @Update("""
            UPDATE nx_product
               SET stock = stock - #{quantity}, sold_count = sold_count + #{quantity},
                   updated_at = GREATEST(CURRENT_TIMESTAMP(6),updated_at + INTERVAL 1 MICROSECOND)
             WHERE id = #{productId} AND is_deleted = 0 AND stock >= #{quantity}
            """)
    int decrementProductStock(@Param("productId") Long productId, @Param("quantity") Integer quantity);

    @Insert("""
            INSERT INTO nx_order (
              user_id, order_no, product_id, quantity, order_type, item_count,
              subtotal_usdt, discount_usdt, amount_usdt, payment_status,
              order_status, activation_status, created_at, updated_at, is_deleted
            ) VALUES (
              #{userId}, #{orderNo}, #{productId}, #{quantity}, 'SINGLE', #{quantity},
              #{subtotalUsdt}, #{discountUsdt}, #{amountUsdt}, 'PENDING',
              'PENDING_PAYMENT', 'WAITING_PAYMENT', NOW(), NOW(), 0
            )
            """)
    int insertOrder(@Param("userId") Long userId,
                    @Param("orderNo") String orderNo,
                    @Param("productId") Long productId,
                    @Param("quantity") Integer quantity,
                    @Param("subtotalUsdt") BigDecimal subtotalUsdt,
                    @Param("discountUsdt") BigDecimal discountUsdt,
                    @Param("amountUsdt") BigDecimal amountUsdt);

    @Select("""
            SELECT o.order_no AS orderNo,
                   o.product_id AS productId,
                   COALESCE((SELECT oi.product_no
                               FROM nx_order_item oi
                              WHERE oi.order_no = o.order_no AND oi.is_deleted = 0
                              ORDER BY oi.sort_order, oi.id LIMIT 1), p.product_no) AS productNo,
                   COALESCE((SELECT oi.product_name
                               FROM nx_order_item oi
                              WHERE oi.order_no = o.order_no AND oi.is_deleted = 0
                              ORDER BY oi.sort_order, oi.id LIMIT 1),
                            ta.target_product_name, p.name, o.order_no) AS productName,
                   o.quantity,
                   COALESCE((SELECT oi.unit_price_usdt
                               FROM nx_order_item oi
                              WHERE oi.order_no = o.order_no AND oi.is_deleted = 0
                              ORDER BY oi.sort_order, oi.id LIMIT 1),
                            p.price_usdt, o.subtotal_usdt) AS unitPriceUsdt,
                   o.discount_usdt AS discountUsdt,
                   o.amount_usdt AS amountUsdt,
                   COALESCE((SELECT pr.provider
                               FROM nx_payment_record pr
                              WHERE pr.order_no = o.order_no AND pr.user_id = o.user_id AND pr.is_deleted = 0
                              ORDER BY pr.id DESC LIMIT 1),
                            CASE WHEN o.order_type = 'TRADE_IN' THEN 'USDT_WALLET' ELSE 'PENDING' END) AS paymentMethod,
                   o.payment_status AS paymentStatus,
                   o.order_status AS orderStatus,
                   o.activation_status AS activationStatus,
                   o.order_type AS orderType,
                   o.created_at AS placedAt,
                   o.paid_at AS paidAt,
                   COALESCE(ud.activated_at, ta.completed_at) AS activatedAt,
                   ud.dc_location AS dataCenter,
                   ta.tradein_no AS tradeinNo,
                   ta.source_device_id AS sourceDeviceId,
                   ta.target_device_id AS targetDeviceId,
                   ud.instance_no AS targetDeviceInstanceNo
              FROM nx_order o
              LEFT JOIN nx_product p
                ON p.id = o.product_id AND p.is_deleted = 0
              LEFT JOIN nx_tradein_application ta
                ON ta.user_id = o.user_id AND ta.target_order_no = o.order_no AND ta.is_deleted = 0
              LEFT JOIN nx_user_device ud
                ON ud.id = COALESCE(ta.target_device_id, (
                     SELECT MIN(ordinary_device.id)
                       FROM nx_user_device ordinary_device
                      WHERE ordinary_device.user_id = o.user_id
                        AND ordinary_device.source_order_no = o.order_no
                        AND ordinary_device.is_deleted = 0
                   ))
               AND ud.user_id = o.user_id AND ud.is_deleted = 0
             WHERE o.user_id = #{userId} AND o.is_deleted = 0
             ORDER BY o.created_at DESC, o.id DESC
             LIMIT 100
            """)
    List<UserOrder> userOrders(@Param("userId") Long userId);

    @Select("""
            SELECT COALESCE((
                SELECT CAST(current_value AS DECIMAL(10,6))
                  FROM nx_growth_trial_policy
                 WHERE policy_key = 'chargeFailRate' AND is_deleted = 0
                 LIMIT 1
            ), 0.01)
            """)
    BigDecimal trialChargeFailRate();

    @Select("""
            SELECT id, claim_no AS claimNo, status, price_usdt AS priceUsdt,
                   LEAST(offset_cap_usdt, daily_usdt * duration_days) AS earnedOffsetUsdt
              FROM nx_trial_claim
             WHERE user_id = #{userId} AND is_deleted = 0
               AND UPPER(status) IN ('CLAIMED', 'ACTIVE', 'GRACE', 'EXTENDED')
             ORDER BY id DESC
             LIMIT 1 FOR UPDATE
            """)
    TrialClaim lockLatestChargeableTrial(@Param("userId") Long userId);

    @Select("""
            SELECT usdt_available
              FROM nx_user_wallet
             WHERE user_id = #{userId} AND is_deleted = 0
             LIMIT 1 FOR UPDATE
            """)
    BigDecimal lockWalletUsdt(@Param("userId") Long userId);

    @Update("""
            UPDATE nx_user_wallet
               SET usdt_available = usdt_available - #{amount}, version = version + 1, updated_at = NOW()
             WHERE user_id = #{userId} AND is_deleted = 0 AND usdt_available >= #{amount}
            """)
    int debitWalletUsdt(@Param("userId") Long userId, @Param("amount") BigDecimal amount);

    @Insert("""
            INSERT INTO nx_wallet_ledger (
              user_id, biz_no, biz_type, asset, direction, amount, balance_after,
              status, remark, created_at, updated_at, is_deleted
            ) VALUES (
              #{userId}, #{claimNo}, 'TRIAL_CHARGE', 'USDT', 'DEBIT', #{amount}, #{balanceAfter},
              'POSTED', 'Trial converted by server canonical charge', NOW(), NOW(), 0
            )
            """)
    int insertTrialChargeLedger(@Param("userId") Long userId,
                                @Param("claimNo") String claimNo,
                                @Param("amount") BigDecimal amount,
                                @Param("balanceAfter") BigDecimal balanceAfter);

    @Update("""
            UPDATE nx_trial_claim
               SET status = #{outcome}, updated_at = NOW()
             WHERE id = #{claimId} AND is_deleted = 0
               AND UPPER(status) IN ('CLAIMED', 'ACTIVE', 'GRACE', 'EXTENDED')
            """)
    int markTrialChargeAttempt(@Param("claimId") Long claimId, @Param("outcome") String outcome);

    record DeviceEarnings(BigDecimal dailyUsdt, BigDecimal dailyNex) {
    }

    record DeviceRealizedToday(Long deviceId, BigDecimal todayEarningsUsdt, BigDecimal todayEarningsNex) {
    }

    record UserCanonicalProfile(BigDecimal usdtAvailable, BigDecimal nexAvailable, LocalDateTime joinedAt) {
    }

    record E3CapacityConfig(String configKey, String configValue) {
    }

    record UserDeviceCommandRow(
            Long id, Long userId, String instanceNo, String status, String ownershipStatus, Long rowVersion,
            boolean pendingDeactivate) {
        public UserDeviceCommandRow(Long id, Long userId, String instanceNo, String status,
                                    String ownershipStatus, Long rowVersion) {
            this(id, userId, instanceNo, status, ownershipStatus, rowVersion, false);
        }
    }

    record OwnedDevice(
            Long id,
            String instanceNo,
            String name,
            String deviceType,
            String productCode,
            String status,
            Long rowVersion,
            boolean pendingDeactivate,
            LocalDateTime activatedAt,
            LocalDateTime purchasedAt,
            BigDecimal dailyUsdt,
            BigDecimal dailyNex,
            String gpuModel,
            Integer vramTotalGb,
            BigDecimal basePowerW,
            String location,
            BigDecimal actualPaidUsdt,
            BigDecimal cumulativeOutputUsdt) {
        public OwnedDevice(
                Long id, String instanceNo, String name, String deviceType, String productCode, String status,
                Long rowVersion, LocalDateTime activatedAt, LocalDateTime purchasedAt,
                BigDecimal dailyUsdt, BigDecimal dailyNex, String gpuModel, Integer vramTotalGb,
                BigDecimal basePowerW, String location, BigDecimal actualPaidUsdt, BigDecimal cumulativeOutputUsdt) {
            this(id, instanceNo, name, deviceType, productCode, status, rowVersion, false, activatedAt, purchasedAt,
                    dailyUsdt, dailyNex, gpuModel, vramTotalGb, basePowerW, location,
                    actualPaidUsdt, cumulativeOutputUsdt);
        }

        public OwnedDevice(
                Long id, String instanceNo, String name, String deviceType, String productCode, String status,
                LocalDateTime activatedAt, LocalDateTime purchasedAt, BigDecimal dailyUsdt, BigDecimal dailyNex,
                String gpuModel, Integer vramTotalGb, BigDecimal basePowerW, String location,
                BigDecimal actualPaidUsdt, BigDecimal cumulativeOutputUsdt) {
            this(id, instanceNo, name, deviceType, productCode, status, 0L, false, activatedAt, purchasedAt,
                    dailyUsdt, dailyNex, gpuModel, vramTotalGb, basePowerW, location,
                    actualPaidUsdt, cumulativeOutputUsdt);
        }
    }

    record ProductStock(Long id, String productNo, BigDecimal priceUsdt, Integer stock, String unlockPhase, String purchaseGateJson) {
        public ProductStock(Long id, String productNo, BigDecimal priceUsdt, Integer stock, String unlockPhase) {
            this(id, productNo, priceUsdt, stock, unlockPhase, null);
        }
    }

    record UserEventAttribution(String phase, Integer accountAgeMonths, String cohort) {
    }

    record PurchaseFacts(Integer rank, Integer activeDirect, BigDecimal teamVolumeUsd) {
    }

    record UserOrder(
            String orderNo,
            Long productId,
            String productNo,
            String productName,
            Integer quantity,
            BigDecimal unitPriceUsdt,
            BigDecimal discountUsdt,
            BigDecimal amountUsdt,
            String paymentMethod,
            String paymentStatus,
            String orderStatus,
            String activationStatus,
            String orderType,
            LocalDateTime placedAt,
            LocalDateTime paidAt,
            LocalDateTime activatedAt,
            String dataCenter,
            String tradeinNo,
            Long sourceDeviceId,
            Long targetDeviceId,
            String targetDeviceInstanceNo) {
    }

    record TrialClaim(Long id, String claimNo, String status, BigDecimal priceUsdt, BigDecimal earnedOffsetUsdt) {
    }
}
