package ffdd.opsconsole.commerce.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Read-only canonical storefront facts. This mapper intentionally has no
 * dependency on the sandbox commerce tables or the canonical catalog service.
 */
@Mapper
public interface AppStorefrontActivityMapper extends BaseMapper<Object> {

    @Select("""
            SELECT sandbox
              FROM nx_user
             WHERE id = #{userId} AND status = 'ACTIVE' AND is_deleted = 0
             LIMIT 1
            """)
    UserEnvironmentRow userEnvironment(@Param("userId") Long userId);

    @Select("""
            SELECT oi.id AS activityId,
                   p.name AS productName,
                   COALESCE(o.paid_at, o.created_at) AS occurredAt,
                   oi.quantity AS quantity
              FROM nx_order_item oi
              JOIN nx_order o ON o.order_no = oi.order_no
              JOIN nx_product p ON p.id = oi.product_id
                              AND p.is_deleted = 0
                              AND p.store_visible = 1
              JOIN nx_user u ON u.id = o.user_id
                            AND u.is_deleted = 0
                            AND u.sandbox = #{sandbox}
             WHERE o.payment_status = 'PAID'
               AND o.is_deleted = 0
               AND oi.is_deleted = 0
               AND (#{cursorAt} IS NULL
                    OR COALESCE(o.paid_at, o.created_at) < #{cursorAt}
                    OR (COALESCE(o.paid_at, o.created_at) = #{cursorAt} AND oi.id < #{cursorId}))
             ORDER BY COALESCE(o.paid_at, o.created_at) DESC, oi.id DESC
             LIMIT #{limit}
            """)
    List<ActivityRow> recentActivities(
            @Param("sandbox") boolean sandbox,
            @Param("cursorAt") LocalDateTime cursorAt,
            @Param("cursorId") Long cursorId,
            @Param("limit") int limit);

    @Select("""
            SELECT id, name
              FROM nx_product
             WHERE product_no = #{productNo}
               AND is_deleted = 0
               AND store_visible = 1
             LIMIT 1
            """)
    ProductRow product(@Param("productNo") String productNo);

    @Select("""
            SELECT COALESCE(SUM(oi.quantity), 0)
              FROM nx_order_item oi
              JOIN nx_order o ON o.order_no = oi.order_no
              JOIN nx_user u ON u.id = o.user_id
                            AND u.is_deleted = 0
                            AND u.sandbox = #{sandbox}
             WHERE oi.product_id = #{productId}
               AND oi.is_deleted = 0
               AND o.payment_status = 'PAID'
               AND o.is_deleted = 0
            """)
    Long salesTotal(
            @Param("productId") long productId,
            @Param("sandbox") boolean sandbox);

    @Select("""
            SELECT COALESCE(SUM(oi.quantity), 0)
              FROM nx_order_item oi
              JOIN nx_order o ON o.order_no = oi.order_no
              JOIN nx_user u ON u.id = o.user_id
                            AND u.is_deleted = 0
                            AND u.sandbox = #{sandbox}
             WHERE oi.product_id = #{productId}
               AND oi.is_deleted = 0
               AND o.payment_status = 'PAID'
               AND o.is_deleted = 0
               AND COALESCE(o.paid_at, o.created_at) >= #{since}
            """)
    Long salesSince(
            @Param("productId") long productId,
            @Param("sandbox") boolean sandbox,
            @Param("since") LocalDateTime since);

    record UserEnvironmentRow(boolean sandbox) { }

    record ActivityRow(long activityId, String productName, LocalDateTime occurredAt, int quantity) { }

    record ProductRow(long id, String name) { }
}
