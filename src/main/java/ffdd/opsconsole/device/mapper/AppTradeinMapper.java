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
                 'tradeinRequireHigherPrice','tradeinMaxDevicesPerOrder'
               )
            """)
    List<ConfigRow> listTradeinConfig();

    @Select("SELECT id FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0 FOR UPDATE")
    Long lockActiveUser(@Param("userId") Long userId);

    @Select("SELECT user_level FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0")
    String userLevel(@Param("userId") Long userId);

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
               AND d.deactivated_at IS NULL AND d.pending_deactivate=0
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
               AND d.deactivated_at IS NULL AND d.pending_deactivate=0
             LIMIT 1 FOR UPDATE
            """)
    SourceDevice lockSourceDevice(@Param("userId") Long userId, @Param("deviceId") Long deviceId);

    @Select("""
            SELECT id, product_no AS productNo, name, tier, status, price_usdt AS priceUsdt, stock,
                   product_type AS deviceType, generation, gpu_model AS gpuModel,
                   vram_total_gb AS vramTotalGb, hashrate, estimated_daily_usdt AS dailyUsdt, daily_nex AS dailyNex
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
                   product_type AS deviceType, generation, gpu_model AS gpuModel,
                   vram_total_gb AS vramTotalGb, hashrate, estimated_daily_usdt AS dailyUsdt, daily_nex AS dailyNex
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

    @Update("""
            UPDATE nx_product
               SET stock=stock-1, sold_count=sold_count+1, updated_at=NOW()
             WHERE id=#{productId} AND is_deleted=0 AND store_visible=1
               AND UPPER(status) IN ('ACTIVE','ON_SALE') AND stock>=1
            """)
    int decrementTargetStock(@Param("productId") Long productId);

    @Update("""
            UPDATE nx_user_device
               SET ownership_status='RECYCLED', status='RECYCLED', pending_deactivate=0,
                   deactivated_at=NOW(), updated_at=NOW()
             WHERE id=#{deviceId} AND user_id=#{userId} AND is_deleted=0
               AND UPPER(ownership_status)='OWNED' AND UPPER(status) IN ('ACTIVE','ONLINE')
               AND deactivated_at IS NULL AND pending_deactivate=0
            """)
    int recycleSourceDevice(@Param("userId") Long userId, @Param("deviceId") Long deviceId);

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

    record SourceDevice(Long id, Long userId, String instanceNo, Long productId, String productNo,
                        String productName, String productTier, String status, BigDecimal actualPaidUsdt) {
    }

    record TargetProduct(Long id, String productNo, String name, String tier, String status,
                         BigDecimal priceUsdt, Integer stock, String deviceType, Integer generation,
                         String gpuModel, Integer vramTotalGb, BigDecimal hashrate,
                         BigDecimal dailyUsdt, BigDecimal dailyNex) {
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
