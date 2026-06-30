package ffdd.opsconsole.growth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface GrowthQuestEventMapper extends BaseMapper<Object> {
    @Select("""
            SELECT quest_code AS id,
                   quest_name AS name,
                   target_type AS kind,
                   CASE status
                     WHEN 0 THEN 'upcoming'
                     WHEN 2 THEN 'ended'
                     ELSE 'ongoing'
                   END AS state,
                   reward_name AS reward,
                   reward_type AS rewardType,
                   reward_amount AS rewardAmount,
                   CASE WHEN badge_achievement_code = 'FEATURED' THEN 1 ELSE 0 END AS featured,
                   1 AS trackable,
                   COALESCE(description, '') AS `condition`,
                   '全区' AS geo,
                   sort_order AS sortOrder
              FROM nx_event_quest
             WHERE is_deleted = 0
             ORDER BY sort_order ASC, id ASC
            """)
    List<Map<String, Object>> listEvents();

    @Select("""
            SELECT COUNT(1)
              FROM nx_event_quest
             WHERE quest_code = #{eventId}
               AND is_deleted = 0
            """)
    long countById(@Param("eventId") String eventId);

    @Insert("""
            INSERT INTO nx_event_quest (
                quest_code, quest_name, description, target_type, target_value,
                reward_type, reward_amount, reward_name, badge_achievement_code,
                sort_order, status, created_at, updated_at, is_deleted
            ) VALUES (
                #{eventId}, #{name}, #{description}, #{kind}, #{targetValue},
                #{rewardType}, #{rewardAmount}, #{rewardName}, #{badgeAchievementCode},
                #{sortOrder}, #{status}, #{now}, #{now}, 0
            )
            """)
    int insertEvent(
            @Param("eventId") String eventId,
            @Param("name") String name,
            @Param("description") String description,
            @Param("kind") String kind,
            @Param("targetValue") int targetValue,
            @Param("rewardType") String rewardType,
            @Param("rewardAmount") BigDecimal rewardAmount,
            @Param("rewardName") String rewardName,
            @Param("badgeAchievementCode") String badgeAchievementCode,
            @Param("sortOrder") int sortOrder,
            @Param("status") int status,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_event_quest
               SET reward_name = #{rewardName},
                   reward_amount = #{rewardAmount},
                   updated_at = #{now}
             WHERE quest_code = #{eventId}
               AND is_deleted = 0
            """)
    int updateReward(
            @Param("eventId") String eventId,
            @Param("rewardName") String rewardName,
            @Param("rewardAmount") BigDecimal rewardAmount,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_event_quest
               SET status = #{status},
                   badge_achievement_code = CASE
                     WHEN #{status} = 2 AND badge_achievement_code = 'FEATURED' THEN NULL
                     ELSE badge_achievement_code
                   END,
                   updated_at = #{now}
             WHERE quest_code = #{eventId}
               AND is_deleted = 0
            """)
    int updateStatus(
            @Param("eventId") String eventId,
            @Param("status") int status,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_event_quest
               SET badge_achievement_code = #{badgeAchievementCode},
                   updated_at = #{now}
             WHERE quest_code = #{eventId}
               AND is_deleted = 0
            """)
    int updateFeatured(
            @Param("eventId") String eventId,
            @Param("badgeAchievementCode") String badgeAchievementCode,
            @Param("now") LocalDateTime now);
}
