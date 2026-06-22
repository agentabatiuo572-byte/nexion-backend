package ffdd.opsconsole.device.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.device.domain.DeviceOrderView;
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
            unlock_phase AS unlockPhase,
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
            created_at AS createdAt,
            updated_at AS updatedAt
            """;

    String ORDER_COLUMNS = """
            order_no AS orderNo,
            user_no AS userNo,
            sku_id AS skuId,
            sku_name AS skuName,
            amount,
            state,
            dc_location AS dcLocation,
            age_text AS ageText,
            ordered_at AS orderedAt,
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
              unlock_phase VARCHAR(16) NOT NULL DEFAULT 'P1',
              purchase_gate_json TEXT,
              image_asset_id VARCHAR(64) DEFAULT NULL,
              image_object_key VARCHAR(255) DEFAULT NULL,
              image_preview_url VARCHAR(1024) DEFAULT NULL,
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
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_admin_device_task (task_id),
              KEY idx_admin_device_task_status (status,is_deleted),
              KEY idx_admin_device_task_name (name)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createTaskTable();

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

    @Insert("""
            INSERT INTO nx_admin_device_sku (
              sku_id,name,tier,tagline,badge,gpu,vram,hash_rate,power_text,datacenter,price,
              daily_earn,daily_earn_nex,share_yield_min,share_yield_max,base_rate,sold,stock_text,
              rating,reviews,ai_image_gen_per_min,ai_llm_tokens_per_sec,ai_video_min_per_hour,
              ai_fine_tune_mins,ai_unlocks,features_json,generation,lifecycle,superseded_by,
              tradein_discount,unlock_phase,purchase_gate_json,image_asset_id,image_object_key,image_preview_url,tag,status,
              created_at,updated_at,is_deleted
            ) VALUES (
              #{sku.skuId},#{sku.name},#{sku.tier},#{sku.tagline},#{sku.badge},#{sku.gpu},#{sku.vram},#{sku.hashRate},
              #{sku.power},#{sku.datacenter},#{sku.price},#{sku.dailyEarn},#{sku.dailyEarnNex},#{sku.shareYieldMin},
              #{sku.shareYieldMax},#{sku.baseRate},#{sku.sold},#{sku.stock},#{sku.rating},#{sku.reviews},
              #{sku.aiImageGenPerMin},#{sku.aiLlmTokensPerSec},#{sku.aiVideoMinPerHour},#{sku.aiFineTuneMins},
              #{sku.aiUnlocks},#{sku.featuresJson},#{sku.generation},#{sku.lifecycle},#{sku.supersededBy},
              #{sku.tradeinDiscount},#{sku.unlockPhase},#{sku.purchaseGateJson},#{sku.imageAssetId},#{sku.imageObjectKey},
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
                   unlock_phase = #{sku.unlockPhase},
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
             <if test='keyword != null and keyword != ""'>
               AND (task_id LIKE CONCAT('%', #{keyword}, '%')
                    OR name LIKE CONCAT('%', #{keyword}, '%')
                    OR requirement LIKE CONCAT('%', #{keyword}, '%'))
             </if>
            </script>
            """)
    long countTasks(@Param("status") String status, @Param("keyword") String keyword);

    @Select("""
            <script>
            SELECT
            """ + TASK_COLUMNS + """
              FROM nx_admin_device_task
             WHERE is_deleted = 0
             <if test='status != null and status != ""'>AND status = #{status}</if>
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
              task_id, name, price, unit_text, requirement, saturation, status, created_at, updated_at, is_deleted
            ) VALUES (
              #{task.taskId}, #{task.name}, #{task.price}, #{task.unit}, #{task.requirement}, #{task.saturation},
              #{task.status}, #{task.createdAt}, #{task.updatedAt}, 0
            )
            """)
    int insertTask(@Param("task") TaskWrite task);

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

    @Select("""
            <script>
            SELECT COUNT(*) FROM nx_admin_device_order
             WHERE is_deleted = 0
             <if test='state != null and state != ""'>AND state = #{state}</if>
             <if test='keyword != null and keyword != ""'>
               AND (order_no LIKE CONCAT('%', #{keyword}, '%')
                    OR user_no LIKE CONCAT('%', #{keyword}, '%')
                    OR sku_id LIKE CONCAT('%', #{keyword}, '%')
                    OR sku_name LIKE CONCAT('%', #{keyword}, '%'))
             </if>
            </script>
            """)
    long countOrders(@Param("state") String state, @Param("keyword") String keyword);

    @Select("""
            <script>
            SELECT
            """ + ORDER_COLUMNS + """
              FROM nx_admin_device_order
             WHERE is_deleted = 0
             <if test='state != null and state != ""'>AND state = #{state}</if>
             <if test='keyword != null and keyword != ""'>
               AND (order_no LIKE CONCAT('%', #{keyword}, '%')
                    OR user_no LIKE CONCAT('%', #{keyword}, '%')
                    OR sku_id LIKE CONCAT('%', #{keyword}, '%')
                    OR sku_name LIKE CONCAT('%', #{keyword}, '%'))
             </if>
             ORDER BY ordered_at DESC, id DESC
             LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<DeviceOrderView> pageOrders(@Param("state") String state, @Param("keyword") String keyword,
                                     @Param("limit") long limit, @Param("offset") long offset);

    @Select("""
            SELECT
            """ + ORDER_COLUMNS + """
              FROM nx_admin_device_order
             WHERE order_no = #{orderNo} AND is_deleted = 0
             LIMIT 1
            """)
    DeviceOrderView findOrder(@Param("orderNo") String orderNo);

    @Update("""
            UPDATE nx_admin_device_order
               SET state = #{state}, updated_at = #{now}
             WHERE order_no = #{orderNo} AND is_deleted = 0
            """)
    int updateOrderState(@Param("orderNo") String orderNo, @Param("state") String state, @Param("now") LocalDateTime now);

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
