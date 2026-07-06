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
    @Update("""
            CREATE TABLE IF NOT EXISTS nx_growth_trial_policy (
              id BIGINT AUTO_INCREMENT PRIMARY KEY,
              policy_key VARCHAR(64) NOT NULL,
              policy_name VARCHAR(128) NOT NULL,
              description VARCHAR(512) NULL,
              current_value VARCHAR(128) NOT NULL DEFAULT '',
              value_type VARCHAR(32) NOT NULL DEFAULT 'STRING',
              hot TINYINT NOT NULL DEFAULT 0,
              section VARCHAR(32) NOT NULL DEFAULT 'live',
              server_only TINYINT NOT NULL DEFAULT 0,
              sort_order INT NOT NULL DEFAULT 100,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_growth_trial_policy_key (policy_key),
              KEY idx_growth_trial_policy_order (is_deleted, sort_order, id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createGrowthTrialPolicyTable();

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_growth_trial_gate (
              id BIGINT AUTO_INCREMENT PRIMARY KEY,
              gate_key VARCHAR(64) NOT NULL,
              gate_name VARCHAR(128) NOT NULL,
              note VARCHAR(512) NULL,
              sort_order INT NOT NULL DEFAULT 100,
              status TINYINT NOT NULL DEFAULT 1,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_growth_trial_gate_key (gate_key),
              KEY idx_growth_trial_gate_order (status, sort_order, id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createGrowthTrialGateTable();

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_growth_checkin_rule (
              id BIGINT AUTO_INCREMENT PRIMARY KEY,
              rule_key VARCHAR(64) NOT NULL,
              rule_name VARCHAR(128) NOT NULL,
              description VARCHAR(512) NULL,
              current_value VARCHAR(128) NOT NULL DEFAULT '',
              value_type VARCHAR(32) NOT NULL DEFAULT 'STRING',
              hot TINYINT NOT NULL DEFAULT 0,
              sort_order INT NOT NULL DEFAULT 100,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_growth_checkin_rule_key (rule_key),
              KEY idx_growth_checkin_rule_order (is_deleted, sort_order, id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createGrowthCheckinRuleTable();

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_growth_wheel_tier (
              id BIGINT AUTO_INCREMENT PRIMARY KEY,
              tier_name VARCHAR(128) NOT NULL,
              reward_name VARCHAR(128) NOT NULL,
              probability_pct DECIMAL(8,4) NOT NULL DEFAULT 0,
              real_outflow TINYINT NOT NULL DEFAULT 0,
              reward_kind VARCHAR(64) NOT NULL DEFAULT '',
              sort_order INT NOT NULL DEFAULT 100,
              status TINYINT NOT NULL DEFAULT 1,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_growth_wheel_tier_name (tier_name),
              KEY idx_growth_wheel_tier_order (status, sort_order, id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createGrowthWheelTierTable();

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_growth_wheel_guard (
              id BIGINT AUTO_INCREMENT PRIMARY KEY,
              guard_key VARCHAR(64) NOT NULL,
              guard_label VARCHAR(128) NOT NULL,
              guard_value VARCHAR(255) NOT NULL DEFAULT '',
              note VARCHAR(512) NULL,
              sort_order INT NOT NULL DEFAULT 100,
              status TINYINT NOT NULL DEFAULT 1,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_growth_wheel_guard_key (guard_key),
              KEY idx_growth_wheel_guard_order (status, sort_order, id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createGrowthWheelGuardTable();

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_growth_promo_banner (
              id BIGINT AUTO_INCREMENT PRIMARY KEY,
              banner_code VARCHAR(64) NOT NULL,
              base_reward VARCHAR(64) NULL,
              multiplier VARCHAR(32) NULL,
              countdown_days INT NULL,
              countdown_hours INT NULL,
              target_device VARCHAR(128) NULL,
              target_daily VARCHAR(64) NULL,
              status VARCHAR(32) NOT NULL DEFAULT 'active',
              sort_order INT NOT NULL DEFAULT 100,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_growth_promo_banner_code (banner_code),
              KEY idx_growth_promo_banner_status (status, sort_order)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createGrowthPromoBannerTable();

    @Select("""
            SELECT COUNT(1)
              FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'nx_event_quest'
               AND COLUMN_NAME = 'geo_scope'
            """)
    int eventQuestGeoScopeColumnCount();

    @Update("ALTER TABLE nx_event_quest ADD COLUMN geo_scope VARCHAR(64) NOT NULL DEFAULT '' AFTER description")
    void addEventQuestGeoScopeColumn();

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
                   COALESCE(geo_scope, '') AS geo,
                   (
                     SELECT COUNT(1)
                       FROM nx_user_event_quest u
                      WHERE u.is_deleted = 0
                        AND u.quest_code = q.quest_code
                   ) AS joinCount,
                   (
                     SELECT COUNT(1)
                       FROM nx_user_event_quest u
                      WHERE u.is_deleted = 0
                        AND u.quest_code = q.quest_code
                        AND (u.progress_value >= q.target_value OR UPPER(u.claim_status) IN ('COMPLETED', 'CLAIMABLE', 'CLAIMED'))
                   ) AS doneCount,
                   (
                     SELECT COUNT(1)
                       FROM nx_user_event_quest u
                      WHERE u.is_deleted = 0
                        AND u.quest_code = q.quest_code
                        AND UPPER(u.claim_status) = 'CLAIMED'
                   ) AS claimCount,
                   sort_order AS sortOrder
              FROM nx_event_quest q
             WHERE q.is_deleted = 0
             ORDER BY sort_order ASC, id ASC
            """)
    List<Map<String, Object>> listEvents();

    @Select("""
            SELECT policy_key AS `key`,
                   policy_name AS name,
                   COALESCE(description, '') AS sub,
                   current_value AS cur,
                   hot,
                   section,
                   server_only AS serverOnly
              FROM nx_growth_trial_policy
             WHERE is_deleted = 0
             ORDER BY sort_order ASC, id ASC
            """)
    List<Map<String, Object>> listTrialPolicies();

    @Select("""
            SELECT current_value
              FROM nx_growth_trial_policy
             WHERE policy_key = #{policyKey}
               AND is_deleted = 0
             LIMIT 1
            """)
    String trialPolicyValue(@Param("policyKey") String policyKey);

    @Insert("""
            INSERT INTO nx_growth_trial_policy (
                policy_key, policy_name, description, current_value, value_type,
                hot, section, server_only, sort_order, created_at, updated_at, is_deleted
            ) VALUES (
                #{policyKey}, #{policyKey}, '', #{value}, #{valueType},
                #{hot}, #{section}, #{serverOnly}, #{sortOrder}, NOW(), NOW(), 0
            )
            ON DUPLICATE KEY UPDATE
                current_value = VALUES(current_value),
                value_type = VALUES(value_type),
                server_only = VALUES(server_only),
                updated_at = NOW(),
                is_deleted = 0
            """)
    int upsertTrialPolicyValue(@Param("policyKey") String policyKey,
                               @Param("value") String value,
                               @Param("valueType") String valueType,
                               @Param("hot") boolean hot,
                               @Param("section") String section,
                               @Param("serverOnly") boolean serverOnly,
                               @Param("sortOrder") int sortOrder);

    @Select("""
            SELECT CONCAT('trial-', claim_no) AS sid,
                   LOWER(status) AS state,
                   CONCAT('$', ROUND(daily_usdt * GREATEST(1, duration_days), 2), ' + ', ROUND(daily_nex * GREATEST(1, duration_days), 2), ' NEX') AS shadow,
                   claim_no AS cardTok
              FROM nx_trial_claim
             WHERE is_deleted = 0
             ORDER BY claimed_at DESC, id DESC
             LIMIT #{limit}
            """)
    List<Map<String, Object>> trialSessions(@Param("limit") int limit);

    @Select("""
            SELECT CONCAT('trial-', claim_no) AS sid,
                   LOWER(status) AS state,
                   claim_no AS claimNo
              FROM nx_trial_claim
             WHERE is_deleted = 0
               AND (CONCAT('trial-', claim_no) = #{sessionId} OR claim_no = #{sessionId})
             LIMIT 1
            """)
    Map<String, Object> trialSession(@Param("sessionId") String sessionId);

    @Update("""
            UPDATE nx_trial_claim
               SET status = #{status},
                   updated_at = NOW()
             WHERE is_deleted = 0
               AND (CONCAT('trial-', claim_no) = #{sessionId} OR claim_no = #{sessionId})
            """)
    int updateTrialSessionStatus(@Param("sessionId") String sessionId,
                                 @Param("status") String status);

    @Select("""
            SELECT COUNT(1) AS activeSessions,
                   SUM(CASE WHEN UPPER(status) IN ('CLAIMED', 'ACTIVE') THEN 1 ELSE 0 END) AS inTrial,
                   SUM(CASE WHEN UPPER(status) = 'GRACE' THEN 1 ELSE 0 END) AS inGrace,
                   SUM(CASE WHEN UPPER(status) = 'EXTENDED' THEN 1 ELSE 0 END) AS inExtended
              FROM nx_trial_claim
             WHERE is_deleted = 0
            """)
    Map<String, Object> trialStats();

    @Select("""
            SELECT gate_name AS gate,
                   COALESCE(note, '') AS note
              FROM nx_growth_trial_gate
             WHERE is_deleted = 0
               AND status = 1
             ORDER BY sort_order ASC, id ASC
            """)
    List<Map<String, Object>> trialGates();

    @Select("""
            SELECT id - 1 AS id,
                   mission_name AS task,
                   mission_name AS cond,
                   CONCAT(reward_points, ' NEX') AS reward,
                   CASE WHEN status = 1 THEN 'active' ELSE 'inactive' END AS status,
                   'event' AS completionType,
                   mission_code AS completionEvent,
                   '' AS href
              FROM nx_mission
             WHERE is_deleted = 0
               AND mission_type = #{missionType}
             ORDER BY id ASC
            """)
    List<Map<String, Object>> missionRows(@Param("missionType") String missionType);

    @Select("""
            SELECT challenge_code AS id,
                   COALESCE(theme, challenge_name) AS theme,
                   CONCAT(months_from, '-', months_to, ' 月') AS age,
                   reward_name AS reward,
                   description AS goals
              FROM nx_monthly_challenge
             WHERE is_deleted = 0
               AND status = 1
             ORDER BY sort_order ASC, id ASC
            """)
    List<Map<String, Object>> monthlyMissions();

    @Select("""
            SELECT mission_type AS label,
                   CONCAT(COUNT(1), ' 个任务定义来自 nx_mission') AS note
              FROM nx_mission
             WHERE is_deleted = 0
             GROUP BY mission_type
             ORDER BY mission_type ASC
            """)
    List<Map<String, Object>> taskMonitor();

    @Select("""
            SELECT id AS taskId,
                   mission_code AS taskKey,
                   mission_code AS serverEvent,
                   '' AS downstream,
                   TRUE AS b3,
                   FALSE AS retentionOnly,
                   '' AS day7,
                   '' AS bi,
                   (
                     SELECT COUNT(1)
                       FROM nx_user_mission u
                      WHERE u.is_deleted = 0
                        AND u.mission_id = m.id
                        AND u.created_at >= DATE_SUB(NOW(), INTERVAL 1 DAY)
                   ) AS sample24h,
                   (
                     SELECT CASE
                       WHEN COUNT(1) = 0 THEN NULL
                       ELSE CONCAT(ROUND(SUM(CASE
                         WHEN UPPER(u.mission_status) IN ('FAILED', 'ANOMALY', 'REJECTED') THEN 1
                         ELSE 0
                       END) * 100 / COUNT(1), 1), '%')
                     END
                       FROM nx_user_mission u
                      WHERE u.is_deleted = 0
                        AND u.mission_id = m.id
                        AND u.created_at >= DATE_SUB(NOW(), INTERVAL 1 DAY)
                   ) AS anomalyPct
              FROM nx_mission m
             WHERE m.is_deleted = 0
             ORDER BY id ASC
            """)
    List<Map<String, Object>> taskContracts();

    @Select("""
            SELECT base_reward AS baseReward,
                   multiplier,
                   countdown_days AS countdownDays,
                   countdown_hours AS countdownHours,
                   target_device AS targetDevice,
                   target_daily AS targetDaily,
                   status
              FROM nx_growth_promo_banner
             WHERE is_deleted = 0
               AND status IN ('active', 'ACTIVE')
             ORDER BY sort_order ASC, id ASC
             LIMIT 1
            """)
    Map<String, Object> promoBanner();

    @Select("""
            SELECT tier_name AS tier,
                   reward_name AS reward,
                   probability_pct AS prob,
                   real_outflow AS `real`,
                   reward_kind AS kind
              FROM nx_growth_wheel_tier
             WHERE is_deleted = 0
               AND status = 1
             ORDER BY sort_order ASC, id ASC
            """)
    List<Map<String, Object>> wheelTiers();

    @Select("""
            SELECT guard_key AS `key`,
                   guard_label AS label,
                   guard_value AS value,
                   COALESCE(note, '') AS note
              FROM nx_growth_wheel_guard
             WHERE is_deleted = 0
               AND status = 1
             ORDER BY sort_order ASC, id ASC
            """)
    List<Map<String, Object>> wheelGuards();

    @Select("""
            SELECT rule_key AS `key`,
                   rule_name AS name,
                   COALESCE(description, '') AS sub,
                   current_value AS cur,
                   hot
              FROM nx_growth_checkin_rule
             WHERE is_deleted = 0
             ORDER BY sort_order ASC, id ASC
            """)
    List<Map<String, Object>> checkInRules();

    @Select("""
            SELECT
              (
                SELECT COUNT(DISTINCT c.user_id)
                  FROM nx_daily_check_in c
                 WHERE c.is_deleted = 0
                   AND c.check_in_date = CURDATE()
              ) AS todaySign,
              (
                SELECT CASE
                  WHEN COUNT(DISTINCT s.user_id) = 0 THEN '0%'
                  ELSE CONCAT(ROUND((
                    SELECT COUNT(DISTINCT c2.user_id)
                      FROM nx_daily_check_in c2
                     WHERE c2.is_deleted = 0
                       AND c2.check_in_date = CURDATE()
                  ) * 100 / COUNT(DISTINCT s.user_id), 1), '%')
                END
                  FROM nx_user_streak s
                 WHERE s.is_deleted = 0
              ) AS signRate,
              (
                SELECT CASE
                  WHEN COUNT(1) = 0 THEN '0%'
                  ELSE CONCAT(ROUND(SUM(CASE WHEN c.reward_multiplier >= 1.5 THEN 1 ELSE 0 END) * 100 / COUNT(1), 1), '%')
                END
                  FROM nx_daily_check_in c
                 WHERE c.is_deleted = 0
                   AND c.check_in_date = CURDATE()
              ) AS lucky15Actual,
              (
                SELECT CASE
                  WHEN COUNT(1) = 0 THEN '0%'
                  ELSE CONCAT(ROUND(SUM(CASE WHEN c.reward_multiplier >= 2 THEN 1 ELSE 0 END) * 100 / COUNT(1), 1), '%')
                END
                  FROM nx_daily_check_in c
                 WHERE c.is_deleted = 0
                   AND c.check_in_date = CURDATE()
              ) AS lucky2Actual,
              (
                SELECT COUNT(1)
                  FROM nx_user_streak_power_up p
                 WHERE p.is_deleted = 0
                   AND p.activated_at >= DATE_SUB(NOW(), INTERVAL 7 DAY)
                   AND (
                     LOWER(p.power_up_code) LIKE '%revive%'
                     OR LOWER(p.power_up_status) = 'revived'
                   )
              ) AS weekRevive,
              (
                SELECT COUNT(1)
                  FROM nx_earning_milestone e
                 WHERE e.is_deleted = 0
                   AND e.achieved_at >= DATE_SUB(NOW(), INTERVAL 7 DAY)
              ) AS weekMsTrigger,
              (
                SELECT CONCAT(COALESCE(SUM(e.reward_nex), 0), ' NEX')
                  FROM nx_earning_milestone e
                 WHERE e.is_deleted = 0
                   AND e.achieved_at >= DATE_SUB(NOW(), INTERVAL 7 DAY)
              ) AS weekMsNex
            """)
    Map<String, Object> checkInStats();

    @Select("""
            SELECT current_value
              FROM nx_growth_checkin_rule
             WHERE rule_key = #{ruleKey}
               AND is_deleted = 0
             LIMIT 1
            """)
    String checkInRuleValue(@Param("ruleKey") String ruleKey);

    @Insert("""
            INSERT INTO nx_growth_checkin_rule (
                rule_key, rule_name, description, current_value, value_type, hot,
                sort_order, created_at, updated_at, is_deleted
            ) VALUES (
                #{ruleKey}, #{ruleKey}, '', #{value}, #{valueType}, #{hot},
                #{sortOrder}, NOW(), NOW(), 0
            )
            ON DUPLICATE KEY UPDATE
                current_value = VALUES(current_value),
                value_type = VALUES(value_type),
                hot = VALUES(hot),
                updated_at = NOW(),
                is_deleted = 0
            """)
    int upsertCheckInRuleValue(@Param("ruleKey") String ruleKey,
                               @Param("value") String value,
                               @Param("valueType") String valueType,
                               @Param("hot") boolean hot,
                               @Param("sortOrder") int sortOrder);

    @Select("""
            SELECT id - 1 AS id,
                   CONCAT(milestone_day, ' 天') AS day,
                   reward_name AS reward,
                   LOWER(reward_type) AS kind
              FROM nx_streak_milestone
             WHERE is_deleted = 0
               AND status = 1
             ORDER BY sort_order ASC, milestone_day ASC
            """)
    List<Map<String, Object>> streakMilestones();

    @Update("""
            UPDATE nx_streak_milestone
               SET reward_name = #{rewardName},
                   reward_type = #{rewardType},
                   reward_amount = #{rewardAmount},
                   updated_at = NOW()
             WHERE id = #{milestoneId} + 1
               AND is_deleted = 0
            """)
    int updateStreakMilestoneReward(
            @Param("milestoneId") int milestoneId,
            @Param("rewardName") String rewardName,
            @Param("rewardType") String rewardType,
            @Param("rewardAmount") BigDecimal rewardAmount);

    @Select("""
            SELECT CONCAT(current_streak, ' 天') AS day,
                   COUNT(1) AS count,
                   LEAST(100, GREATEST(4, current_streak * 4)) AS height
              FROM nx_user_streak
             WHERE is_deleted = 0
             GROUP BY current_streak
             ORDER BY current_streak ASC
             LIMIT 30
            """)
    List<Map<String, Object>> streakDistribution();

    @Select("""
            SELECT id - 1 AS id,
                   unlock_streak_days AS day,
                   power_up_name AS label,
                   COALESCE(effect_value, target_path) AS sub,
                   target_path AS downstream
              FROM nx_streak_power_up
             WHERE is_deleted = 0
               AND status = 1
             ORDER BY sort_order ASC, unlock_streak_days ASC
            """)
    List<Map<String, Object>> powerUps();

    @Update("""
            UPDATE nx_streak_power_up
               SET unlock_streak_days = #{day},
                   updated_at = NOW()
             WHERE id = #{powerUpId} + 1
               AND is_deleted = 0
            """)
    int updatePowerUpDay(@Param("powerUpId") int powerUpId, @Param("day") int day);

    @Update("""
            UPDATE nx_streak_power_up
               SET effect_value = #{note},
                   updated_at = NOW()
             WHERE id = #{powerUpId} + 1
               AND is_deleted = 0
            """)
    int updatePowerUpNote(@Param("powerUpId") int powerUpId, @Param("note") String note);

    @Select("""
            SELECT r.id - 1 AS id,
                   r.milestone_id AS `key`,
                   r.threshold_usdt AS threshold,
                   r.reward_nex AS nex,
                   (
                     SELECT COUNT(1)
                       FROM nx_earning_milestone e
                      WHERE e.is_deleted = 0
                        AND e.milestone_id = r.milestone_id
                        AND e.achieved_at >= DATE_SUB(NOW(), INTERVAL 7 DAY)
                   ) AS weekTrigger
              FROM nx_earning_milestone_rule r
             WHERE r.is_deleted = 0
               AND r.status = 1
             ORDER BY r.sort_order ASC, r.threshold_usdt ASC
            """)
    List<Map<String, Object>> earnMilestones();

    @Update("""
            UPDATE nx_earning_milestone_rule
               SET threshold_usdt = #{thresholdUsd},
                   reward_nex = #{rewardNex},
                   updated_at = NOW()
             WHERE milestone_id = #{milestoneId}
               AND is_deleted = 0
            """)
    int updateEarnMilestoneRule(
            @Param("milestoneId") String milestoneId,
            @Param("thresholdUsd") BigDecimal thresholdUsd,
            @Param("rewardNex") BigDecimal rewardNex);

    @Select("""
            SELECT COUNT(1)
              FROM nx_event_quest
             WHERE quest_code = #{eventId}
               AND is_deleted = 0
            """)
    long countById(@Param("eventId") String eventId);

    @Insert("""
            INSERT INTO nx_event_quest (
                quest_code, quest_name, description, geo_scope, target_type, target_value,
                reward_type, reward_amount, reward_name, badge_achievement_code,
                sort_order, status, created_at, updated_at, is_deleted
            ) VALUES (
                #{eventId}, #{name}, #{description}, '', #{kind}, #{targetValue},
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

    // ===== H3 业务实体创建(4 张业务表原本只读,补 @Insert + 唯一性预检) =====

    @Insert("""
            INSERT INTO nx_mission (
                mission_code, mission_name, mission_type, reward_points, status, created_at, updated_at, is_deleted
            ) VALUES (
                #{missionCode}, #{missionName}, #{missionType}, #{rewardPoints}, #{status}, #{now}, #{now}, 0
            )
            """)
    int insertMission(
            @Param("missionCode") String missionCode,
            @Param("missionName") String missionName,
            @Param("missionType") String missionType,
            @Param("rewardPoints") int rewardPoints,
            @Param("status") int status,
            @Param("now") LocalDateTime now);

    @Select("""
            SELECT COUNT(1) FROM nx_mission WHERE mission_code = #{missionCode} AND is_deleted = 0
            """)
    long countByMissionCode(@Param("missionCode") String missionCode);

    @Insert("""
            INSERT INTO nx_monthly_challenge (
                challenge_code, challenge_name, description, theme, months_from, months_to,
                target_type, target_value, reward_type, reward_amount, reward_name, badge_achievement_code,
                sort_order, status, created_at, updated_at, is_deleted
            ) VALUES (
                #{challengeCode}, #{challengeName}, #{description}, #{theme}, #{monthsFrom}, #{monthsTo},
                #{targetType}, #{targetValue}, #{rewardType}, #{rewardAmount}, #{rewardName}, #{badgeAchievementCode},
                #{sortOrder}, #{status}, #{now}, #{now}, 0
            )
            """)
    int insertMonthlyChallenge(
            @Param("challengeCode") String challengeCode,
            @Param("challengeName") String challengeName,
            @Param("description") String description,
            @Param("theme") String theme,
            @Param("monthsFrom") int monthsFrom,
            @Param("monthsTo") int monthsTo,
            @Param("targetType") String targetType,
            @Param("targetValue") int targetValue,
            @Param("rewardType") String rewardType,
            @Param("rewardAmount") BigDecimal rewardAmount,
            @Param("rewardName") String rewardName,
            @Param("badgeAchievementCode") String badgeAchievementCode,
            @Param("sortOrder") int sortOrder,
            @Param("status") int status,
            @Param("now") LocalDateTime now);

    @Select("""
            SELECT COUNT(1) FROM nx_monthly_challenge WHERE challenge_code = #{challengeCode} AND is_deleted = 0
            """)
    long countByChallengeCode(@Param("challengeCode") String challengeCode);

    @Insert("""
            INSERT INTO nx_growth_wheel_tier (
                tier_name, reward_name, probability_pct, real_outflow, reward_kind, sort_order, status, created_at, updated_at, is_deleted
            ) VALUES (
                #{tierName}, #{rewardName}, #{probabilityPct}, #{realOutflow}, #{rewardKind}, #{sortOrder}, #{status}, #{now}, #{now}, 0
            )
            """)
    int insertWheelTier(
            @Param("tierName") String tierName,
            @Param("rewardName") String rewardName,
            @Param("probabilityPct") BigDecimal probabilityPct,
            @Param("realOutflow") int realOutflow,
            @Param("rewardKind") String rewardKind,
            @Param("sortOrder") int sortOrder,
            @Param("status") int status,
            @Param("now") LocalDateTime now);

    @Select("""
            SELECT COUNT(1) FROM nx_growth_wheel_tier WHERE tier_name = #{tierName} AND is_deleted = 0
            """)
    long countByTierName(@Param("tierName") String tierName);

    @Insert("""
            INSERT INTO nx_growth_wheel_guard (
                guard_key, guard_label, guard_value, note, sort_order, status, created_at, updated_at, is_deleted
            ) VALUES (
                #{guardKey}, #{guardLabel}, #{guardValue}, #{note}, #{sortOrder}, #{status}, #{now}, #{now}, 0
            )
            """)
    int insertWheelGuard(
            @Param("guardKey") String guardKey,
            @Param("guardLabel") String guardLabel,
            @Param("guardValue") String guardValue,
            @Param("note") String note,
            @Param("sortOrder") int sortOrder,
            @Param("status") int status,
            @Param("now") LocalDateTime now);

    @Select("""
            SELECT COUNT(1) FROM nx_growth_wheel_guard WHERE guard_key = #{guardKey} AND is_deleted = 0
            """)
    long countByGuardKey(@Param("guardKey") String guardKey);

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
