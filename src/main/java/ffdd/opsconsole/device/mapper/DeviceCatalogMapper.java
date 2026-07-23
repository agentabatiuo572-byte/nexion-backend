package ffdd.opsconsole.device.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.device.domain.DeviceGenerationGateView;
import ffdd.opsconsole.device.domain.DeviceOrderFacts;
import ffdd.opsconsole.device.domain.DeviceOrderFundingView;
import ffdd.opsconsole.device.domain.DeviceOrderHistoryView;
import ffdd.opsconsole.device.domain.DeviceOrderView;
import ffdd.opsconsole.device.domain.DevicePhaseView;
import ffdd.opsconsole.device.domain.DevicePhoneTierRewardView;
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
    String SKU_COLUMNS = """
            sku_id AS skuId,
            name,
            tier,
            tagline,
            badge,
            gpu,
            vram,
            hash_rate AS hashRate,
            power_text AS power,
            datacenter,
            price,
            daily_earn AS dailyEarn,
            daily_earn_nex AS dailyEarnNex,
            share_yield_min AS shareYieldMin,
            share_yield_max AS shareYieldMax,
            base_rate AS baseRate,
            sold,
            stock_text AS stock,
            rating,
            reviews,
            ai_image_gen_per_min AS aiImageGenPerMin,
            ai_llm_tokens_per_sec AS aiLlmTokensPerSec,
            ai_video_min_per_hour AS aiVideoMinPerHour,
            ai_fine_tune_mins AS aiFineTuneMins,
            ai_unlocks AS aiUnlocks,
            features_json AS featuresJson,
            generation,
            lifecycle,
            superseded_by AS supersededBy,
            tradein_discount AS tradeinDiscount,
            COALESCE(CAST(unlock_phase_id AS CHAR), unlock_phase) AS unlockPhase,
            purchase_gate_json AS purchaseGateJson,
            image_asset_id AS imageAssetId,
            image_object_key AS imageObjectKey,
            image_preview_url AS imagePreviewUrl,
            tag,
            status,
            created_at AS createdAt,
            updated_at AS updatedAt
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

    String PHONE_TIER_COLUMNS = """
            tier,
            name,
            note,
            daily_usdt AS dailyUsdt,
            daily_nex AS dailyNex,
            status,
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
                       ORDER BY oi.sort_order,oi.id LIMIT 1), p.name, o.order_no) AS skuName,
            o.amount_usdt AS amount,
            """ + ORDER_STATE_SQL + """
             AS state,
            (SELECT ud.dc_location FROM nx_user_device ud
              WHERE ud.source_order_no=o.order_no AND ud.is_deleted=0 ORDER BY ud.id DESC LIMIT 1) AS dcLocation,
            CONCAT(TIMESTAMPDIFF(MINUTE,o.created_at,NOW()),'m') AS ageText,
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
            SELECT COUNT(*)
              FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'nx_admin_device_sku'
               AND COLUMN_NAME = 'purchase_gate_json'
            """)
    int countSkuPurchaseGateColumn();

    @Update("ALTER TABLE nx_admin_device_sku ADD COLUMN purchase_gate_json TEXT NULL AFTER unlock_phase")
    void addSkuPurchaseGateColumn();

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
              FROM nx_admin_device_sku
             WHERE (CAST(unlock_phase_id AS CHAR) = #{phaseId} OR unlock_phase = #{phaseId})
               AND is_deleted = 0
               AND status <> 'off'
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
            SELECT COUNT(*) FROM nx_admin_device_sku
             WHERE is_deleted = 0
             <if test='status != null and status != ""'>AND status = #{status}</if>
             <if test='keyword != null and keyword != ""'>
               AND (sku_id LIKE CONCAT('%', #{keyword}, '%')
                    OR name LIKE CONCAT('%', #{keyword}, '%')
                    OR tagline LIKE CONCAT('%', #{keyword}, '%')
                    OR gpu LIKE CONCAT('%', #{keyword}, '%'))
             </if>
            </script>
            """)
    long countSkus(@Param("status") String status, @Param("keyword") String keyword);

    @Select("""
            <script>
            SELECT
            """ + SKU_COLUMNS + """
              FROM nx_admin_device_sku
             WHERE is_deleted = 0
             <if test='status != null and status != ""'>AND status = #{status}</if>
             <if test='keyword != null and keyword != ""'>
               AND (sku_id LIKE CONCAT('%', #{keyword}, '%')
                    OR name LIKE CONCAT('%', #{keyword}, '%')
                    OR tagline LIKE CONCAT('%', #{keyword}, '%')
                    OR gpu LIKE CONCAT('%', #{keyword}, '%'))
             </if>
             ORDER BY FIELD(status,'on','pending','off'), updated_at DESC, id DESC
             LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<SkuRow> pageSkus(@Param("status") String status, @Param("keyword") String keyword,
                          @Param("limit") long limit, @Param("offset") long offset);

    @Select("""
            SELECT
            """ + SKU_COLUMNS + """
              FROM nx_admin_device_sku
             WHERE sku_id = #{skuId} AND is_deleted = 0
             LIMIT 1
            """)
    SkuRow findSku(@Param("skuId") String skuId);

    @Select("""
            SELECT
            """ + SKU_COLUMNS + """
              FROM nx_admin_device_sku
             WHERE ai_unlocks = #{taskId} AND is_deleted = 0
             ORDER BY FIELD(status,'on','pending','off'), updated_at DESC, id DESC
            """)
    List<SkuRow> findSkusByAiUnlocks(@Param("taskId") String taskId);

    @Insert("""
            INSERT INTO nx_admin_device_sku (
              sku_id,name,tier,tagline,badge,gpu,vram,hash_rate,power_text,datacenter,price,
              daily_earn,daily_earn_nex,share_yield_min,share_yield_max,base_rate,sold,stock_text,
              rating,reviews,ai_image_gen_per_min,ai_llm_tokens_per_sec,ai_video_min_per_hour,
              ai_fine_tune_mins,ai_unlocks,features_json,generation,lifecycle,superseded_by,
              tradein_discount,unlock_phase,unlock_phase_id,purchase_gate_json,image_asset_id,image_object_key,image_preview_url,tag,status,
              created_at,updated_at,is_deleted
            ) VALUES (
              #{sku.skuId},#{sku.name},#{sku.tier},#{sku.tagline},#{sku.badge},#{sku.gpu},#{sku.vram},#{sku.hashRate},
              #{sku.power},#{sku.datacenter},#{sku.price},#{sku.dailyEarn},#{sku.dailyEarnNex},#{sku.shareYieldMin},
              #{sku.shareYieldMax},#{sku.baseRate},#{sku.sold},#{sku.stock},#{sku.rating},#{sku.reviews},
              #{sku.aiImageGenPerMin},#{sku.aiLlmTokensPerSec},#{sku.aiVideoMinPerHour},#{sku.aiFineTuneMins},
              #{sku.aiUnlocks},#{sku.featuresJson},#{sku.generation},#{sku.lifecycle},#{sku.supersededBy},
              #{sku.tradeinDiscount},'',#{sku.unlockPhaseId},#{sku.purchaseGateJson},#{sku.imageAssetId},#{sku.imageObjectKey},
              #{sku.imagePreviewUrl},#{sku.tag},#{sku.status},#{sku.createdAt},#{sku.updatedAt},0
            )
            """)
    int insertSku(@Param("sku") SkuWrite sku);

    @Update("""
            UPDATE nx_admin_device_sku
               SET name = #{sku.name},
                   tier = #{sku.tier},
                   tagline = #{sku.tagline},
                   badge = #{sku.badge},
                   gpu = #{sku.gpu},
                   vram = #{sku.vram},
                   hash_rate = #{sku.hashRate},
                   power_text = #{sku.power},
                   datacenter = #{sku.datacenter},
                   price = #{sku.price},
                   daily_earn = #{sku.dailyEarn},
                   daily_earn_nex = #{sku.dailyEarnNex},
                   share_yield_min = #{sku.shareYieldMin},
                   share_yield_max = #{sku.shareYieldMax},
                   base_rate = #{sku.baseRate},
                   sold = #{sku.sold},
                   stock_text = #{sku.stock},
                   rating = #{sku.rating},
                   reviews = #{sku.reviews},
                   ai_image_gen_per_min = #{sku.aiImageGenPerMin},
                   ai_llm_tokens_per_sec = #{sku.aiLlmTokensPerSec},
                   ai_video_min_per_hour = #{sku.aiVideoMinPerHour},
                   ai_fine_tune_mins = #{sku.aiFineTuneMins},
                   ai_unlocks = #{sku.aiUnlocks},
                   features_json = #{sku.featuresJson},
                   generation = #{sku.generation},
                   lifecycle = #{sku.lifecycle},
                   superseded_by = #{sku.supersededBy},
                   tradein_discount = #{sku.tradeinDiscount},
                   unlock_phase = '',
                   unlock_phase_id = #{sku.unlockPhaseId},
                   purchase_gate_json = #{sku.purchaseGateJson},
                   image_asset_id = #{sku.imageAssetId},
                   image_object_key = #{sku.imageObjectKey},
                   image_preview_url = #{sku.imagePreviewUrl},
                   tag = #{sku.tag},
                   status = #{sku.status},
                   updated_at = #{sku.updatedAt}
             WHERE sku_id = #{sku.skuId} AND is_deleted = 0
            """)
    int updateSku(@Param("sku") SkuWrite sku);

    @Update("""
            UPDATE nx_admin_device_sku
               SET status = #{status}, updated_at = #{now}
             WHERE sku_id = #{skuId} AND is_deleted = 0
            """)
    int updateSkuStatus(@Param("skuId") String skuId, @Param("status") String status, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_admin_device_sku
               SET is_deleted = 1, updated_at = #{now}
             WHERE sku_id = #{skuId} AND is_deleted = 0
            """)
    int softDeleteSku(@Param("skuId") String skuId, @Param("now") LocalDateTime now);

    @Select("""
            <script>
            SELECT COUNT(*)
              FROM nx_admin_device_review r
              LEFT JOIN nx_admin_device_sku s ON s.sku_id = r.sku_id AND s.is_deleted = 0
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
              LEFT JOIN nx_admin_device_sku s ON s.sku_id = r.sku_id AND s.is_deleted = 0
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
              LEFT JOIN nx_admin_device_sku s ON s.sku_id = r.sku_id AND s.is_deleted = 0
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
              FROM nx_admin_phone_tier_reward
             WHERE is_deleted = 0
             ORDER BY tier ASC
            """)
    List<DevicePhoneTierRewardView> listPhoneTierRewards();

    @Select("""
            SELECT
            """ + PHONE_TIER_COLUMNS + """
              FROM nx_admin_phone_tier_reward
             WHERE tier = #{tier} AND is_deleted = 0
             LIMIT 1
            """)
    DevicePhoneTierRewardView findPhoneTierReward(@Param("tier") Integer tier);

    @Insert("""
            INSERT INTO nx_admin_phone_tier_reward (
              tier, name, note, daily_usdt, daily_nex, status, created_at, updated_at, is_deleted
            ) VALUES (
              #{row.tier}, #{row.name}, #{row.note}, #{row.dailyUsdt}, #{row.dailyNex},
              #{row.status}, #{row.createdAt}, #{row.updatedAt}, 0
            )
            """)
    int insertPhoneTierReward(@Param("row") PhoneTierRewardWrite row);

    @Update("""
            UPDATE nx_admin_phone_tier_reward
               SET daily_usdt = #{row.dailyUsdt},
                   daily_nex = #{row.dailyNex},
                   updated_at = #{row.updatedAt}
             WHERE tier = #{row.tier} AND is_deleted = 0
            """)
    int updatePhoneTierReward(@Param("row") PhoneTierRewardWrite row);

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

    @Update("""
            UPDATE nx_product p
              JOIN nx_order o ON o.product_id=p.id AND o.order_no=#{orderNo} AND o.is_deleted=0
               SET p.stock=p.stock+o.quantity,p.sold_count=GREATEST(0,p.sold_count-o.quantity),p.updated_at=#{now}
             WHERE p.is_deleted=0
            """)
    int restockOrderProduct(@Param("orderNo") String orderNo, @Param("now") LocalDateTime now);

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
