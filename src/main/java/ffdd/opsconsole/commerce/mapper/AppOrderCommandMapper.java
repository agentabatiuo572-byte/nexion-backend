package ffdd.opsconsole.commerce.mapper;

import ffdd.opsconsole.shared.canonical.mapper.CanonicalStateMapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AppOrderCommandMapper extends BaseMapper<Object> {
    @Select("""
            SELECT t.id, t.quota_code quotaCode, t.product_no productNo,
                   t.monthly_quota monthlyQuota, t.status,
                   CASE WHEN UPPER(COALESCE(o.order_type,'SINGLE'))='BUNDLE' THEN oi.quantity ELSE o.quantity END quantity
              FROM nx_order o
              LEFT JOIN nx_order_item oi ON oi.order_no=o.order_no AND oi.is_deleted=0
               AND UPPER(COALESCE(o.order_type,'SINGLE'))='BUNDLE'
              JOIN nx_product p ON p.id=CASE WHEN UPPER(COALESCE(o.order_type,'SINGLE'))='BUNDLE'
                   THEN oi.product_id ELSE o.product_id END
              JOIN nx_team_hardware_quota_tier t ON t.product_no=p.product_no AND t.is_deleted=0
             WHERE o.order_no=#{orderNo} AND o.is_deleted=0
             ORDER BY t.id FOR UPDATE
            """)
    List<MonthlyQuota> lockOrderMonthlyQuotas(@Param("orderNo") String orderNo);

    @Select("""
            SELECT quantity FROM nx_team_hardware_quota_usage
             WHERE quota_tier_id=#{tierId} AND status='ACTIVE' AND is_deleted=0
               AND occurred_at >= #{from} AND occurred_at < #{until}
             ORDER BY id FOR UPDATE
            """)
    List<Integer> lockMonthlyQuotaUsage(@Param("tierId") Long tierId,
            @Param("from") java.time.LocalDateTime from, @Param("until") java.time.LocalDateTime until);

    @Insert("""
            INSERT INTO nx_team_hardware_quota_usage
              (quota_tier_id,quota_code,product_no,user_id,order_no,usage_type,quantity,status,occurred_at,remark)
            VALUES (#{tier.id},#{tier.quotaCode},#{tier.productNo},#{userId},#{orderNo},'SOLD',#{tier.quantity},'ACTIVE',#{occurredAt},'Canonical wallet checkout')
            """)
    int consumeMonthlyQuota(@Param("tier") MonthlyQuota tier, @Param("userId") Long userId,
            @Param("orderNo") String orderNo, @Param("occurredAt") java.time.LocalDateTime occurredAt);

    record MonthlyQuota(Long id, String quotaCode, String productNo, Integer monthlyQuota, Integer status, Integer quantity) { }

    @Select("SELECT sandbox FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0 LIMIT 1")
    Integer activeUserEnvironment(@Param("userId") Long userId);

    @Select("SELECT id,sandbox FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0 FOR UPDATE")
    CanonicalStateMapper.UserLock lockUser(@Param("userId") Long userId);

    @Select("""
            SELECT order_no orderNo,user_id userId,product_id productId,quantity,order_type orderType,
                   item_count itemCount,
                   payment_status paymentStatus,order_status orderStatus,activation_status activationStatus
              FROM nx_order WHERE order_no=#{orderNo} AND is_deleted=0 LIMIT 1 FOR UPDATE
            """)
    OrderRow lockOrder(@Param("orderNo") String orderNo);

    @Select("""
            SELECT COUNT(1)
              FROM nx_vietqr_intent i
              LEFT JOIN nx_hdpay_payin_order h ON h.merchant_order_id=i.intent_no
             WHERE i.target_order_no=#{orderNo}
               AND i.settlement_target_type='COMMERCE_ORDER'
               AND i.is_deleted=0
               AND ((i.status='AWAITING_PAYMENT' AND i.expires_at > NOW())
                    OR (h.submission_status IN ('PENDING','CREATED','SUBMIT_UNKNOWN')
                        AND h.settlement_status IN ('UNSETTLED','MANUAL_REVIEW')))
            """)
    long countNonCancellableHdPaySessions(@Param("orderNo") String orderNo);

    /** Lock E1 product rows before revalidating sale state so an admin update cannot race settlement. */
    @Select("""
            SELECT p.id
              FROM nx_order o
              LEFT JOIN nx_order_item oi ON oi.order_no=o.order_no AND oi.is_deleted=0
              JOIN nx_product p ON p.id=CASE
                WHEN UPPER(COALESCE(o.order_type,'SINGLE'))='BUNDLE' THEN oi.product_id
                ELSE o.product_id END
             WHERE o.order_no=#{orderNo} AND o.is_deleted=0
             ORDER BY p.id
             FOR UPDATE
            """)
    List<Long> lockOrderProductsForPayment(@Param("orderNo") String orderNo);

    /** E1 updates product then SKU extension; payment takes locks in the same order. */
    @Select("""
            SELECT s.id
              FROM nx_order o
              LEFT JOIN nx_order_item oi ON oi.order_no=o.order_no AND oi.is_deleted=0
              JOIN nx_product p ON p.id=CASE
                WHEN UPPER(COALESCE(o.order_type,'SINGLE'))='BUNDLE' THEN oi.product_id
                ELSE o.product_id END
              JOIN nx_admin_device_sku s ON s.sku_id=p.product_no AND s.is_deleted=0
             WHERE o.order_no=#{orderNo} AND o.is_deleted=0
             ORDER BY s.id
             FOR UPDATE
            """)
    List<Long> lockOrderSkusForPayment(@Param("orderNo") String orderNo);

    /**
     * Payment is a second authoritative storefront boundary: PENDING_PAYMENT
     * reservations cannot settle after E1 hides, retires, or deletes any item.
     */
    @Select("""
            SELECT CASE WHEN EXISTS (
                SELECT 1
                  FROM nx_order o
                  LEFT JOIN nx_order_item oi ON oi.order_no=o.order_no AND oi.is_deleted=0
                  LEFT JOIN nx_product p ON p.id=CASE
                    WHEN UPPER(COALESCE(o.order_type,'SINGLE'))='BUNDLE' THEN oi.product_id
                    ELSE o.product_id END
                 WHERE o.order_no=#{orderNo} AND o.is_deleted=0
                   AND (p.id IS NULL OR p.is_deleted<>0
                     OR UPPER(COALESCE(p.status,'')) NOT IN ('ACTIVE','ON_SALE')
                     OR COALESCE(p.store_visible,1)<>1
                     OR UPPER(COALESCE(NULLIF(p.product_type,''),'')) NOT IN ('DEVICE','SERVER','SHARE')
                     OR UPPER(COALESCE(NULLIF(p.inventory_mode,''),'')) NOT IN ('FINITE','UNLIMITED')
                     OR (UPPER(COALESCE(NULLIF(p.inventory_mode,''),''))='UNLIMITED'
                         AND UPPER(COALESCE(NULLIF(p.product_type,''),''))<>'SHARE')
                     OR (UPPER(COALESCE(NULLIF(p.product_type,''),'')) IN ('DEVICE','SERVER')
                         AND (NULLIF(TRIM(p.gpu_model),'') IS NULL
                           OR p.vram_total_gb IS NULL OR p.vram_total_gb<=0
                           OR (SELECT NULLIF(TRIM(s.power_text),'') FROM nx_admin_device_sku s
                                WHERE s.sku_id=p.product_no AND s.is_deleted=0 LIMIT 1) IS NULL
                           OR (SELECT NULLIF(TRIM(s.datacenter),'') FROM nx_admin_device_sku s
                                WHERE s.sku_id=p.product_no AND s.is_deleted=0 LIMIT 1) IS NULL)))
            ) THEN 1 ELSE 0 END
            """)
    boolean hasNonPayableOrderProduct(@Param("orderNo") String orderNo);

    @Select("""
            SELECT setting_value FROM nx_emergency_control_setting
             WHERE setting_key=#{settingKey} AND is_deleted=0
             LIMIT 1 FOR UPDATE
            """)
    String emergencyValue(@Param("settingKey") String settingKey);

    @Select("""
            SELECT order_no orderNo,product_id productId,product_no productNo,quantity,
                   COALESCE(lifetime_quota_reserved,0) quotaReserved,
                   lifetime_quota_gate_generation quotaGateGeneration
              FROM nx_order_item WHERE order_no=#{orderNo} AND is_deleted=0 ORDER BY sort_order,id FOR UPDATE
            """)
    List<ItemRow> lockItems(@Param("orderNo") String orderNo);

    @Select("SELECT id,COALESCE(stock,0) stock,COALESCE(sold_count,0) soldCount,inventory_mode inventoryMode FROM nx_product WHERE id=#{productId} AND is_deleted=0 FOR UPDATE")
    ProductRow lockProduct(@Param("productId") Long productId);

    @Update("""
            UPDATE nx_product
               SET stock=CASE WHEN inventory_mode='FINITE' THEN stock+#{quantity} ELSE stock END,
                   sold_count=sold_count-#{quantity},
                   updated_at=GREATEST(CURRENT_TIMESTAMP(6),updated_at + INTERVAL 1 MICROSECOND)
             WHERE id=#{productId} AND is_deleted=0
               AND (inventory_mode='UNLIMITED' OR stock <= 2147483647-#{quantity})
               AND sold_count >= #{quantity}
            """)
    int returnStock(@Param("productId") Long productId, @Param("quantity") Integer quantity);

    @Select("""
            SELECT CAST(JSON_UNQUOTE(JSON_EXTRACT(purchase_gate_json,'$.quotaSold')) AS UNSIGNED) quotaSold,
                   purchase_gate_generation quotaGateGeneration
              FROM nx_admin_device_sku
             WHERE sku_id=#{productNo} AND is_deleted=0
               AND JSON_VALID(purchase_gate_json)=1
               AND JSON_EXTRACT(purchase_gate_json,'$.quotaSold') IS NOT NULL
             LIMIT 1 FOR UPDATE
            """)
    QuotaState lockLifetimeQuotaState(@Param("productNo") String productNo);

    @Update("""
            UPDATE nx_admin_device_sku
               SET purchase_gate_json=JSON_SET(purchase_gate_json,'$.quotaSold',
                   CAST(JSON_UNQUOTE(JSON_EXTRACT(purchase_gate_json,'$.quotaSold')) AS UNSIGNED)-#{quantity}),
                   updated_at=NOW()
             WHERE sku_id=#{productNo} AND is_deleted=0
               AND purchase_gate_generation=#{expectedGateGeneration}
               AND JSON_VALID(purchase_gate_json)=1
               AND JSON_EXTRACT(purchase_gate_json,'$.quotaSold') IS NOT NULL
               AND CAST(JSON_UNQUOTE(JSON_EXTRACT(purchase_gate_json,'$.quotaSold')) AS UNSIGNED)>=#{quantity}
            """)
    int releaseLifetimeQuota(@Param("productNo") String productNo,
                             @Param("quantity") Integer quantity,
                             @Param("expectedGateGeneration") Long expectedGateGeneration);

    @Update("""
            UPDATE nx_order SET payment_status='CANCELLED',order_status='CANCELLED',activation_status='WAITING_PAYMENT',
                   updated_at=GREATEST(CURRENT_TIMESTAMP(6),updated_at + INTERVAL 1 MICROSECOND)
             WHERE order_no=#{orderNo} AND user_id=#{userId} AND is_deleted=0
               AND UPPER(order_status)='PENDING_PAYMENT' AND UPPER(payment_status)='PENDING'
            """)
    int cancelOrder(@Param("orderNo") String orderNo, @Param("userId") Long userId);

    @Select("""
            SELECT o.order_no orderNo,o.user_id userId
              FROM nx_order o
              JOIN nx_user u ON u.id=o.user_id AND u.sandbox=0
             WHERE o.is_deleted=0 AND u.is_deleted=0 AND u.status='ACTIVE'
               AND UPPER(o.order_status)='PENDING_PAYMENT'
               AND UPPER(o.payment_status)='PENDING'
               AND o.created_at <= TIMESTAMPADD(MINUTE, -#{ttlMinutes}, NOW())
               AND NOT EXISTS (
                 SELECT 1
                   FROM nx_vietqr_intent i
                   LEFT JOIN nx_hdpay_payin_order h ON h.merchant_order_id=i.intent_no
                  WHERE i.target_order_no=o.order_no
                    AND i.settlement_target_type='COMMERCE_ORDER'
                    AND i.is_deleted=0
                    AND ((i.status='AWAITING_PAYMENT' AND i.expires_at > NOW())
                      OR (h.submission_status IN ('PENDING','CREATED','SUBMIT_UNKNOWN')
                          AND h.settlement_status IN ('UNSETTLED','MANUAL_REVIEW')))
               )
             ORDER BY o.created_at,o.id
             LIMIT #{limit}
            """)
    List<PendingOrderExpiryCandidate> expiredPendingOrders(
            @Param("ttlMinutes") Integer ttlMinutes, @Param("limit") Integer limit);

    @Update("""
            UPDATE nx_order SET payment_status='EXPIRED',order_status='EXPIRED',
                   activation_status='WAITING_PAYMENT',
                   updated_at=GREATEST(CURRENT_TIMESTAMP(6),updated_at + INTERVAL 1 MICROSECOND)
             WHERE order_no=#{orderNo} AND user_id=#{userId} AND is_deleted=0
               AND UPPER(order_status)='PENDING_PAYMENT' AND UPPER(payment_status)='PENDING'
            """)
    int expireOrder(@Param("orderNo") String orderNo, @Param("userId") Long userId);

    @Select("""
            SELECT grant_id grantId
              FROM nx_growth_voucher_grant
             WHERE user_id=#{userId} AND used_order_no=#{orderNo}
               AND status='USED' AND is_deleted=0
             ORDER BY grant_id FOR UPDATE
            """)
    List<VoucherGrantRow> lockUsedVouchersForOrder(@Param("userId") Long userId,
                                                   @Param("orderNo") String orderNo);

    @Update("""
            UPDATE nx_growth_voucher_grant
               SET status='AVAILABLE',used_order_no=NULL,used_at=NULL,updated_at=NOW(6)
             WHERE grant_id=#{grantId} AND user_id=#{userId} AND used_order_no=#{orderNo}
               AND status='USED' AND is_deleted=0
            """)
    int restoreVoucher(@Param("grantId") String grantId,
                       @Param("userId") Long userId,
                       @Param("orderNo") String orderNo);

    @Select("""
            SELECT o.order_no orderNo,o.user_id userId,o.product_id productId,o.quantity,
                   o.order_type orderType,o.item_count itemCount,
                   o.amount_usdt amountUsdt,o.payment_no paymentNo,o.payment_status paymentStatus,
                   o.order_status orderStatus,o.activation_status activationStatus
              FROM nx_order o
              JOIN nx_user u ON u.id=o.user_id AND u.sandbox=0
             WHERE o.order_no=#{orderNo} AND o.is_deleted=0
               AND u.status='ACTIVE' AND u.is_deleted=0
             LIMIT 1 FOR UPDATE
            """)
    DevelopmentPayOrder lockDevelopmentPayOrder(@Param("orderNo") String orderNo);

    @Select("""
            SELECT COUNT(*)
              FROM nx_order
             WHERE order_no=#{orderNo} AND user_id=#{userId} AND is_deleted=0
               AND UPPER(payment_status)='PENDING'
               AND UPPER(order_status)='PENDING_PAYMENT'
               AND created_at <= DATE_SUB(NOW(6), INTERVAL #{ttlMinutes} MINUTE)
            """)
    int countExpiredPayableOrder(@Param("orderNo") String orderNo,
                                 @Param("userId") Long userId,
                                 @Param("ttlMinutes") int ttlMinutes);

    @Select("""
            SELECT product_id productId,product_no productNo,quantity,sort_order sortOrder
              FROM nx_order_item
             WHERE order_no=#{orderNo} AND is_deleted=0
             ORDER BY sort_order,id
             FOR UPDATE
            """)
    List<DevelopmentPaymentItem> lockDevelopmentPaymentItems(@Param("orderNo") String orderNo);

    @Select("""
            SELECT w.usdt_available usdtAvailable,w.version
              FROM nx_user_wallet w
              JOIN nx_user u ON u.id=w.user_id AND u.sandbox=0
             WHERE w.user_id=#{userId} AND w.is_deleted=0
               AND u.status='ACTIVE' AND u.is_deleted=0
             LIMIT 1 FOR UPDATE
            """)
    DevelopmentWallet lockDevelopmentWallet(@Param("userId") Long userId);

    @Update("""
            UPDATE nx_user_wallet w
              JOIN nx_user u ON u.id=w.user_id AND u.sandbox=0
               SET w.usdt_available=w.usdt_available-#{amount},w.version=w.version+1,w.updated_at=NOW(6)
             WHERE w.user_id=#{userId} AND w.is_deleted=0
               AND u.status='ACTIVE' AND u.is_deleted=0
               AND w.version=#{expectedVersion} AND w.usdt_available>=#{amount}
            """)
    int debitDevelopmentWallet(@Param("userId") Long userId,
                               @Param("amount") BigDecimal amount,
                               @Param("expectedVersion") Long expectedVersion);

    @Insert("""
            INSERT INTO nx_wallet_ledger
              (user_id,biz_no,biz_type,asset,direction,amount,balance_after,status,remark,
               created_at,updated_at,is_deleted)
            VALUES
              (#{userId},#{orderNo},'ORDER_PURCHASE','USDT','OUT',#{amount},#{balanceAfter},'SUCCESS',
               'NexGrid wallet order settlement',NOW(6),NOW(6),0)
            """)
    int insertDevelopmentPurchaseLedger(@Param("orderNo") String orderNo,
                                        @Param("userId") Long userId,
                                        @Param("amount") BigDecimal amount,
                                        @Param("balanceAfter") BigDecimal balanceAfter);

    @Update("""
            UPDATE nx_order o
              JOIN nx_user u ON u.id=o.user_id AND u.sandbox=0
               SET o.payment_no=#{paymentNo},o.payment_status='PAID',o.order_status='COMPLETED',
                   o.activation_status='ACTIVATED',o.paid_at=COALESCE(o.paid_at,NOW(6)),
                   o.updated_at=GREATEST(NOW(6),o.updated_at + INTERVAL 1 MICROSECOND)
             WHERE o.order_no=#{orderNo} AND o.user_id=#{userId} AND o.is_deleted=0
               AND u.status='ACTIVE' AND u.is_deleted=0
               AND UPPER(o.payment_status)='PENDING'
               AND UPPER(o.order_status)='PENDING_PAYMENT'
               AND UPPER(o.activation_status)='WAITING_PAYMENT'
            """)
    int markDevelopmentOrderActivated(@Param("orderNo") String orderNo,
                                      @Param("userId") Long userId,
                                      @Param("paymentNo") String paymentNo);

    @Insert("""
            INSERT IGNORE INTO nx_payment_record
              (payment_no,order_no,user_id,provider,provider_payment_id,amount_usdt,currency,
               payment_status,signature_status,raw_callback,paid_at,created_at,updated_at,is_deleted)
            VALUES
              (#{paymentNo},#{orderNo},#{userId},
               CASE WHEN #{amountUsdt}=0 THEN 'VOUCHER' ELSE 'NEXGRID_WALLET' END,
               #{paymentNo},#{amountUsdt},'USDT',
               'PAID','NOT_APPLICABLE',
               CASE WHEN #{amountUsdt}=0
                    THEN '{"mode":"voucher","serverCanonical":true}'
                    ELSE '{"mode":"wallet","serverCanonical":true}' END,
               NOW(6),NOW(6),NOW(6),0)
            """)
    int insertDevelopmentPayment(@Param("orderNo") String orderNo,
                                 @Param("userId") Long userId,
                                 @Param("paymentNo") String paymentNo,
                                 @Param("amountUsdt") BigDecimal amountUsdt);

    @Insert("""
            INSERT IGNORE INTO nx_user_device
              (user_id,source_order_no,product_id,product_code,product_tier,instance_no,name,device_type,
               generation,gpu_model,vram_total_gb,base_power_w,dc_location,price_usdt_snapshot,
               ownership_status,source_channel,source_environment,run_id,status,hashrate,daily_usdt,daily_nex,
               last_seen_at,purchased_at,activated_at,pending_deactivate,row_version,created_at,updated_at,is_deleted)
            SELECT #{userId},o.order_no,p.id,p.product_no,COALESCE(p.tier,'STANDARD'),#{instanceNo},p.name,
                   COALESCE(NULLIF(p.product_type,''),'BOX'),GREATEST(COALESCE(p.generation,1),1),
                   COALESCE(NULLIF(p.gpu_model,''),NULLIF(s.gpu,''),'Nexion accelerator'),
                   GREATEST(COALESCE(p.vram_total_gb,0),0),
                   CASE WHEN UPPER(p.product_type) IN ('SHARE','CLOUD_SHARE') THEN 0
                        ELSE CAST(TRIM(REPLACE(REPLACE(s.power_text,'W',''),'w','')) AS DECIMAL(18,6)) END,
                   COALESCE(NULLIF(s.datacenter,''),'NexGrid DC'),
                   CASE WHEN o.quantity > 0 THEN o.amount_usdt/o.quantity ELSE p.price_usdt END,
                   'OWNED','ORDER','PRODUCTION','','ACTIVE',GREATEST(COALESCE(p.hashrate,0),0),
                   GREATEST(COALESCE(p.estimated_daily_usdt,0),0),GREATEST(COALESCE(p.daily_nex,0),0),
                   NOW(6),NOW(6),NOW(6),0,0,NOW(6),NOW(6),0
              FROM nx_order o
              JOIN nx_user u ON u.id=o.user_id AND u.sandbox=0
              JOIN nx_product p ON p.id=o.product_id AND p.is_deleted=0
              LEFT JOIN nx_admin_device_sku s ON s.sku_id=p.product_no AND s.is_deleted=0
             WHERE o.order_no=#{orderNo} AND o.user_id=#{userId} AND o.is_deleted=0
               AND o.payment_status='PAID' AND o.order_status='COMPLETED'
               AND o.activation_status='ACTIVATED' AND #{unitIndex} >= 0 AND #{unitIndex} < o.quantity
               AND (UPPER(p.product_type) IN ('SHARE','CLOUD_SHARE') OR
                    (TRIM(s.power_text) REGEXP '^[0-9]+([.][0-9]+)?[[:space:]]*[Ww]?$'
                     AND CAST(TRIM(REPLACE(REPLACE(s.power_text,'W',''),'w','')) AS DECIMAL(18,6)) > 0))
            """)
    int insertDevelopmentDevice(@Param("orderNo") String orderNo,
                                @Param("userId") Long userId,
                                @Param("instanceNo") String instanceNo,
                                @Param("unitIndex") Integer unitIndex);

    @Insert("""
            INSERT IGNORE INTO nx_user_device
              (user_id,source_order_no,product_id,product_code,product_tier,instance_no,name,device_type,
               generation,gpu_model,vram_total_gb,base_power_w,dc_location,price_usdt_snapshot,
               ownership_status,source_channel,source_environment,run_id,status,hashrate,daily_usdt,daily_nex,
               last_seen_at,purchased_at,activated_at,pending_deactivate,row_version,created_at,updated_at,is_deleted)
            SELECT #{userId},o.order_no,p.id,p.product_no,COALESCE(p.tier,'STANDARD'),#{instanceNo},p.name,
                   COALESCE(NULLIF(p.product_type,''),'BOX'),GREATEST(COALESCE(p.generation,1),1),
                   COALESCE(NULLIF(p.gpu_model,''),NULLIF(s.gpu,''),'Nexion accelerator'),
                   GREATEST(COALESCE(p.vram_total_gb,0),0),
                   CASE WHEN UPPER(p.product_type) IN ('SHARE','CLOUD_SHARE') THEN 0
                        ELSE CAST(TRIM(REPLACE(REPLACE(s.power_text,'W',''),'w','')) AS DECIMAL(18,6)) END,
                   COALESCE(NULLIF(s.datacenter,''),'NexGrid DC'),
                   CASE WHEN oi.quantity > 0 THEN oi.line_amount_usdt/oi.quantity ELSE p.price_usdt END,
                   'OWNED','ORDER','PRODUCTION','','ACTIVE',GREATEST(COALESCE(p.hashrate,0),0),
                   GREATEST(COALESCE(p.estimated_daily_usdt,0),0),GREATEST(COALESCE(p.daily_nex,0),0),
                   NOW(6),NOW(6),NOW(6),0,0,NOW(6),NOW(6),0
              FROM nx_order o
              JOIN nx_user u ON u.id=o.user_id AND u.sandbox=0
              JOIN nx_order_item oi ON oi.order_no=o.order_no AND oi.product_id=#{productId}
                   AND oi.is_deleted=0 AND #{unitIndex} >= 0 AND #{unitIndex} < oi.quantity
              JOIN nx_product p ON p.id=oi.product_id AND p.is_deleted=0
              LEFT JOIN nx_admin_device_sku s ON s.sku_id=p.product_no AND s.is_deleted=0
             WHERE o.order_no=#{orderNo} AND o.user_id=#{userId} AND o.order_type='BUNDLE'
               AND o.is_deleted=0 AND o.payment_status='PAID' AND o.order_status='COMPLETED'
               AND o.activation_status='ACTIVATED'
               AND (UPPER(p.product_type) IN ('SHARE','CLOUD_SHARE') OR
                    (TRIM(s.power_text) REGEXP '^[0-9]+([.][0-9]+)?[[:space:]]*[Ww]?$'
                     AND CAST(TRIM(REPLACE(REPLACE(s.power_text,'W',''),'w','')) AS DECIMAL(18,6)) > 0))
            """)
    int insertWalletDevice(@Param("orderNo") String orderNo,
                           @Param("userId") Long userId,
                           @Param("productId") Long productId,
                           @Param("instanceNo") String instanceNo,
                           @Param("unitIndex") Integer unitIndex);

    @Select("""
            SELECT id deviceId,instance_no instanceNo
              FROM nx_user_device
             WHERE instance_no=#{instanceNo} AND status='ACTIVE' AND is_deleted=0
             LIMIT 1 FOR UPDATE
            """)
    DevelopmentDeviceFact developmentDeviceFact(@Param("instanceNo") String instanceNo);

    @Select("""
            SELECT COALESCE((SELECT config_value FROM nx_config_item
                              WHERE config_key='growth.phase.current' AND status=1 AND is_deleted=0 LIMIT 1),'P1') phase,
                   GREATEST(TIMESTAMPDIFF(MONTH,u.created_at,NOW()),0) accountAgeMonths,
                   DATE_FORMAT(u.created_at,'%x-W%v') cohort
              FROM nx_user u WHERE u.id=#{userId} AND u.is_deleted=0 LIMIT 1
            """)
    Map<String, Object> attribution(@Param("userId") Long userId);

    @Insert("""
            INSERT IGNORE INTO nx_order_state_history
              (order_no,from_state,to_state,reason,operator,idempotency_key,created_at)
            VALUES
              (#{orderNo},#{fromState},#{toState},'NexGrid wallet checkout',
               'system:wallet-checkout',#{idempotencyKey},NOW(6))
            """)
    int insertDevelopmentOrderHistory(@Param("orderNo") String orderNo,
                                      @Param("fromState") String fromState,
                                      @Param("toState") String toState,
                                      @Param("idempotencyKey") String idempotencyKey);

    record OrderRow(String orderNo, Long userId, Long productId, Integer quantity, String orderType, Integer itemCount,
                    String paymentStatus, String orderStatus, String activationStatus) {
        public OrderRow(String orderNo, Long userId, Long productId, Integer quantity, String orderType,
                        String paymentStatus, String orderStatus, String activationStatus) {
            this(orderNo, userId, productId, quantity, orderType, null, paymentStatus, orderStatus, activationStatus);
        }
    }
    record ItemRow(String orderNo, Long productId, String productNo, Integer quantity, Boolean quotaReserved,
                   Long quotaGateGeneration) {
        public ItemRow(String orderNo, Long productId, String productNo, Integer quantity, Boolean quotaReserved) {
            this(orderNo, productId, productNo, quantity, quotaReserved, null);
        }
        public ItemRow(String orderNo, Long productId, String productNo, Integer quantity) {
            this(orderNo, productId, productNo, quantity, false, null);
        }
    }
    record QuotaState(Integer quotaSold, Long quotaGateGeneration) { }
    record VoucherGrantRow(String grantId) { }
    record PendingOrderExpiryCandidate(String orderNo, Long userId) { }
    record ProductRow(Long id, Integer stock, Integer soldCount, String inventoryMode) {
        public ProductRow(Long id, Integer stock, Integer soldCount) {
            this(id, stock, soldCount, "FINITE");
        }
    }
    record DevelopmentPayOrder(String orderNo, Long userId, Long productId, Integer quantity,
                               String orderType, Integer itemCount, BigDecimal amountUsdt,
                               String paymentNo, String paymentStatus,
                               String orderStatus, String activationStatus) {
        public DevelopmentPayOrder(String orderNo, Long userId, Long productId, Integer quantity,
                                   BigDecimal amountUsdt, String paymentNo, String paymentStatus,
                                   String orderStatus, String activationStatus) {
            this(orderNo, userId, productId, quantity, "SINGLE", 1, amountUsdt, paymentNo,
                    paymentStatus, orderStatus, activationStatus);
        }
    }
    record DevelopmentPaymentItem(Long productId, String productNo, Integer quantity, Integer sortOrder) { }
    record DevelopmentWallet(BigDecimal usdtAvailable, Long version) { }
    record DevelopmentDeviceFact(Long deviceId, String instanceNo) { }
}
