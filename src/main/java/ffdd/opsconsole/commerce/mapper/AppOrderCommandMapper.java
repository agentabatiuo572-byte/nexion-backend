package ffdd.opsconsole.commerce.mapper;

import ffdd.opsconsole.shared.canonical.mapper.CanonicalStateMapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AppOrderCommandMapper extends BaseMapper<Object> {
    @Select("SELECT id,sandbox FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0 FOR UPDATE")
    CanonicalStateMapper.UserLock lockUser(@Param("userId") Long userId);

    @Select("""
            SELECT order_no orderNo,user_id userId,product_id productId,quantity,order_type orderType,
                   payment_status paymentStatus,order_status orderStatus,activation_status activationStatus
              FROM nx_order WHERE order_no=#{orderNo} AND is_deleted=0 LIMIT 1 FOR UPDATE
            """)
    OrderRow lockOrder(@Param("orderNo") String orderNo);

    @Select("""
            SELECT order_no orderNo,product_id productId,product_no productNo,quantity
              FROM nx_order_item WHERE order_no=#{orderNo} AND is_deleted=0 ORDER BY sort_order,id FOR UPDATE
            """)
    List<ItemRow> lockItems(@Param("orderNo") String orderNo);

    @Select("SELECT id,COALESCE(stock,0) stock,COALESCE(sold_count,0) soldCount FROM nx_product WHERE id=#{productId} AND is_deleted=0 FOR UPDATE")
    ProductRow lockProduct(@Param("productId") Long productId);

    @Update("""
            UPDATE nx_product SET stock=stock+#{quantity},sold_count=sold_count-#{quantity},
                   updated_at=GREATEST(CURRENT_TIMESTAMP(6),updated_at + INTERVAL 1 MICROSECOND)
             WHERE id=#{productId} AND is_deleted=0 AND stock <= 2147483647-#{quantity} AND sold_count >= #{quantity}
            """)
    int returnStock(@Param("productId") Long productId, @Param("quantity") Integer quantity);

    @Update("""
            UPDATE nx_order SET payment_status='CANCELLED',order_status='CANCELLED',activation_status='WAITING_PAYMENT',
                   updated_at=GREATEST(CURRENT_TIMESTAMP(6),updated_at + INTERVAL 1 MICROSECOND)
             WHERE order_no=#{orderNo} AND user_id=#{userId} AND is_deleted=0
               AND UPPER(order_status)='PENDING_PAYMENT' AND UPPER(payment_status)='PENDING'
            """)
    int cancelOrder(@Param("orderNo") String orderNo, @Param("userId") Long userId);

    record OrderRow(String orderNo, Long userId, Long productId, Integer quantity, String orderType,
                    String paymentStatus, String orderStatus, String activationStatus) { }
    record ItemRow(String orderNo, Long productId, String productNo, Integer quantity) { }
    record ProductRow(Long id, Integer stock, Integer soldCount) { }
}
