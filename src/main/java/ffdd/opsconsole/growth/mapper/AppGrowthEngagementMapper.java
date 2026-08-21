package ffdd.opsconsole.growth.mapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Server-authoritative H3/H4/H5 user actions. */
@Mapper
// Statement-only command boundary spanning engagement, wallet and reward tables.
@SuppressWarnings("MybatisPlusBaseMapper")
public interface AppGrowthEngagementMapper {

    @Select("SELECT id FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0 "
            + "AND COALESCE(sandbox,0)=0 FOR UPDATE")
    Long lockActiveUser(@Param("userId") Long userId);

    @Select("SELECT id FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0 "
            + "AND COALESCE(sandbox,0)=1 FOR UPDATE")
    Long lockActiveSandboxUser(@Param("userId") Long userId);

    @Select("SELECT id FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0 "
            + "LIMIT 1")
    Long findActiveUser(@Param("userId") Long userId);

    @Select("""
            SELECT COALESCE((SELECT config_value FROM nx_config_item
                              WHERE config_key='growth.phase.current' AND status=1 AND is_deleted=0 LIMIT 1),'P1') phase,
                   GREATEST(TIMESTAMPDIFF(MONTH,u.created_at,NOW()),0) accountAgeMonths,
                   DATE_FORMAT(u.created_at,'%x-W%v') cohort
              FROM nx_user u WHERE u.id=#{userId} AND u.is_deleted=0 LIMIT 1
            """)
    Attribution attribution(@Param("userId") Long userId);

    @Select("""
            SELECT voucher_id voucherId,audience
              FROM nx_growth_voucher
             WHERE voucher_id=#{voucherId} AND is_deleted=0 AND LOWER(status)='active'
               AND (start_at=0 OR start_at<=#{nowMillis}) AND (end_at=0 OR end_at>=#{nowMillis})
               AND (claim_surfaces IS NULL OR JSON_LENGTH(claim_surfaces)=0
                    OR JSON_CONTAINS(claim_surfaces,JSON_QUOTE(#{surface})))
             LIMIT 1 FOR UPDATE
            """)
    VoucherClaimDefinition lockUserClaimableVoucher(
            @Param("voucherId") String voucherId,
            @Param("surface") String surface,
            @Param("nowMillis") long nowMillis);

    @Select("""
            SELECT m.id missionId,m.mission_code questCode,m.mission_type layer,m.reward_points rewardNex
              FROM nx_user_mission um
              JOIN nx_mission m ON m.id=um.mission_id AND m.status=1 AND m.is_deleted=0
             WHERE um.user_id=#{userId} AND m.mission_code=#{questCode}
               AND UPPER(um.mission_status) IN ('COMPLETED','CLAIMABLE') AND um.is_deleted=0
             LIMIT 1 FOR UPDATE
            """)
    QuestReward lockClaimableQuest(@Param("userId") Long userId, @Param("questCode") String questCode);

    @Select("""
            SELECT m.mission_code questCode,
                   m.mission_name name,
                   m.mission_type layer,
                   m.reward_points rewardNex,
                   CASE UPPER(COALESCE(um.mission_status, 'PENDING'))
                     WHEN 'COMPLETED' THEN 'COMPLETED'
                     WHEN 'CLAIMABLE' THEN 'CLAIMABLE'
                     WHEN 'CLAIMED' THEN 'CLAIMED'
                     ELSE 'PENDING'
                   END status,
                   m.updated_at updatedAt
              FROM nx_mission m
              LEFT JOIN nx_user_mission um
                ON um.mission_id=m.id AND um.user_id=#{userId} AND um.is_deleted=0
             WHERE m.status=1
               AND m.is_deleted=0
               AND m.mission_type IN ('DAY_ONE','WEEKLY_T1','WEEKLY_T2')
             ORDER BY FIELD(m.mission_type,'DAY_ONE','WEEKLY_T1','WEEKLY_T2'),m.id
            """)
    List<Map<String, Object>> questState(@Param("userId") Long userId);

    @Select("""
            SELECT q.quest_code eventCode,
                   LOWER(q.target_type) kind,
                   CASE
                     WHEN q.status=2 OR (q.ends_at IS NOT NULL AND q.ends_at<UTC_TIMESTAMP()) THEN 'ended'
                     WHEN q.status=0 OR (q.starts_at IS NOT NULL AND q.starts_at>UTC_TIMESTAMP()) THEN 'upcoming'
                     ELSE 'ongoing'
                   END state,
                   q.quest_name title,
                   COALESCE(q.description,'') subtitle,
                   q.reward_name rewardName,
                   UPPER(q.reward_type) rewardType,
                   q.reward_amount rewardAmount,
                   CASE WHEN q.badge_achievement_code='FEATURED' THEN 1 ELSE 0 END featured,
                   CASE WHEN LOWER(q.target_type)<>'wheel' AND q.target_value>0 THEN 1 ELSE 0 END trackable,
                   q.target_value targetValue,
                   COALESCE(u.progress_value,0) progressValue,
                   CASE
                     WHEN UPPER(COALESCE(u.claim_status,''))='CLAIMED' THEN 'CLAIMED'
                     WHEN u.id IS NOT NULL AND
                          (u.progress_value>=q.target_value
                           OR UPPER(COALESCE(u.claim_status,'')) IN ('COMPLETED','CLAIMABLE'))
                       THEN 'CLAIMABLE'
                     WHEN u.id IS NOT NULL THEN 'JOINED'
                     ELSE 'AVAILABLE'
                   END userStatus,
                   q.geo_scope geo,
                   COALESCE(q.cta_href,'') href,
                   q.starts_at startsAt,
                   q.ends_at endsAt,
                   q.updated_at updatedAt
              FROM nx_event_quest q
              LEFT JOIN nx_user_event_quest u
                ON u.quest_id=q.id AND u.user_id=#{userId} AND u.is_deleted=0
             WHERE q.is_deleted=0
             ORDER BY q.sort_order,q.id
            """)
    List<Map<String, Object>> eventState(@Param("userId") Long userId);

    @Select("""
            SELECT banner_code bannerCode,
                   base_reward baseReward,
                   multiplier,
                   countdown_days countdownDays,
                   countdown_hours countdownHours,
                   target_device targetDevice,
                   target_daily targetDaily,
                   LOWER(status) status,
                   updated_at updatedAt
              FROM nx_growth_promo_banner
             WHERE is_deleted=0
             ORDER BY sort_order,id
             LIMIT 1
            """)
    Map<String, Object> questPromoBanner();

    @Update("""
            UPDATE nx_user_mission SET mission_status='CLAIMED',updated_at=NOW()
             WHERE user_id=#{userId} AND mission_id=#{missionId}
               AND UPPER(mission_status) IN ('COMPLETED','CLAIMABLE') AND is_deleted=0
            """)
    int claimQuest(@Param("userId") Long userId, @Param("missionId") Long missionId);

    @Select("""
            SELECT id questId,quest_code eventCode,target_value targetValue,reward_type rewardType,
                   reward_amount rewardAmount,badge_achievement_code badgeCode
              FROM nx_event_quest
             WHERE quest_code=#{eventCode} AND status=1 AND is_deleted=0
               AND (starts_at IS NULL OR starts_at<=NOW()) AND (ends_at IS NULL OR ends_at>=NOW())
             LIMIT 1 FOR UPDATE
            """)
    EventReward lockOpenEvent(@Param("eventCode") String eventCode);

    @Insert("""
            INSERT IGNORE INTO nx_user_event_quest
              (user_id,quest_id,quest_code,progress_value,claim_status,reward_type,reward_amount,created_at,updated_at,is_deleted)
            VALUES
              (#{userId},#{event.questId},#{event.eventCode},0,'JOINED',#{event.rewardType},#{event.rewardAmount},NOW(),NOW(),0)
            """)
    int joinEvent(@Param("userId") Long userId, @Param("event") EventReward event);

    @Select("""
            SELECT q.id questId,q.quest_code eventCode,q.target_value targetValue,
                   u.reward_type rewardType,u.reward_amount rewardAmount,q.badge_achievement_code badgeCode
              FROM nx_user_event_quest u
              JOIN nx_event_quest q ON q.id=u.quest_id AND q.status=1 AND q.is_deleted=0
             WHERE u.user_id=#{userId} AND u.quest_code=#{eventCode} AND u.is_deleted=0
               AND (u.progress_value>=q.target_value OR UPPER(u.claim_status) IN ('COMPLETED','CLAIMABLE'))
             LIMIT 1 FOR UPDATE
            """)
    EventReward lockClaimableEvent(@Param("userId") Long userId, @Param("eventCode") String eventCode);

    @Update("""
            UPDATE nx_user_event_quest SET claim_status='CLAIMED',claimed_at=NOW(),updated_at=NOW()
             WHERE user_id=#{userId} AND quest_code=#{eventCode}
               AND UPPER(claim_status)<>'CLAIMED' AND is_deleted=0
               AND progress_value >= (SELECT target_value FROM nx_event_quest
                                        WHERE quest_code=#{eventCode} AND is_deleted=0 LIMIT 1)
            """)
    int claimEvent(@Param("userId") Long userId, @Param("eventCode") String eventCode);

    @Insert("""
            INSERT IGNORE INTO nx_user_achievement
              (user_id,achievement_id,achievement_code,achievement_status,unlocked_at,created_at,updated_at,is_deleted)
            SELECT #{userId},a.id,a.achievement_code,'UNLOCKED',NOW(),NOW(),NOW(),0
              FROM nx_achievement a
             WHERE a.achievement_code=#{badgeCode} AND a.status=1 AND a.is_deleted=0
            """)
    int unlockAchievement(@Param("userId") Long userId, @Param("badgeCode") String badgeCode);

    @Select("""
            SELECT id FROM nx_user_achievement
             WHERE user_id=#{userId} AND achievement_code=#{badgeCode} AND is_deleted=0
             LIMIT 1 FOR UPDATE
            """)
    Long lockUserAchievement(@Param("userId") Long userId, @Param("badgeCode") String badgeCode);

    @Select("SELECT nex_available FROM nx_user_wallet WHERE user_id=#{userId} AND is_deleted=0 LIMIT 1 FOR UPDATE")
    BigDecimal lockWalletNex(@Param("userId") Long userId);

    @Update("""
            UPDATE nx_user_wallet SET nex_available=nex_available+#{amount},lifetime_earned=lifetime_earned+#{amount},
                   version=version+1,updated_at=NOW()
             WHERE user_id=#{userId} AND is_deleted=0
            """)
    int creditWalletNex(@Param("userId") Long userId, @Param("amount") BigDecimal amount);

    @Insert("""
            INSERT INTO nx_wallet_ledger
              (user_id,biz_no,biz_type,asset,direction,amount,balance_after,status,remark,created_at,updated_at,is_deleted)
            VALUES
              (#{userId},#{bizNo},#{bizType},'NEX','IN',#{amount},#{balanceAfter},'POSTED',#{remark},NOW(),NOW(),0)
            """)
    int insertNexLedger(
            @Param("userId") Long userId,
            @Param("bizNo") String bizNo,
            @Param("bizType") String bizType,
            @Param("amount") BigDecimal amount,
            @Param("balanceAfter") BigDecimal balanceAfter,
            @Param("remark") String remark);

    @Select("SELECT usdt_available FROM nx_user_wallet WHERE user_id=#{userId} AND is_deleted=0 LIMIT 1 FOR UPDATE")
    BigDecimal lockWalletUsdt(@Param("userId") Long userId);

    @Update("""
            UPDATE nx_user_wallet SET usdt_available=usdt_available+#{amount},
                   lifetime_earned=lifetime_earned+#{amount},version=version+1,updated_at=NOW()
             WHERE user_id=#{userId} AND is_deleted=0
            """)
    int creditWalletUsdt(@Param("userId") Long userId, @Param("amount") BigDecimal amount);

    @Insert("""
            INSERT INTO nx_wallet_ledger
              (user_id,biz_no,biz_type,asset,direction,amount,balance_after,status,remark,created_at,updated_at,is_deleted)
            VALUES
              (#{userId},#{bizNo},#{bizType},'USDT','IN',#{amount},#{balanceAfter},'POSTED',#{remark},NOW(),NOW(),0)
            """)
    int insertUsdtLedger(
            @Param("userId") Long userId,
            @Param("bizNo") String bizNo,
            @Param("bizType") String bizType,
            @Param("amount") BigDecimal amount,
            @Param("balanceAfter") BigDecimal balanceAfter,
            @Param("remark") String remark);

    @Select("SELECT COALESCE(SUM(points),0) FROM nx_points_ledger WHERE user_id=#{userId} AND is_deleted=0")
    Integer currentPointsBalance(@Param("userId") Long userId);

    @Insert("""
            INSERT INTO nx_points_ledger
              (user_id,biz_no,biz_type,points,balance_after,created_at,updated_at,is_deleted)
            VALUES
              (#{userId},#{bizNo},#{bizType},#{points},#{balanceAfter},NOW(),NOW(),0)
            """)
    int insertPointsLedger(
            @Param("userId") Long userId,
            @Param("bizNo") String bizNo,
            @Param("bizType") String bizType,
            @Param("points") int points,
            @Param("balanceAfter") int balanceAfter);

    @Insert("""
            INSERT INTO nx_growth_spin_ticket
              (ticket_id,user_id,source_type,source_id,status,created_at,updated_at,is_deleted)
            VALUES
              (#{ticketId},#{userId},#{sourceType},#{sourceId},'AVAILABLE',NOW(),NOW(),0)
            """)
    int insertSpinTicket(
            @Param("ticketId") String ticketId,
            @Param("userId") Long userId,
            @Param("sourceType") String sourceType,
            @Param("sourceId") String sourceId);

    @Select("SELECT id FROM nx_mission WHERE mission_type='DAILY' AND status=1 AND is_deleted=0 ORDER BY id LIMIT 1")
    Long dailyMissionId();

    @Select("SELECT current_value FROM nx_growth_checkin_rule WHERE rule_key=#{ruleKey} AND is_deleted=0 LIMIT 1")
    String checkInRule(@Param("ruleKey") String ruleKey);

    @Insert("""
            INSERT IGNORE INTO nx_user_streak
              (user_id,current_streak,longest_streak,streak_savers,created_at,updated_at,is_deleted)
            VALUES (#{userId},0,0,1,NOW(),NOW(),0)
            """)
    int ensureUserStreak(@Param("userId") Long userId);

    @Select("""
            SELECT current_streak currentStreak,longest_streak longestStreak,
                   streak_savers streakSavers,last_check_in_date lastCheckInDate
              FROM nx_user_streak WHERE user_id=#{userId} AND is_deleted=0 LIMIT 1 FOR UPDATE
            """)
    StreakState lockStreak(@Param("userId") Long userId);

    @Update("""
            UPDATE nx_user_streak
               SET current_streak=#{restoredStreak},
                   streak_savers=streak_savers-1,
                   last_check_in_date=#{effectiveLastCheckInDate},
                   updated_at=NOW()
             WHERE user_id=#{userId} AND is_deleted=0 AND streak_savers>0
               AND last_check_in_date<#{effectiveLastCheckInDate}
            """)
    int useStreakSaver(
            @Param("userId") Long userId,
            @Param("restoredStreak") int restoredStreak,
            @Param("effectiveLastCheckInDate") LocalDate effectiveLastCheckInDate);

    @Insert("""
            INSERT IGNORE INTO nx_daily_check_in
              (user_id,mission_id,check_in_date,base_points,reward_multiplier,bonus_points,
               streak_bonus_points,reward_points,created_at,updated_at,is_deleted)
            VALUES
              (#{userId},#{missionId},#{checkInDate},#{basePoints},#{multiplier},#{bonusPoints},
               #{streakBonusPoints},#{rewardPoints},NOW(),NOW(),0)
            """)
    int insertCheckIn(
            @Param("userId") Long userId,
            @Param("missionId") Long missionId,
            @Param("checkInDate") LocalDate checkInDate,
            @Param("basePoints") int basePoints,
            @Param("multiplier") BigDecimal multiplier,
            @Param("bonusPoints") int bonusPoints,
            @Param("streakBonusPoints") int streakBonusPoints,
            @Param("rewardPoints") int rewardPoints);

    @Update("""
            UPDATE nx_user_streak
               SET current_streak=#{currentStreak},longest_streak=GREATEST(longest_streak,#{currentStreak}),
                   last_check_in_date=#{checkInDate},updated_at=NOW()
             WHERE user_id=#{userId} AND is_deleted=0
            """)
    int updateStreak(
            @Param("userId") Long userId,
            @Param("currentStreak") int currentStreak,
            @Param("checkInDate") LocalDate checkInDate);

    @Select("""
            SELECT m.id milestoneId,m.milestone_day milestoneDay,m.reward_type rewardType,
                   m.reward_amount rewardAmount,m.badge_achievement_code badgeCode
              FROM nx_streak_milestone m
              JOIN nx_user_streak s ON s.user_id=#{userId} AND s.is_deleted=0 AND s.current_streak>=m.milestone_day
              LEFT JOIN nx_user_streak_milestone u
                ON u.user_id=#{userId} AND u.milestone_day=m.milestone_day AND u.is_deleted=0
             WHERE m.id=#{milestoneId} AND m.status=1 AND m.is_deleted=0
               AND (u.id IS NULL OR UPPER(u.claim_status)<>'CLAIMED')
             LIMIT 1 FOR UPDATE
            """)
    DailyMilestone lockClaimableDailyMilestone(
            @Param("userId") Long userId, @Param("milestoneId") Long milestoneId);

    @Insert("""
            INSERT INTO nx_user_streak_milestone
              (user_id,milestone_id,milestone_day,reward_type,reward_amount,claim_status,claimed_at,created_at,updated_at,is_deleted)
            VALUES
              (#{userId},#{row.milestoneId},#{row.milestoneDay},#{row.rewardType},#{row.rewardAmount},'CLAIMED',NOW(),NOW(),NOW(),0)
            ON DUPLICATE KEY UPDATE
              milestone_id=VALUES(milestone_id),reward_type=VALUES(reward_type),reward_amount=VALUES(reward_amount),
              claim_status='CLAIMED',claimed_at=NOW(),updated_at=NOW(),is_deleted=0
            """)
    int claimDailyMilestone(@Param("userId") Long userId, @Param("row") DailyMilestone row);

    @Select("""
            SELECT r.milestone_id milestoneId,r.threshold_usdt thresholdUsdt,r.reward_nex rewardNex,
                   COALESCE((SELECT SUM(e.amount) FROM nx_earning_event e
                              WHERE e.user_id=#{userId} AND e.asset='USDT'
                                AND UPPER(e.status) IN ('POSTED','SUCCESS') AND e.is_deleted=0),0) lifetimeEarningsUsdt
              FROM nx_earning_milestone_rule r
             WHERE r.status=1 AND r.is_deleted=0
               AND r.threshold_usdt<=COALESCE((SELECT SUM(e.amount) FROM nx_earning_event e
                                                WHERE e.user_id=#{userId} AND e.asset='USDT'
                                                  AND UPPER(e.status) IN ('POSTED','SUCCESS') AND e.is_deleted=0),0)
               AND NOT EXISTS (SELECT 1 FROM nx_earning_milestone m
                                WHERE m.user_id=#{userId} AND m.milestone_id=r.milestone_id AND m.is_deleted=0)
             ORDER BY r.threshold_usdt,r.id FOR UPDATE
            """)
    List<EarningMilestone> lockEligibleEarningMilestones(@Param("userId") Long userId);

    @Insert("""
            INSERT INTO nx_earning_milestone
              (user_id,milestone_id,threshold_usdt,reward_nex,status,event_no,achieved_at,created_at,updated_at,is_deleted)
            VALUES
              (#{userId},#{row.milestoneId},#{row.thresholdUsdt},#{row.rewardNex},'FIRED',#{eventNo},NOW(),NOW(),NOW(),0)
            """)
    int insertEarningMilestone(
            @Param("userId") Long userId,
            @Param("row") EarningMilestone row,
            @Param("eventNo") String eventNo);

    @Select("""
            SELECT COALESCE(s.current_streak,0) currentStreak,
                   COALESCE(s.longest_streak,0) longestStreak,
                   COALESCE(s.streak_savers,0) streakSavers,
                   s.last_check_in_date lastCheckInDate,
                   EXISTS(SELECT 1 FROM nx_daily_check_in c
                           WHERE c.user_id=#{userId} AND c.check_in_date=#{today} AND c.is_deleted=0) checkedInToday
              FROM (SELECT #{userId} user_id) anchor
              LEFT JOIN nx_user_streak s ON s.user_id=anchor.user_id AND s.is_deleted=0
            """)
    Map<String, Object> pointState(@Param("userId") Long userId, @Param("today") LocalDate today);

    @Select("""
            SELECT rule_key `key`,current_value `value`
              FROM nx_growth_checkin_rule
             WHERE is_deleted=0
             ORDER BY sort_order,id
            """)
    List<Map<String, Object>> checkInRuleState();

    @Select("""
            SELECT p.id powerUpId,p.power_up_code powerUpCode,p.power_up_name name,
                   p.unlock_streak_days unlockStreakDays,p.target_path targetPath,
                   p.effect_type effectType,p.effect_value effectValue,
                   CASE WHEN UPPER(COALESCE(u.power_up_status,''))='ACTIVATED' THEN 'ACTIVATED'
                        WHEN COALESCE(s.current_streak,0)>=p.unlock_streak_days THEN 'AVAILABLE'
                        ELSE 'LOCKED' END status
              FROM nx_streak_power_up p
              LEFT JOIN nx_user_streak s ON s.user_id=#{userId} AND s.is_deleted=0
              LEFT JOIN nx_user_streak_power_up u
                ON u.user_id=#{userId} AND u.power_up_code=p.power_up_code AND u.is_deleted=0
             WHERE p.status=1 AND p.is_deleted=0
             ORDER BY p.sort_order,p.unlock_streak_days,p.id
            """)
    List<Map<String, Object>> streakPowerUpState(@Param("userId") Long userId);

    @Select("""
            SELECT CONCAT(LEFT(u.nickname,1),'***') name,u.country_code countryCode,
                   s.current_streak streak
              FROM nx_user_streak s
              JOIN nx_user u ON u.id=s.user_id AND u.status='ACTIVE' AND u.is_deleted=0
                                    AND COALESCE(u.sandbox,0)=0
             WHERE s.current_streak>0 AND s.is_deleted=0
             ORDER BY s.current_streak DESC,s.longest_streak DESC,s.user_id
             LIMIT 5
            """)
    List<Map<String, Object>> topStreakers();

    @Select("""
            SELECT p.id powerUpId,p.power_up_code powerUpCode,
                   p.badge_achievement_code badgeCode,p.duration_days durationDays
              FROM nx_streak_power_up p
              JOIN nx_user_streak s
                ON s.user_id=#{userId} AND s.is_deleted=0
               AND s.current_streak>=p.unlock_streak_days
              LEFT JOIN nx_user_streak_power_up u
                ON u.user_id=#{userId} AND u.power_up_code=p.power_up_code AND u.is_deleted=0
             WHERE p.id=#{powerUpId} AND p.status=1 AND p.is_deleted=0
               AND (u.id IS NULL OR UPPER(u.power_up_status)<>'ACTIVATED')
             LIMIT 1 FOR UPDATE
            """)
    StreakPowerUp lockActivatablePowerUp(
            @Param("userId") Long userId, @Param("powerUpId") Long powerUpId);

    @Insert("""
            INSERT IGNORE INTO nx_user_streak_power_up
              (user_id,power_up_id,power_up_code,power_up_status,unlocked_at,activated_at,
               expires_at,created_at,updated_at,is_deleted)
            VALUES
              (#{userId},#{row.powerUpId},#{row.powerUpCode},'ACTIVATED',NOW(),NOW(),
               CASE WHEN #{row.durationDays}>0 THEN DATE_ADD(NOW(),INTERVAL #{row.durationDays} DAY) ELSE NULL END,
               NOW(),NOW(),0)
            """)
    int activatePowerUp(@Param("userId") Long userId, @Param("row") StreakPowerUp row);

    @Select("""
            SELECT m.id milestoneId,m.milestone_day milestoneDay,m.reward_type rewardType,
                   m.reward_amount rewardAmount,m.badge_achievement_code badgeCode,
                   CASE WHEN UPPER(COALESCE(u.claim_status,''))='CLAIMED' THEN 'CLAIMED'
                        WHEN COALESCE(s.current_streak,0)>=m.milestone_day THEN 'CLAIMABLE'
                        ELSE 'LOCKED' END status
              FROM nx_streak_milestone m
              LEFT JOIN nx_user_streak s ON s.user_id=#{userId} AND s.is_deleted=0
              LEFT JOIN nx_user_streak_milestone u
                ON u.user_id=#{userId} AND u.milestone_day=m.milestone_day AND u.is_deleted=0
             WHERE m.status=1 AND m.is_deleted=0
             ORDER BY m.milestone_day,m.id
            """)
    List<Map<String, Object>> dailyMilestoneState(@Param("userId") Long userId);

    @Select("""
            SELECT r.milestone_id milestoneId,r.threshold_usdt thresholdUsdt,r.reward_nex rewardNex,
                   COALESCE((SELECT SUM(e.amount) FROM nx_earning_event e
                              WHERE e.user_id=#{userId} AND e.asset='USDT'
                                AND UPPER(e.status) IN ('POSTED','SUCCESS') AND e.is_deleted=0),0) lifetimeEarningsUsdt,
                   CASE WHEN m.id IS NOT NULL THEN UPPER(m.status)
                        WHEN r.threshold_usdt<=COALESCE((SELECT SUM(e.amount) FROM nx_earning_event e
                                                         WHERE e.user_id=#{userId} AND e.asset='USDT'
                                                           AND UPPER(e.status) IN ('POSTED','SUCCESS') AND e.is_deleted=0),0)
                          THEN 'CLAIMABLE' ELSE 'LOCKED' END status,m.achieved_at achievedAt
              FROM nx_earning_milestone_rule r
              LEFT JOIN nx_earning_milestone m
                ON m.user_id=#{userId} AND m.milestone_id=r.milestone_id AND m.is_deleted=0
             WHERE r.status=1 AND r.is_deleted=0
             ORDER BY r.threshold_usdt,r.id
            """)
    List<Map<String, Object>> earningMilestoneState(@Param("userId") Long userId);

    @Select("""
            SELECT v.voucher_id voucherId,v.voucher_name voucherName,v.voucher_type voucherType,
                   v.amount_usd amountUsd,v.percent_value percentValue,v.min_purchase_usd minPurchaseUsd,
                   v.max_discount_usd maxDiscountUsd,v.applicable_skus applicableSkus,
                   v.audience,v.claim_surfaces claimSurfaces,v.start_at startAt,v.end_at endAt,
                   v.popup_enabled popupEnabled,v.stack_with_trial stackWithTrial,
                   v.popup_delay_ms popupDelayMs,v.popup_cooldown_hours popupCooldownHours,
                   v.popup_max_per_session popupMaxPerSession,v.popup_cadence_enabled popupCadenceEnabled,
                   COALESCE(ps.last_seen_at,0) popupLastSeenAt,
                   COALESCE(ps.session_count,0) popupSessionCount,
                   v.stack_with_others stackWithOthers,v.splittable,
                   v.status definitionStatus,v.is_deleted definitionDeleted,
                   g.grant_id grantId,COALESCE(g.status,'UNCLAIMED') grantStatus,g.used_order_no usedOrderNo
              FROM nx_growth_voucher v
              LEFT JOIN nx_voucher_popup_state ps
                ON ps.user_id=#{userId} AND ps.voucher_id=v.voucher_id
              LEFT JOIN nx_growth_voucher_grant g
                ON g.id = (
                  SELECT gg.id
                    FROM nx_growth_voucher_grant gg
                   WHERE gg.voucher_id=v.voucher_id
                     AND gg.user_id=#{userId}
                     AND gg.is_deleted=0
                   ORDER BY CASE UPPER(gg.status)
                              WHEN 'AVAILABLE' THEN 0
                              WHEN 'USED' THEN 1
                              WHEN 'REVOKED' THEN 2
                              ELSE 3
                            END,
                            gg.granted_at,gg.id
                   LIMIT 1
                )
             WHERE g.id IS NOT NULL
                OR (v.is_deleted=0 AND LOWER(v.status)='active'
                    AND (v.start_at=0 OR v.start_at<=#{nowMillis})
                    AND (v.end_at=0 OR v.end_at>=#{nowMillis}))
             ORDER BY v.updated_at DESC,v.id DESC
            """)
    List<Map<String, Object>> voucherState(
            @Param("userId") Long userId, @Param("nowMillis") long nowMillis);

    /**
     * Sandbox reads the operator-owned voucher definitions but joins only the
     * run/user-scoped popup facts. No production grant or popup state is read.
     */
    @Select("""
            SELECT v.voucher_id voucherId,v.voucher_name voucherName,v.voucher_type voucherType,
                   v.amount_usd amountUsd,v.percent_value percentValue,v.min_purchase_usd minPurchaseUsd,
                   v.max_discount_usd maxDiscountUsd,v.applicable_skus applicableSkus,
                   v.audience,v.claim_surfaces claimSurfaces,v.start_at startAt,v.end_at endAt,
                   v.popup_enabled popupEnabled,v.stack_with_trial stackWithTrial,
                   v.popup_delay_ms popupDelayMs,v.popup_cooldown_hours popupCooldownHours,
                   v.popup_max_per_session popupMaxPerSession,v.popup_cadence_enabled popupCadenceEnabled,
                   COALESCE(ps.last_seen_at,0) popupLastSeenAt,
                   COALESCE(ps.session_count,0) popupSessionCount,
                   v.stack_with_others stackWithOthers,v.splittable,
                   v.status definitionStatus,v.is_deleted definitionDeleted,
                   COALESCE(ps.claim_status,'UNCLAIMED') grantStatus,ps.claim_id grantId,NULL usedOrderNo
              FROM nx_growth_voucher v
              LEFT JOIN nx_voucher_popup_sandbox_state ps
                ON ps.run_id=#{runId} AND ps.user_id=#{userId} AND ps.voucher_id=v.voucher_id
             WHERE v.is_deleted=0 AND LOWER(v.status)='active'
               AND (v.start_at=0 OR v.start_at<=#{nowMillis})
               AND (v.end_at=0 OR v.end_at>=#{nowMillis})
             ORDER BY v.updated_at DESC,v.id DESC
            """)
    List<Map<String, Object>> voucherStateSandbox(
            @Param("runId") String runId,
            @Param("userId") Long userId,
            @Param("nowMillis") long nowMillis);

    @Select("""
            SELECT v.voucher_id voucherId,v.audience
              FROM nx_growth_voucher v
             WHERE v.voucher_id=#{voucherId} AND v.is_deleted=0 AND LOWER(v.status)='active'
               AND (v.start_at=0 OR v.start_at<=#{nowMillis})
               AND (v.end_at=0 OR v.end_at>=#{nowMillis})
               AND (v.claim_surfaces IS NULL OR JSON_LENGTH(v.claim_surfaces)=0
                    OR JSON_CONTAINS(v.claim_surfaces,JSON_QUOTE(#{surface})))
             LIMIT 1 FOR UPDATE
            """)
    VoucherClaimDefinition lockSandboxClaimableVoucher(
            @Param("runId") String runId,
            @Param("userId") Long userId,
            @Param("voucherId") String voucherId,
            @Param("surface") String surface,
            @Param("nowMillis") long nowMillis);

    @Select("""
            SELECT claim_id grantId,claim_status grantStatus,claim_idempotency_key idempotencyKey
              FROM nx_voucher_popup_sandbox_state
             WHERE run_id=#{runId} AND user_id=#{userId} AND voucher_id=#{voucherId}
             LIMIT 1 FOR UPDATE
            """)
    SandboxVoucherClaim lockSandboxVoucherClaim(
            @Param("runId") String runId,
            @Param("userId") Long userId,
            @Param("voucherId") String voucherId);

    @Insert("""
            INSERT IGNORE INTO nx_voucher_popup_sandbox_state
              (run_id,user_id,voucher_id,last_seen_at,session_count,version,claim_status,claim_id,claim_idempotency_key)
            VALUES (#{runId},#{userId},#{voucherId},0,0,0,'AVAILABLE',#{grantId},#{idempotencyKey})
            """)
    int insertSandboxVoucherClaim(
            @Param("runId") String runId,
            @Param("userId") Long userId,
            @Param("voucherId") String voucherId,
            @Param("grantId") String grantId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("nowMillis") long nowMillis);

    @Update("""
            UPDATE nx_voucher_popup_sandbox_state
               SET claim_status='AVAILABLE',claim_id=#{grantId},claim_idempotency_key=#{idempotencyKey},
                   version=version+1,updated_at=CURRENT_TIMESTAMP
             WHERE run_id=#{runId} AND user_id=#{userId} AND voucher_id=#{voucherId}
               AND UPPER(COALESCE(claim_status,'UNCLAIMED'))='UNCLAIMED'
            """)
    int claimExistingSandboxVoucher(
            @Param("runId") String runId,
            @Param("userId") Long userId,
            @Param("voucherId") String voucherId,
            @Param("grantId") String grantId,
            @Param("idempotencyKey") String idempotencyKey);

    @Insert("INSERT INTO nx_voucher_popup_state (user_id,voucher_id,last_seen_at,session_count,version) VALUES (#{userId},#{voucherId},#{nowMillis},1,0) "
            + "ON DUPLICATE KEY UPDATE last_seen_at=#{nowMillis},session_count=session_count+1,version=version+1")
    int markVoucherPopupSeen(@Param("userId") Long userId, @Param("voucherId") String voucherId, @Param("nowMillis") long nowMillis);

    @Insert("INSERT INTO nx_voucher_popup_sandbox_state (run_id,user_id,voucher_id,last_seen_at,session_count,version) "
            + "VALUES (#{runId},#{userId},#{voucherId},#{nowMillis},1,0) "
            + "ON DUPLICATE KEY UPDATE last_seen_at=#{nowMillis},session_count=session_count+1,version=version+1")
    int markVoucherPopupSeenSandbox(
            @Param("runId") String runId,
            @Param("userId") Long userId,
            @Param("voucherId") String voucherId,
            @Param("nowMillis") long nowMillis);

    @Select("SELECT id FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0 "
            + "AND COALESCE(sandbox,0)=1 LIMIT 1")
    Long findSandboxUser(@Param("userId") Long userId);

    record Attribution(String phase, Integer accountAgeMonths, String cohort) {
    }

    record VoucherClaimDefinition(String voucherId, String audience) {
    }

    record SandboxVoucherClaim(String grantId, String grantStatus, String idempotencyKey) {
    }

    record QuestReward(
            Long missionId, String questCode, String layer, BigDecimal rewardNex) {
    }

    record EventReward(
            Long questId,
            String eventCode,
            Integer targetValue,
            String rewardType,
            BigDecimal rewardAmount,
            String badgeCode) {
    }

    record StreakState(
            Integer currentStreak, Integer longestStreak, Integer streakSavers, LocalDate lastCheckInDate) {
    }

    record DailyMilestone(
            Long milestoneId,
            Integer milestoneDay,
            String rewardType,
            BigDecimal rewardAmount,
            String badgeCode) {
    }

    record EarningMilestone(
            String milestoneId, BigDecimal thresholdUsdt, BigDecimal rewardNex, BigDecimal lifetimeEarningsUsdt) {
    }

    record StreakPowerUp(
            Long powerUpId, String powerUpCode, String badgeCode, Integer durationDays) {
    }
}
