package ffdd.opsconsole.commerce.mapper;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
// Statement-only SQL boundary for product, user and subscription joins.
@SuppressWarnings("MybatisPlusBaseMapper")
public interface AppStoreProductNotificationMapper {
    @Select("SELECT id FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0 "
            + "AND COALESCE(sandbox,0)=0 LIMIT 1")
    Long activeUser(@Param("userId") Long userId);

    @Select("SELECT id FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0 "
            + "AND COALESCE(sandbox,0)=1 LIMIT 1")
    Long activeSandboxUser(@Param("userId") Long userId);

    @Select("""
            SELECT id, product_no AS productNo, name, status, unlock_phase AS unlockPhase,
                   updated_at AS updatedAt
              FROM nx_product
             WHERE product_no=#{productNo} AND store_visible=1 AND is_deleted=0
             LIMIT 1
            """)
    ProductRow product(@Param("productNo") String productNo);

    @Select("""
            SELECT id, user_id AS userId, product_no AS productNo,
                   release_state AS releaseState, release_phase_id AS releasePhaseId,
                   revision, source, source_environment AS sourceEnvironment, run_id AS runId,
                   updated_at AS updatedAt
              FROM nx_product_notification_subscription
             WHERE user_id=#{userId} AND product_no=#{productNo}
               AND source_environment='PRODUCTION' AND run_id=''
               AND status='ACTIVE' AND is_deleted=0
             LIMIT 1
            """)
    SubscriptionRow activeSubscription(@Param("userId") Long userId, @Param("productNo") String productNo);

    @Select("""
            SELECT id, user_id AS userId, product_no AS productNo,
                   release_state AS releaseState, release_phase_id AS releasePhaseId,
                   revision, source, source_environment AS sourceEnvironment, run_id AS runId,
                   updated_at AS updatedAt
              FROM nx_product_notification_subscription
             WHERE user_id=#{userId} AND product_no=#{productNo}
               AND source_environment=#{sourceEnvironment} AND run_id=#{runId}
               AND status='ACTIVE' AND is_deleted=0
             LIMIT 1
            """)
    SubscriptionRow activeSubscriptionScoped(@Param("userId") Long userId, @Param("productNo") String productNo,
                                              @Param("sourceEnvironment") String sourceEnvironment,
                                              @Param("runId") String runId);

    @Select("""
            SELECT id, user_id AS userId, product_no AS productNo,
                   release_state AS releaseState, release_phase_id AS releasePhaseId,
                   revision, source, source_environment AS sourceEnvironment, run_id AS runId,
                   updated_at AS updatedAt
              FROM nx_product_notification_subscription
             WHERE user_id=#{userId} AND source_environment='PRODUCTION' AND run_id=''
               AND status='ACTIVE' AND is_deleted=0
             ORDER BY updated_at DESC, id DESC
            """)
    List<SubscriptionRow> activeSubscriptions(@Param("userId") Long userId);

    @Select("""
            SELECT id, user_id AS userId, product_no AS productNo,
                   release_state AS releaseState, release_phase_id AS releasePhaseId,
                   revision, source, source_environment AS sourceEnvironment, run_id AS runId,
                   updated_at AS updatedAt
              FROM nx_product_notification_subscription
             WHERE user_id=#{userId} AND source_environment=#{sourceEnvironment} AND run_id=#{runId}
               AND status='ACTIVE' AND is_deleted=0
             ORDER BY updated_at DESC, id DESC
            """)
    List<SubscriptionRow> activeSubscriptionsScoped(@Param("userId") Long userId,
                                                      @Param("sourceEnvironment") String sourceEnvironment,
                                                      @Param("runId") String runId);

    @Insert("""
            INSERT INTO nx_product_notification_subscription
              (user_id, product_id, product_no, release_state, release_phase_id,
               revision, source, source_environment, run_id, status, created_at, updated_at, is_deleted)
            VALUES
              (#{userId}, #{product.id}, #{product.productNo}, #{releaseState}, #{releasePhaseId},
               #{revision}, 'nx_product', 'PRODUCTION', '', 'ACTIVE', NOW(), NOW(), 0)
            ON DUPLICATE KEY UPDATE
              product_id=VALUES(product_id), release_state=VALUES(release_state),
              release_phase_id=VALUES(release_phase_id), revision=VALUES(revision),
              source='nx_product', status='ACTIVE', is_deleted=0, updated_at=NOW()
            """)
    int upsert(@Param("userId") Long userId,
               @Param("product") ProductRow product,
               @Param("releaseState") String releaseState,
               @Param("releasePhaseId") String releasePhaseId,
               @Param("revision") String revision);

    @Insert("""
            INSERT INTO nx_product_notification_subscription
              (user_id, product_id, product_no, release_state, release_phase_id,
               revision, source, source_environment, run_id, status, created_at, updated_at, is_deleted)
            VALUES
              (#{userId}, #{product.id}, #{product.productNo}, #{releaseState}, #{releasePhaseId},
               #{revision}, 'nx_product', #{sourceEnvironment}, #{runId}, 'ACTIVE', NOW(), NOW(), 0)
            ON DUPLICATE KEY UPDATE
              product_id=VALUES(product_id), release_state=VALUES(release_state),
              release_phase_id=VALUES(release_phase_id), revision=VALUES(revision),
              source='nx_product', status='ACTIVE', is_deleted=0, updated_at=NOW()
            """)
    int upsertScoped(@Param("userId") Long userId,
                     @Param("product") ProductRow product,
                     @Param("releaseState") String releaseState,
                     @Param("releasePhaseId") String releasePhaseId,
                     @Param("revision") String revision,
                     @Param("sourceEnvironment") String sourceEnvironment,
                     @Param("runId") String runId);

    @Update("""
            UPDATE nx_product_notification_subscription
             SET status='REMOVED', updated_at=NOW()
             WHERE user_id=#{userId} AND product_no=#{productNo}
               AND source_environment='PRODUCTION' AND run_id=''
               AND status='ACTIVE' AND is_deleted=0
            """)
    int deactivate(@Param("userId") Long userId, @Param("productNo") String productNo);

    @Update("""
            UPDATE nx_product_notification_subscription
               SET status='REMOVED', updated_at=NOW()
             WHERE user_id=#{userId} AND product_no=#{productNo}
               AND source_environment=#{sourceEnvironment} AND run_id=#{runId}
               AND status='ACTIVE' AND is_deleted=0
            """)
    int deactivateScoped(@Param("userId") Long userId, @Param("productNo") String productNo,
                         @Param("sourceEnvironment") String sourceEnvironment, @Param("runId") String runId);

    record ProductRow(Long id, String productNo, String name, String status,
                      String unlockPhase, LocalDateTime updatedAt) { }

    record SubscriptionRow(Long id, Long userId, String productNo, String releaseState,
                           String releasePhaseId, String revision, String source,
                           String sourceEnvironment, String runId, LocalDateTime updatedAt) {
        public SubscriptionRow(Long id, Long userId, String productNo, String releaseState,
                               String releasePhaseId, String revision, String source,
                               LocalDateTime updatedAt) {
            this(id, userId, productNo, releaseState, releasePhaseId, revision, source,
                    "PRODUCTION", "", updatedAt);
        }
    }
}
