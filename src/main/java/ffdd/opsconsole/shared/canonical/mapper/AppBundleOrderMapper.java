package ffdd.opsconsole.shared.canonical.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AppBundleOrderMapper extends BaseMapper<Object> {
    @Select("SELECT id,sandbox FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0 FOR UPDATE")
    UserLock lockUser(@Param("userId") Long userId);

    @Select("""
            <script>
            SELECT p.id,p.product_no productNo,p.name,p.price_usdt priceUsdt,p.stock,
                   (SELECT s.purchase_gate_json FROM nx_admin_device_sku s
                     WHERE s.sku_id=p.product_no AND s.is_deleted=0 LIMIT 1) purchaseGateJson
              FROM nx_product p
             WHERE is_deleted=0 AND COALESCE(store_visible,1)=1
               AND UPPER(status) IN ('ACTIVE','ON_SALE') AND price_usdt > 0
               AND product_no IN
               <foreach collection='productNos' item='productNo' open='(' separator=',' close=')'>#{productNo}</foreach>
             ORDER BY product_no
             FOR UPDATE
            </script>
            """)
    List<ProductRow> lockProducts(@Param("productNos") List<String> productNos);

    @Select("""
            SELECT COUNT(1) FROM nx_user_device
             WHERE user_id=#{userId} AND is_deleted=0 AND UPPER(ownership_status)='OWNED'
               AND UPPER(status) IN ('ACTIVE','ONLINE','BUSY','RUNNING')
               AND deactivated_at IS NULL AND pending_deactivate=0
            """)
    int activeDeviceCount(@Param("userId") Long userId);

    @Select("""
            SELECT COALESCE(SUM(quantity),0) FROM nx_order
             WHERE user_id=#{userId} AND is_deleted=0
               AND UPPER(order_status) IN ('PENDING_PAYMENT','PAID','PROCESSING','PROVISIONING')
               AND UPPER(COALESCE(activation_status,'WAITING_PAYMENT')) NOT IN
                   ('ACTIVATED','REFUNDED','CANCELLED','PROVISIONING_FAILED')
            """)
    int reservedDeviceOrderCount(@Param("userId") Long userId);

    @Select("""
            SELECT COALESCE((SELECT CAST(config_value AS UNSIGNED) FROM nx_config_item
              WHERE config_key='device.max_active_slots' AND status=1 AND is_deleted=0 LIMIT 1),6)
            """)
    int deviceSlotCap();

    @Select("""
            SELECT COALESCE((SELECT config_value FROM nx_config_item
              WHERE config_key='growth.phase.current' AND status=1 AND is_deleted=0 LIMIT 1),'P1') phase,
                   GREATEST(TIMESTAMPDIFF(MONTH,u.created_at,NOW()),0) accountAgeMonths,
                   DATE_FORMAT(u.created_at,'%x-W%v') cohort
              FROM nx_user u WHERE u.id=#{userId} AND u.is_deleted=0 LIMIT 1
            """)
    Attribution attribution(@Param("userId") Long userId);

    @Select("""
            SELECT CAST(COALESCE(NULLIF(REPLACE(UPPER(u.v_rank),'V',''),''),'0') AS UNSIGNED) rank,
                   (SELECT COUNT(*) FROM nx_team_member tm JOIN nx_user child ON child.id=tm.member_user_id
                     WHERE tm.user_id=u.id AND tm.level=1 AND tm.is_deleted=0 AND child.sandbox=u.sandbox
                       AND child.status='ACTIVE' AND child.is_deleted=0) activeDirect,
                   (SELECT COALESCE(SUM(tm.volume),0) FROM nx_team_member tm WHERE tm.user_id=u.id AND tm.is_deleted=0) teamVolumeUsd
              FROM nx_user u WHERE u.id=#{userId} AND u.status='ACTIVE' AND u.is_deleted=0 LIMIT 1
            """)
    PurchaseFacts purchaseFacts(@Param("userId") Long userId);

    @Update("""
            UPDATE nx_product
               SET stock=stock-1,sold_count=sold_count+1,
                   updated_at=GREATEST(CURRENT_TIMESTAMP(6),updated_at + INTERVAL 1 MICROSECOND)
             WHERE id=#{productId} AND is_deleted=0 AND stock>=1
            """)
    int decrementStock(@Param("productId") Long productId);

    @Insert("""
            INSERT INTO nx_order(user_id,order_no,product_id,quantity,order_type,item_count,
              subtotal_usdt,discount_usdt,amount_usdt,payment_status,order_status,activation_status,
              created_at,updated_at,is_deleted)
            VALUES(#{userId},#{orderNo},#{primaryProductId},#{itemCount},'BUNDLE',#{itemCount},
              #{subtotalUsdt},#{discountUsdt},#{amountUsdt},'PENDING','PENDING_PAYMENT','WAITING_PAYMENT',
              NOW(),NOW(),0)
            """)
    int insertBundleOrder(@Param("userId") Long userId,
                          @Param("orderNo") String orderNo,
                          @Param("primaryProductId") Long primaryProductId,
                          @Param("itemCount") Integer itemCount,
                          @Param("subtotalUsdt") BigDecimal subtotalUsdt,
                          @Param("discountUsdt") BigDecimal discountUsdt,
                          @Param("amountUsdt") BigDecimal amountUsdt);

    @Insert("""
            INSERT INTO nx_order_item(order_no,product_id,product_no,product_name,quantity,
              unit_price_usdt,line_amount_usdt,sort_order,created_at,updated_at,is_deleted)
            VALUES(#{orderNo},#{product.id},#{product.productNo},#{product.name},1,
              #{product.priceUsdt},#{product.priceUsdt},#{sortOrder},NOW(),NOW(),0)
            """)
    int insertBundleItem(@Param("orderNo") String orderNo,
                         @Param("product") ProductRow product,
                         @Param("sortOrder") Integer sortOrder);

    record UserLock(Long id, boolean sandbox) { }
    record ProductRow(Long id, String productNo, String name, BigDecimal priceUsdt, Integer stock, String purchaseGateJson) {
        public ProductRow(Long id, String productNo, String name, BigDecimal priceUsdt, Integer stock) {
            this(id, productNo, name, priceUsdt, stock, null);
        }
    }
    record Attribution(String phase, Integer accountAgeMonths, String cohort) { }
    record PurchaseFacts(Integer rank, Integer activeDirect, BigDecimal teamVolumeUsd) { }
}
