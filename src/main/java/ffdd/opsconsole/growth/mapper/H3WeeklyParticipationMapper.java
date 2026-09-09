package ffdd.opsconsole.growth.mapper;

import java.time.LocalDateTime;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Storage boundary for server-observed H3 weekly participation only. */
@Mapper
@SuppressWarnings("MybatisPlusBaseMapper")
public interface H3WeeklyParticipationMapper {

    @Insert("""
            INSERT IGNORE INTO nx_growth_weekly_participation_observation
              (user_id,instance_key,observation_type,subject_key,observed_at,created_at,updated_at,is_deleted)
            VALUES (#{userId},#{instanceKey},#{observationType},#{subjectKey},#{observedAt},NOW(),NOW(),0)
            """)
    int insertObservation(
            @Param("userId") Long userId,
            @Param("instanceKey") String instanceKey,
            @Param("observationType") String observationType,
            @Param("subjectKey") String subjectKey,
            @Param("observedAt") LocalDateTime observedAt);

    @Insert("""
            INSERT INTO nx_growth_weekly_participation_threshold
              (user_id,instance_key,threshold_event_type,created_at,updated_at,is_deleted)
            VALUES (#{userId},#{instanceKey},#{thresholdEventType},NOW(),NOW(),0)
            ON DUPLICATE KEY UPDATE updated_at=NOW(),is_deleted=0
            """)
    int ensureThreshold(
            @Param("userId") Long userId,
            @Param("instanceKey") String instanceKey,
            @Param("thresholdEventType") String thresholdEventType);

    @Select("""
            SELECT id AS rowId, emitted_at AS emittedAt
              FROM nx_growth_weekly_participation_threshold
             WHERE user_id=#{userId} AND instance_key=#{instanceKey}
               AND threshold_event_type=#{thresholdEventType} AND is_deleted=0
             LIMIT 1 FOR UPDATE
            """)
    ThresholdRow lockThreshold(
            @Param("userId") Long userId,
            @Param("instanceKey") String instanceKey,
            @Param("thresholdEventType") String thresholdEventType);

    /**
     * Locking current read after the per-threshold marker lock. Returning ids
     * rather than COUNT avoids an RR snapshot missing the preceding commit.
     */
    @Select("""
            SELECT id
              FROM nx_growth_weekly_participation_observation
             WHERE user_id=#{userId} AND instance_key=#{instanceKey}
               AND observation_type=#{observationType} AND is_deleted=0
             FOR UPDATE
            """)
    java.util.List<Long> lockObservationIds(
            @Param("userId") Long userId,
            @Param("instanceKey") String instanceKey,
            @Param("observationType") String observationType);

    @Update("""
            UPDATE nx_growth_weekly_participation_threshold
               SET emitted_at=#{emittedAt},updated_at=NOW()
             WHERE user_id=#{userId} AND instance_key=#{instanceKey}
               AND threshold_event_type=#{thresholdEventType} AND emitted_at IS NULL AND is_deleted=0
            """)
    int markThresholdEmitted(
            @Param("userId") Long userId,
            @Param("instanceKey") String instanceKey,
            @Param("thresholdEventType") String thresholdEventType,
            @Param("emittedAt") LocalDateTime emittedAt);

    @Select("""
            SELECT effective_at FROM nx_growth_weekly_participation_rollout WHERE id=1
            """)
    LocalDateTime rolloutEffectiveAt();

    @Select("""
            SELECT t.completed_at completedAt
              FROM nx_compute_task t
              JOIN nx_compute_receipt r ON r.task_no=t.task_no AND r.user_id=t.user_id
                AND r.source_environment='PRODUCTION' AND UPPER(r.earning_status)='CREDITED' AND r.is_deleted=0
              JOIN nx_user u ON u.id=t.user_id AND u.status='ACTIVE'
                AND u.is_deleted=0 AND COALESCE(u.sandbox,0)=0
             WHERE t.user_id=#{userId} AND t.task_no=#{taskNo}
               AND t.source_environment='PRODUCTION' AND t.is_deleted=0
               AND UPPER(t.status)='COMPLETED' AND t.completed_at IS NOT NULL
               AND t.proof_consumed_at IS NOT NULL
            """)
    VerifiedProductionCompletion verifiedProductionCompletedTask(@Param("userId") Long userId, @Param("taskNo") String taskNo);

    @Select("""
            SELECT COALESCE((SELECT config_value FROM nx_config_item
                              WHERE config_key='growth.phase.current' AND status=1 AND is_deleted=0 LIMIT 1),'P1') phase,
                   GREATEST(TIMESTAMPDIFF(MONTH,u.created_at,NOW()),0) accountAgeMonths,
                   DATE_FORMAT(u.created_at,'%x-W%v') cohort
              FROM nx_user u
             WHERE u.id=#{userId} AND u.status='ACTIVE' AND u.is_deleted=0
               AND COALESCE(u.sandbox,0)=0
             LIMIT 1
            """)
    Map<String, Object> attribution(@Param("userId") Long userId);

    record ThresholdRow(Long rowId, LocalDateTime emittedAt) {}

    record VerifiedProductionCompletion(LocalDateTime completedAt) {}
}
