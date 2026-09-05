package ffdd.opsconsole.growth.mapper;

import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Durable, replay-safe intake for trusted server quest-completion facts. */
@Mapper
// Statement-only fact intake boundary spanning mission, user state and idempotency rows.
@SuppressWarnings("MybatisPlusBaseMapper")
public interface QuestCompletionFactMapper {

    @Select("SELECT id FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0 "
            + "AND COALESCE(sandbox,0)=0 FOR UPDATE")
    Long lockActiveUser(@Param("userId") Long userId);

    @Select("SELECT id FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0 "
            + "AND COALESCE(sandbox,0)=1 FOR UPDATE")
    Long lockActiveSandboxUser(@Param("userId") Long userId);

    @Select("""
            SELECT m.id missionId,m.mission_code questCode,m.mission_type layer,
                   CASE WHEN m.mission_type='DAY_ONE'
                        THEN CONCAT('DAY_ONE:',DATE_FORMAT(u.created_at,'%Y%m%dT%H%i%s'))
                        ELSE CONCAT('WEEK:',DATE_FORMAT(CONVERT_TZ(UTC_TIMESTAMP(),'+00:00','+08:00'),'%x-W%v'))
                   END instanceKey
              FROM nx_mission m
              JOIN nx_user u ON u.id=#{userId} AND u.status='ACTIVE' AND u.is_deleted=0
             WHERE m.mission_code=#{questCode} AND m.status=1 AND m.is_deleted=0
               AND m.mission_type IN ('DAY_ONE','WEEKLY_T1','WEEKLY_T2')
               AND (m.mission_type<>'DAY_ONE' OR NOW()<DATE_ADD(u.created_at,INTERVAL COALESCE((
                     SELECT CASE WHEN c.config_value REGEXP '^[0-9]{1,3}$'
                                      AND CAST(c.config_value AS UNSIGNED) BETWEEN 24 AND 720
                                 THEN CAST(c.config_value AS UNSIGNED) END
                       FROM nx_config_item c
                      WHERE c.config_key='growth.quest.day_one.eligibility_hours'
                        AND c.status=1 AND c.is_deleted=0 LIMIT 1),72) HOUR))
             LIMIT 1 FOR UPDATE
            """)
    MissionDefinition lockMissionInstance(
            @Param("userId") Long userId,
            @Param("questCode") String questCode);

    @Select("SELECT COUNT(*) FROM nx_mission WHERE mission_code=#{questCode} AND status=1 AND is_deleted=0")
    int activeMissionCount(@Param("questCode") String questCode);

    @Select("""
            SELECT mission_status FROM nx_user_mission
             WHERE user_id=#{userId} AND mission_id=#{missionId}
               AND instance_key=#{instanceKey} AND is_deleted=0
             LIMIT 1 FOR UPDATE
            """)
    String lockUserMissionStatus(
            @Param("userId") Long userId,
            @Param("missionId") Long missionId,
            @Param("instanceKey") String instanceKey);

    @Insert("""
            INSERT IGNORE INTO nx_growth_quest_completion_fact
              (producer,event_id,payload_hash,user_id,mission_id,quest_code,instance_key,created_at,updated_at,is_deleted)
            VALUES
              (#{producer},#{eventId},#{payloadHash},#{userId},#{missionId},#{questCode},#{instanceKey},NOW(),NOW(),0)
            """)
    int insertFact(
            @Param("producer") String producer,
            @Param("eventId") String eventId,
            @Param("payloadHash") String payloadHash,
            @Param("userId") Long userId,
            @Param("missionId") Long missionId,
            @Param("questCode") String questCode,
            @Param("instanceKey") String instanceKey);

    @Select("""
            SELECT producer,event_id eventId,payload_hash payloadHash,user_id userId,
                    mission_id missionId,quest_code questCode,instance_key instanceKey
              FROM nx_growth_quest_completion_fact
             WHERE producer=#{producer} AND event_id=#{eventId} AND is_deleted=0
             LIMIT 1 FOR UPDATE
            """)
    CompletionFact lockFact(@Param("producer") String producer, @Param("eventId") String eventId);

    @Select("""
            SELECT producer,event_id eventId,payload_hash payloadHash,user_id userId,
                    mission_id missionId,quest_code questCode,instance_key instanceKey
              FROM nx_growth_quest_completion_fact
             WHERE producer=#{producer} AND event_id=#{eventId} AND user_id=#{userId}
                AND mission_id=#{missionId} AND quest_code=#{questCode}
                AND instance_key=#{instanceKey} AND is_deleted=0
             LIMIT 1 FOR UPDATE
            """)
    CompletionFact lockFactForUserMission(
            @Param("producer") String producer,
            @Param("eventId") String eventId,
            @Param("userId") Long userId,
            @Param("missionId") Long missionId,
            @Param("questCode") String questCode,
            @Param("instanceKey") String instanceKey);

    @Insert("""
            INSERT INTO nx_user_mission
              (user_id,mission_id,instance_key,mission_status,completed_at,created_at,updated_at,is_deleted)
            VALUES
              (#{userId},#{missionId},#{instanceKey},'COMPLETED',NOW(),NOW(),NOW(),0)
            ON DUPLICATE KEY UPDATE
              mission_status=CASE WHEN UPPER(mission_status)='CLAIMED' THEN mission_status ELSE 'COMPLETED' END,
              completed_at=COALESCE(completed_at,NOW()),updated_at=NOW(),is_deleted=0
            """)
    int markMissionCompleted(
            @Param("userId") Long userId,
            @Param("missionId") Long missionId,
            @Param("instanceKey") String instanceKey);

    @Select("""
            SELECT COALESCE((SELECT config_value FROM nx_config_item
                              WHERE config_key='growth.phase.current' AND status=1 AND is_deleted=0 LIMIT 1),'P1') phase,
                   GREATEST(TIMESTAMPDIFF(MONTH,u.created_at,NOW()),0) accountAgeMonths,
                   DATE_FORMAT(u.created_at,'%x-W%v') cohort
              FROM nx_user u WHERE u.id=#{userId} AND u.is_deleted=0 LIMIT 1
            """)
    Map<String, Object> attribution(@Param("userId") Long userId);

    record MissionDefinition(Long missionId, String questCode, String layer, String instanceKey) {
        public MissionDefinition(Long missionId, String questCode, String layer) {
            this(missionId, questCode, layer, "TEST-INSTANCE");
        }
    }

    record CompletionFact(
            String producer,
            String eventId,
            String payloadHash,
            Long userId,
            Long missionId,
            String questCode,
            String instanceKey) {
        public CompletionFact(
                String producer,
                String eventId,
                String payloadHash,
                Long userId,
                Long missionId,
                String questCode) {
            this(producer, eventId, payloadHash, userId, missionId, questCode, "TEST-INSTANCE");
        }
    }
}
