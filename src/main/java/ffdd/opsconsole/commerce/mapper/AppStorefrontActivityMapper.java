package ffdd.opsconsole.commerce.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Read-only storefront facts. Production reads canonical commerce tables;
 * explicit Sandbox reads only the current run-scoped commerce projection.
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
            SELECT COUNT(*)
              FROM nx_user u
             WHERE u.id = #{userId}
               AND REPLACE(TRIM(COALESCE(u.country_code, '')), '+', '') = REPLACE(#{countryCode}, '+', '')
               AND u.phone = #{phone}
               AND u.sandbox = 1
               AND UPPER(COALESCE(u.status, 'ACTIVE')) = 'ACTIVE'
               AND u.is_deleted = 0
            """)
    int developmentUserScope(
            @Param("userId") Long userId,
            @Param("countryCode") String countryCode,
            @Param("phone") String phone);

    @Select("""
            SELECT activity.activity_id AS activityId,
                   activity.product_name AS productName,
                   activity.occurred_at AS occurredAt,
                   activity.quantity
              FROM (
                    SELECT oi.id AS activity_id,
                           COALESCE(NULLIF(TRIM(oi.product_name), ''), p.name) AS product_name,
                           COALESCE(o.paid_at, o.created_at) AS occurred_at,
                           oi.quantity
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
                    UNION ALL
                    SELECT -o.id AS activity_id,
                           p.name AS product_name,
                           COALESCE(o.paid_at, o.created_at) AS occurred_at,
                           o.quantity
                      FROM nx_order o
                      JOIN nx_product p ON p.id = o.product_id
                                       AND p.is_deleted = 0
                                       AND p.store_visible = 1
                      JOIN nx_user u ON u.id = o.user_id
                                    AND u.is_deleted = 0
                                    AND u.sandbox = #{sandbox}
                     WHERE o.payment_status = 'PAID'
                       AND o.is_deleted = 0
                       AND UPPER(o.order_type) = 'SINGLE'
                       AND o.quantity > 0
                       AND NOT EXISTS (
                             SELECT 1 FROM nx_order_item historical_item
                              WHERE historical_item.order_no = o.order_no
                                AND historical_item.is_deleted = 0
                       )
                   ) activity
             WHERE (#{cursorAt} IS NULL
                    OR activity.occurred_at < #{cursorAt}
                    OR (activity.occurred_at = #{cursorAt} AND activity.activity_id < #{cursorId}))
             ORDER BY activity.occurred_at DESC, activity.activity_id DESC
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
            SELECT product_id id, name
              FROM nx_commerce_sandbox_catalog
             WHERE run_id=#{runId}
               AND product_no=#{productNo}
               AND is_deleted=0
               AND source='mock'
               AND source_environment='SANDBOX'
             LIMIT 1
            """)
    ProductRow sandboxProduct(@Param("runId") String runId, @Param("productNo") String productNo);

    @Select("""
            SELECT COALESCE(SUM(paid.quantity), 0)
              FROM (
                    SELECT oi.quantity
                      FROM nx_order_item oi
                      JOIN nx_order o ON o.order_no = oi.order_no
                      JOIN nx_user u ON u.id = o.user_id AND u.is_deleted = 0 AND u.sandbox = #{sandbox}
                     WHERE oi.product_id = #{productId}
                       AND oi.is_deleted = 0
                       AND o.payment_status = 'PAID'
                       AND o.is_deleted = 0
                    UNION ALL
                    SELECT o.quantity
                      FROM nx_order o
                      JOIN nx_user u ON u.id = o.user_id AND u.is_deleted = 0 AND u.sandbox = #{sandbox}
                     WHERE o.product_id = #{productId}
                       AND o.payment_status = 'PAID'
                       AND o.is_deleted = 0
                       AND UPPER(o.order_type) = 'SINGLE'
                       AND o.quantity > 0
                       AND NOT EXISTS (
                             SELECT 1 FROM nx_order_item historical_item
                              WHERE historical_item.order_no = o.order_no
                                AND historical_item.is_deleted = 0
                       )
                   ) paid
            """)
    Long salesTotal(
            @Param("productId") long productId,
            @Param("sandbox") boolean sandbox);

    @Select("""
            SELECT COALESCE(SUM(paid.quantity), 0)
              FROM (
                    SELECT oi.quantity
                      FROM nx_order_item oi
                      JOIN nx_order o ON o.order_no = oi.order_no
                      JOIN nx_user u ON u.id = o.user_id AND u.is_deleted = 0 AND u.sandbox = #{sandbox}
                     WHERE oi.product_id = #{productId}
                       AND oi.is_deleted = 0
                       AND o.payment_status = 'PAID'
                       AND o.is_deleted = 0
                       AND COALESCE(o.paid_at, o.created_at) >= #{since}
                    UNION ALL
                    SELECT o.quantity
                      FROM nx_order o
                      JOIN nx_user u ON u.id = o.user_id AND u.is_deleted = 0 AND u.sandbox = #{sandbox}
                     WHERE o.product_id = #{productId}
                       AND o.payment_status = 'PAID'
                       AND o.is_deleted = 0
                       AND UPPER(o.order_type) = 'SINGLE'
                       AND o.quantity > 0
                       AND COALESCE(o.paid_at, o.created_at) >= #{since}
                       AND NOT EXISTS (
                             SELECT 1 FROM nx_order_item historical_item
                              WHERE historical_item.order_no = o.order_no
                                AND historical_item.is_deleted = 0
                       )
                   ) paid
            """)
    Long salesSince(
            @Param("productId") long productId,
            @Param("sandbox") boolean sandbox,
            @Param("since") LocalDateTime since);

    @Select("""
            SELECT COALESCE(SUM(i.reserved_quantity), 0)
              FROM nx_commerce_sandbox_order o
              JOIN nx_commerce_sandbox_inventory i
                ON i.run_id=o.run_id AND i.order_no=o.order_no AND i.is_deleted=0
               AND i.source='mock' AND i.source_environment='SANDBOX'
             WHERE o.run_id=#{runId}
               AND i.product_id=#{productId}
               AND o.state IN ('PAID','ACTIVATED','PROVISIONING_FAILED')
               AND o.is_deleted=0
               AND o.source='mock'
               AND o.source_environment='SANDBOX'
            """)
    Long sandboxSalesTotal(@Param("runId") String runId, @Param("productId") long productId);

    @Select("""
            SELECT COALESCE(SUM(i.reserved_quantity), 0)
              FROM nx_commerce_sandbox_order o
              JOIN nx_commerce_sandbox_inventory i
                ON i.run_id=o.run_id AND i.order_no=o.order_no AND i.is_deleted=0
               AND i.source='mock' AND i.source_environment='SANDBOX'
             WHERE o.run_id=#{runId}
               AND i.product_id=#{productId}
               AND o.state IN ('PAID','ACTIVATED','PROVISIONING_FAILED')
               AND o.created_at >= #{since}
               AND o.is_deleted=0
               AND o.source='mock'
               AND o.source_environment='SANDBOX'
            """)
    Long sandboxSalesSince(@Param("runId") String runId, @Param("productId") long productId,
                           @Param("since") LocalDateTime since);

    record UserEnvironmentRow(boolean sandbox) { }

    record ActivityRow(long activityId, String productName, LocalDateTime occurredAt, int quantity) { }

    record ProductRow(long id, String name) { }
}
