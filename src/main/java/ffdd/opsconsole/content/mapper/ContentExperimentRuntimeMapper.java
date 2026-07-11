package ffdd.opsconsole.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.content.infrastructure.ContentExperimentRuntimeRows.AssignmentRow;
import ffdd.opsconsole.content.infrastructure.ContentExperimentRuntimeRows.CopyBodyRow;
import ffdd.opsconsole.content.infrastructure.ContentExperimentRuntimeRows.ExperimentRow;
import ffdd.opsconsole.content.infrastructure.ContentExperimentRuntimeRows.UserAudienceRow;
import ffdd.opsconsole.content.infrastructure.ContentExperimentRuntimeRows.VariantRow;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface ContentExperimentRuntimeMapper extends BaseMapper<Object> {
    @Select("""
            SELECT id AS userId,
                   COALESCE(status, 'ACTIVE') AS status,
                   COALESCE(language, '') AS language,
                   created_at AS registeredAt
              FROM nx_user
             WHERE id = #{userId}
               AND is_deleted = 0
             LIMIT 1
            """)
    UserAudienceRow findUserAudienceProfile(@Param("userId") long userId);

    @Select("""
            SELECT experiment_id AS experimentId,
                   copy_key AS copyKey,
                   CAST(audience_snapshot_json AS CHAR) AS audienceSnapshotJson
              FROM nx_content_experiment
             WHERE copy_key = #{copyKey}
               AND state = 'RUNNING'
               AND is_deleted = 0
             ORDER BY id DESC
             LIMIT 1
            """)
    ExperimentRow findRunningExperiment(@Param("copyKey") String copyKey);

    @Select("""
            SELECT experiment_id AS experimentId,
                   copy_key AS copyKey,
                   CAST(audience_snapshot_json AS CHAR) AS audienceSnapshotJson
              FROM nx_content_experiment
             WHERE copy_key = #{copyKey}
               AND state = 'RUNNING'
               AND is_deleted = 0
             ORDER BY id DESC
             LIMIT 1
             FOR UPDATE
            """)
    ExperimentRow findRunningExperimentForUpdate(@Param("copyKey") String copyKey);

    @Select("""
            SELECT variant_name AS variantName,
                   copy_version AS copyVersion,
                   split_pct AS splitPct,
                   sort_order AS sortOrder
              FROM nx_content_experiment_variant
             WHERE experiment_id = #{experimentId}
               AND is_deleted = 0
             ORDER BY sort_order, id
            """)
    List<VariantRow> listVariants(@Param("experimentId") String experimentId);

    @Select("""
            SELECT experiment_id AS experimentId,
                   user_id AS userId,
                   variant_name AS variantName,
                   copy_version AS copyVersion,
                   bucket_no AS bucketNo,
                   exposed_at AS exposedAt
              FROM nx_content_experiment_assignment
             WHERE experiment_id = #{experimentId}
               AND user_id = #{userId}
             LIMIT 1
            """)
    AssignmentRow findAssignment(
            @Param("experimentId") String experimentId,
            @Param("userId") long userId);

    @Select("""
            SELECT COUNT(*)
              FROM nx_content_experiment
             WHERE experiment_id = #{experimentId}
               AND state = 'RUNNING'
               AND is_deleted = 0
            """)
    long countRunningExperiment(@Param("experimentId") String experimentId);

    @Select("""
            SELECT COUNT(*)
              FROM nx_order
             WHERE user_id = #{userId}
               AND order_no = #{orderNo}
               AND is_deleted = 0
               AND (
                    UPPER(COALESCE(payment_status, '')) IN ('PAID', 'SUCCESS', 'CONFIRMED')
                    OR UPPER(COALESCE(order_status, '')) IN ('PAID', 'COMPLETED', 'SUCCESS')
               )
            """)
    long countEligibleConversionOrder(
            @Param("userId") long userId,
            @Param("orderNo") String orderNo);

    @Insert("""
            INSERT IGNORE INTO nx_content_experiment_assignment
              (experiment_id, user_id, variant_name, copy_version, bucket_no,
               exposed_at, created_at, updated_at)
            SELECT e.experiment_id, #{userId}, #{variantName}, #{copyVersion}, #{bucketNo},
                   NULL, #{createdAt}, #{createdAt}
              FROM nx_content_experiment e
             WHERE e.experiment_id = #{experimentId}
               AND e.state = 'RUNNING'
               AND e.is_deleted = 0
            """)
    int insertAssignmentIfAbsent(
            @Param("experimentId") String experimentId,
            @Param("userId") long userId,
            @Param("variantName") String variantName,
            @Param("copyVersion") String copyVersion,
            @Param("bucketNo") int bucketNo,
            @Param("createdAt") LocalDateTime createdAt);

    @Update("""
            UPDATE nx_content_experiment_assignment a
              JOIN nx_content_experiment e
                ON e.experiment_id = a.experiment_id
               AND e.state = 'RUNNING'
               AND e.is_deleted = 0
               SET a.exposed_at = #{exposedAt},
                   a.updated_at = #{exposedAt}
             WHERE a.experiment_id = #{experimentId}
               AND a.user_id = #{userId}
               AND a.exposed_at IS NULL
            """)
    int markExposedIfFirst(
            @Param("experimentId") String experimentId,
            @Param("userId") long userId,
            @Param("exposedAt") LocalDateTime exposedAt);

    @Select("""
            SELECT v.copy_key AS copyKey,
                   v.version AS version,
                   v.zh_text AS zhText,
                   v.en_text AS enText,
                   v.vi_text AS viText
              FROM nx_content_copy c
              JOIN nx_content_copy_version v
                ON v.copy_key = c.copy_key
               AND v.version = c.current_version
               AND v.is_deleted = 0
             WHERE c.copy_key = #{copyKey}
               AND c.is_deleted = 0
               AND c.status = 'PUBLISHED'
               AND v.status = 'PUBLISHED'
             LIMIT 1
            """)
    CopyBodyRow findPublishedCopy(@Param("copyKey") String copyKey);

    @Select("""
            SELECT copy_key AS copyKey,
                   version AS version,
                   zh_text AS zhText,
                   en_text AS enText,
                   vi_text AS viText
              FROM nx_content_copy_version
             WHERE copy_key = #{copyKey}
               AND version = #{version}
               AND is_deleted = 0
             LIMIT 1
            """)
    CopyBodyRow findCopyVersion(
            @Param("copyKey") String copyKey,
            @Param("version") String version);

    @Insert("""
            INSERT IGNORE INTO nx_content_experiment_conversion
              (experiment_id, user_id, conversion_key, variant_name, converted_at)
            SELECT a.experiment_id, a.user_id, #{conversionKey}, a.variant_name, #{convertedAt}
              FROM nx_content_experiment_assignment a
              JOIN nx_content_experiment e
                ON e.experiment_id = a.experiment_id
               AND e.state = 'RUNNING'
               AND e.is_deleted = 0
              JOIN nx_order o
                ON o.user_id = a.user_id
               AND o.order_no = #{conversionKey}
               AND o.is_deleted = 0
               AND (
                    UPPER(COALESCE(o.payment_status, '')) IN ('PAID', 'SUCCESS', 'CONFIRMED')
                    OR UPPER(COALESCE(o.order_status, '')) IN ('PAID', 'COMPLETED', 'SUCCESS')
               )
             WHERE a.experiment_id = #{experimentId}
               AND a.user_id = #{userId}
               AND a.variant_name = #{variantName}
            """)
    int insertConversionIfAbsent(
            @Param("experimentId") String experimentId,
            @Param("userId") long userId,
            @Param("conversionKey") String conversionKey,
            @Param("variantName") String variantName,
            @Param("convertedAt") LocalDateTime convertedAt);
}
