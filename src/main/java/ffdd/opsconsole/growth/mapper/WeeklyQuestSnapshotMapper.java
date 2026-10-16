package ffdd.opsconsole.growth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import ffdd.opsconsole.growth.mapper.QuestCanonicalEventBindingMapper.CanonicalQuestEventBinding;
import ffdd.opsconsole.growth.mapper.QuestCompletionFactMapper.MissionDefinition;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/** Published weekly definitions, distinct from mutable per-user completion progress. */
@Mapper
public interface WeeklyQuestSnapshotMapper extends BaseMapper<Object> {
    @Select("""
            SELECT instance_key instanceKey,eligible_from eligibleFrom,eligible_until eligibleUntil,
                   quest_bonus_multiplier questBonusMultiplier,rhythm_month rhythmMonth,
                   required_task_count requiredTaskCount
              FROM nx_growth_weekly_definition WHERE instance_key=#{key}
            """)
    Header find(@Param("key") String key);

    @Select("""
            SELECT instance_key instanceKey,eligible_from eligibleFrom,eligible_until eligibleUntil,
                   quest_bonus_multiplier questBonusMultiplier,rhythm_month rhythmMonth,
                   required_task_count requiredTaskCount
              FROM nx_growth_weekly_definition WHERE instance_key=#{key} FOR UPDATE
            """)
    Header findAfterLock(@Param("key") String key);

    @Select("SELECT instance_key FROM nx_growth_weekly_definition WHERE instance_key=#{key} FOR SHARE")
    String publishedKey(@Param("key") String key);

    @Insert("INSERT INTO nx_admin_operation_mutex(lock_key,updated_at) VALUES('H3_CONFIG',NOW()) ON DUPLICATE KEY UPDATE updated_at=updated_at")
    int ensureMutex();

    @Select("SELECT lock_key FROM nx_admin_operation_mutex WHERE lock_key='H3_CONFIG' FOR UPDATE")
    String lockMutex();

    @Select("""
            SELECT m.id missionId,m.mission_code questCode,m.mission_name name,m.mission_type layer,
                   m.mission_category category,m.action_route actionRoute,m.reward_points rewardPoints,
                   b.id bindingId,b.binding_code bindingCode,b.producer,b.event_type eventType,b.user_id_field userIdField
              FROM nx_mission m JOIN nx_growth_quest_event_binding b
                ON b.quest_code=m.mission_code AND b.status=1 AND b.is_deleted=0
             WHERE m.mission_type IN ('WEEKLY_T1','WEEKLY_T2') AND m.status=1 AND m.is_deleted=0
               AND (m.mission_code NOT IN ('weekly_t2_browse_store','weekly_t2_invite_friend',
                     'weekly_t2_nex_swap','weekly_t2_ai_jobs_50','weekly_t2_genesis_browse')
                    OR (b.producer='SYSTEM' AND b.user_id_field='user_id' AND b.event_type=CASE m.mission_code
                        WHEN 'weekly_t2_browse_store' THEN 'H3_STOREFRONT_THREE_PRODUCTS_VIEWED'
                        WHEN 'weekly_t2_invite_friend' THEN 'H3_REFERRAL_REGISTERED'
                        WHEN 'weekly_t2_nex_swap' THEN 'H3_EXCHANGE_COMPLETED'
                        WHEN 'weekly_t2_ai_jobs_50' THEN 'H3_COMPUTE_COMPLETED_50'
                        WHEN 'weekly_t2_genesis_browse' THEN 'H3_GENESIS_SECONDARY_MARKET_VIEWED' END))
             ORDER BY m.mission_type,m.id,b.id FOR UPDATE
            """)
    List<Definition> lockDefinitions();

    @Insert("""
            INSERT INTO nx_growth_weekly_definition
              (instance_key,eligible_from,eligible_until,quest_bonus_multiplier,rhythm_month,required_task_count,localized_content)
            VALUES(#{h.instanceKey},#{h.eligibleFrom},#{h.eligibleUntil},#{h.questBonusMultiplier},
                   #{h.rhythmMonth},#{h.requiredTaskCount},
                   (SELECT config_value FROM nx_config_item WHERE config_key='growth.content.localized'
                     AND status=1 AND is_deleted=0 LIMIT 1))
            """)
    int insertHeader(@Param("h") Header header);

    @Insert("""
            INSERT INTO nx_growth_weekly_definition_item
              (instance_key,source_mission_id,quest_code,name,layer,category,action_route,reward_points,ordinal)
            VALUES(#{key},#{d.missionId},#{d.questCode},#{d.name},#{d.layer},#{d.category},
                   #{d.actionRoute},#{d.rewardPoints},#{ordinal})
            """)
    int insertItem(@Param("key") String key,@Param("d") Definition definition,@Param("ordinal") int ordinal);

    @Insert("""
            INSERT INTO nx_growth_weekly_definition_binding
              (instance_key,source_mission_id,source_binding_id,binding_code,producer,event_type,user_id_field)
            VALUES(#{key},#{d.missionId},#{d.bindingId},#{d.bindingCode},#{d.producer},#{d.eventType},#{d.userIdField})
            """)
    int insertBinding(@Param("key") String key,@Param("d") Definition definition);

    @Select("""
            SELECT i.quest_code questCode,
                   COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(
                       CASE WHEN JSON_VALID(h.localized_content) THEN h.localized_content ELSE '{}' END,
                       CONCAT('$.mission."',i.quest_code,'"."',#{locale},'".name'))),''),i.name) name,
                   i.layer,i.reward_points rewardNex,LOWER(i.category) category,i.action_route actionRoute,
                   h.instance_key instanceKey,
                   DATE_FORMAT(h.eligible_from,'%Y-%m-%dT%H:%i:%s+08:00') eligibleFrom,
                   DATE_FORMAT(h.eligible_until,'%Y-%m-%dT%H:%i:%s+08:00') eligibleUntil,
                   1 eligible,CASE UPPER(COALESCE(um.mission_status,'PENDING'))
                     WHEN 'COMPLETED' THEN 'COMPLETED' WHEN 'CLAIMABLE' THEN 'CLAIMABLE'
                     WHEN 'CLAIMED' THEN 'CLAIMED' ELSE 'PENDING' END status,h.created_at updatedAt
              FROM nx_growth_weekly_definition h
              JOIN nx_growth_weekly_definition_item i ON i.instance_key=h.instance_key
              LEFT JOIN nx_user_mission um ON um.user_id=#{userId} AND um.mission_id=i.source_mission_id
                AND um.instance_key=h.instance_key AND um.is_deleted=0
             WHERE h.instance_key=#{key}
             ORDER BY i.ordinal,i.source_mission_id FOR SHARE
            """)
    List<Map<String,Object>> state(@Param("key") String key,@Param("userId") Long userId,@Param("locale") String locale);

    @Select("""
            SELECT b.binding_code bindingCode,b.producer,b.event_type eventType,
                   i.quest_code questCode,b.user_id_field userIdField,i.layer missionType
              FROM nx_growth_weekly_definition_binding b
              JOIN nx_growth_weekly_definition_item i
                ON i.instance_key=b.instance_key AND i.source_mission_id=b.source_mission_id
             WHERE b.instance_key=#{key} AND b.event_type=#{eventType}
             ORDER BY b.source_binding_id FOR SHARE
            """)
    List<CanonicalQuestEventBinding> bindings(@Param("key") String key,@Param("eventType") String eventType);

    @Select("""
            SELECT COUNT(*) FROM nx_growth_quest_event_binding b JOIN nx_mission m ON m.mission_code=b.quest_code
             WHERE m.mission_type IN ('WEEKLY_T1','WEEKLY_T2') AND m.status=1 AND m.is_deleted=0
               AND b.event_type=#{eventType} AND b.status=1 AND b.is_deleted=0
            """)
    int liveWeeklyBindingCount(@Param("eventType") String eventType);

    @Select("SELECT COUNT(*) FROM nx_mission WHERE mission_code=#{code} AND mission_type IN ('WEEKLY_T1','WEEKLY_T2')")
    int weeklyCode(@Param("code") String code);

    @Select("""
            SELECT i.source_mission_id missionId,i.quest_code questCode,i.layer,h.instance_key instanceKey
              FROM nx_growth_weekly_definition h JOIN nx_growth_weekly_definition_item i ON i.instance_key=h.instance_key
             WHERE h.instance_key=#{key} AND i.quest_code=#{code}
               AND #{occurredAt}>=h.eligible_from AND #{occurredAt}<h.eligible_until
             LIMIT 1 FOR SHARE
            """)
    MissionDefinition mission(@Param("key") String key,@Param("code") String code,@Param("occurredAt") LocalDateTime occurredAt);

    @Select("""
            SELECT i.source_mission_id missionId,i.quest_code questCode,i.layer,i.reward_points rewardNex
              FROM nx_growth_weekly_definition_item i
              JOIN nx_user_mission um ON um.mission_id=i.source_mission_id AND um.instance_key=i.instance_key
               AND um.user_id=#{userId} AND um.is_deleted=0
             WHERE i.instance_key=#{key} AND i.quest_code=#{code}
               AND UPPER(um.mission_status) IN ('COMPLETED','CLAIMABLE') LIMIT 1 FOR UPDATE
            """)
    Reward lockReward(@Param("key") String key,@Param("code") String code,@Param("userId") Long userId);

    // History can include older weeks; this selector is never a claim authorization.
    @Select("""
            SELECT DISTINCT m.mission_code FROM nx_user_mission um
              JOIN nx_mission m ON m.id=um.mission_id AND m.mission_type IN ('WEEKLY_T1','WEEKLY_T2')
              JOIN nx_growth_weekly_definition h ON h.instance_key=#{key}
             WHERE um.user_id=#{userId} AND um.is_deleted=0 AND um.created_at<=h.created_at
               AND UPPER(um.mission_status) IN ('COMPLETED','CLAIMABLE','CLAIMED')
               AND NOT EXISTS (SELECT 1 FROM nx_growth_weekly_definition_item i
                               WHERE i.instance_key=h.instance_key AND i.source_mission_id=um.mission_id)
             FOR SHARE
            """)
    List<String> legacyProgressCodes(@Param("key") String key,@Param("userId") Long userId);

    @Select("""
            SELECT um.mission_id FROM nx_user_mission um
              JOIN nx_mission m ON m.id=um.mission_id AND m.mission_code=#{code}
                AND m.mission_type IN ('WEEKLY_T1','WEEKLY_T2') AND m.is_deleted=0
              JOIN nx_growth_weekly_definition h ON h.instance_key=#{key}
             WHERE um.user_id=#{userId} AND um.instance_key=h.instance_key
               AND um.is_deleted=0 AND um.created_at<=h.created_at
               AND UPPER(um.mission_status) IN ('COMPLETED','CLAIMABLE')
               AND NOT EXISTS (SELECT 1 FROM nx_growth_weekly_definition_item i
                               WHERE i.instance_key=h.instance_key AND i.source_mission_id=um.mission_id)
             LIMIT 1 FOR SHARE
            """)
    Long legacyClaimMission(@Param("key") String key,@Param("userId") Long userId,@Param("code") String code);

    record Header(String instanceKey,LocalDateTime eligibleFrom,LocalDateTime eligibleUntil,
                  BigDecimal questBonusMultiplier,int rhythmMonth,int requiredTaskCount) {}
    record Definition(Long missionId,String questCode,String name,String layer,String category,String actionRoute,
                      int rewardPoints,Long bindingId,String bindingCode,String producer,String eventType,String userIdField) {}
    record Reward(Long missionId,String questCode,String layer,BigDecimal rewardNex) {}
}
