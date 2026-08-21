package ffdd.opsconsole.device.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.device.domain.DeviceGenerationGateView;
import ffdd.opsconsole.device.domain.DeviceOrderFacts;
import ffdd.opsconsole.device.domain.DeviceOrderFundingView;
import ffdd.opsconsole.device.domain.DeviceOrderHistoryView;
import ffdd.opsconsole.device.domain.DeviceOrderView;
import ffdd.opsconsole.device.domain.DevicePhaseView;
import ffdd.opsconsole.device.domain.DevicePhoneTierRewardView;
import ffdd.opsconsole.device.domain.OnboardingYieldComparisonView;
import ffdd.opsconsole.device.domain.DeviceReviewView;
import ffdd.opsconsole.device.domain.DeviceTaskView;
import ffdd.opsconsole.device.infrastructure.DeviceSkuEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface DeviceCatalogMapper extends BaseMapper<DeviceSkuEntity> {
    String PHONE_TIER_COLUMNS = """
            tier,
            name,
            '' AS note,
            base_rate_usdt AS dailyUsdt,
            base_rate_nex AS dailyNex,
            CASE WHEN active=1 THEN 'active' ELSE 'inactive' END AS status,
            created_at AS createdAt,
            updated_at AS updatedAt,
            revision
            """;

    String SKU_STATUS_SQL = """
            CASE
              WHEN LOWER(COALESCE(NULLIF(p.store_status,''),'')) IN ('on','active','listed','on_sale') THEN 'on'
              WHEN LOWER(COALESCE(NULLIF(p.store_status,''),'')) IN ('pending','draft') THEN 'pending'
              WHEN UPPER(COALESCE(p.status,'')) IN ('ACTIVE','ON_SALE') THEN 'on'
              WHEN UPPER(COALESCE(p.status,'')) IN ('PENDING','DRAFT') THEN 'pending'
              ELSE 'off'
            END
            """;

    /**
     * Commerce identity, price, stock, sale state and earnings come only from
     * nx_product. nx_admin_device_sku retains E1 extensions, including the
     * server-enforced purchase gate that must round-trip through ordinary edits.
     */
    String SKU_COLUMNS = """
            p.product_no AS skuId,
            p.name,
            p.tier,
            p.tagline,
            p.badge,
            p.gpu_model AS gpu,
            COALESCE(NULLIF(s.vram,''), CASE WHEN p.vram_total_gb IS NULL THEN NULL ELSE CONCAT(p.vram_total_gb,'GB') END) AS vram,
            COALESCE(NULLIF(s.hash_rate,''), CAST(p.hashrate AS CHAR)) AS hashRate,
            s.power_text AS power,
            s.datacenter,
            s.uptime AS uptime,
            s.warranty AS warranty,
            s.phone_daily_earn AS phoneDailyEarn,
            s.phone_daily_earn_nex AS phoneDailyEarnNex,
            p.price_usdt AS price,
            p.estimated_daily_usdt AS dailyEarn,
            p.daily_nex AS dailyEarnNex,
            p.share_yield_min AS shareYieldMin,
            p.share_yield_max AS shareYieldMax,
            s.base_rate AS baseRate,
            p.sold_count AS sold,
            CAST(p.stock AS CHAR) AS stock,
            p.rating_value AS rating,
            p.review_count AS reviews,
            s.ai_image_gen_per_min AS aiImageGenPerMin,
            s.ai_llm_tokens_per_sec AS aiLlmTokensPerSec,
            s.ai_video_min_per_hour AS aiVideoMinPerHour,
            s.ai_fine_tune_mins AS aiFineTuneMins,
            s.ai_unlocks AS aiUnlocks,
            s.features_json AS featuresJson,
            p.generation,
            COALESCE(NULLIF(s.lifecycle,''),'active') AS lifecycle,
            p.superseded_by_product_no AS supersededBy,
            s.tradein_discount AS tradeinDiscount,
            p.unlock_phase AS unlockPhase,
            s.purchase_gate_json AS purchaseGateJson,
            s.image_asset_id AS imageAssetId,
            s.image_object_key AS imageObjectKey,
            COALESCE(NULLIF(s.image_preview_url,''),p.cover_url) AS imagePreviewUrl,
            s.tag,
            """ + SKU_STATUS_SQL + """
             AS status,
            p.created_at AS createdAt,
            p.updated_at AS updatedAt
            """;

    String TASK_COLUMNS = """
            task_id AS taskId,
            name,
            price,
            unit_text AS unit,
            requirement,
            saturation,
            status,
            task_class AS taskClass,
            model_name AS model,
            min_reward AS minReward,
            max_reward AS maxReward,
            min_vram AS minVram,
            kill_init AS killInit,
            created_at AS createdAt,
            updated_at AS updatedAt
            """;

    String ORDER_STATE_SQL = """
            CASE
              WHEN UPPER(COALESCE(o.order_status,'')) = 'REFUNDED'
                OR UPPER(COALESCE(o.payment_status,'')) = 'REFUNDED' THEN 'refunded'
              WHEN UPPER(COALESCE(o.order_status,'')) IN ('CHARGEBACK','DISPUTED')
                OR UPPER(COALESCE(o.payment_status,'')) IN ('CHARGEBACK','DISPUTED') THEN 'chargeback'
              WHEN UPPER(COALESCE(o.order_status,'')) IN ('PAYMENT_FAILED','FAILED')
                OR UPPER(COALESCE(o.payment_status,'')) IN ('PAYMENT_FAILED','FAILED') THEN 'payment_failed'
              WHEN UPPER(COALESCE(o.order_status,'')) = 'EXPIRED'
                OR UPPER(COALESCE(o.payment_status,'')) = 'EXPIRED' THEN 'expired'
              WHEN UPPER(COALESCE(o.order_status,'')) = 'PROVISIONING_FAILED'
                OR UPPER(COALESCE(o.activation_status,'')) = 'PROVISIONING_FAILED' THEN 'provisioning_failed'
              WHEN UPPER(COALESCE(o.order_status,'')) IN ('CANCELLED','CANCELED') THEN 'cancelled'
              WHEN UPPER(COALESCE(o.order_status,'')) = 'COMPLETED'
                OR UPPER(COALESCE(o.activation_status,'')) = 'ACTIVATED' THEN 'activated'
              WHEN UPPER(COALESCE(o.order_status,'')) IN ('PROVISIONING','ALLOCATING')
                OR UPPER(COALESCE(o.activation_status,'')) IN ('PROVISIONING','ALLOCATING') THEN 'provisioning'
              WHEN UPPER(COALESCE(o.payment_status,'')) IN ('PAID','CONFIRMED','SUCCESS')
                OR o.paid_at IS NOT NULL THEN 'paid'
              ELSE 'placed'
            END
            """;

    String ORDER_COLUMNS = """
            o.order_no AS orderNo,
            CONCAT('U', o.user_id) AS userNo,
            COALESCE((SELECT oi.product_no FROM nx_order_item oi
                       WHERE oi.order_no=o.order_no AND oi.is_deleted=0
                       ORDER BY oi.sort_order,oi.id LIMIT 1), p.product_no, CAST(o.product_id AS CHAR)) AS skuId,
            COALESCE((SELECT oi.product_name FROM nx_order_item oi
                       WHERE oi.order_no=o.order_no AND oi.is_deleted=0
                         AND NULLIF(TRIM(oi.product_name),'') IS NOT NULL
                       ORDER BY oi.sort_order,oi.id LIMIT 1),
                     NULLIF(TRIM(p.name),'')) AS skuName,
            CASE
              WHEN EXISTS(SELECT 1 FROM nx_order_item oi
                          WHERE oi.order_no=o.order_no AND oi.is_deleted=0
                            AND NULLIF(TRIM(oi.product_name),'') IS NOT NULL) THEN 'ORDER_ITEM'
              WHEN NULLIF(TRIM(p.name),'') IS NOT NULL THEN 'PRODUCT_CATALOG'
              ELSE 'UNAVAILABLE'
            END AS skuSource,
            o.amount_usdt AS amount,
            """ + ORDER_STATE_SQL + """
             AS state,
            (SELECT ud.dc_location FROM nx_user_device ud
              WHERE ud.source_order_no=o.order_no AND ud.is_deleted=0 ORDER BY ud.id DESC LIMIT 1) AS dcLocation,
            CASE
              WHEN 60 > TIMESTAMPDIFF(MINUTE,o.created_at,NOW())
                THEN CONCAT(TIMESTAMPDIFF(MINUTE,o.created_at,NOW()),'分钟')
              WHEN 1440 > TIMESTAMPDIFF(MINUTE,o.created_at,NOW())
                THEN CONCAT(
                  FLOOR(TIMESTAMPDIFF(MINUTE,o.created_at,NOW()) / 60),'小时',
                  MOD(TIMESTAMPDIFF(MINUTE,o.created_at,NOW()),60),'分钟')
              ELSE CONCAT(
                FLOOR(TIMESTAMPDIFF(MINUTE,o.created_at,NOW()) / 1440),'天',
                FLOOR(MOD(TIMESTAMPDIFF(MINUTE,o.created_at,NOW()),1440) / 60),'小时')
            END AS ageText,
            o.created_at AS orderedAt,
            o.updated_at AS updatedAt
            """;

    String GENERATION_GATE_COLUMNS = """
            sku_id AS id,
            name,
            release_month AS releaseMonth,
            COALESCE(CAST(phase_id AS CHAR), phase) AS phase,
            tradein_discount AS discount,
            eligibility,
            phase_offset AS phaseOffset,
            force_unlock AS forceUnlock,
            status,
            created_at AS createdAt,
            updated_at AS updatedAt
            """;

    String PHASE_COLUMNS = """
            CAST(id AS CHAR) AS p,
            label,
            meta,
            sku_label AS skus,
            sort_order AS sortOrder,
            status,
            created_at AS createdAt,
            updated_at AS updatedAt
            """;

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_admin_device_sku (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,
              sku_id VARCHAR(64) NOT NULL,
              name VARCHAR(128) NOT NULL,
              tier VARCHAR(32) DEFAULT NULL,
              tagline VARCHAR(255) DEFAULT NULL,
              badge VARCHAR(64) DEFAULT NULL,
              gpu VARCHAR(128) DEFAULT NULL,
              vram VARCHAR(64) DEFAULT NULL,
              hash_rate VARCHAR(64) DEFAULT NULL,
              power_text VARCHAR(64) DEFAULT NULL,
              datacenter VARCHAR(128) DEFAULT NULL,
              uptime VARCHAR(64) DEFAULT NULL,
              warranty VARCHAR(128) DEFAULT NULL,
              phone_daily_earn DECIMAL(18,6) DEFAULT NULL,
              phone_daily_earn_nex DECIMAL(18,6) DEFAULT NULL,
              price DECIMAL(18,4) NOT NULL DEFAULT 0,
              daily_earn DECIMAL(18,4) NOT NULL DEFAULT 0,
              daily_earn_nex DECIMAL(18,4) NOT NULL DEFAULT 0,
              share_yield_min DECIMAL(9,4) DEFAULT NULL,
              share_yield_max DECIMAL(9,4) DEFAULT NULL,
              base_rate VARCHAR(128) DEFAULT NULL,
              sold BIGINT DEFAULT NULL,
              stock_text VARCHAR(32) NOT NULL DEFAULT '0',
              rating DECIMAL(4,2) DEFAULT NULL,
              reviews BIGINT DEFAULT NULL,
              ai_image_gen_per_min BIGINT DEFAULT NULL,
              ai_llm_tokens_per_sec BIGINT DEFAULT NULL,
              ai_video_min_per_hour BIGINT DEFAULT NULL,
              ai_fine_tune_mins BIGINT DEFAULT NULL,
              ai_unlocks VARCHAR(255) DEFAULT NULL,
              features_json TEXT,
              generation INT DEFAULT NULL,
              lifecycle VARCHAR(32) DEFAULT NULL,
              superseded_by VARCHAR(64) DEFAULT NULL,
              tradein_discount DECIMAL(18,4) DEFAULT NULL,
              unlock_phase VARCHAR(32) NOT NULL DEFAULT '',
              unlock_phase_id BIGINT DEFAULT NULL,
              purchase_gate_json TEXT,
              image_asset_id VARCHAR(512) DEFAULT NULL,
              image_object_key VARCHAR(255) DEFAULT NULL,
              image_preview_url TEXT NULL,
              tag VARCHAR(32) DEFAULT NULL,
              status VARCHAR(32) NOT NULL DEFAULT 'pending',
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_admin_device_sku (sku_id),
              KEY idx_admin_device_sku_status (status,is_deleted),
              KEY idx_admin_device_sku_name (name)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createSkuTable();

    @Select("""
            SELECT COALESCE(DATETIME_PRECISION,0)
              FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_product' AND COLUMN_NAME='updated_at'
            """)
    int productUpdatedAtPrecision();

    @Update("ALTER TABLE nx_product MODIFY COLUMN updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)")
    void widenProductUpdatedAtPrecision();

    /** One-way compatibility migration: missing legacy E1 rows become canonical products; existing nx_product rows always win. */
    @Insert("""
            INSERT INTO nx_product (
              product_no,name,product_type,tier,status,price_usdt,hashrate,estimated_daily_usdt,daily_nex,stock,
              badge,tagline,store_status,store_visible,sort_order,generation,gpu_model,vram_total_gb,
              share_yield_min,share_yield_max,superseded_by_product_no,unlock_phase,sold_count,rating_value,review_count,
              created_at,updated_at,is_deleted
            )
            SELECT s.sku_id,s.name,CASE WHEN s.tier='Share' THEN 'SHARE' ELSE 'DEVICE' END,s.tier,
                   CASE WHEN LOWER(s.status) IN ('on','active') THEN 'ON_SALE' WHEN LOWER(s.status)='pending' THEN 'PENDING' ELSE 'OFF_SALE' END,
                   s.price,
                   CASE WHEN TRIM(COALESCE(s.hash_rate,'')) REGEXP '^[0-9]+([.][0-9]+)?$'
                              AND CHAR_LENGTH(SUBSTRING_INDEX(TRIM(s.hash_rate),'.',1)) <= 11
                        THEN CAST(s.hash_rate AS DECIMAL(18,6)) ELSE 0 END,
                   s.daily_earn,s.daily_earn_nex,
                   CASE WHEN TRIM(COALESCE(s.stock_text,'')) REGEXP '^[0-9]+$'
                              AND (CHAR_LENGTH(TRIM(s.stock_text)) < 10
                                   OR (CHAR_LENGTH(TRIM(s.stock_text)) = 10 AND TRIM(s.stock_text) <= '2147483647'))
                        THEN CAST(s.stock_text AS UNSIGNED) ELSE 0 END,
                   s.badge,s.tagline,
                   CASE WHEN LOWER(s.status) IN ('on','active') THEN 'on' ELSE LOWER(s.status) END,
                   CASE WHEN LOWER(s.status) IN ('on','active') THEN 1 ELSE 0 END,s.id,
                   COALESCE(s.generation,1),s.gpu,
                   CASE WHEN TRIM(COALESCE(s.vram,'')) REGEXP '^[0-9]+(GB|gb)?$'
                              AND CHAR_LENGTH(REGEXP_SUBSTR(TRIM(s.vram),'^[0-9]+')) <= 3
                        THEN CAST(REGEXP_SUBSTR(s.vram,'^[0-9]+') AS UNSIGNED) ELSE NULL END,
                   s.share_yield_min,s.share_yield_max,s.superseded_by,
                   COALESCE(CAST(s.unlock_phase_id AS CHAR),NULLIF(s.unlock_phase,'')),COALESCE(s.sold,0),COALESCE(s.rating,0),COALESCE(s.reviews,0),
                   s.created_at,s.updated_at,0
              FROM nx_admin_device_sku s
             WHERE s.is_deleted=0
               AND NOT EXISTS (SELECT 1 FROM nx_product p WHERE p.product_no=s.sku_id)
            """)
    int backfillProductsFromLegacySkus();

    /** Repairs only a missing canonical phase reference; existing nx_product values remain authoritative. */
    @Update("""
            UPDATE nx_product p
              JOIN nx_admin_device_sku s ON s.sku_id=p.product_no
               SET p.unlock_phase=CAST(s.unlock_phase_id AS CHAR),
                   p.updated_at=GREATEST(CURRENT_TIMESTAMP(6),p.updated_at + INTERVAL 1 MICROSECOND,s.updated_at)
             WHERE p.is_deleted=0 AND s.is_deleted=0
               AND (p.unlock_phase IS NULL OR p.unlock_phase='')
               AND s.unlock_phase_id IS NOT NULL
            """)
    int backfillProductUnlockPhasesFromSkuMetadata();

    @Select("""
            SELECT COUNT(*)
              FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'nx_admin_device_sku'
               AND COLUMN_NAME = 'purchase_gate_json'
            """)
    int countSkuPurchaseGateColumn();

    @Update("ALTER TABLE nx_admin_device_sku ADD COLUMN purchase_gate_json TEXT NULL AFTER unlock_phase")
    void addSkuPurchaseGateColumn();

    @Update("""
            UPDATE nx_admin_device_sku
               SET purchase_gate_json=CASE sku_id
                     WHEN 'stellarbox-pro-v2' THEN '{"rankMin":2,"mode":"all","enforce":true}'
                     WHEN 'stellarrack-p2' THEN '{"rankMin":4,"mode":"all","enforce":true}'
                     ELSE purchase_gate_json END,
                   updated_at=NOW()
             WHERE sku_id IN ('stellarbox-pro-v2','stellarrack-p2')
               AND is_deleted=0 AND (purchase_gate_json IS NULL OR TRIM(purchase_gate_json)='')
            """)
    int seedGen2PurchaseGatesIfMissing();

    @Update("ALTER TABLE nx_admin_device_sku MODIFY COLUMN unlock_phase VARCHAR(32) NOT NULL DEFAULT ''")
    void widenSkuUnlockPhaseColumn();

    @Select("""
            SELECT COUNT(*)
              FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'nx_admin_device_sku'
               AND COLUMN_NAME = 'unlock_phase_id'
            """)
    int countSkuUnlockPhaseIdColumn();

    @Update("ALTER TABLE nx_admin_device_sku ADD COLUMN unlock_phase_id BIGINT NULL AFTER unlock_phase")
    void addSkuUnlockPhaseIdColumn();

    @Select("""
            SELECT COUNT(*)
              FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'nx_admin_device_sku'
               AND COLUMN_NAME = 'image_asset_id'
            """)
    int countSkuImageAssetIdColumn();

    @Update("ALTER TABLE nx_admin_device_sku ADD COLUMN image_asset_id VARCHAR(512) DEFAULT NULL AFTER purchase_gate_json")
    void addSkuImageAssetIdColumn();

    @Update("ALTER TABLE nx_admin_device_sku MODIFY COLUMN image_asset_id VARCHAR(512) DEFAULT NULL")
    void widenSkuImageAssetIdColumn();

    @Select("""
            SELECT COUNT(*)
              FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'nx_admin_device_sku'
               AND COLUMN_NAME = 'image_object_key'
            """)
    int countSkuImageObjectKeyColumn();

    @Update("ALTER TABLE nx_admin_device_sku ADD COLUMN image_object_key VARCHAR(255) DEFAULT NULL AFTER image_asset_id")
    void addSkuImageObjectKeyColumn();

    @Update("ALTER TABLE nx_admin_device_sku MODIFY COLUMN image_object_key VARCHAR(255) DEFAULT NULL")
    void widenSkuImageObjectKeyColumn();

    @Select("""
            SELECT COUNT(*)
              FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'nx_admin_device_sku'
               AND COLUMN_NAME = 'image_preview_url'
            """)
    int countSkuImagePreviewUrlColumn();

    @Update("ALTER TABLE nx_admin_device_sku ADD COLUMN image_preview_url TEXT NULL AFTER image_object_key")
    void addSkuImagePreviewUrlColumn();

    @Update("ALTER TABLE nx_admin_device_sku MODIFY COLUMN image_preview_url TEXT NULL")
    void widenSkuImagePreviewUrlColumn();

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_admin_device_review (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,
              review_id VARCHAR(64) NOT NULL,
              sku_id VARCHAR(64) NOT NULL,
              author VARCHAR(128) NOT NULL,
              rating INT NOT NULL,
              content VARCHAR(1000) NOT NULL,
              date_text VARCHAR(64) NOT NULL,
              status VARCHAR(32) NOT NULL DEFAULT 'published',
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_admin_device_review (review_id),
              KEY idx_admin_device_review_sku_status (sku_id,status,is_deleted),
              KEY idx_admin_device_review_rating (rating)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createReviewTable();

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_admin_device_task (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,
              task_id VARCHAR(64) NOT NULL,
              name VARCHAR(128) NOT NULL,
              price DECIMAL(18,4) NOT NULL DEFAULT 0,
              unit_text VARCHAR(32) NOT NULL DEFAULT '/job',
              requirement VARCHAR(128) NOT NULL DEFAULT 'S1+',
              saturation DECIMAL(7,4) NOT NULL DEFAULT 0,
              status VARCHAR(32) NOT NULL DEFAULT 'active',
              task_class VARCHAR(64) NOT NULL DEFAULT 'LL',
              model_name VARCHAR(128) NOT NULL DEFAULT '',
              min_reward DECIMAL(18,5) NOT NULL DEFAULT 0,
              max_reward DECIMAL(18,5) NOT NULL DEFAULT 0,
              min_vram VARCHAR(64) NOT NULL DEFAULT '',
              kill_init VARCHAR(32) NOT NULL DEFAULT '派发中',
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_admin_device_task (task_id),
              KEY idx_admin_device_task_status (status,is_deleted),
              KEY idx_admin_device_task_name (name)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createTaskTable();

    @Select("""
            SELECT
              (SELECT COUNT(*)
                 FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'nx_admin_device_task_price_history'
                  AND TABLE_TYPE = 'BASE TABLE') AS tableCount,
              (SELECT COUNT(*)
                 FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'nx_admin_device_task_price_history'
                  AND (
                    (COLUMN_NAME = 'id' AND DATA_TYPE = 'bigint' AND IS_NULLABLE = 'NO'
                      AND COLUMN_KEY = 'PRI' AND EXTRA LIKE '%auto_increment%') OR
                    (COLUMN_NAME = 'task_id' AND DATA_TYPE = 'varchar' AND CHARACTER_MAXIMUM_LENGTH = 64 AND IS_NULLABLE = 'NO') OR
                    (COLUMN_NAME = 'task_class' AND DATA_TYPE = 'varchar' AND CHARACTER_MAXIMUM_LENGTH = 64 AND IS_NULLABLE = 'NO') OR
                    (COLUMN_NAME = 'price' AND DATA_TYPE = 'decimal' AND NUMERIC_PRECISION = 18 AND NUMERIC_SCALE = 8 AND IS_NULLABLE = 'NO') OR
                    (COLUMN_NAME = 'unit_text' AND DATA_TYPE = 'varchar' AND CHARACTER_MAXIMUM_LENGTH = 32 AND IS_NULLABLE = 'NO') OR
                    (COLUMN_NAME = 'source_type' AND DATA_TYPE = 'varchar' AND CHARACTER_MAXIMUM_LENGTH = 32 AND IS_NULLABLE = 'NO') OR
                    (COLUMN_NAME = 'sample_key' AND DATA_TYPE = 'varchar' AND CHARACTER_MAXIMUM_LENGTH = 128 AND IS_NULLABLE = 'YES') OR
                    (COLUMN_NAME = 'observed_at' AND DATA_TYPE = 'datetime' AND DATETIME_PRECISION = 3 AND IS_NULLABLE = 'NO') OR
                    (COLUMN_NAME = 'created_at' AND DATA_TYPE = 'datetime' AND DATETIME_PRECISION = 3 AND IS_NULLABLE = 'NO')
                  )) AS columnCount,
              (SELECT COUNT(*) FROM (
                 SELECT INDEX_NAME
                   FROM information_schema.STATISTICS
                  WHERE TABLE_SCHEMA = DATABASE()
                    AND TABLE_NAME = 'nx_admin_device_task_price_history'
                    AND INDEX_NAME = 'uk_admin_task_price_history_sample'
                  GROUP BY INDEX_NAME
                 HAVING MAX(NON_UNIQUE) = 0
                    AND GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) = 'task_id,sample_key'
               ) unique_index_shape) AS uniqueIndexCount,
              (SELECT COUNT(*) FROM (
                 SELECT INDEX_NAME
                   FROM information_schema.STATISTICS
                  WHERE TABLE_SCHEMA = DATABASE()
                    AND TABLE_NAME = 'nx_admin_device_task_price_history'
                    AND INDEX_NAME = 'idx_admin_task_price_history_observed_at'
                  GROUP BY INDEX_NAME
                 HAVING MAX(NON_UNIQUE) = 1
                    AND GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) = 'observed_at'
               ) observed_index_shape) AS observedIndexCount,
              (SELECT COUNT(*) FROM (
                 SELECT INDEX_NAME
                   FROM information_schema.STATISTICS
                  WHERE TABLE_SCHEMA = DATABASE()
                    AND TABLE_NAME = 'nx_admin_device_task_price_history'
                    AND INDEX_NAME = 'idx_admin_task_price_history_task_time'
                  GROUP BY INDEX_NAME
                 HAVING MAX(NON_UNIQUE) = 1
                    AND GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) = 'task_id,observed_at'
               ) task_time_index_shape) AS taskTimeIndexCount,
              (SELECT COUNT(*) FROM (
                 SELECT INDEX_NAME
                   FROM information_schema.STATISTICS
                  WHERE TABLE_SCHEMA = DATABASE()
                    AND TABLE_NAME = 'nx_admin_device_task_price_history'
                    AND INDEX_NAME = 'idx_admin_task_price_history_class_time'
                  GROUP BY INDEX_NAME
                 HAVING MAX(NON_UNIQUE) = 1
                    AND GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) = 'task_class,observed_at'
               ) class_time_index_shape) AS classTimeIndexCount
            """)
    TaskPriceHistorySchemaRow taskPriceHistorySchema();

    @Select("""
            SELECT task_id AS taskId,
                   task_class AS taskClass,
                   price,
                   unit_text AS unit
              FROM nx_admin_device_task
             WHERE is_deleted = 0
               AND LOWER(TRIM(status)) = 'active'
               AND price > 0
             ORDER BY id
            """)
    List<TaskPriceSeedRow> activeTaskPriceSeeds();

    @Insert("""
            INSERT INTO nx_admin_device_task_price_history (
              task_id, task_class, price, unit_text, source_type, sample_key, observed_at, created_at
            ) VALUES (
              #{taskId}, #{taskClass}, #{price}, #{unit}, #{sourceType}, #{sampleKey}, #{observedAt}, #{createdAt}
            )
            ON DUPLICATE KEY UPDATE sample_key = VALUES(sample_key)
            """)
    int insertTaskPriceHistory(@Param("taskId") String taskId,
                               @Param("taskClass") String taskClass,
                               @Param("price") BigDecimal price,
                               @Param("unit") String unit,
                               @Param("sourceType") String sourceType,
                               @Param("sampleKey") String sampleKey,
                               @Param("observedAt") LocalDateTime observedAt,
                               @Param("createdAt") LocalDateTime createdAt);

    @Insert("""
            INSERT INTO nx_admin_device_task_price_history (
              task_id, task_class, price, unit_text, source_type, sample_key, observed_at, created_at
            )
            SELECT task_id, task_class, price, unit_text, #{sourceType}, NULL, #{observedAt}, #{observedAt}
              FROM nx_admin_device_task
             WHERE task_id = #{taskId}
               AND is_deleted = 0
               AND price > 0
            """)
    int insertTaskPriceHistoryFromTask(@Param("taskId") String taskId,
                                       @Param("sourceType") String sourceType,
                                       @Param("observedAt") LocalDateTime observedAt);

    @Insert("""
            INSERT INTO nx_admin_device_task_price_history (
              task_id, task_class, price, unit_text, source_type, sample_key, observed_at, created_at
            )
            SELECT task_id, task_class, price, unit_text, 'SCHEDULED_SNAPSHOT', #{sampleKey}, #{observedAt}, #{observedAt}
              FROM nx_admin_device_task
             WHERE is_deleted = 0
               AND LOWER(TRIM(status)) = 'active'
               AND price > 0
            ON DUPLICATE KEY UPDATE sample_key = VALUES(sample_key)
            """)
    int snapshotActiveTaskPrices(@Param("observedAt") LocalDateTime observedAt,
                                 @Param("sampleKey") String sampleKey);

    @Select("""
            SELECT COUNT(*)
              FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'nx_admin_device_task'
               AND COLUMN_NAME = 'task_class'
            """)
    int countTaskExtensionColumn();

    @Update("""
            ALTER TABLE nx_admin_device_task
              ADD COLUMN task_class VARCHAR(64) NOT NULL DEFAULT 'LL' AFTER status,
              ADD COLUMN model_name VARCHAR(128) NOT NULL DEFAULT '' AFTER task_class,
              ADD COLUMN min_reward DECIMAL(18,5) NOT NULL DEFAULT 0 AFTER model_name,
              ADD COLUMN max_reward DECIMAL(18,5) NOT NULL DEFAULT 0 AFTER min_reward,
              ADD COLUMN min_vram VARCHAR(64) NOT NULL DEFAULT '' AFTER max_reward,
              ADD COLUMN kill_init VARCHAR(32) NOT NULL DEFAULT '派发中' AFTER min_vram
            """)
    void addTaskExtensionColumns();

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_admin_phone_tier_reward (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,
              tier INT NOT NULL,
              name VARCHAR(64) NOT NULL,
              note VARCHAR(255) NOT NULL DEFAULT '',
              daily_usdt DECIMAL(18,4) NOT NULL DEFAULT 0,
              daily_nex DECIMAL(18,4) NOT NULL DEFAULT 0,
              status VARCHAR(32) NOT NULL DEFAULT 'active',
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_admin_phone_tier_reward (tier),
              KEY idx_admin_phone_tier_reward_status (status,is_deleted)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createPhoneTierRewardTable();

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_admin_device_order (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,
              order_no VARCHAR(64) NOT NULL,
              user_no VARCHAR(64) NOT NULL,
              sku_id VARCHAR(64) DEFAULT NULL,
              sku_name VARCHAR(128) NOT NULL,
              amount DECIMAL(18,4) NOT NULL DEFAULT 0,
              state VARCHAR(32) NOT NULL DEFAULT 'created',
              dc_location VARCHAR(128) DEFAULT NULL,
              age_text VARCHAR(32) NOT NULL DEFAULT '',
              ordered_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_admin_device_order (order_no),
              KEY idx_admin_device_order_state (state,is_deleted),
              KEY idx_admin_device_order_user (user_no)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createOrderTable();

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_order_state_history (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,
              order_no VARCHAR(96) NOT NULL,
              from_state VARCHAR(32) NOT NULL,
              to_state VARCHAR(32) NOT NULL,
              reason VARCHAR(255) NOT NULL,
              operator VARCHAR(128) NOT NULL,
              idempotency_key VARCHAR(128) NOT NULL,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              UNIQUE KEY uk_order_state_history_idempotency (order_no,idempotency_key),
              KEY idx_order_state_history_order_time (order_no,created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createOrderStateHistoryTable();

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_admin_device_generation_gate (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,
              sku_id VARCHAR(64) NOT NULL,
              name VARCHAR(128) NOT NULL,
              release_month INT NOT NULL,
              phase VARCHAR(32) NOT NULL DEFAULT '',
              phase_id BIGINT DEFAULT NULL,
              tradein_discount DECIMAL(18,4) NOT NULL DEFAULT 0,
              eligibility TINYINT NOT NULL DEFAULT 0,
              phase_offset INT NOT NULL DEFAULT 0,
              force_unlock TINYINT NOT NULL DEFAULT 0,
              status VARCHAR(32) NOT NULL DEFAULT 'active',
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_admin_device_generation_gate_sku (sku_id),
              KEY idx_admin_device_generation_gate_status (status,is_deleted),
              KEY idx_admin_device_generation_gate_phase (phase,release_month),
              KEY idx_admin_device_generation_gate_phase_id (phase_id,release_month)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createGenerationGateTable();

    @Update("ALTER TABLE nx_admin_device_generation_gate MODIFY COLUMN phase VARCHAR(32) NOT NULL DEFAULT ''")
    void widenGenerationGatePhaseColumn();

    @Select("""
            SELECT COUNT(*)
              FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'nx_admin_device_generation_gate'
               AND COLUMN_NAME = 'phase_id'
            """)
    int countGenerationGatePhaseIdColumn();

    @Update("ALTER TABLE nx_admin_device_generation_gate ADD COLUMN phase_id BIGINT NULL AFTER phase")
    void addGenerationGatePhaseIdColumn();

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_admin_phase_config (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,
              scope VARCHAR(32) NOT NULL DEFAULT 'E1',
              label VARCHAR(128) NOT NULL,
              meta VARCHAR(128) DEFAULT NULL,
              sku_label VARCHAR(255) DEFAULT NULL,
              sort_order INT NOT NULL DEFAULT 0,
              status VARCHAR(32) NOT NULL DEFAULT 'active',
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_admin_phase_scope_label (scope, label),
              KEY idx_admin_phase_scope_sort (scope, status, is_deleted, sort_order)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createPhaseTable();

    @Select("""
            SELECT COUNT(*)
              FROM information_schema.STATISTICS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'nx_admin_phase_config'
               AND INDEX_NAME = 'uk_admin_phase_scope_label'
            """)
    int countPhaseLabelIndex();

    @Update("ALTER TABLE nx_admin_phase_config ADD UNIQUE KEY uk_admin_phase_scope_label (scope, label)")
    void addPhaseLabelIndex();

    @Select("""
            SELECT COUNT(*)
              FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'nx_admin_phase_config'
               AND COLUMN_NAME = 'phase_id'
            """)
    int countPhaseIdColumn();

    @Update("ALTER TABLE nx_admin_phase_config MODIFY COLUMN phase_id VARCHAR(32) NULL DEFAULT NULL")
    void makeLegacyPhaseIdNullable();

    @Select("""
            <script>
            SELECT
            """ + PHASE_COLUMNS + """
              FROM nx_admin_phase_config
             WHERE scope = #{scope} AND is_deleted = 0
             <if test='includeArchived == false'>AND status = 'active'</if>
             ORDER BY sort_order ASC, id ASC
            </script>
            """)
    List<DevicePhaseView> listPhases(@Param("scope") String scope, @Param("includeArchived") boolean includeArchived);

    @Select("""
            SELECT
            """ + PHASE_COLUMNS + """
              FROM nx_admin_phase_config
             WHERE scope = #{scope} AND id = #{phaseId} AND is_deleted = 0
             LIMIT 1
            """)
    DevicePhaseView findPhase(@Param("scope") String scope, @Param("phaseId") String phaseId);

    @Select("""
            SELECT
            """ + PHASE_COLUMNS + """
              FROM nx_admin_phase_config
             WHERE scope = #{scope} AND label = #{label} AND is_deleted = 0
             LIMIT 1
            """)
    DevicePhaseView findPhaseByLabel(@Param("scope") String scope, @Param("label") String label);

    @Insert("""
            INSERT INTO nx_admin_phase_config (
              scope, label, meta, sku_label, sort_order, status, created_at, updated_at, is_deleted
            ) VALUES (
              #{phase.scope}, #{phase.label}, #{phase.meta}, #{phase.skus},
              #{phase.sortOrder}, #{phase.status}, #{phase.createdAt}, #{phase.updatedAt}, 0
            )
            ON DUPLICATE KEY UPDATE
              meta = VALUES(meta),
              sku_label = VALUES(sku_label),
              sort_order = VALUES(sort_order),
              status = VALUES(status),
              updated_at = VALUES(updated_at),
              is_deleted = 0
            """)
    int upsertPhase(@Param("phase") PhaseWrite phase);

    @Update("""
            UPDATE nx_admin_phase_config
               SET label = #{phase.label},
                   meta = #{phase.meta},
                   sku_label = #{phase.skus},
                   sort_order = #{phase.sortOrder},
                   status = #{phase.status},
                   updated_at = #{phase.updatedAt},
                   is_deleted = 0
             WHERE scope = #{phase.scope}
               AND id = #{currentPhaseId}
               AND is_deleted = 0
            """)
    int updatePhase(@Param("currentPhaseId") String currentPhaseId, @Param("phase") PhaseWrite phase);

    @Update("""
            UPDATE nx_admin_phase_config
               SET status = 'archived',
                   updated_at = #{now}
             WHERE scope = #{scope} AND id = #{phaseId} AND is_deleted = 0 AND status <> 'archived'
            """)
    int archivePhase(@Param("scope") String scope, @Param("phaseId") String phaseId, @Param("now") LocalDateTime now);

    @Select("""
            SELECT COUNT(*)
              FROM nx_admin_phase_config
             WHERE scope = #{scope} AND status = 'active' AND is_deleted = 0
            """)
    int countActivePhases(@Param("scope") String scope);

    @Select("""
            SELECT COUNT(*)
              FROM nx_product
             WHERE unlock_phase = #{phaseId}
               AND is_deleted = 0
               AND store_visible = 1
            """)
    int countSkusByUnlockPhase(@Param("phaseId") String phaseId);

    @Select("""
            SELECT COUNT(*)
              FROM nx_admin_device_generation_gate
             WHERE (CAST(phase_id AS CHAR) = #{phaseId} OR phase = #{phaseId})
               AND is_deleted = 0
               AND status = 'active'
            """)
    int countGenerationGatesByPhase(@Param("phaseId") String phaseId);

    @Update("""
            UPDATE nx_admin_device_sku
               JOIN nx_admin_phase_config p
                 ON p.scope = #{scope}
                AND p.is_deleted = 0
                AND p.label = nx_admin_device_sku.unlock_phase
               SET nx_admin_device_sku.unlock_phase_id = p.id,
                   nx_admin_device_sku.updated_at = #{now}
             WHERE nx_admin_device_sku.is_deleted = 0
               AND nx_admin_device_sku.unlock_phase_id IS NULL
               AND nx_admin_device_sku.unlock_phase <> ''
            """)
    int backfillSkuUnlockPhaseIdsByLabel(@Param("scope") String scope, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_product p
              JOIN nx_admin_phase_config phase
                ON phase.scope=#{scope} AND phase.is_deleted=0 AND phase.label=p.unlock_phase
               SET p.unlock_phase=CAST(phase.id AS CHAR),
                   p.updated_at=GREATEST(CURRENT_TIMESTAMP(6),p.updated_at + INTERVAL 1 MICROSECOND)
             WHERE p.is_deleted=0 AND p.unlock_phase IS NOT NULL AND p.unlock_phase<>''
            """)
    int backfillProductUnlockPhasesByLabel(@Param("scope") String scope, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_admin_device_generation_gate
               JOIN nx_admin_phase_config p
                 ON p.scope = #{scope}
                AND p.is_deleted = 0
                AND p.label = nx_admin_device_generation_gate.phase
               SET nx_admin_device_generation_gate.phase_id = p.id,
                   nx_admin_device_generation_gate.updated_at = #{now}
             WHERE nx_admin_device_generation_gate.is_deleted = 0
               AND nx_admin_device_generation_gate.phase_id IS NULL
               AND nx_admin_device_generation_gate.phase <> ''
            """)
    int backfillGenerationGatePhaseIdsByLabel(@Param("scope") String scope, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_admin_device_sku
               JOIN nx_admin_phase_config p
                 ON p.scope = #{scope}
                AND p.is_deleted = 0
                AND p.phase_id = nx_admin_device_sku.unlock_phase
               SET nx_admin_device_sku.unlock_phase_id = p.id,
                   nx_admin_device_sku.updated_at = #{now}
             WHERE nx_admin_device_sku.is_deleted = 0
               AND nx_admin_device_sku.unlock_phase_id IS NULL
               AND nx_admin_device_sku.unlock_phase <> ''
            """)
    int backfillSkuUnlockPhaseIdsByLegacyPhaseId(@Param("scope") String scope, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_product p
              JOIN nx_admin_phase_config phase
                ON phase.scope=#{scope} AND phase.is_deleted=0 AND phase.phase_id=p.unlock_phase
               SET p.unlock_phase=CAST(phase.id AS CHAR),
                   p.updated_at=GREATEST(CURRENT_TIMESTAMP(6),p.updated_at + INTERVAL 1 MICROSECOND)
             WHERE p.is_deleted=0 AND p.unlock_phase IS NOT NULL AND p.unlock_phase<>''
            """)
    int backfillProductUnlockPhasesByLegacyId(@Param("scope") String scope, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_admin_device_generation_gate
               JOIN nx_admin_phase_config p
                 ON p.scope = #{scope}
                AND p.is_deleted = 0
                AND p.phase_id = nx_admin_device_generation_gate.phase
               SET nx_admin_device_generation_gate.phase_id = p.id,
                   nx_admin_device_generation_gate.updated_at = #{now}
             WHERE nx_admin_device_generation_gate.is_deleted = 0
               AND nx_admin_device_generation_gate.phase_id IS NULL
               AND nx_admin_device_generation_gate.phase <> ''
            """)
    int backfillGenerationGatePhaseIdsByLegacyPhaseId(@Param("scope") String scope, @Param("now") LocalDateTime now);

    @Select("""
            <script>
            SELECT COUNT(*) FROM nx_product p
              LEFT JOIN nx_admin_device_sku s ON s.sku_id=p.product_no AND s.is_deleted=0
             WHERE p.is_deleted = 0
             <if test='status != null and status != ""'>AND (
            """ + SKU_STATUS_SQL + """
             ) = #{status}</if>
             <if test='keyword != null and keyword != ""'>
               AND (p.product_no LIKE CONCAT('%', #{keyword}, '%')
                    OR p.name LIKE CONCAT('%', #{keyword}, '%')
                    OR p.tagline LIKE CONCAT('%', #{keyword}, '%')
                    OR p.gpu_model LIKE CONCAT('%', #{keyword}, '%'))
             </if>
            </script>
            """)
    long countSkus(@Param("status") String status, @Param("keyword") String keyword);

    @Select("""
            <script>
            SELECT
            """ + SKU_COLUMNS + """
              FROM nx_product p
              LEFT JOIN nx_admin_device_sku s ON s.sku_id=p.product_no AND s.is_deleted=0
             WHERE p.is_deleted = 0
             <if test='status != null and status != ""'>AND (
            """ + SKU_STATUS_SQL + """
             ) = #{status}</if>
             <if test='keyword != null and keyword != ""'>
               AND (p.product_no LIKE CONCAT('%', #{keyword}, '%')
                    OR p.name LIKE CONCAT('%', #{keyword}, '%')
                    OR p.tagline LIKE CONCAT('%', #{keyword}, '%')
                    OR p.gpu_model LIKE CONCAT('%', #{keyword}, '%'))
             </if>
             ORDER BY FIELD(
            """ + SKU_STATUS_SQL + """
             ,'on','pending','off'), p.updated_at DESC, p.id DESC
             LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<SkuRow> pageSkus(@Param("status") String status, @Param("keyword") String keyword,
                          @Param("limit") long limit, @Param("offset") long offset);

    @Select("""
            SELECT
            """ + SKU_COLUMNS + """
              FROM nx_product p
              LEFT JOIN nx_admin_device_sku s ON s.sku_id=p.product_no AND s.is_deleted=0
             WHERE p.product_no = #{skuId} AND p.is_deleted = 0
             LIMIT 1
            """)
    SkuRow findSku(@Param("skuId") String skuId);

    @Select("""
            SELECT
            """ + SKU_COLUMNS + """
              FROM nx_product p
              JOIN nx_admin_device_sku s ON s.sku_id=p.product_no AND s.is_deleted=0
             WHERE s.ai_unlocks = #{taskId} AND p.is_deleted = 0
             ORDER BY FIELD(
            """ + SKU_STATUS_SQL + """
             ,'on','pending','off'), p.updated_at DESC, p.id DESC
            """)
    List<SkuRow> findSkusByAiUnlocks(@Param("taskId") String taskId);

    @Insert("""
            INSERT INTO nx_product (
              product_no,name,product_type,tier,status,price_usdt,hashrate,estimated_daily_usdt,daily_nex,stock,
              badge,tagline,store_status,store_visible,sort_order,generation,gpu_model,vram_total_gb,
              share_yield_min,share_yield_max,superseded_by_product_no,unlock_phase,sold_count,rating_value,review_count,
              created_at,updated_at,is_deleted
            ) VALUES (
              #{sku.skuId},#{sku.name},CASE WHEN #{sku.tier}='Share' THEN 'SHARE' ELSE 'DEVICE' END,#{sku.tier},
              CASE WHEN #{sku.status}='on' THEN 'ON_SALE' WHEN #{sku.status}='pending' THEN 'PENDING' ELSE 'OFF_SALE' END,
              #{sku.price},#{sku.canonicalHashrate},#{sku.dailyEarn},#{sku.dailyEarnNex},#{sku.canonicalStock},
              #{sku.badge},#{sku.tagline},#{sku.status},CASE WHEN #{sku.status}='on' THEN 1 ELSE 0 END,0,
              COALESCE(#{sku.generation},1),#{sku.gpu},#{sku.canonicalVramGb},#{sku.shareYieldMin},#{sku.shareYieldMax},
              #{sku.supersededBy},CAST(#{sku.unlockPhaseId} AS CHAR),COALESCE(#{sku.sold},0),COALESCE(#{sku.rating},0),COALESCE(#{sku.reviews},0),
              #{sku.createdAt},#{sku.updatedAt},0
            )
            """)
    int insertSku(@Param("sku") SkuWrite sku);

    @Insert("""
            INSERT INTO nx_admin_device_sku (
              sku_id,name,tier,tagline,badge,gpu,vram,hash_rate,power_text,datacenter,uptime,warranty,phone_daily_earn,phone_daily_earn_nex,price,
              daily_earn,daily_earn_nex,share_yield_min,share_yield_max,base_rate,sold,stock_text,
              rating,reviews,ai_image_gen_per_min,ai_llm_tokens_per_sec,ai_video_min_per_hour,
              ai_fine_tune_mins,ai_unlocks,features_json,generation,lifecycle,superseded_by,
              tradein_discount,unlock_phase,unlock_phase_id,purchase_gate_json,image_asset_id,image_object_key,image_preview_url,tag,status,
              created_at,updated_at,is_deleted
            ) VALUES (
              #{sku.skuId},#{sku.name},#{sku.tier},#{sku.tagline},#{sku.badge},#{sku.gpu},#{sku.vram},#{sku.hashRate},
              #{sku.power},#{sku.datacenter},#{sku.uptime},#{sku.warranty},#{sku.phoneDailyEarn},#{sku.phoneDailyEarnNex},#{sku.price},#{sku.dailyEarn},#{sku.dailyEarnNex},#{sku.shareYieldMin},
              #{sku.shareYieldMax},#{sku.baseRate},#{sku.sold},#{sku.stock},#{sku.rating},#{sku.reviews},
              #{sku.aiImageGenPerMin},#{sku.aiLlmTokensPerSec},#{sku.aiVideoMinPerHour},#{sku.aiFineTuneMins},
              #{sku.aiUnlocks},#{sku.featuresJson},#{sku.generation},#{sku.lifecycle},#{sku.supersededBy},
              #{sku.tradeinDiscount},'',#{sku.unlockPhaseId},#{sku.purchaseGateJson},#{sku.imageAssetId},#{sku.imageObjectKey},
              #{sku.imagePreviewUrl},#{sku.tag},#{sku.status},COALESCE(#{sku.createdAt},#{sku.updatedAt}),#{sku.updatedAt},0
            )
            ON DUPLICATE KEY UPDATE
              name=VALUES(name),tier=VALUES(tier),tagline=VALUES(tagline),badge=VALUES(badge),gpu=VALUES(gpu),vram=VALUES(vram),
              hash_rate=VALUES(hash_rate),power_text=VALUES(power_text),datacenter=VALUES(datacenter),uptime=VALUES(uptime),warranty=VALUES(warranty),phone_daily_earn=VALUES(phone_daily_earn),phone_daily_earn_nex=VALUES(phone_daily_earn_nex),price=VALUES(price),
              daily_earn=VALUES(daily_earn),daily_earn_nex=VALUES(daily_earn_nex),share_yield_min=VALUES(share_yield_min),
              share_yield_max=VALUES(share_yield_max),base_rate=VALUES(base_rate),sold=VALUES(sold),stock_text=VALUES(stock_text),
              rating=VALUES(rating),reviews=VALUES(reviews),ai_image_gen_per_min=VALUES(ai_image_gen_per_min),
              ai_llm_tokens_per_sec=VALUES(ai_llm_tokens_per_sec),ai_video_min_per_hour=VALUES(ai_video_min_per_hour),
              ai_fine_tune_mins=VALUES(ai_fine_tune_mins),ai_unlocks=VALUES(ai_unlocks),features_json=VALUES(features_json),
              generation=VALUES(generation),lifecycle=VALUES(lifecycle),superseded_by=VALUES(superseded_by),
              tradein_discount=VALUES(tradein_discount),unlock_phase='',unlock_phase_id=VALUES(unlock_phase_id),
              purchase_gate_json=VALUES(purchase_gate_json),image_asset_id=VALUES(image_asset_id),image_object_key=VALUES(image_object_key),
              image_preview_url=VALUES(image_preview_url),tag=VALUES(tag),status=VALUES(status),updated_at=VALUES(updated_at),is_deleted=0
            """)
    int upsertSkuMetadata(@Param("sku") SkuWrite sku);

    @Update("""
            UPDATE nx_product
               SET name=#{sku.name},product_type=CASE WHEN #{sku.tier}='Share' THEN 'SHARE' ELSE 'DEVICE' END,tier=#{sku.tier},
                   status=CASE WHEN #{sku.status}='on' THEN 'ON_SALE' WHEN #{sku.status}='pending' THEN 'PENDING' ELSE 'OFF_SALE' END,
                   price_usdt=#{sku.price},hashrate=#{sku.canonicalHashrate},estimated_daily_usdt=#{sku.dailyEarn},daily_nex=#{sku.dailyEarnNex},
                   stock=#{sku.canonicalStock},badge=#{sku.badge},tagline=#{sku.tagline},store_status=#{sku.status},
                   store_visible=CASE WHEN #{sku.status}='on' THEN 1 ELSE 0 END,generation=COALESCE(#{sku.generation},1),
                   gpu_model=#{sku.gpu},vram_total_gb=#{sku.canonicalVramGb},share_yield_min=#{sku.shareYieldMin},
                   share_yield_max=#{sku.shareYieldMax},superseded_by_product_no=#{sku.supersededBy},
                   unlock_phase=CAST(#{sku.unlockPhaseId} AS CHAR),sold_count=COALESCE(#{sku.sold},0),
                   rating_value=COALESCE(#{sku.rating},0),review_count=COALESCE(#{sku.reviews},0),
                   updated_at=GREATEST(CURRENT_TIMESTAMP(6),updated_at + INTERVAL 1 MICROSECOND)
             WHERE product_no=#{sku.skuId} AND is_deleted=0 AND updated_at=#{expectedUpdatedAt}
            """)
    int updateSku(@Param("sku") SkuWrite sku, @Param("expectedUpdatedAt") LocalDateTime expectedUpdatedAt);

    @Update("""
            UPDATE nx_product
               SET status=CASE WHEN #{status}='on' THEN 'ON_SALE' WHEN #{status}='pending' THEN 'PENDING' ELSE 'OFF_SALE' END,
                   store_status=#{status},store_visible=CASE WHEN #{status}='on' THEN 1 ELSE 0 END,
                   updated_at=GREATEST(CURRENT_TIMESTAMP(6),updated_at + INTERVAL 1 MICROSECOND)
             WHERE product_no=#{skuId} AND is_deleted=0 AND updated_at=#{expectedUpdatedAt}
            """)
    int updateSkuStatus(@Param("skuId") String skuId, @Param("status") String status,
                        @Param("expectedUpdatedAt") LocalDateTime expectedUpdatedAt, @Param("now") LocalDateTime now);

    @Update("UPDATE nx_admin_device_sku SET status=#{status},updated_at=#{now} WHERE sku_id=#{skuId} AND is_deleted=0")
    int updateSkuMetadataStatus(@Param("skuId") String skuId, @Param("status") String status, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_product
               SET is_deleted=1,store_visible=0,store_status='off',status='OFF_SALE',
                   updated_at=GREATEST(CURRENT_TIMESTAMP(6),updated_at + INTERVAL 1 MICROSECOND)
             WHERE product_no=#{skuId} AND is_deleted=0 AND updated_at=#{expectedUpdatedAt}
            """)
    int softDeleteSku(@Param("skuId") String skuId, @Param("expectedUpdatedAt") LocalDateTime expectedUpdatedAt,
                      @Param("now") LocalDateTime now);

    @Update("UPDATE nx_admin_device_sku SET is_deleted=1,status='off',updated_at=#{now} WHERE sku_id=#{skuId} AND is_deleted=0")
    int softDeleteSkuMetadata(@Param("skuId") String skuId, @Param("now") LocalDateTime now);

    @Select("""
            <script>
            SELECT COUNT(*)
              FROM nx_admin_device_review r
              LEFT JOIN nx_product s ON s.product_no = r.sku_id AND s.is_deleted = 0
             WHERE r.is_deleted = 0
             <if test='skuId != null and skuId != ""'>AND r.sku_id = #{skuId}</if>
             <if test='status != null and status != ""'>AND r.status = #{status}</if>
             <if test='rating != null'>AND r.rating = #{rating}</if>
             <if test='keyword != null and keyword != ""'>
               AND (r.review_id LIKE CONCAT('%', #{keyword}, '%')
                    OR r.author LIKE CONCAT('%', #{keyword}, '%')
                    OR r.content LIKE CONCAT('%', #{keyword}, '%')
                    OR s.name LIKE CONCAT('%', #{keyword}, '%'))
             </if>
            </script>
            """)
    long countReviews(@Param("skuId") String skuId, @Param("status") String status,
                      @Param("rating") Integer rating, @Param("keyword") String keyword);

    @Select("""
            <script>
            SELECT r.review_id AS reviewId,
                   r.sku_id AS skuId,
                   s.name AS skuName,
                   r.author,
                   r.rating,
                   r.content,
                   r.date_text AS dateText,
                   r.status,
                   r.created_at AS createdAt,
                   r.updated_at AS updatedAt
              FROM nx_admin_device_review r
              LEFT JOIN nx_product s ON s.product_no = r.sku_id AND s.is_deleted = 0
             WHERE r.is_deleted = 0
             <if test='skuId != null and skuId != ""'>AND r.sku_id = #{skuId}</if>
             <if test='status != null and status != ""'>AND r.status = #{status}</if>
             <if test='rating != null'>AND r.rating = #{rating}</if>
             <if test='keyword != null and keyword != ""'>
               AND (r.review_id LIKE CONCAT('%', #{keyword}, '%')
                    OR r.author LIKE CONCAT('%', #{keyword}, '%')
                    OR r.content LIKE CONCAT('%', #{keyword}, '%')
                    OR s.name LIKE CONCAT('%', #{keyword}, '%'))
             </if>
             ORDER BY FIELD(r.status,'published','hidden'), r.updated_at DESC, r.id DESC
             LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<DeviceReviewView> pageReviews(@Param("skuId") String skuId, @Param("status") String status,
                                       @Param("rating") Integer rating, @Param("keyword") String keyword,
                                       @Param("limit") long limit, @Param("offset") long offset);

    @Select("""
            SELECT r.review_id AS reviewId,
                   r.sku_id AS skuId,
                   s.name AS skuName,
                   r.author,
                   r.rating,
                   r.content,
                   r.date_text AS dateText,
                   r.status,
                   r.created_at AS createdAt,
                   r.updated_at AS updatedAt
              FROM nx_admin_device_review r
              LEFT JOIN nx_product s ON s.product_no = r.sku_id AND s.is_deleted = 0
             WHERE r.review_id = #{reviewId} AND r.is_deleted = 0
             LIMIT 1
            """)
    DeviceReviewView findReview(@Param("reviewId") String reviewId);

    @Insert("""
            INSERT INTO nx_admin_device_review (
              review_id, sku_id, author, rating, content, date_text, status, created_at, updated_at, is_deleted
            ) VALUES (
              #{review.reviewId}, #{review.skuId}, #{review.author}, #{review.rating}, #{review.content},
              #{review.dateText}, #{review.status}, #{review.createdAt}, #{review.updatedAt}, 0
            )
            """)
    int insertReview(@Param("review") ReviewWrite review);

    @Update("""
            UPDATE nx_admin_device_review
               SET sku_id = #{review.skuId},
                   author = #{review.author},
                   rating = #{review.rating},
                   content = #{review.content},
                   date_text = #{review.dateText},
                   status = #{review.status},
                   updated_at = #{review.updatedAt}
             WHERE review_id = #{review.reviewId} AND is_deleted = 0
            """)
    int updateReview(@Param("review") ReviewWrite review);

    @Update("""
            UPDATE nx_admin_device_review
               SET status = #{status}, updated_at = #{now}
             WHERE review_id = #{reviewId} AND is_deleted = 0
            """)
    int updateReviewStatus(@Param("reviewId") String reviewId, @Param("status") String status, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_admin_device_review
               SET is_deleted = 1, updated_at = #{now}
             WHERE review_id = #{reviewId} AND is_deleted = 0
            """)
    int softDeleteReview(@Param("reviewId") String reviewId, @Param("now") LocalDateTime now);

    @Select("""
            <script>
            SELECT COUNT(*) FROM nx_admin_device_task
             WHERE is_deleted = 0
             <if test='status != null and status != ""'>AND status = #{status}</if>
             <if test='taskClass != null and taskClass != ""'>AND task_class = #{taskClass}</if>
             <if test='keyword != null and keyword != ""'>
               AND (task_id LIKE CONCAT('%', #{keyword}, '%')
                    OR name LIKE CONCAT('%', #{keyword}, '%')
                    OR requirement LIKE CONCAT('%', #{keyword}, '%'))
             </if>
            </script>
            """)
    long countTasks(@Param("status") String status, @Param("keyword") String keyword,
                    @Param("taskClass") String taskClass);

    @Select("""
            <script>
            SELECT
            """ + TASK_COLUMNS + """
              FROM nx_admin_device_task
             WHERE is_deleted = 0
             <if test='status != null and status != ""'>AND status = #{status}</if>
             <if test='taskClass != null and taskClass != ""'>AND task_class = #{taskClass}</if>
             <if test='keyword != null and keyword != ""'>
               AND (task_id LIKE CONCAT('%', #{keyword}, '%')
                    OR name LIKE CONCAT('%', #{keyword}, '%')
                    OR requirement LIKE CONCAT('%', #{keyword}, '%'))
             </if>
             ORDER BY FIELD(status,'active','paused','inactive'), updated_at DESC, id DESC
             LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<DeviceTaskView> pageTasks(@Param("status") String status, @Param("keyword") String keyword,
                                   @Param("taskClass") String taskClass,
                                   @Param("limit") long limit, @Param("offset") long offset);

    @Select("""
            SELECT
            """ + TASK_COLUMNS + """
              FROM nx_admin_device_task
             WHERE task_id = #{taskId} AND is_deleted = 0
             LIMIT 1
            """)
    DeviceTaskView findTask(@Param("taskId") String taskId);

    @Insert("""
            INSERT INTO nx_admin_device_task (
              task_id, name, price, unit_text, requirement, saturation, status,
              task_class, model_name, min_reward, max_reward, min_vram, kill_init,
              created_at, updated_at, is_deleted
            ) VALUES (
              #{task.taskId}, #{task.name}, #{task.price}, #{task.unit}, #{task.requirement}, #{task.saturation},
              #{task.status}, #{task.taskClass}, #{task.model}, #{task.minReward}, #{task.maxReward},
              #{task.minVram}, #{task.killInit}, #{task.createdAt}, #{task.updatedAt}, 0
            )
            """)
    int insertTask(@Param("task") TaskWrite task);

    @Update("""
            UPDATE nx_admin_device_task
               SET name = #{task.name},
                   price = #{task.price},
                   unit_text = #{task.unit},
                   requirement = #{task.requirement},
                   saturation = #{task.saturation},
                   status = #{task.status},
                   task_class = #{task.taskClass},
                   model_name = #{task.model},
                   min_reward = #{task.minReward},
                   max_reward = #{task.maxReward},
                   min_vram = #{task.minVram},
                   kill_init = #{task.killInit},
                   updated_at = #{task.updatedAt}
             WHERE task_id = #{task.taskId} AND is_deleted = 0
            """)
    int updateTask(@Param("task") TaskWrite task);

    @Update("""
            UPDATE nx_admin_device_task
               SET price = #{price}, updated_at = #{now}
             WHERE task_id = #{taskId} AND is_deleted = 0
            """)
    int updateTaskPrice(@Param("taskId") String taskId, @Param("price") BigDecimal price, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_admin_device_task
               SET status = #{status}, updated_at = #{now}
             WHERE task_id = #{taskId} AND is_deleted = 0
            """)
    int updateTaskStatus(@Param("taskId") String taskId, @Param("status") String status, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_admin_device_task
               SET is_deleted = 1, status = 'inactive', updated_at = #{now}
             WHERE task_id = #{taskId} AND is_deleted = 0
            """)
    int softDeleteTask(@Param("taskId") String taskId, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_admin_device_task
               SET task_class = CASE task_id
                     WHEN 'TK-1' THEN 'LL'
                     WHEN 'TK-2' THEN 'SP'
                     WHEN 'TK-3' THEN 'IG'
                     WHEN 'TK-4' THEN 'VG'
                     WHEN 'TK-5' THEN 'FT'
                     WHEN 'TK-6' THEN 'EM'
                     ELSE task_class END,
                   model_name = CASE task_id
                     WHEN 'TK-1' THEN 'Llama 70B,Phi-3-mini'
                     WHEN 'TK-2' THEN 'Whisper'
                     WHEN 'TK-3' THEN 'SDXL Turbo,Flux Schnell'
                     WHEN 'TK-4' THEN 'Sora-class'
                     WHEN 'TK-5' THEN 'LoRA'
                     WHEN 'TK-6' THEN 'BGE-M3'
                     ELSE model_name END,
                   min_reward = CASE task_id
                     WHEN 'TK-1' THEN 0.00005
                     WHEN 'TK-2' THEN 0.00005
                     WHEN 'TK-3' THEN 0.00010
                     WHEN 'TK-4' THEN 0.45000
                     WHEN 'TK-5' THEN 0.06000
                     WHEN 'TK-6' THEN 0.00001
                     ELSE min_reward END,
                   max_reward = CASE task_id
                     WHEN 'TK-1' THEN 0.8500
                     WHEN 'TK-2' THEN 0.0720
                     WHEN 'TK-3' THEN 0.0450
                     WHEN 'TK-4' THEN 1.8000
                     WHEN 'TK-5' THEN 0.4200
                     WHEN 'TK-6' THEN 0.0900
                     ELSE max_reward END,
                   min_vram = CASE task_id
                     WHEN 'TK-1' THEN '80GB'
                     WHEN 'TK-2' THEN '8GB'
                     WHEN 'TK-3' THEN '12GB'
                     WHEN 'TK-4' THEN '48GB'
                     WHEN 'TK-5' THEN '48GB'
                     WHEN 'TK-6' THEN '8GB'
                     ELSE min_vram END,
                   kill_init = '派发中'
             WHERE is_deleted = 0
               AND task_id IN ('TK-1','TK-2','TK-3','TK-4','TK-5','TK-6')
               AND (model_name = '' OR min_reward = 0 OR max_reward = 0 OR min_vram = '')
            """)
    int backfillDefaultTaskExtensions();

    @Update("""
            UPDATE nx_admin_device_task
               SET task_class = CASE LOWER(task_class)
                     WHEN 'llm-inference' THEN 'LL'
                     WHEN 'image-gen' THEN 'IG'
                     WHEN 'video-render' THEN 'VG'
                     WHEN 'fine-tune' THEN 'FT'
                     WHEN 'embedding' THEN 'EM'
                     WHEN 'speech' THEN 'SP'
                     ELSE task_class END
             WHERE is_deleted = 0
               AND LOWER(task_class) IN ('llm-inference','image-gen','video-render','fine-tune','embedding','speech')
            """)
    int normalizeLegacyTaskClasses();

    @Select("""
            SELECT
            """ + PHONE_TIER_COLUMNS + """
              FROM nx_onboarding_phone_tier_config
             WHERE is_deleted = 0
             ORDER BY tier ASC
            """)
    List<DevicePhoneTierRewardView> listPhoneTierRewards();

    @Select("""
            SELECT
            """ + PHONE_TIER_COLUMNS + """
              FROM nx_onboarding_phone_tier_config
             WHERE tier = #{tier} AND active = 1 AND is_deleted = 0
             LIMIT 1
            """)
    DevicePhoneTierRewardView findPhoneTierReward(@Param("tier") Integer tier);

    @Insert("""
            INSERT INTO nx_onboarding_phone_tier_config (
              tier, name, tops_min, tops_max, base_rate_usdt, base_rate_nex, revision, active, is_deleted,
              created_at, updated_at
            ) VALUES (
              #{row.tier}, #{row.name}, 1, 999999, #{row.dailyUsdt}, #{row.dailyNex},
              1, 1, 0, #{row.createdAt}, #{row.updatedAt}
            )
            """)
    int insertPhoneTierReward(@Param("row") PhoneTierRewardWrite row);

    @Update("""
            UPDATE nx_onboarding_phone_tier_config
               SET base_rate_usdt = #{row.dailyUsdt},
                   base_rate_nex = #{row.dailyNex},
                   revision = revision + 1,
                   updated_at = #{row.updatedAt}
             WHERE tier = #{row.tier} AND active = 1 AND is_deleted = 0
               AND revision = #{expectedRevision}
            """)
    int updatePhoneTierReward(@Param("row") PhoneTierRewardWrite row,
                              @Param("expectedRevision") Long expectedRevision);

    @Select("""
            SELECT config_key configKey,label,daily_usdt dailyUsdt,daily_nex dailyNex,
                   sort_order sortOrder,revision,updated_at updatedAt
              FROM nx_onboarding_yield_comparison_config
             WHERE active=1 AND is_deleted=0 ORDER BY sort_order,config_key
            """)
    List<OnboardingYieldComparisonView> listOnboardingYieldComparisons();

    @Select("""
            SELECT config_key configKey,label,daily_usdt dailyUsdt,daily_nex dailyNex,
                   sort_order sortOrder,revision,updated_at updatedAt
              FROM nx_onboarding_yield_comparison_config
             WHERE config_key=#{configKey} AND active=1 AND is_deleted=0 LIMIT 1
            """)
    OnboardingYieldComparisonView findOnboardingYieldComparison(@Param("configKey") String configKey);

    @Update("""
            UPDATE nx_onboarding_yield_comparison_config
               SET label=#{label},daily_usdt=#{dailyUsdt},daily_nex=#{dailyNex},
                   revision=revision+1,updated_at=#{updatedAt}
             WHERE config_key=#{configKey} AND active=1 AND is_deleted=0
               AND revision=#{expectedRevision}
            """)
    int updateOnboardingYieldComparison(@Param("configKey") String configKey,
                                        @Param("label") String label,
                                        @Param("dailyUsdt") BigDecimal dailyUsdt,
                                        @Param("dailyNex") BigDecimal dailyNex,
                                        @Param("expectedRevision") Long expectedRevision,
                                        @Param("updatedAt") LocalDateTime updatedAt);

    @Select("""
            <script>
            SELECT COUNT(*) FROM nx_order o
             WHERE o.is_deleted = 0
             <if test='state != null and state != ""'>AND (
            """ + ORDER_STATE_SQL + """
             ) = #{state}</if>
             <if test='keyword != null and keyword != ""'>
               AND (o.order_no LIKE CONCAT('%', #{keyword}, '%')
                    OR CONCAT('U',o.user_id) LIKE CONCAT('%', #{keyword}, '%')
                    OR CAST(o.product_id AS CHAR) LIKE CONCAT('%', #{keyword}, '%')
                    OR EXISTS (SELECT 1 FROM nx_order_item oi WHERE oi.order_no=o.order_no AND oi.is_deleted=0
                                AND (oi.product_no LIKE CONCAT('%',#{keyword},'%')
                                     OR oi.product_name LIKE CONCAT('%',#{keyword},'%'))))
             </if>
            </script>
            """)
    long countOrders(@Param("state") String state, @Param("keyword") String keyword);

    @Select("""
            <script>
            SELECT
            """ + ORDER_COLUMNS + """
              FROM nx_order o
              LEFT JOIN nx_product p ON p.id=o.product_id AND p.is_deleted=0
             WHERE o.is_deleted = 0
             <if test='state != null and state != ""'>AND (
            """ + ORDER_STATE_SQL + """
             ) = #{state}</if>
             <if test='keyword != null and keyword != ""'>
               AND (o.order_no LIKE CONCAT('%', #{keyword}, '%')
                    OR CONCAT('U',o.user_id) LIKE CONCAT('%', #{keyword}, '%')
                    OR CAST(o.product_id AS CHAR) LIKE CONCAT('%', #{keyword}, '%')
                    OR EXISTS (SELECT 1 FROM nx_order_item oi WHERE oi.order_no=o.order_no AND oi.is_deleted=0
                                AND (oi.product_no LIKE CONCAT('%',#{keyword},'%')
                                     OR oi.product_name LIKE CONCAT('%',#{keyword},'%'))))
             </if>
             ORDER BY o.created_at DESC, o.id DESC
             LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<DeviceOrderView> pageOrders(@Param("state") String state, @Param("keyword") String keyword,
                                     @Param("limit") long limit, @Param("offset") long offset);

    @Select("""
            SELECT
            """ + ORDER_COLUMNS + """
              FROM nx_order o
              LEFT JOIN nx_product p ON p.id=o.product_id AND p.is_deleted=0
             WHERE o.order_no = #{orderNo} AND o.is_deleted = 0
             LIMIT 1
            """)
    DeviceOrderView findOrder(@Param("orderNo") String orderNo);

    @Select("""
            SELECT o.order_no AS orderNo, o.user_id AS userId, o.quantity, o.order_type AS orderType,
                   o.subtotal_usdt AS subtotalUsdt, o.discount_usdt AS discountUsdt,
                   o.amount_usdt AS amountUsdt, o.payment_no AS paymentNo,
                   COALESCE((SELECT pr.provider FROM nx_payment_record pr
                              WHERE pr.order_no=o.order_no AND pr.is_deleted=0 ORDER BY pr.id DESC LIMIT 1),
                            CASE WHEN o.payment_no IS NULL THEN 'USDT_WALLET' ELSE 'UNKNOWN' END) AS paymentMethod,
                   o.payment_status AS paymentStatus, o.order_status AS orderStatus,
                   o.activation_status AS activationStatus, o.product_id AS productId,
                   COALESCE((SELECT oi.product_no FROM nx_order_item oi
                              WHERE oi.order_no=o.order_no AND oi.is_deleted=0 ORDER BY oi.sort_order,oi.id LIMIT 1),
                            p.product_no) AS productNo,
                   COALESCE((SELECT oi.product_name FROM nx_order_item oi
                              WHERE oi.order_no=o.order_no AND oi.is_deleted=0 ORDER BY oi.sort_order,oi.id LIMIT 1),
                            p.name,o.order_no) AS productName,
                   (SELECT ud.id FROM nx_user_device ud WHERE ud.source_order_no=o.order_no AND ud.is_deleted=0
                     ORDER BY ud.id DESC LIMIT 1) AS deviceId,
                   (SELECT ud.instance_no FROM nx_user_device ud WHERE ud.source_order_no=o.order_no AND ud.is_deleted=0
                     ORDER BY ud.id DESC LIMIT 1) AS deviceInstanceNo,
                   (SELECT ud.dc_location FROM nx_user_device ud WHERE ud.source_order_no=o.order_no AND ud.is_deleted=0
                     ORDER BY ud.id DESC LIMIT 1) AS dcLocation,
                   o.created_at AS createdAt, o.paid_at AS paidAt,
                   (SELECT ud.activated_at FROM nx_user_device ud WHERE ud.source_order_no=o.order_no AND ud.is_deleted=0
                     ORDER BY ud.id DESC LIMIT 1) AS activatedAt,
                   o.updated_at AS updatedAt
              FROM nx_order o
              LEFT JOIN nx_product p ON p.id=o.product_id AND p.is_deleted=0
             WHERE o.order_no=#{orderNo} AND o.is_deleted=0
             LIMIT 1
            """)
    DeviceOrderFacts findOrderFacts(@Param("orderNo") String orderNo);

    @Select("""
            SELECT from_state AS fromState, to_state AS toState, reason, operator, created_at AS createdAt
              FROM nx_order_state_history
             WHERE order_no=#{orderNo}
             ORDER BY created_at ASC,id ASC
            """)
    List<DeviceOrderHistoryView> listOrderHistory(@Param("orderNo") String orderNo);

    @Select("""
            SELECT source,bizNo,status,direction,amount,occurredAt FROM (
              SELECT 'D1_PAYMENT' AS source,pr.payment_no AS bizNo,pr.payment_status AS status,
                     'OUT' AS direction,pr.amount_usdt AS amount,COALESCE(pr.paid_at,pr.created_at) AS occurredAt
                FROM nx_payment_record pr WHERE pr.order_no=#{orderNo} AND pr.is_deleted=0
              UNION ALL
              SELECT 'D4_LEDGER',wl.biz_no,wl.status,wl.direction,wl.amount,wl.created_at
                FROM nx_wallet_ledger wl
               WHERE wl.is_deleted=0 AND (wl.biz_no=#{orderNo} OR wl.biz_no=CONCAT('E4-REFUND-',#{orderNo}))
              UNION ALL
              SELECT 'D4_BILL',wb.bill_no,'SUCCESS',wb.direction,wb.amount,wb.occurred_at
                FROM nx_wallet_bill wb
               WHERE wb.deleted=0 AND wb.bill_no=CONCAT('E4-BILL-',#{orderNo})
            ) evidence ORDER BY occurredAt ASC
            """)
    List<DeviceOrderFundingView> listOrderFunding(@Param("orderNo") String orderNo);

    @Update("""
            UPDATE nx_order o
               SET payment_status = CASE #{state}
                     WHEN 'placed' THEN 'PENDING' WHEN 'paid' THEN 'PAID'
                     WHEN 'provisioning' THEN 'PAID' WHEN 'activated' THEN 'PAID'
                     WHEN 'payment_failed' THEN 'FAILED' WHEN 'expired' THEN 'EXPIRED'
                     WHEN 'refunded' THEN 'REFUNDED' WHEN 'chargeback' THEN 'CHARGEBACK'
                     WHEN 'provisioning_failed' THEN 'PAID' WHEN 'cancelled' THEN 'CANCELLED'
                     ELSE payment_status END,
                   order_status = CASE #{state}
                     WHEN 'placed' THEN 'PENDING_PAYMENT' WHEN 'paid' THEN 'PAID'
                     WHEN 'provisioning' THEN 'PROVISIONING' WHEN 'activated' THEN 'COMPLETED'
                     WHEN 'payment_failed' THEN 'PAYMENT_FAILED' WHEN 'expired' THEN 'EXPIRED'
                     WHEN 'refunded' THEN 'REFUNDED' WHEN 'chargeback' THEN 'CHARGEBACK'
                     WHEN 'provisioning_failed' THEN 'PROVISIONING_FAILED' WHEN 'cancelled' THEN 'CANCELLED'
                     ELSE order_status END,
                   activation_status = CASE #{state}
                     WHEN 'placed' THEN 'WAITING_PAYMENT' WHEN 'paid' THEN 'WAITING_PROVISIONING'
                     WHEN 'provisioning' THEN 'PROVISIONING' WHEN 'activated' THEN 'ACTIVATED'
                     WHEN 'payment_failed' THEN 'WAITING_PAYMENT' WHEN 'expired' THEN 'WAITING_PAYMENT'
                     WHEN 'refunded' THEN 'REFUNDED' WHEN 'chargeback' THEN 'DEACTIVATED'
                     WHEN 'provisioning_failed' THEN 'PROVISIONING_FAILED' WHEN 'cancelled' THEN 'WAITING_PAYMENT'
                     ELSE activation_status END,
                   paid_at = CASE WHEN #{state} IN ('paid','provisioning','activated','provisioning_failed')
                                  THEN COALESCE(paid_at,#{now}) ELSE paid_at END,
                   updated_at = #{now}
             WHERE o.order_no = #{orderNo} AND o.is_deleted = 0
               AND (
            """ + ORDER_STATE_SQL + """
               ) = #{expectedState}
            """)
    int updateOrderState(@Param("orderNo") String orderNo,
                         @Param("expectedState") String expectedState,
                         @Param("state") String state,
                         @Param("now") LocalDateTime now);

    @Insert("""
            INSERT INTO nx_order_state_history
              (order_no,from_state,to_state,reason,operator,idempotency_key,created_at)
            VALUES
              (#{orderNo},#{fromState},#{toState},#{reason},#{operator},#{idempotencyKey},#{now})
            """)
    int insertOrderHistory(@Param("orderNo") String orderNo,
                           @Param("fromState") String fromState,
                           @Param("toState") String toState,
                           @Param("reason") String reason,
                           @Param("operator") String operator,
                           @Param("idempotencyKey") String idempotencyKey,
                           @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_user_device
               SET ownership_status='REFUNDED',status='DEACTIVATED',pending_deactivate=0,
                   deactivated_at=COALESCE(deactivated_at,#{now}),updated_at=#{now}
             WHERE source_order_no=#{orderNo} AND is_deleted=0
               AND UPPER(ownership_status) NOT IN ('REFUNDED','RECYCLED')
            """)
    int rollbackOrderDevices(@Param("orderNo") String orderNo, @Param("now") LocalDateTime now);

    @Select("""
            SELECT UPPER(o.order_type) orderType,
                   o.quantity orderQuantity,
                   o.item_count itemCount,
                   COUNT(oi.id) itemRows,
                   COALESCE(SUM(CASE WHEN oi.product_id IS NOT NULL AND oi.quantity>0
                                     THEN oi.quantity ELSE 0 END),0) validItemQuantity,
                   COALESCE(SUM(CASE WHEN oi.id IS NOT NULL
                                          AND (oi.product_id IS NULL OR oi.quantity IS NULL OR oi.quantity<=0)
                                     THEN 1 ELSE 0 END),0) invalidItemRows,
                   COUNT(DISTINCT CASE WHEN oi.product_id IS NOT NULL AND oi.quantity>0
                                       THEN oi.product_id END) productGroups
              FROM nx_order o
              LEFT JOIN nx_order_item oi ON oi.order_no=o.order_no AND oi.is_deleted=0
             WHERE o.order_no=#{orderNo} AND o.is_deleted=0
             GROUP BY o.id,o.order_type,o.quantity,o.item_count
            """)
    OrderRestockPlan orderRestockPlan(@Param("orderNo") String orderNo);

    @Update("""
            UPDATE nx_product p
             JOIN (
                    SELECT oi.product_id,SUM(oi.quantity) quantity
                      FROM nx_order o
                      JOIN nx_order_item oi ON oi.order_no=o.order_no AND oi.is_deleted=0
                     WHERE o.order_no=#{orderNo} AND o.is_deleted=0
                       AND EXISTS (
                             SELECT 1
                               FROM nx_order complete_order
                               JOIN nx_order_item complete_item
                                 ON complete_item.order_no=complete_order.order_no
                                AND complete_item.is_deleted=0
                              WHERE complete_order.order_no=o.order_no AND complete_order.is_deleted=0
                                AND complete_order.quantity>0
                              GROUP BY complete_order.id,complete_order.order_type,
                                       complete_order.quantity,complete_order.item_count
                             HAVING SUM(CASE WHEN complete_item.product_id IS NOT NULL
                                                  AND complete_item.quantity>0
                                             THEN complete_item.quantity ELSE 0 END)=complete_order.quantity
                                AND SUM(CASE WHEN complete_item.product_id IS NULL
                                                  OR complete_item.quantity IS NULL
                                                  OR complete_item.quantity<=0
                                             THEN 1 ELSE 0 END)=0
                                AND (
                                      UPPER(complete_order.order_type)='SINGLE'
                                      OR (
                                           UPPER(complete_order.order_type) IN ('BUNDLE','TRADE_IN')
                                           AND COUNT(complete_item.id)=complete_order.item_count
                                           AND complete_order.item_count=complete_order.quantity
                                         )
                                    )
                           )
                     GROUP BY oi.product_id
                    HAVING oi.product_id IS NOT NULL AND MIN(oi.quantity)>0
                  ) items ON items.product_id=p.id
               SET p.stock=p.stock+items.quantity,p.sold_count=p.sold_count-items.quantity,
                   p.updated_at=GREATEST(CURRENT_TIMESTAMP(6),p.updated_at + INTERVAL 1 MICROSECOND)
             WHERE p.sold_count>=items.quantity
               AND p.stock<=2147483647-items.quantity
            """)
    int restockOrderItemProducts(@Param("orderNo") String orderNo, @Param("now") LocalDateTime now);

    /** Historical single-item orders may predate nx_order_item and use nx_order.product_id. */
    @Update("""
            UPDATE nx_product p
             JOIN nx_order o ON o.product_id=p.id AND o.order_no=#{orderNo} AND o.is_deleted=0
               SET p.stock=p.stock+o.quantity,p.sold_count=GREATEST(0,p.sold_count-o.quantity),
                   p.updated_at=GREATEST(CURRENT_TIMESTAMP(6),p.updated_at + INTERVAL 1 MICROSECOND)
             WHERE o.quantity>0 AND p.sold_count>=o.quantity
               AND UPPER(o.order_type)='SINGLE'
               AND NOT EXISTS (SELECT 1 FROM nx_order_item oi
                                WHERE oi.order_no=o.order_no AND oi.is_deleted=0)
               AND p.stock<=2147483647-o.quantity
            """)
    int restockOrderProduct(@Param("orderNo") String orderNo, @Param("now") LocalDateTime now);

    record OrderRestockPlan(
            String orderType,
            Integer orderQuantity,
            Integer itemCount,
            Long itemRows,
            Long validItemQuantity,
            Long invalidItemRows,
            Long productGroups) {
    }

    @Select("""
            <script>
            SELECT
            """ + GENERATION_GATE_COLUMNS + """
              FROM nx_admin_device_generation_gate
             WHERE is_deleted = 0
             <if test='includeArchived == false'>AND status = 'active'</if>
             ORDER BY release_month ASC, id ASC
            </script>
            """)
    List<DeviceGenerationGateView> listGenerationGates(@Param("includeArchived") boolean includeArchived);

    @Select("""
            SELECT
            """ + GENERATION_GATE_COLUMNS + """
              FROM nx_admin_device_generation_gate
             WHERE sku_id = #{skuId} AND is_deleted = 0
             LIMIT 1
            """)
    DeviceGenerationGateView findGenerationGate(@Param("skuId") String skuId);

    @Insert("""
            INSERT INTO nx_admin_device_generation_gate (
              sku_id, name, release_month, phase, phase_id, tradein_discount, eligibility,
              phase_offset, force_unlock, status, created_at, updated_at, is_deleted
            ) VALUES (
              #{gate.skuId}, #{gate.name}, #{gate.releaseMonth}, '', #{gate.phaseId}, #{gate.discount}, #{gate.eligibility},
              #{gate.phaseOffset}, #{gate.forceUnlock}, #{gate.status}, #{gate.createdAt}, #{gate.updatedAt}, 0
            )
            ON DUPLICATE KEY UPDATE
              name = VALUES(name),
              release_month = VALUES(release_month),
              phase = VALUES(phase),
              phase_id = VALUES(phase_id),
              tradein_discount = VALUES(tradein_discount),
              eligibility = VALUES(eligibility),
              phase_offset = VALUES(phase_offset),
              force_unlock = VALUES(force_unlock),
              status = VALUES(status),
              updated_at = VALUES(updated_at),
              is_deleted = 0
            """)
    int upsertGenerationGate(@Param("gate") GenerationGateWrite gate);

    @Update("""
            UPDATE nx_admin_device_generation_gate
               SET status = 'archived',
                   updated_at = #{now}
             WHERE sku_id = #{skuId} AND is_deleted = 0 AND status <> 'archived'
            """)
    int archiveGenerationGate(@Param("skuId") String skuId, @Param("now") LocalDateTime now);

    record SkuRow(
            String skuId,
            String name,
            String tier,
            String tagline,
            String badge,
            String gpu,
            String vram,
            String hashRate,
            String power,
            String datacenter,
            String uptime,
            String warranty,
            BigDecimal phoneDailyEarn,
            BigDecimal phoneDailyEarnNex,
            BigDecimal price,
            BigDecimal dailyEarn,
            BigDecimal dailyEarnNex,
            BigDecimal shareYieldMin,
            BigDecimal shareYieldMax,
            String baseRate,
            Long sold,
            String stock,
            BigDecimal rating,
            Long reviews,
            Long aiImageGenPerMin,
            Long aiLlmTokensPerSec,
            Long aiVideoMinPerHour,
            Long aiFineTuneMins,
            String aiUnlocks,
            String featuresJson,
            Integer generation,
            String lifecycle,
            String supersededBy,
            BigDecimal tradeinDiscount,
            String unlockPhase,
            String purchaseGateJson,
            String imageAssetId,
            String imageObjectKey,
            String imagePreviewUrl,
            String tag,
            String status,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    record SkuWrite(
            String skuId,
            String name,
            String tier,
            String tagline,
            String badge,
            String gpu,
            String vram,
            String hashRate,
            String power,
            String datacenter,
            String uptime,
            String warranty,
            BigDecimal phoneDailyEarn,
            BigDecimal phoneDailyEarnNex,
            BigDecimal price,
            BigDecimal dailyEarn,
            BigDecimal dailyEarnNex,
            BigDecimal shareYieldMin,
            BigDecimal shareYieldMax,
            String baseRate,
            Long sold,
            String stock,
            BigDecimal rating,
            Long reviews,
            Long aiImageGenPerMin,
            Long aiLlmTokensPerSec,
            Long aiVideoMinPerHour,
            Long aiFineTuneMins,
            String aiUnlocks,
            String featuresJson,
            Integer generation,
            String lifecycle,
            String supersededBy,
            BigDecimal tradeinDiscount,
            Long unlockPhaseId,
            String purchaseGateJson,
            String imageAssetId,
            String imageObjectKey,
            String imagePreviewUrl,
            String tag,
            String status,
            BigDecimal canonicalHashrate,
            Integer canonicalVramGb,
            Integer canonicalStock,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    record GenerationGateWrite(
            String skuId,
            String name,
            Integer releaseMonth,
            Long phaseId,
            BigDecimal discount,
            Boolean eligibility,
            Integer phaseOffset,
            Boolean forceUnlock,
            String status,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    record PhaseWrite(
            String scope,
            String label,
            String meta,
            String skus,
            Integer sortOrder,
            String status,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    record ReviewWrite(
            String reviewId,
            String skuId,
            String author,
            Integer rating,
            String content,
            String dateText,
            String status,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    record TaskWrite(
            String taskId,
            String name,
            BigDecimal price,
            String unit,
            String requirement,
            BigDecimal saturation,
            String status,
            String taskClass,
            String model,
            BigDecimal minReward,
            BigDecimal maxReward,
            String minVram,
            String killInit,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    record TaskPriceSeedRow(
            String taskId,
            String taskClass,
            BigDecimal price,
            String unit) {
    }

    record TaskPriceHistorySchemaRow(
            long tableCount,
            long columnCount,
            long uniqueIndexCount,
            long observedIndexCount,
            long taskTimeIndexCount,
            long classTimeIndexCount) {
    }

    record PhoneTierRewardWrite(
            Integer tier,
            String name,
            String note,
            BigDecimal dailyUsdt,
            BigDecimal dailyNex,
            String status,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    record OrderWrite(
            String orderNo,
            String userNo,
            String skuId,
            String skuName,
            BigDecimal amount,
            String state,
            String dcLocation,
            String ageText) {
    }
}
