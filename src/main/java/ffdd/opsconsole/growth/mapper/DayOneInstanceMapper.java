package ffdd.opsconsole.growth.mapper;

import ffdd.opsconsole.growth.facade.DayOneInstanceFacade.DayOneInstanceSnapshot;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Immutable Day-One instance snapshot storage. */
@Mapper
@SuppressWarnings("MybatisPlusBaseMapper")
public interface DayOneInstanceMapper {

    @Select("""
            SELECT id userId,created_at enteredAt
              FROM nx_user
             WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0
             LIMIT 1 FOR UPDATE
            """)
    RegisteredUser lockRegisteredUser(@Param("userId") Long userId);

    @Select("""
            SELECT id,user_id userId,instance_key instanceKey,snapshot_status snapshotStatus,
                   entered_at enteredAt,eligibility_hours eligibilityHours,full_reward_hours fullRewardHours,
                   eligible_until eligibleUntil,tri_reward triReward,
                   quest_bonus_multiplier questBonusMultiplier,rhythm_month rhythmMonth,
                   required_task_count requiredTaskCount,
                   definition_hash definitionHash
              FROM nx_growth_day_one_instance
             WHERE user_id=#{userId} AND instance_key=#{instanceKey} AND is_deleted=0
             LIMIT 1 FOR UPDATE
            """)
    DayOneInstanceSnapshot lockExistingInstance(
            @Param("userId") Long userId,
            @Param("instanceKey") String instanceKey);

    @Select("""
            SELECT config_value
              FROM nx_config_item
             WHERE config_key=#{configKey} AND status=1 AND is_deleted=0
             LIMIT 1 FOR UPDATE
            """)
    String lockConfigValue(@Param("configKey") String configKey);

    @Select("""
            SELECT id
              FROM nx_mission
             WHERE mission_type='DAY_ONE' AND status=1 AND is_deleted=0
             ORDER BY id
             FOR UPDATE
            """)
    List<Long> lockActiveDayOneDefinitionIds();

    @Select("""
            SELECT m.id sourceMissionId,b.id sourceBindingId,m.mission_code questCode,m.mission_name name,
                   m.mission_category category,m.action_route actionRoute,m.reward_points rewardPoints,
                   b.binding_code bindingCode,b.producer producer,
                   b.event_type eventType,b.user_id_field userIdField
              FROM nx_mission m
              JOIN nx_growth_quest_event_binding b
                ON b.quest_code=m.mission_code AND b.status=1 AND b.is_deleted=0
             WHERE m.mission_type='DAY_ONE' AND m.status=1 AND m.is_deleted=0
             ORDER BY m.id,b.id
             FOR UPDATE
            """)
    List<DayOneDefinitionBinding> lockActiveDayOneDefinitionBindings();

    @Insert("""
            INSERT IGNORE INTO nx_growth_day_one_instance
              (user_id,instance_key,snapshot_status,entered_at,eligibility_hours,full_reward_hours,
               eligible_until,tri_reward,quest_bonus_multiplier,rhythm_month,required_task_count,definition_hash,snapshot_source,
               created_at,updated_at,is_deleted)
            VALUES
              (#{snapshot.userId},#{snapshot.instanceKey},#{snapshot.snapshotStatus},#{snapshot.enteredAt},
               #{snapshot.eligibilityHours},#{snapshot.fullRewardHours},#{snapshot.eligibleUntil},
                #{snapshot.triReward},#{snapshot.questBonusMultiplier},#{snapshot.rhythmMonth},
                #{snapshot.requiredTaskCount},#{snapshot.definitionHash},
               'REGISTERED_V1',NOW(),NOW(),0)
            """)
    int insertInstanceIfAbsent(@Param("snapshot") DayOneInstanceSnapshot snapshot);

    @Insert("""
            INSERT INTO nx_growth_day_one_instance_item
              (instance_id,source_mission_id,quest_code,name,category,action_route,reward_points,
               ordinal,completion_mode,created_at,updated_at,is_deleted)
            VALUES
              (#{instanceId},#{sourceMissionId},#{questCode},#{name},#{category},#{actionRoute},
               #{rewardPoints},#{ordinal},'CANONICAL_EVENT',NOW(),NOW(),0)
            """)
    int insertItem(
            @Param("instanceId") Long instanceId,
            @Param("sourceMissionId") Long sourceMissionId,
            @Param("questCode") String questCode,
            @Param("name") String name,
            @Param("category") String category,
            @Param("actionRoute") String actionRoute,
            @Param("rewardPoints") int rewardPoints,
            @Param("ordinal") int ordinal);

    @Insert("""
            INSERT INTO nx_growth_day_one_instance_binding
              (instance_id,source_mission_id,source_binding_id,binding_code,producer,event_type,
               user_id_field,rule_json,binding_hash,created_at,updated_at,is_deleted)
            VALUES
              (#{instanceId},#{sourceMissionId},#{sourceBindingId},#{bindingCode},#{producer},#{eventType},
               #{userIdField},JSON_OBJECT('bindingCode',#{bindingCode},'producer',#{producer},
               'eventType',#{eventType},'userIdField',#{userIdField}),SHA2(CONCAT(#{sourceBindingId},'|',#{bindingCode},'|',
               #{producer},'|',#{eventType},'|',#{userIdField}),256),NOW(),NOW(),0)
            """)
    int insertBinding(
            @Param("instanceId") Long instanceId,
            @Param("sourceMissionId") Long sourceMissionId,
            @Param("sourceBindingId") Long sourceBindingId,
            @Param("bindingCode") String bindingCode,
            @Param("producer") String producer,
            @Param("eventType") String eventType,
            @Param("userIdField") String userIdField);

    @Select("""
            <script>
            SELECT i.id instanceId,i.user_id userId,i.instance_key instanceKey,
                   item.source_mission_id sourceMissionId,item.quest_code questCode,
                   binding.binding_code bindingCode,binding.producer producer,binding.event_type eventType,
                   binding.user_id_field userIdField,binding.rule_json ruleJson
              FROM nx_growth_day_one_instance i
              JOIN nx_growth_day_one_instance_item item
                ON item.instance_id=i.id AND item.is_deleted=0
              JOIN nx_growth_day_one_instance_binding binding
                ON binding.instance_id=i.id AND binding.source_mission_id=item.source_mission_id
               AND binding.is_deleted=0
             WHERE i.user_id IN
               <foreach collection="userIds" item="userId" open="(" separator="," close=")">#{userId}</foreach>
               AND i.snapshot_status='SNAPSHOT' AND i.is_deleted=0
               AND binding.event_type=#{eventType}
               AND i.entered_at&lt;=#{occurredAt} AND #{occurredAt}&lt;i.eligible_until
             ORDER BY i.id,binding.id
            </script>
            """)
    List<DayOneSnapshotBinding> listInWindowSnapshotBindings(
            @Param("userIds") List<Long> userIds,
            @Param("eventType") String eventType,
            @Param("occurredAt") LocalDateTime occurredAt);

    @Select("""
            SELECT id,user_id userId,instance_key instanceKey,snapshot_status snapshotStatus,
                   entered_at enteredAt,eligibility_hours eligibilityHours,full_reward_hours fullRewardHours,
                   eligible_until eligibleUntil,tri_reward triReward,
                   quest_bonus_multiplier questBonusMultiplier,rhythm_month rhythmMonth,
                   required_task_count requiredTaskCount,
                   definition_hash definitionHash
              FROM nx_growth_day_one_instance
             WHERE user_id=#{userId} AND is_deleted=0
             ORDER BY entered_at DESC,id DESC
             LIMIT 1
            """)
    DayOneInstanceSnapshot findLatestByUserId(@Param("userId") Long userId);

    @Select("""
            SELECT instance_id instanceId,source_mission_id sourceMissionId,quest_code questCode,
                   name,category,action_route actionRoute,reward_points rewardPoints,ordinal,completion_mode completionMode
              FROM nx_growth_day_one_instance_item
             WHERE instance_id=#{instanceId} AND is_deleted=0
             ORDER BY ordinal,id
            """)
    List<DayOneInstanceItem> listItems(@Param("instanceId") Long instanceId);

    record RegisteredUser(Long userId, LocalDateTime enteredAt) {
    }

    record DayOneDefinitionBinding(
            Long sourceMissionId,
            Long sourceBindingId,
            String questCode,
            String name,
            String category,
            String actionRoute,
            int rewardPoints,
            String bindingCode,
            String producer,
            String eventType,
            String userIdField) {
    }

    record DayOneSnapshotBinding(
            Long instanceId,
            Long userId,
            String instanceKey,
            Long sourceMissionId,
            String questCode,
            String bindingCode,
            String producer,
            String eventType,
            String userIdField,
            String ruleJson) {
    }

    record DayOneInstanceItem(
            Long instanceId,
            Long sourceMissionId,
            String questCode,
            String name,
            String category,
            String actionRoute,
            int rewardPoints,
            int ordinal,
            String completionMode) {
    }
}
