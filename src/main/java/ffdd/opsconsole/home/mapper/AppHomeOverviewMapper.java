package ffdd.opsconsole.home.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Read-only, account- and environment-scoped facts for the App Home/Earn projection. */
@Mapper
public interface AppHomeOverviewMapper extends BaseMapper<Object> {

    @Select("""
            SELECT sandbox
              FROM nx_user
             WHERE id = #{userId} AND status = 'ACTIVE' AND is_deleted = 0
             LIMIT 1
            """)
    UserEnvironmentRow userEnvironment(@Param("userId") Long userId);

    @Select("""
            SELECT SUM(r.reward_usdt) AS usdt,
                   SUM(r.reward_nex) AS nex,
                   COUNT(*) AS jobCount
              FROM nx_compute_receipt r
             WHERE r.user_id = #{userId}
               AND COALESCE(r.source_environment, 'PRODUCTION') = #{sourceEnvironment}
               AND r.is_deleted = 0
               AND UPPER(r.earning_status) IN ('POSTED','SUCCESS','SETTLED','CREDITED','PAID')
               AND r.completed_at >= #{startAt}
               AND r.completed_at < #{endAt}
            """)
    PeriodRow earnings(@Param("userId") Long userId,
                       @Param("sourceEnvironment") String sourceEnvironment,
                       @Param("startAt") LocalDateTime startAt,
                       @Param("endAt") LocalDateTime endAt);

    @Select("""
            SELECT COALESCE(NULLIF(r.task_no, ''), r.receipt_no) AS id,
                   r.client_name AS client,
                   COALESCE(NULLIF(t.model_name, ''), r.task_type) AS model,
                   r.reward_usdt AS rewardUsdt,
                   r.completed_at AS completedAt
              FROM nx_compute_receipt r
              LEFT JOIN nx_compute_task t ON t.task_no = r.task_no
                                         AND t.user_id = #{userId}
                                         AND t.is_deleted = 0
             WHERE r.user_id = #{userId}
               AND COALESCE(r.source_environment, 'PRODUCTION') = #{sourceEnvironment}
               AND r.is_deleted = 0
               AND UPPER(r.earning_status) IN ('POSTED','SUCCESS','SETTLED','CREDITED','PAID')
             ORDER BY r.completed_at DESC, r.id DESC
             LIMIT 5
            """)
    List<EarningsLedgerRow> earningsLedger(@Param("userId") Long userId,
                                           @Param("sourceEnvironment") String sourceEnvironment);

    @Select("""
            SELECT SUM(o.amount_usdt) AS amountUsdt,
                   COUNT(*) AS orderCount
              FROM nx_order o
              JOIN nx_user u ON u.id = o.user_id
                            AND u.id = #{userId}
                            AND u.sandbox = #{sandbox}
                            AND u.status = 'ACTIVE'
                            AND u.is_deleted = 0
             WHERE o.user_id = #{userId}
               AND o.payment_status = 'PAID'
               AND o.is_deleted = 0
            """)
    PaidSummary cumulativePaid(@Param("userId") Long userId, @Param("sandbox") boolean sandbox);

    @Select("""
            SELECT COUNT(*)
              FROM nx_user_device d
              JOIN nx_user u ON u.id = d.user_id
                            AND u.id = #{userId}
                            AND u.sandbox = #{sandbox}
                            AND u.status = 'ACTIVE'
                            AND u.is_deleted = 0
             WHERE d.user_id = #{userId}
               AND d.is_deleted = 0
               AND UPPER(d.ownership_status) = 'OWNED'
               AND UPPER(d.status) IN ('ACTIVE','ONLINE','BUSY','RUNNING')
               AND d.deactivated_at IS NULL
               AND d.pending_deactivate = 0
            """)
    Long activeDevices(@Param("userId") Long userId, @Param("sandbox") boolean sandbox);

    @Select("""
            SELECT d.name,
                   d.product_code AS productCode,
                   d.product_tier AS productTier,
                   d.device_type AS deviceType,
                   d.daily_usdt AS dailyUsdt
              FROM nx_user_device d
              JOIN nx_user u ON u.id = d.user_id
                            AND u.id = #{userId}
                            AND u.sandbox = #{sandbox}
                            AND u.status = 'ACTIVE'
                            AND u.is_deleted = 0
             WHERE d.user_id = #{userId}
               AND d.is_deleted = 0
               AND UPPER(d.ownership_status) = 'OWNED'
               AND UPPER(d.status) IN ('ACTIVE','ONLINE','BUSY','RUNNING')
               AND d.deactivated_at IS NULL
               AND d.pending_deactivate = 0
               AND d.daily_usdt IS NOT NULL
               AND d.daily_usdt > 0
             ORDER BY d.daily_usdt DESC, d.id ASC
             LIMIT 1
            """)
    OwnedDeviceRow highestActiveDevice(@Param("userId") Long userId,
                                       @Param("sandbox") boolean sandbox);

    @Select("""
            SELECT COUNT(*)
              FROM nx_user_device d
              JOIN nx_user u ON u.id = d.user_id
                            AND u.id = #{userId}
                            AND u.sandbox = 1
                            AND u.status = 'ACTIVE'
                            AND u.is_deleted = 0
             WHERE d.user_id = #{userId}
               AND d.source_environment = 'SANDBOX'
               AND d.run_id = #{runId}
               AND d.is_deleted = 0
               AND UPPER(d.ownership_status) = 'OWNED'
               AND UPPER(d.status) IN ('ACTIVE','ONLINE','BUSY','RUNNING')
               AND d.deactivated_at IS NULL
               AND d.pending_deactivate = 0
            """)
    Long sandboxActiveDevices(@Param("userId") Long userId, @Param("runId") String runId);

    @Select("""
            SELECT COUNT(*)
              FROM nx_user_device d
              JOIN nx_user u ON u.id = d.user_id
                            AND u.sandbox = #{sandbox}
                            AND u.status = 'ACTIVE'
                            AND u.is_deleted = 0
             WHERE d.is_deleted = 0
               AND UPPER(d.ownership_status) = 'OWNED'
               AND UPPER(d.status) IN ('ACTIVE','ONLINE','BUSY','RUNNING')
               AND d.deactivated_at IS NULL
               AND d.pending_deactivate = 0
            """)
    Long globalActiveDevices(@Param("sandbox") boolean sandbox);

    @Select("""
            SELECT COUNT(*) AS activeJobs,
                   SUM(CASE WHEN t.required_seconds > 0
                            THEN t.reward_usdt / t.required_seconds ELSE NULL END) AS perSecUsdt
              FROM nx_compute_task t
              JOIN nx_user_device d ON d.id = t.user_device_id AND d.is_deleted = 0
              JOIN nx_user u ON u.id = d.user_id
                            AND u.sandbox = #{sandbox}
                            AND u.status = 'ACTIVE'
                            AND u.is_deleted = 0
             WHERE COALESCE(t.source_environment, 'PRODUCTION') = #{sourceEnvironment}
               AND t.is_deleted = 0
               AND UPPER(t.status) IN ('ASSIGNED','CLAIMED','RUNNING','PROCESSING')
            """)
    OnGridSummary onGrid(@Param("sourceEnvironment") String sourceEnvironment,
                         @Param("sandbox") boolean sandbox);

    @Select("""
            SELECT CONCAT('client_', LEFT(SHA2(CONCAT(
                         COALESCE(d.device_type,''), '|', COALESCE(d.dc_location,''), '|',
                         COALESCE(d.gpu_model,''), '|', COALESCE(current_client.client_name,'')), 256), 16)) AS id,
                   COALESCE(current_client.client_name, dc.display_name) AS name,
                   d.gpu_model AS model,
                   COALESCE(dc.location, NULLIF(d.dc_location, '')) AS city,
                   COUNT(*) AS gpus
              FROM nx_user_device d
              JOIN nx_user u ON u.id = d.user_id
                            AND u.sandbox = #{sandbox}
                            AND u.status = 'ACTIVE'
                            AND u.is_deleted = 0
              LEFT JOIN nx_compute_datacenter dc
                ON dc.dc_location = d.dc_location AND dc.is_deleted = 0
              LEFT JOIN (
                SELECT ranked.user_device_id, ranked.client_name
                  FROM (
                    SELECT t.user_device_id, t.client_name,
                           ROW_NUMBER() OVER (
                             PARTITION BY t.user_device_id
                             ORDER BY COALESCE(t.completed_at, t.updated_at, t.created_at) DESC, t.id DESC
                            ) AS task_rank
                      FROM nx_compute_task t
                     WHERE t.is_deleted = 0 AND NULLIF(TRIM(t.client_name), '') IS NOT NULL
                  ) ranked
                 WHERE ranked.task_rank = 1
              ) current_client ON current_client.user_device_id = d.id
             WHERE d.is_deleted = 0
               AND UPPER(d.ownership_status) = 'OWNED'
               AND UPPER(d.status) IN ('ACTIVE','ONLINE','BUSY','RUNNING')
               AND d.deactivated_at IS NULL
               AND d.pending_deactivate = 0
             GROUP BY d.device_type, d.dc_location, d.gpu_model,
                      current_client.client_name, dc.display_name, dc.location
             ORDER BY COUNT(*) DESC, id ASC
             LIMIT 20
            """)
    List<OnGridClientRow> onGridClients(@Param("sandbox") boolean sandbox);

    @Select("""
            SELECT task_id AS taskId,
                   task_class AS taskClass,
                   name,
                   unit_text AS unit,
                   price,
                   model_name AS modelName,
                   min_reward AS minReward,
                   max_reward AS maxReward
              FROM nx_admin_device_task
             WHERE is_deleted = 0
               AND LOWER(TRIM(status)) = 'active'
               AND UPPER(TRIM(task_class)) IN ('IG','VG','LL','FT','EM','SP',
                                               'IMAGE_GEN','VIDEO_RENDER','LLM_INFERENCE',
                                               'FINE_TUNE','EMBEDDING','SPEECH')
             ORDER BY updated_at DESC, id DESC
            """)
    List<MarketTaskRow> marketTasks();

    @Select("""
            SELECT h.task_id AS taskId,
                   h.price,
                   h.observed_at AS observedAt
              FROM nx_admin_device_task_price_history h
              JOIN nx_admin_device_task t
                ON t.task_id = h.task_id
               AND t.is_deleted = 0
               AND LOWER(TRIM(t.status)) = 'active'
             WHERE h.observed_at >= DATE_SUB(NOW(3), INTERVAL 25 HOUR)
             ORDER BY h.task_id, h.observed_at, h.id
            """)
    List<TaskPriceHistoryRow> marketTaskPriceHistory();

    @Select("""
            SELECT p.product_no AS productNo,
                   p.name,
                   p.product_type AS productType,
                   p.tier,
                   p.price_usdt AS priceUsdt,
                   p.estimated_daily_usdt AS dailyUsdt,
                   p.stock,
                   p.inventory_mode AS inventoryMode
              FROM nx_product p
             WHERE p.is_deleted = 0
               AND p.store_visible = 1
               AND UPPER(COALESCE(p.status,'')) IN ('ACTIVE','ON_SALE')
               AND p.price_usdt IS NOT NULL AND p.price_usdt > 0
             ORDER BY p.sort_order, p.updated_at DESC, p.id DESC
             LIMIT 20
            """)
    List<MarketProductRow> marketProducts();

    @Select("""
            SELECT b.base_reward AS baseReward,
                   b.multiplier,
                   b.countdown_days AS countdownDays,
                   b.countdown_hours AS countdownHours,
                   b.target_device AS targetDevice,
                   b.target_daily AS targetDaily,
                   LOWER(b.status) AS status,
                   b.updated_at AS updatedAt,
                   (SELECT p.price_usdt
                      FROM nx_product p
                     WHERE p.is_deleted = 0
                       AND (p.product_no = b.target_device OR p.name = b.target_device)
                     ORDER BY p.id
                     LIMIT 1) AS productPriceUsdt
              FROM nx_growth_promo_banner b
             WHERE b.is_deleted = 0
             ORDER BY b.sort_order, b.id
             LIMIT 1
            """)
    PromoRow promo();

    record UserEnvironmentRow(boolean sandbox) { }
    record PeriodRow(BigDecimal usdt, BigDecimal nex, Long jobCount) { }
    record PaidSummary(BigDecimal amountUsdt, Long orderCount) { }
    record OwnedDeviceRow(String name, String productCode, String productTier,
                          String deviceType, BigDecimal dailyUsdt) { }
    record OnGridSummary(Long activeJobs, BigDecimal perSecUsdt) { }
    record OnGridClientRow(String id, String name, String model, String city, Long gpus) { }
    record EarningsLedgerRow(String id, String client, String model, BigDecimal rewardUsdt,
                             LocalDateTime completedAt) { }
    record MarketTaskRow(String taskId, String taskClass, String name, String unit, BigDecimal price,
                         String modelName, BigDecimal minReward, BigDecimal maxReward) { }
    record TaskPriceHistoryRow(String taskId, BigDecimal price, LocalDateTime observedAt) { }
    record MarketProductRow(String productNo, String name, String productType, String tier,
                            BigDecimal priceUsdt, BigDecimal dailyUsdt, Integer stock, String inventoryMode) {
        public MarketProductRow(String productNo, String name, String productType, String tier,
                                BigDecimal priceUsdt, BigDecimal dailyUsdt, Integer stock) {
            this(productNo, name, productType, tier, priceUsdt, dailyUsdt, stock, "FINITE");
        }
    }
    record PromoRow(String baseReward, String multiplier, Integer countdownDays, Integer countdownHours,
                    String targetDevice, String targetDaily, String status, LocalDateTime updatedAt,
                    BigDecimal productPriceUsdt) { }
}
