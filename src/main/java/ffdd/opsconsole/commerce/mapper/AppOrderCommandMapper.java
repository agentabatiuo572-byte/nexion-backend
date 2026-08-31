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
            SELECT order_no orderNo,product_id productId,product_no productNo,quantity
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

    @Update("""
            UPDATE nx_order SET payment_status='CANCELLED',order_status='CANCELLED',activation_status='WAITING_PAYMENT',
                   updated_at=GREATEST(CURRENT_TIMESTAMP(6),updated_at + INTERVAL 1 MICROSECOND)
             WHERE order_no=#{orderNo} AND user_id=#{userId} AND is_deleted=0
               AND UPPER(order_status)='PENDING_PAYMENT' AND UPPER(payment_status)='PENDING'
            """)
    int cancelOrder(@Param("orderNo") String orderNo, @Param("userId") Long userId);

    @Select("""
            SELECT o.order_no orderNo,o.user_id userId,o.product_id productId,o.quantity,
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
               'Development wallet order settlement',NOW(6),NOW(6),0)
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
              (#{paymentNo},#{orderNo},#{userId},'DEVELOPMENT_SIMULATED',#{paymentNo},#{amountUsdt},'USDT',
               'PAID','DEVELOPMENT_LOCAL','{"mode":"development","simulated":true}',
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
                   GREATEST(COALESCE(p.vram_total_gb,0),0),0,
                   COALESCE(NULLIF(s.datacenter,''),'Development DC'),
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
            """)
    int insertDevelopmentDevice(@Param("orderNo") String orderNo,
                                @Param("userId") Long userId,
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
              (#{orderNo},#{fromState},#{toState},'Development simulated checkout',
               'system:development-checkout',#{idempotencyKey},NOW(6))
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
    record ItemRow(String orderNo, Long productId, String productNo, Integer quantity) { }
    record ProductRow(Long id, Integer stock, Integer soldCount, String inventoryMode) {
        public ProductRow(Long id, Integer stock, Integer soldCount) {
            this(id, stock, soldCount, "FINITE");
        }
    }
    record DevelopmentPayOrder(String orderNo, Long userId, Long productId, Integer quantity,
                               BigDecimal amountUsdt, String paymentNo, String paymentStatus,
                               String orderStatus, String activationStatus) { }
    record DevelopmentWallet(BigDecimal usdtAvailable, Long version) { }
    record DevelopmentDeviceFact(Long deviceId, String instanceNo) { }
}
