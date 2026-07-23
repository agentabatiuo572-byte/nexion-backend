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

    @Select("SELECT id FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0 FOR UPDATE")
    Long lockActiveUser(@Param("userId") Long userId);

    @Select("""
            SELECT id missionId,mission_code questCode,mission_type layer
              FROM nx_mission
             WHERE mission_code=#{questCode} AND status=1 AND is_deleted=0
             LIMIT 1 FOR UPDATE
            """)
    MissionDefinition lockMission(@Param("questCode") String questCode);

    @Select("""
            SELECT mission_status FROM nx_user_mission
             WHERE user_id=#{userId} AND mission_id=#{missionId} AND is_deleted=0
             LIMIT 1 FOR UPDATE
            """)
    String lockUserMissionStatus(@Param("userId") Long userId, @Param("missionId") Long missionId);

    @Insert("""
            INSERT IGNORE INTO nx_growth_quest_completion_fact
              (producer,event_id,payload_hash,user_id,mission_id,quest_code,created_at,updated_at,is_deleted)
            VALUES
              (#{producer},#{eventId},#{payloadHash},#{userId},#{missionId},#{questCode},NOW(),NOW(),0)
            """)
    int insertFact(
            @Param("producer") String producer,
            @Param("eventId") String eventId,
            @Param("payloadHash") String payloadHash,
            @Param("userId") Long userId,
            @Param("missionId") Long missionId,
            @Param("questCode") String questCode);

    @Select("""
            SELECT producer,event_id eventId,payload_hash payloadHash,user_id userId,
                   mission_id missionId,quest_code questCode
              FROM nx_growth_quest_completion_fact
             WHERE producer=#{producer} AND event_id=#{eventId} AND is_deleted=0
             LIMIT 1 FOR UPDATE
            """)
    CompletionFact lockFact(@Param("producer") String producer, @Param("eventId") String eventId);

    @Insert("""
            INSERT INTO nx_user_mission
              (user_id,mission_id,mission_status,completed_at,created_at,updated_at,is_deleted)
            VALUES
              (#{userId},#{missionId},'COMPLETED',NOW(),NOW(),NOW(),0)
            ON DUPLICATE KEY UPDATE
              mission_status=CASE WHEN UPPER(mission_status)='CLAIMED' THEN mission_status ELSE 'COMPLETED' END,
              completed_at=COALESCE(completed_at,NOW()),updated_at=NOW(),is_deleted=0
            """)
    int markMissionCompleted(@Param("userId") Long userId, @Param("missionId") Long missionId);

    @Select("""
            SELECT COALESCE((SELECT config_value FROM nx_config_item
                              WHERE config_key='growth.phase.current' AND status=1 AND is_deleted=0 LIMIT 1),'P1') phase,
                   GREATEST(TIMESTAMPDIFF(MONTH,u.created_at,NOW()),0) accountAgeMonths,
                   DATE_FORMAT(u.created_at,'%x-W%v') cohort
              FROM nx_user u WHERE u.id=#{userId} AND u.is_deleted=0 LIMIT 1
            """)
    Map<String, Object> attribution(@Param("userId") Long userId);

    record MissionDefinition(Long missionId, String questCode, String layer) {
    }

    record CompletionFact(
            String producer,
            String eventId,
            String payloadHash,
            Long userId,
            Long missionId,
            String questCode) {
    }
}
