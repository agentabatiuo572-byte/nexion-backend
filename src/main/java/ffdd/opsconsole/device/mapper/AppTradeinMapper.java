package ffdd.opsconsole.device.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.device.infrastructure.UserDeviceEntity;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AppTradeinMapper extends BaseMapper<UserDeviceEntity> {
    @Select("""
            SELECT config_key AS configKey, config_value AS configValue
              FROM nx_compute_e3_config
             WHERE is_deleted=0
               AND config_key IN (
                 'tradeinEnabled','eligibility',
                 'tradeinLadderCut1','tradeinLadderCut2','tradeinLadderCut3','tradeinLadderCut4',
                 'tradeinLadderCredit1','tradeinLadderCredit2','tradeinLadderCredit3',
                 'tradeinLadderCredit4','tradeinLadderCredit5',
                 'tradeinRequireHigherPrice','tradeinMaxDevicesPerOrder',
                 'earlyAccessEnabled','earlyAccessLeadDays'
               )
            """)
    List<ConfigRow> listTradeinConfig();

    @Select("SELECT sandbox FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0 LIMIT 1")
    Integer activeUserEnvironment(@Param("userId") Long userId);

    @Select("SELECT id FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0 AND sandbox=0 FOR UPDATE")
    Long lockActiveUser(@Param("userId") Long userId);

    @Select("""
            SELECT COUNT(*)
              FROM nx_user_device
             WHERE user_id=#{userId} AND is_deleted=0
               AND UPPER(ownership_status)='OWNED'
               AND UPPER(status) IN ('ACTIVE','ONLINE','BUSY','RUNNING')
               AND UPPER(COALESCE(NULLIF(device_type,''),'DEVICE')) <> 'SHARE'
               AND deactivated_at IS NULL AND pending_deactivate=0
            """)
    int countActiveDevices(@Param("userId") Long userId);

    @Select("""
            SELECT COALESCE((SELECT CAST(config_value AS UNSIGNED) FROM nx_config_item
                              WHERE config_key='device.max_active_slots' AND status=1 AND is_deleted=0 LIMIT 1),3)
            """)
    int deviceSlotCap();

    @Select("SELECT user_level FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0")
    String userLevel(@Param("userId") Long userId);

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
    PurchaseGateFacts purchaseGateFacts(@Param("userId") Long userId);

    @Select("""
            SELECT s.purchase_gate_json FROM nx_admin_device_sku s
             WHERE s.sku_id=#{productNo} AND s.is_deleted=0 LIMIT 1
            """)
    String purchaseGateJson(@Param("productNo") String productNo);

    @Select("""
            SELECT s.purchase_gate_json FROM nx_admin_device_sku s
             WHERE s.sku_id=#{productNo} AND s.is_deleted=0 LIMIT 1 FOR UPDATE
            """)
    String lockPurchaseGateJson(@Param("productNo") String productNo);

    /**
     * Atomically reserves a production purchase-gate quota unit.  The cap and
     * sold counter are read from the same canonical JSON row, so concurrent
     * trade-ins (including different entry keys) cannot oversell it.
     */
    @Update("""
            UPDATE nx_admin_device_sku
               SET purchase_gate_json=JSON_SET(purchase_gate_json,'$.quotaSold',
                   CAST(JSON_UNQUOTE(JSON_EXTRACT(purchase_gate_json,'$.quotaSold')) AS UNSIGNED)+#{quantity}),
                   updated_at=NOW()
             WHERE sku_id=#{productNo} AND is_deleted=0
               AND JSON_VALID(purchase_gate_json)=1
               AND JSON_UNQUOTE(JSON_EXTRACT(purchase_gate_json,'$.enforce'))='true'
               AND JSON_EXTRACT(purchase_gate_json,'$.quotaCap') IS NOT NULL
               AND JSON_EXTRACT(purchase_gate_json,'$.quotaSold') IS NOT NULL
               AND CAST(JSON_UNQUOTE(JSON_EXTRACT(purchase_gate_json,'$.quotaSold')) AS UNSIGNED)+#{quantity}
                   <= CAST(JSON_UNQUOTE(JSON_EXTRACT(purchase_gate_json,'$.quotaCap')) AS UNSIGNED)
            """)
    int consumePurchaseQuota(@Param("productNo") String productNo, @Param("quantity") Integer quantity);

    @Select("""
            SELECT COALESCE((SELECT config_value FROM nx_config_item
                              WHERE config_key='growth.phase.current' AND status=1 AND is_deleted=0 LIMIT 1),'P1') AS phase,
                   GREATEST(TIMESTAMPDIFF(MONTH,u.created_at,NOW()),0) AS accountAgeMonths,
                   DATE_FORMAT(u.created_at,'%x-W%v') AS cohort
              FROM nx_user u
             WHERE u.id=#{userId} AND u.status='ACTIVE' AND u.is_deleted=0
            """)
    UserEventAttribution userEventAttribution(@Param("userId") Long userId);

    @Select("""
            SELECT d.id, d.user_id AS userId, d.instance_no AS instanceNo,
                   COALESCE(d.product_id,p.id) AS productId,
                   COALESCE(NULLIF(d.product_code,''),p.product_no) AS productNo,
                   COALESCE(NULLIF(d.name,''),p.name) AS productName,
                   COALESCE(NULLIF(d.product_tier,''),p.tier) AS productTier,
                   d.status,
                   COALESCE(NULLIF(CASE WHEN o.quantity>0 THEN o.amount_usdt/o.quantity END,0),
                            NULLIF(d.price_usdt_snapshot,0),p.price_usdt,0) AS actualPaidUsdt
              FROM nx_user_device d
              LEFT JOIN nx_product p ON p.id=d.product_id AND p.is_deleted=0
              LEFT JOIN nx_order o ON o.order_no=d.source_order_no AND o.user_id=d.user_id
                                  AND o.payment_status='PAID' AND o.is_deleted=0
             WHERE d.id=#{deviceId} AND d.user_id=#{userId} AND d.is_deleted=0
               AND UPPER(d.ownership_status)='OWNED'
               AND UPPER(d.status) IN ('ACTIVE','ONLINE')
               AND UPPER(COALESCE(NULLIF(d.device_type,''),'DEVICE')) <> 'SHARE'
               AND d.deactivated_at IS NULL AND d.pending_deactivate=0
               AND NOT EXISTS (
                 SELECT 1 FROM nx_compute_task t
                  WHERE t.user_id=d.user_id AND t.user_device_id=d.id AND t.is_deleted=0
                    AND UPPER(t.status) IN ('CLAIMED','RUNNING')
               )
             LIMIT 1
            """)
    SourceDevice findSourceDevice(@Param("userId") Long userId, @Param("deviceId") Long deviceId);

    @Select("""
            SELECT d.id, d.user_id AS userId, d.instance_no AS instanceNo,
                   COALESCE(d.product_id,p.id) AS productId,
                   COALESCE(NULLIF(d.product_code,''),p.product_no) AS productNo,
                   COALESCE(NULLIF(d.name,''),p.name) AS productName,
                   COALESCE(NULLIF(d.product_tier,''),p.tier) AS productTier,
                   d.status,
                   COALESCE(NULLIF(CASE WHEN o.quantity>0 THEN o.amount_usdt/o.quantity END,0),
                            NULLIF(d.price_usdt_snapshot,0),p.price_usdt,0) AS actualPaidUsdt
              FROM nx_user_device d
              LEFT JOIN nx_product p ON p.id=d.product_id AND p.is_deleted=0
              LEFT JOIN nx_order o ON o.order_no=d.source_order_no AND o.user_id=d.user_id
                                  AND o.payment_status='PAID' AND o.is_deleted=0
             WHERE d.id=#{deviceId} AND d.user_id=#{userId} AND d.is_deleted=0
               AND UPPER(d.ownership_status)='OWNED'
               AND UPPER(d.status) IN ('ACTIVE','ONLINE')
               AND UPPER(COALESCE(NULLIF(d.device_type,''),'DEVICE')) <> 'SHARE'
               AND d.deactivated_at IS NULL AND d.pending_deactivate=0
               AND NOT EXISTS (
                 SELECT 1 FROM nx_compute_task t
                  WHERE t.user_id=d.user_id AND t.user_device_id=d.id AND t.is_deleted=0
                    AND UPPER(t.status) IN ('CLAIMED','RUNNING')
               )
             LIMIT 1 FOR UPDATE
            """)
    SourceDevice lockSourceDevice(@Param("userId") Long userId, @Param("deviceId") Long deviceId);

    @Select("""
            SELECT d.id, d.user_id AS userId, d.instance_no AS instanceNo,
                   COALESCE(d.product_id,p.id) AS productId,
                   COALESCE(NULLIF(d.product_code,''),p.product_no) AS productNo,
                   COALESCE(NULLIF(d.name,''),p.name) AS productName,
                   COALESCE(NULLIF(d.product_tier,''),p.tier) AS productTier,
                   d.status,
                   COALESCE(NULLIF(CASE WHEN o.quantity>0 THEN o.amount_usdt/o.quantity END,0),
                            NULLIF(d.price_usdt_snapshot,0),p.price_usdt,0) AS actualPaidUsdt
              FROM nx_user_device d
              LEFT JOIN nx_product p ON p.id=d.product_id AND p.is_deleted=0
              LEFT JOIN nx_order o ON o.order_no=d.source_order_no AND o.user_id=d.user_id
                                  AND o.payment_status='PAID' AND o.is_deleted=0
             WHERE d.user_id=#{userId} AND d.is_deleted=0
               AND UPPER(d.ownership_status)='OWNED'
               AND UPPER(d.status) IN ('ACTIVE','ONLINE')
               AND UPPER(COALESCE(NULLIF(d.device_type,''),'DEVICE')) <> 'SHARE'
               AND d.deactivated_at IS NULL AND d.pending_deactivate=0
               AND NOT EXISTS (
                 SELECT 1 FROM nx_compute_task t
                  WHERE t.user_id=d.user_id AND t.user_device_id=d.id AND t.is_deleted=0
                    AND UPPER(t.status) IN ('CLAIMED','RUNNING')
               )
             ORDER BY d.id
            """)
    List<SourceDevice> listTradeinSourceCandidates(@Param("userId") Long userId);

    @Select("""
            SELECT d.id, d.user_id AS userId, d.instance_no AS instanceNo,
                   COALESCE(d.product_id,p.id) AS productId,
                   COALESCE(NULLIF(d.product_code,''),p.product_no) AS productNo,
                   COALESCE(NULLIF(d.name,''),p.name) AS productName,
                   COALESCE(NULLIF(d.product_tier,''),p.tier) AS productTier,
                   d.status,
                   COALESCE(NULLIF(CASE WHEN o.quantity>0 THEN o.amount_usdt/o.quantity END,0),
                            NULLIF(d.price_usdt_snapshot,0),p.price_usdt,0) AS actualPaidUsdt
              FROM nx_user_device d
              LEFT JOIN nx_product p ON p.id=d.product_id AND p.is_deleted=0
              LEFT JOIN nx_order o ON o.order_no=d.source_order_no AND o.user_id=d.user_id
                                  AND o.payment_status='PAID' AND o.is_deleted=0
             WHERE d.user_id=#{userId} AND d.is_deleted=0
               AND UPPER(d.ownership_status)='OWNED'
               AND UPPER(d.status) IN ('ACTIVE','ONLINE')
               AND UPPER(COALESCE(NULLIF(d.device_type,''),'DEVICE')) <> 'SHARE'
               AND d.deactivated_at IS NULL AND d.pending_deactivate=0
               AND NOT EXISTS (
                 SELECT 1 FROM nx_compute_task t
                  WHERE t.user_id=d.user_id AND t.user_device_id=d.id AND t.is_deleted=0
                    AND UPPER(t.status) IN ('CLAIMED','RUNNING')
               )
             ORDER BY COALESCE(d.daily_usdt,0), d.id
             LIMIT 1
            """)
    SourceDevice findCapacityReplacementSource(@Param("userId") Long userId);

    @Select("""
            SELECT d.id, d.user_id AS userId, d.instance_no AS instanceNo,
                   COALESCE(d.product_id,p.id) AS productId,
                   COALESCE(NULLIF(d.product_code,''),p.product_no) AS productNo,
                   COALESCE(NULLIF(d.name,''),p.name) AS productName,
                   COALESCE(NULLIF(d.product_tier,''),p.tier) AS productTier,
                   d.status,
                   COALESCE(NULLIF(CASE WHEN o.quantity>0 THEN o.amount_usdt/o.quantity END,0),
                            NULLIF(d.price_usdt_snapshot,0),p.price_usdt,0) AS actualPaidUsdt
              FROM nx_user_device d
              LEFT JOIN nx_product p ON p.id=d.product_id AND p.is_deleted=0
              LEFT JOIN nx_order o ON o.order_no=d.source_order_no AND o.user_id=d.user_id
                                  AND o.payment_status='PAID' AND o.is_deleted=0
             WHERE d.user_id=#{userId} AND d.is_deleted=0
               AND UPPER(d.ownership_status)='OWNED'
               AND UPPER(d.status) IN ('ACTIVE','ONLINE')
               AND UPPER(COALESCE(NULLIF(d.device_type,''),'DEVICE')) <> 'SHARE'
               AND d.deactivated_at IS NULL AND d.pending_deactivate=0
               AND NOT EXISTS (
                 SELECT 1 FROM nx_compute_task t
                  WHERE t.user_id=d.user_id AND t.user_device_id=d.id AND t.is_deleted=0
                    AND UPPER(t.status) IN ('CLAIMED','RUNNING')
               )
             ORDER BY COALESCE(d.daily_usdt,0), d.id
             LIMIT 1 FOR UPDATE
            """)
    SourceDevice lockCapacityReplacementSource(@Param("userId") Long userId);

    /**
     * The App may publish only products that this mapper's quote/submit lookups
     * can resolve.  Keeping the catalogue on nx_product prevents an admin-only
     * SKU from presenting a purchase CTA that the transaction service rejects.
     */
    @Select("""
            SELECT p.product_no AS productNo,p.name,p.tier,p.price_usdt AS priceUsdt,p.stock,
                   p.product_type AS deviceType,p.generation,p.gpu_model AS gpuModel,
                   p.vram_total_gb AS vramTotalGb,p.hashrate,p.estimated_daily_usdt AS dailyUsdt,
                   p.daily_nex AS dailyNex,p.tagline,p.badge,
                   COALESCE((SELECT SUM(oi.quantity)
                               FROM nx_order_item oi
                               JOIN nx_order o ON o.order_no=oi.order_no
                               JOIN nx_user u ON u.id=o.user_id AND u.is_deleted=0 AND u.sandbox=0
                              WHERE oi.product_id=p.id AND oi.is_deleted=0
                                AND o.payment_status='PAID' AND o.is_deleted=0),0)
                   + COALESCE((SELECT SUM(o.quantity)
                                  FROM nx_order o
                                  JOIN nx_user u ON u.id=o.user_id AND u.is_deleted=0 AND u.sandbox=0
                                 WHERE o.product_id=p.id AND o.payment_status='PAID' AND o.is_deleted=0
                                   AND UPPER(o.order_type)='SINGLE' AND o.quantity>0
                                   AND NOT EXISTS (SELECT 1 FROM nx_order_item historical_item
                                                     WHERE historical_item.order_no=o.order_no
                                                       AND historical_item.is_deleted=0)),0) AS sold,
                   p.unlock_phase AS unlockPhase,p.updated_at AS updatedAt,
                   s.power_text AS power,s.datacenter AS datacenter,s.uptime AS uptime,s.warranty AS warranty,
                   s.phone_daily_earn AS phoneDailyEarn,s.phone_daily_earn_nex AS phoneDailyEarnNex,s.features_json AS featuresJson,
                   s.ai_image_gen_per_min AS aiImageGenPerMin,s.ai_llm_tokens_per_sec AS aiLlmTokensPerSec,
                   s.ai_video_min_per_hour AS aiVideoMinPerHour,s.ai_fine_tune_mins AS aiFineTuneMins,
                   s.ai_unlocks AS aiUnlocks,NULL AS purchaseGateJson,
                   s.image_asset_id AS imageAssetId,s.image_object_key AS imageObjectKey,
                   p.inventory_mode AS inventoryMode
              FROM nx_product p
              LEFT JOIN nx_admin_device_sku s ON s.sku_id=p.product_no AND s.is_deleted=0
             WHERE p.is_deleted=0 AND p.store_visible=1
               AND UPPER(p.status) IN ('ACTIVE','ON_SALE')
               AND p.price_usdt>0 AND (p.inventory_mode='UNLIMITED' OR p.stock>=0)
             ORDER BY p.store_featured DESC,p.sort_order ASC,p.id ASC
            """)
    List<CatalogTargetProduct> listPurchasableCatalogTargets();

    @Select("""
            SELECT id, product_no AS productNo, name, tier, status, price_usdt AS priceUsdt, stock,
                   unlock_phase AS unlockPhase,
                   product_type AS deviceType, generation, gpu_model AS gpuModel,
                   vram_total_gb AS vramTotalGb, hashrate, estimated_daily_usdt AS dailyUsdt, daily_nex AS dailyNex,
                   inventory_mode AS inventoryMode
              FROM nx_product
             WHERE is_deleted=0 AND store_visible=1
               AND ((#{productId} IS NOT NULL AND id=#{productId}
                     AND (#{productNo} IS NULL OR product_no=#{productNo}))
                 OR (#{productId} IS NULL AND product_no=#{productNo}))
               AND UPPER(status) IN ('ACTIVE','ON_SALE')
             LIMIT 1
            """)
    TargetProduct findTargetProduct(@Param("productId") Long productId, @Param("productNo") String productNo);

    @Select("""
            SELECT id, product_no AS productNo, name, tier, status, price_usdt AS priceUsdt, stock,
                   unlock_phase AS unlockPhase,
                   product_type AS deviceType, generation, gpu_model AS gpuModel,
                   vram_total_gb AS vramTotalGb, hashrate, estimated_daily_usdt AS dailyUsdt, daily_nex AS dailyNex,
                   inventory_mode AS inventoryMode
              FROM nx_product
             WHERE is_deleted=0 AND store_visible=1
               AND ((#{productId} IS NOT NULL AND id=#{productId}
                     AND (#{productNo} IS NULL OR product_no=#{productNo}))
                 OR (#{productId} IS NULL AND product_no=#{productNo}))
               AND UPPER(status) IN ('ACTIVE','ON_SALE')
             LIMIT 1 FOR UPDATE
            """)
    TargetProduct lockTargetProduct(@Param("productId") Long productId, @Param("productNo") String productNo);

    @Select("""
            SELECT COALESCE(SUM(reward_usdt),0)
             FROM nx_compute_receipt
             WHERE user_device_id=#{deviceId} AND is_deleted=0
               AND COALESCE(source_environment, 'PRODUCTION') = 'PRODUCTION'
               AND UPPER(earning_status) IN ('POSTED','SUCCESS','SETTLED','CREDITED','PAID')
            """)
    BigDecimal cumulativeDeviceOutputUsdt(@Param("deviceId") Long deviceId);

    @Select("SELECT usdt_available FROM nx_user_wallet WHERE user_id=#{userId} AND is_deleted=0 LIMIT 1")
    BigDecimal walletBalanceUsdt(@Param("userId") Long userId);

    @Select("SELECT usdt_available FROM nx_user_wallet WHERE user_id=#{userId} AND is_deleted=0 LIMIT 1 FOR UPDATE")
    BigDecimal lockWalletBalanceUsdt(@Param("userId") Long userId);

    @Update("""
            UPDATE nx_user_wallet
               SET usdt_available=usdt_available-#{amount}, version=version+1, updated_at=NOW()
             WHERE user_id=#{userId} AND is_deleted=0 AND usdt_available>=#{amount}
            """)
    int debitWalletUsdt(@Param("userId") Long userId, @Param("amount") BigDecimal amount);

    @Insert("""
            INSERT INTO nx_wallet_ledger
              (user_id,biz_no,biz_type,asset,direction,amount,balance_after,status,remark,created_at,updated_at,is_deleted)
            VALUES
              (#{userId},#{bizNo},'TRADE_IN_PURCHASE','USDT','OUT',#{amount},#{balanceAfter},'SUCCESS',
               'E3 trade-in upgrade wallet payment',NOW(),NOW(),0)
            """)
    int insertWalletLedger(@Param("bizNo") String bizNo, @Param("userId") Long userId,
                           @Param("amount") BigDecimal amount, @Param("balanceAfter") BigDecimal balanceAfter);

    @Insert("""
            INSERT INTO nx_wallet_ledger
              (user_id,biz_no,biz_type,asset,direction,amount,balance_after,status,remark,created_at,updated_at,is_deleted)
            VALUES
              (#{userId},#{bizNo},'DEVICE_PURCHASE','USDT','OUT',#{amount},#{balanceAfter},'SUCCESS',
               'E3 capacity keep purchase wallet payment',NOW(),NOW(),0)
            """)
    int insertCapacityKeepWalletLedger(@Param("bizNo") String bizNo, @Param("userId") Long userId,
                                       @Param("amount") BigDecimal amount, @Param("balanceAfter") BigDecimal balanceAfter);

    @Update("""
            UPDATE nx_product
               SET stock=stock-1, sold_count=sold_count+1,
                   updated_at=GREATEST(CURRENT_TIMESTAMP(6),updated_at + INTERVAL 1 MICROSECOND)
             WHERE id=#{productId} AND is_deleted=0 AND store_visible=1
               AND UPPER(status) IN ('ACTIVE','ON_SALE') AND inventory_mode='FINITE' AND stock>=1
            """)
    int decrementTargetStock(@Param("productId") Long productId);

    @Update("""
            UPDATE nx_user_device
               SET ownership_status='RECYCLED', status='RECYCLED', pending_deactivate=0,
                   deactivated_at=NOW(), updated_at=NOW()
             WHERE id=#{deviceId} AND user_id=#{userId} AND is_deleted=0
               AND UPPER(ownership_status)='OWNED' AND UPPER(status) IN ('ACTIVE','ONLINE')
               AND UPPER(COALESCE(NULLIF(device_type,''),'DEVICE')) <> 'SHARE'
               AND deactivated_at IS NULL AND pending_deactivate=0
               AND NOT EXISTS (
                 SELECT 1 FROM nx_compute_task t
                  WHERE t.user_id=nx_user_device.user_id AND t.user_device_id=nx_user_device.id AND t.is_deleted=0
                    AND UPPER(t.status) IN ('CLAIMED','RUNNING')
               )
            """)
    int recycleSourceDevice(@Param("userId") Long userId, @Param("deviceId") Long deviceId);

    @Update("""
            UPDATE nx_user_device
               SET status='INVENTORY', pending_deactivate=0, activated_at=NULL,
                   deactivated_at=NOW(), updated_at=NOW()
             WHERE id=#{deviceId} AND user_id=#{userId} AND is_deleted=0
               AND UPPER(ownership_status)='OWNED' AND UPPER(status) IN ('ACTIVE','ONLINE')
               AND UPPER(COALESCE(NULLIF(device_type,''),'DEVICE')) <> 'SHARE'
               AND deactivated_at IS NULL AND pending_deactivate=0
               AND NOT EXISTS (
                 SELECT 1 FROM nx_compute_task t
                  WHERE t.user_id=nx_user_device.user_id AND t.user_device_id=nx_user_device.id AND t.is_deleted=0
                    AND UPPER(t.status) IN ('CLAIMED','RUNNING')
               )
            """)
    int moveSourceDeviceToInventory(@Param("userId") Long userId, @Param("deviceId") Long deviceId);

    @Insert("""
            INSERT INTO nx_order
              (user_id,order_no,product_id,quantity,order_type,item_count,subtotal_usdt,discount_usdt,
               amount_usdt,payment_status,order_status,activation_status,paid_at,created_at,updated_at,is_deleted)
            VALUES
              (#{row.userId},#{row.orderNo},#{row.productId},1,'TRADE_IN',1,#{row.targetPriceUsdt},
               #{row.discountUsdt},#{row.walletDebitUsdt},'PAID','COMPLETED','ACTIVATED',NOW(),NOW(),NOW(),0)
            """)
    int insertPaidOrder(@Param("row") PaidOrderWrite row);

    @Insert("""
            INSERT INTO nx_order
              (user_id,order_no,product_id,quantity,order_type,item_count,subtotal_usdt,discount_usdt,
               amount_usdt,payment_status,order_status,activation_status,paid_at,created_at,updated_at,is_deleted)
            VALUES
              (#{row.userId},#{row.orderNo},#{row.productId},1,'CAPACITY_KEEP',1,#{row.targetPriceUsdt},
               #{row.discountUsdt},#{row.walletDebitUsdt},'PAID','PAID','WAITING_PROVISIONING',NOW(),NOW(),NOW(),0)
            """)
    int insertCapacityKeepOrder(@Param("row") PaidOrderWrite row);

    @Insert("""
            INSERT INTO nx_order_item
              (order_no,product_id,product_no,product_name,quantity,unit_price_usdt,line_amount_usdt,
               sort_order,created_at,updated_at,is_deleted)
            VALUES
              (#{row.orderNo},#{row.productId},#{row.productNo},#{row.productName},1,
               #{row.targetPriceUsdt},#{row.targetPriceUsdt},0,NOW(),NOW(),0)
            """)
    int insertPaidOrderItem(@Param("row") PaidOrderWrite row);

    @Insert("""
            INSERT INTO nx_user_device
              (user_id,source_order_no,product_id,product_code,product_tier,instance_no,name,device_type,
               generation,gpu_model,vram_total_gb,base_power_w,price_usdt_snapshot,ownership_status,
               source_channel,status,hashrate,daily_usdt,daily_nex,purchased_at,activated_at,last_seen_at,
               pending_deactivate,created_at,updated_at,is_deleted)
            VALUES
              (#{row.userId},#{row.orderNo},#{row.productId},#{row.productNo},#{row.productTier},
               #{row.instanceNo},#{row.productName},#{row.deviceType},#{row.generation},#{row.gpuModel},
               #{row.vramTotalGb},0,#{row.targetPriceUsdt},'OWNED','TRADE_IN','ACTIVE',#{row.hashrate},
               #{row.dailyUsdt},#{row.dailyNex},NOW(),NOW(),NOW(),0,NOW(),NOW(),0)
            """)
    int insertTargetDevice(@Param("row") DeliveredDeviceWrite row);

    @Insert("""
            INSERT INTO nx_user_device
              (user_id,source_order_no,product_id,product_code,product_tier,instance_no,name,device_type,
               generation,gpu_model,vram_total_gb,base_power_w,price_usdt_snapshot,ownership_status,
               source_channel,status,hashrate,daily_usdt,daily_nex,purchased_at,activated_at,last_seen_at,
               pending_deactivate,created_at,updated_at,is_deleted)
            VALUES
              (#{row.userId},#{row.orderNo},#{row.productId},#{row.productNo},#{row.productTier},
               #{row.instanceNo},#{row.productName},#{row.deviceType},#{row.generation},#{row.gpuModel},
               #{row.vramTotalGb},0,#{row.targetPriceUsdt},'OWNED','ORDER','INACTIVE',#{row.hashrate},
               #{row.dailyUsdt},#{row.dailyNex},NOW(),NULL,NULL,0,NOW(),NOW(),0)
            """)
    int insertInventoryTargetDevice(@Param("row") DeliveredDeviceWrite row);

    @Select("SELECT id FROM nx_user_device WHERE instance_no=#{instanceNo} AND is_deleted=0 LIMIT 1")
    Long findDeviceIdByInstanceNo(@Param("instanceNo") String instanceNo);

    @Insert("""
            INSERT INTO nx_tradein_application
              (tradein_no,user_id,source_device_id,source_instance_no,source_product_id,source_product_name,
               source_product_tier,target_product_id,target_product_name,target_product_tier,
               source_price_usdt,target_price_usdt,tradein_discount_usdt,
               net_upgrade_cost_usdt,status,review_note,reviewer,submitted_at,reviewed_at,
               idempotency_key,cumulative_output_usdt,output_ratio_pct,credit_rate_pct,wallet_debit_usdt,
               target_order_no,target_device_id,completed_at,created_at,updated_at,is_deleted)
            VALUES
              (#{row.tradeinNo},#{row.userId},#{row.sourceDeviceId},#{row.sourceInstanceNo},#{row.sourceProductId},
               #{row.sourceProductName},#{row.sourceProductTier},#{row.targetProductId},#{row.targetProductName},
               #{row.targetProductTier},#{row.sourceActualPaidUsdt},#{row.targetPriceUsdt},
               #{row.discountUsdt},#{row.walletDebitUsdt},'COMPLETED','Server-canonical output ladder',CONCAT('user:',#{row.userId}),
               NOW(),NOW(),#{row.idempotencyKey},#{row.cumulativeOutputUsdt},#{row.outputRatioPct},
               #{row.creditRatePct},#{row.walletDebitUsdt},#{row.orderNo},#{row.targetDeviceId},NOW(),NOW(),NOW(),0)
            """)
    int insertTradeinApplication(@Param("row") TradeinApplicationWrite row);

    @Insert("""
            INSERT INTO nx_trade_in_order
              (user_id,trade_in_no,source_device_id,target_product_id,valuation_usdt,status,created_at,updated_at,is_deleted)
            VALUES
              (#{row.userId},#{row.tradeinNo},#{row.sourceDeviceId},#{row.targetProductId},
               #{row.discountUsdt},'COMPLETED',NOW(),NOW(),0)
            """)
    int insertTradeinCompatibilityOrder(@Param("row") TradeinApplicationWrite row);

    record ConfigRow(String configKey, String configValue) {
    }

    record UserEventAttribution(String phase, Integer accountAgeMonths, String cohort) {
    }

    record PurchaseGateFacts(Integer rank, Integer activeDirect, BigDecimal teamVolumeUsd) { }

    record SourceDevice(Long id, Long userId, String instanceNo, Long productId, String productNo,
                        String productName, String productTier, String status, BigDecimal actualPaidUsdt) {
    }

    record TargetProduct(Long id, String productNo, String name, String tier, String status,
                         BigDecimal priceUsdt, Integer stock, String unlockPhase, String deviceType, Integer generation,
                         String gpuModel, Integer vramTotalGb, BigDecimal hashrate,
                         BigDecimal dailyUsdt, BigDecimal dailyNex, String inventoryMode) {
        public TargetProduct(Long id, String productNo, String name, String tier, String status,
                             BigDecimal priceUsdt, Integer stock, String unlockPhase, String deviceType, Integer generation,
                             String gpuModel, Integer vramTotalGb, BigDecimal hashrate,
                             BigDecimal dailyUsdt, BigDecimal dailyNex) {
            this(id, productNo, name, tier, status, priceUsdt, stock, unlockPhase, deviceType, generation,
                    gpuModel, vramTotalGb, hashrate, dailyUsdt, dailyNex, "FINITE");
        }
        public TargetProduct(Long id, String productNo, String name, String tier, String status,
                             BigDecimal priceUsdt, Integer stock, String deviceType, Integer generation,
                             String gpuModel, Integer vramTotalGb, BigDecimal hashrate,
                             BigDecimal dailyUsdt, BigDecimal dailyNex) {
            this(id, productNo, name, tier, status, priceUsdt, stock, null, deviceType, generation,
                    gpuModel, vramTotalGb, hashrate, dailyUsdt, dailyNex, "FINITE");
        }
    }

    record CatalogTargetProduct(String productNo, String name, String tier, BigDecimal priceUsdt, Integer stock,
                                String deviceType, Integer generation, String gpuModel, Integer vramTotalGb,
                                BigDecimal hashrate, BigDecimal dailyUsdt, BigDecimal dailyNex, String tagline,
                                String badge, Integer sold, String unlockPhase, java.time.LocalDateTime updatedAt,
                                String power, String datacenter, String uptime, String warranty, BigDecimal phoneDailyEarn,
                                BigDecimal phoneDailyEarnNex, String featuresJson, BigDecimal aiImageGenPerMin,
                                BigDecimal aiLlmTokensPerSec, BigDecimal aiVideoMinPerHour,
                                BigDecimal aiFineTuneMins, String aiUnlocks, String purchaseGateJson,
                                String imageAssetId, String imageObjectKey,
                                String inventoryMode) {
        /** Compatibility constructor for call sites that do not project media metadata. */
        public CatalogTargetProduct(String productNo, String name, String tier, BigDecimal priceUsdt, Integer stock,
                                    String deviceType, Integer generation, String gpuModel, Integer vramTotalGb,
                                    BigDecimal hashrate, BigDecimal dailyUsdt, BigDecimal dailyNex, String tagline,
                                    String badge, Integer sold, String unlockPhase, java.time.LocalDateTime updatedAt,
                                    String power, String datacenter, String uptime, String warranty, BigDecimal phoneDailyEarn,
                                    BigDecimal phoneDailyEarnNex, String featuresJson, BigDecimal aiImageGenPerMin,
                                    BigDecimal aiLlmTokensPerSec, BigDecimal aiVideoMinPerHour,
                                    BigDecimal aiFineTuneMins, String aiUnlocks, String purchaseGateJson,
                                    String inventoryMode) {
            this(productNo, name, tier, priceUsdt, stock, deviceType, generation, gpuModel, vramTotalGb,
                    hashrate, dailyUsdt, dailyNex, tagline, badge, sold, unlockPhase, updatedAt, power, datacenter,
                    uptime, warranty, phoneDailyEarn, phoneDailyEarnNex, featuresJson, aiImageGenPerMin,
                    aiLlmTokensPerSec, aiVideoMinPerHour, aiFineTuneMins, aiUnlocks, purchaseGateJson,
                    null, null, inventoryMode);
        }
        /** Compatibility constructor for callers before explicit inventory semantics. */
        public CatalogTargetProduct(String productNo, String name, String tier, BigDecimal priceUsdt, Integer stock,
                                    String deviceType, Integer generation, String gpuModel, Integer vramTotalGb,
                                    BigDecimal hashrate, BigDecimal dailyUsdt, BigDecimal dailyNex, String tagline,
                                    String badge, Integer sold, String unlockPhase, java.time.LocalDateTime updatedAt,
                                    String power, String datacenter, String uptime, String warranty, BigDecimal phoneDailyEarn,
                                    BigDecimal phoneDailyEarnNex, String featuresJson, BigDecimal aiImageGenPerMin,
                                    BigDecimal aiLlmTokensPerSec, BigDecimal aiVideoMinPerHour,
                                    BigDecimal aiFineTuneMins, String aiUnlocks, String purchaseGateJson) {
            this(productNo, name, tier, priceUsdt, stock, deviceType, generation, gpuModel, vramTotalGb,
                    hashrate, dailyUsdt, dailyNex, tagline, badge, sold, unlockPhase, updatedAt, power, datacenter,
                    uptime, warranty, phoneDailyEarn, phoneDailyEarnNex, featuresJson, aiImageGenPerMin,
                    aiLlmTokensPerSec, aiVideoMinPerHour, aiFineTuneMins, aiUnlocks, purchaseGateJson, "FINITE");
        }
        /** Compatibility constructor for callers compiled against the pre-datacenter projection. */
        public CatalogTargetProduct(String productNo, String name, String tier, BigDecimal priceUsdt, Integer stock,
                                    String deviceType, Integer generation, String gpuModel, Integer vramTotalGb,
                                    BigDecimal hashrate, BigDecimal dailyUsdt, BigDecimal dailyNex, String tagline,
                                    String badge, Integer sold, String unlockPhase, java.time.LocalDateTime updatedAt,
                                    String power, String datacenter, String featuresJson, BigDecimal aiImageGenPerMin,
                                    BigDecimal aiLlmTokensPerSec, BigDecimal aiVideoMinPerHour,
                                    BigDecimal aiFineTuneMins, String aiUnlocks, String purchaseGateJson) {
            this(productNo, name, tier, priceUsdt, stock, deviceType, generation, gpuModel, vramTotalGb,
                    hashrate, dailyUsdt, dailyNex, tagline, badge, sold, unlockPhase, updatedAt, power, datacenter,
                    null, null, null, null, featuresJson, aiImageGenPerMin, aiLlmTokensPerSec, aiVideoMinPerHour,
                    aiFineTuneMins, aiUnlocks, purchaseGateJson, "FINITE");
        }

        /** Compatibility constructor for callers compiled against the pre-P2 projection. */
        public CatalogTargetProduct(String productNo, String name, String tier, BigDecimal priceUsdt, Integer stock,
                                    String deviceType, Integer generation, String gpuModel, Integer vramTotalGb,
                                    BigDecimal hashrate, BigDecimal dailyUsdt, BigDecimal dailyNex, String tagline,
                                    String badge, Integer sold, String unlockPhase, java.time.LocalDateTime updatedAt,
                                    String power, String featuresJson, BigDecimal aiImageGenPerMin,
                                    BigDecimal aiLlmTokensPerSec, BigDecimal aiVideoMinPerHour,
                                    BigDecimal aiFineTuneMins, String aiUnlocks, String purchaseGateJson) {
            this(productNo, name, tier, priceUsdt, stock, deviceType, generation, gpuModel, vramTotalGb,
                    hashrate, dailyUsdt, dailyNex, tagline, badge, sold, unlockPhase, updatedAt,
                    power, null, null, null, null, null, featuresJson, aiImageGenPerMin, aiLlmTokensPerSec, aiVideoMinPerHour,
                    aiFineTuneMins, aiUnlocks, purchaseGateJson, "FINITE");
        }

        public CatalogTargetProduct(String productNo, String name, String tier, BigDecimal priceUsdt, Integer stock,
                                    String deviceType, Integer generation, String gpuModel, Integer vramTotalGb,
                                    BigDecimal hashrate, BigDecimal dailyUsdt, BigDecimal dailyNex, String tagline,
                                    String badge, Integer sold, String unlockPhase, java.time.LocalDateTime updatedAt) {
            this(productNo, name, tier, priceUsdt, stock, deviceType, generation, gpuModel, vramTotalGb,
                    hashrate, dailyUsdt, dailyNex, tagline, badge, sold, unlockPhase, updatedAt,
                    null, null, null, null, null, null, null, null, null, null, null, null, null, "FINITE");
        }
    }

    record PaidOrderWrite(Long userId, String orderNo, Long productId, String productNo, String productName,
                          BigDecimal targetPriceUsdt, BigDecimal discountUsdt, BigDecimal walletDebitUsdt) {
    }

    record DeliveredDeviceWrite(Long userId, String orderNo, Long productId, String productNo,
                                String productTier, String instanceNo, String productName, String deviceType,
                                Integer generation, String gpuModel, Integer vramTotalGb, BigDecimal hashrate,
                                BigDecimal dailyUsdt, BigDecimal dailyNex, BigDecimal targetPriceUsdt) {
    }

    record TradeinApplicationWrite(
            String tradeinNo, String idempotencyKey, Long userId, Long sourceDeviceId, String sourceInstanceNo,
            Long sourceProductId, String sourceProductName, String sourceProductTier, Long targetProductId,
            String targetProductName, String targetProductTier, BigDecimal sourceActualPaidUsdt,
            BigDecimal targetPriceUsdt, BigDecimal cumulativeOutputUsdt, BigDecimal outputRatioPct,
            BigDecimal creditRatePct, BigDecimal discountUsdt, BigDecimal walletDebitUsdt,
            String orderNo, Long targetDeviceId) {
    }
}
