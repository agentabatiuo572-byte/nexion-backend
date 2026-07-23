package ffdd.opsconsole.team.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface TeamCommissionMapper extends BaseMapper<Object> {
    @Select("""
            SELECT c.rank_code AS v,
                   c.title_cn AS label,
                   c.self_buy_usd AS selfBuyUsd,
                   c.direct_refs AS directRefs,
                   c.team_volume_usd AS teamGvUsd,
                   c.required_downline_count AS legCount,
                   c.required_downline_rank AS legRank,
                   c.leadership_votes AS votes,
                   COALESCE(c.physical_reward, '') AS physicalReward,
                   COUNT(m.id) AS pop
              FROM nx_v_rank_config c
              LEFT JOIN nx_team_member m
                ON m.v_rank = c.rank_code
               AND m.is_deleted = 0
             WHERE c.is_deleted = 0
               AND c.status = 1
             GROUP BY c.id, c.rank_code, c.title_cn, c.self_buy_usd, c.direct_refs,
                      c.team_volume_usd, c.required_downline_count, c.required_downline_rank,
                      c.leadership_votes, c.physical_reward, c.sort_order
             ORDER BY c.sort_order ASC, c.id ASC
            """)
    List<Map<String, Object>> vRankRows();

    @Update("""
            UPDATE nx_v_rank_config
               SET self_buy_usd = #{value},
                   updated_at = NOW()
             WHERE rank_code = #{rank}
               AND is_deleted = 0
            """)
    int updateVRankSelfBuy(@Param("rank") String rank, @Param("value") Object value);

    @Update("""
            UPDATE nx_v_rank_config
               SET direct_refs = #{value},
                   updated_at = NOW()
             WHERE rank_code = #{rank}
               AND is_deleted = 0
            """)
    int updateVRankDirectRefs(@Param("rank") String rank, @Param("value") Object value);

    @Update("""
            UPDATE nx_v_rank_config
               SET team_volume_usd = #{value},
                   updated_at = NOW()
             WHERE rank_code = #{rank}
               AND is_deleted = 0
            """)
    int updateVRankTeamGv(@Param("rank") String rank, @Param("value") Object value);

    @Update("""
            UPDATE nx_v_rank_config
               SET required_downline_count = #{value},
                   updated_at = NOW()
             WHERE rank_code = #{rank}
               AND is_deleted = 0
            """)
    int updateVRankLegCount(@Param("rank") String rank, @Param("value") Object value);

    @Update("""
            UPDATE nx_v_rank_config
               SET required_downline_rank = #{value},
                   updated_at = NOW()
             WHERE rank_code = #{rank}
               AND is_deleted = 0
            """)
    int updateVRankLegRank(@Param("rank") String rank, @Param("value") String value);

    @Select("""
            SELECT metric.id,
                   metric.label,
                   metric.value,
                   metric.sub,
                   metric.tone
              FROM (
                    SELECT 'l1TriggerRate' AS id,
                           'L1 触发率' AS label,
                           CONCAT(ROUND(SUM(CASE WHEN level = 1 THEN 1 ELSE 0 END) * 100 / COUNT(1), 1), '%') AS value,
                           CONCAT(SUM(CASE WHEN level = 1 THEN 1 ELSE 0 END), ' / ', COUNT(1), ' 团队成员') AS sub,
                           '' AS tone,
                           COUNT(1) AS sampleCount
                      FROM nx_team_member
                     WHERE is_deleted = 0
                    UNION ALL
                    SELECT 'weeklyUsdtRoyalty' AS id,
                           '本周 USDT 版税' AS label,
                           CONCAT('$', ROUND(SUM(amount_usdt), 2)) AS value,
                           CONCAT(COUNT(1), ' 笔佣金事件') AS sub,
                           'ok' AS tone,
                           COUNT(1) AS sampleCount
                      FROM nx_commission_event
                     WHERE is_deleted = 0
                       AND UPPER(currency) = 'USDT'
                       AND created_at >= DATE_SUB(NOW(), INTERVAL 7 DAY)
                    UNION ALL
                    SELECT 'weeklyNexPayout' AS id,
                           '本周 NEX 派发' AS label,
                           ROUND(SUM(amount_nex), 2) AS value,
                           CONCAT(COUNT(1), ' 笔 NEX 佣金事件') AS sub,
                           'cyan' AS tone,
                           COUNT(1) AS sampleCount
                      FROM nx_commission_event
                     WHERE is_deleted = 0
                       AND UPPER(currency) = 'NEX'
                       AND created_at >= DATE_SUB(NOW(), INTERVAL 7 DAY)
                   ) metric
             WHERE metric.sampleCount > 0
            """)
    List<Map<String, Object>> f2Metrics();

    @Select("""
            SELECT CONCAT('L', layer_no) AS level,
                   ROUND(usdt_rate * 100, 6) AS usdtPct,
                   nex_per_usd AS nexReward,
                   CASE WHEN layer_no = 1 THEN '直推 DIRECT' ELSE '扩展 EXTENDED' END AS label,
                   CASE WHEN layer_no = 1 THEN TRUE ELSE FALSE END AS direct,
                   'nx_commission_rule' AS source
              FROM nx_commission_rule
             WHERE is_deleted = 0
               AND status = 1
               AND LOWER(commission_type) = 'unilevel'
               AND layer_no IS NOT NULL
             ORDER BY layer_no ASC, id ASC
            """)
    List<Map<String, Object>> unilevelRates();

    @Update("""
            UPDATE nx_commission_rule
               SET usdt_rate = #{value},
                   updated_at = NOW()
             WHERE is_deleted = 0
               AND LOWER(commission_type) = 'unilevel'
               AND layer_no = #{layerNo}
            """)
    int updateUnilevelUsdtRate(@Param("layerNo") int layerNo, @Param("value") Object value);

    @Update("""
            UPDATE nx_commission_rule
               SET nex_per_usd = #{value},
                   updated_at = NOW()
             WHERE is_deleted = 0
               AND LOWER(commission_type) = 'unilevel'
               AND layer_no = #{layerNo}
            """)
    int updateUnilevelNexPerUsd(@Param("layerNo") int layerNo, @Param("value") Object value);

    /**
     * F2: userId 的 L1-L7 上级链(递归 CTE 上溯 member_user_id→user_id,限深 maxDepth)。
     * 返回每行 {userId(上级 id), layer(1..maxDepth), vRank(上级自循环行 v_rank,null 视为 V0)},
     * 按 layer 升序。供 UnilevelCommissionService 按 layer 费率派发 network 佣金 + depthGate 阶位校验。
     * vRank 子查询取 nx_team_member 自循环行(user_id=member_user_id=该上级)的 v_rank。
     */
    @Select("""
            WITH RECURSIVE upline AS (
                SELECT user_id AS userId, 1 AS layer
                  FROM nx_team_member
                 WHERE member_user_id = #{userId}
                   AND level = 1
                   AND is_deleted = 0
                UNION ALL
                SELECT u.user_id, up.layer + 1
                  FROM upline up
                  JOIN nx_team_member u
                    ON u.member_user_id = up.userId
                   AND u.level = 1
                   AND u.is_deleted = 0
                 WHERE up.layer < #{maxDepth}
            )
            SELECT userId,
                   layer,
                   (SELECT tm.v_rank FROM nx_team_member tm
                     WHERE tm.user_id = upline.userId
                       AND tm.member_user_id = upline.userId
                       AND tm.is_deleted = 0) AS vRank
              FROM upline ORDER BY layer ASC
            """)
    List<Map<String, Object>> listUplineChain(@Param("userId") Long userId, @Param("maxDepth") int maxDepth);

    @Select("""
            SELECT c.rank_code AS name,
                   CONCAT('Team volume >= ', ROUND(c.team_volume_usd, 0), ' USDT') AS requirement,
                   CONCAT(ROUND(c.peer_bonus_rate * 100, 2), '%') AS rate,
                   CASE
                     WHEN totals.total_count = 0 THEN '0%'
                     ELSE CONCAT(ROUND(COUNT(m.id) * 100 / totals.total_count, 1), '%')
                   END AS distribution,
                   CONCAT('t-', c.sort_order) AS className
              FROM nx_v_rank_config c
              LEFT JOIN nx_team_member m
                ON m.v_rank = c.rank_code
               AND m.is_deleted = 0
              CROSS JOIN (
                    SELECT COUNT(1) AS total_count
                      FROM nx_team_member
                     WHERE is_deleted = 0
              ) totals
             WHERE c.is_deleted = 0
               AND c.status = 1
             GROUP BY c.id, c.rank_code, c.team_volume_usd, c.peer_bonus_rate, c.sort_order, totals.total_count
             ORDER BY c.sort_order ASC, c.id ASC
            """)
    List<Map<String, Object>> rateTiers();

    @Select("""
            SELECT reward_id AS id,
                   reward_type AS type,
                   amount,
                   voucher_id AS voucherId,
                   sku_id AS skuId,
                   custom_label AS custom,
                   'nx_v_rank_reward_rule' AS source
              FROM nx_v_rank_reward_rule
             WHERE is_deleted = 0
               AND status = 1
               AND rank_code = #{rank}
             ORDER BY sort_order ASC, id ASC
            """)
    List<Map<String, Object>> vRankRewards(@Param("rank") String rank);

    @Insert("""
            INSERT INTO nx_v_rank_reward_rule
              (reward_id, rank_code, reward_type, amount, voucher_id, sku_id, custom_label, sort_order, status)
            VALUES
              (#{rewardId}, #{rank}, #{type}, #{amount}, #{voucherId}, #{skuId}, #{custom}, 0, 1)
            ON DUPLICATE KEY UPDATE
              rank_code = VALUES(rank_code),
              reward_type = VALUES(reward_type),
              amount = VALUES(amount),
              voucher_id = VALUES(voucher_id),
              sku_id = VALUES(sku_id),
              custom_label = VALUES(custom_label),
              status = VALUES(status),
              is_deleted = 0,
              updated_at = NOW()
            """)
    int insertVRankReward(@Param("rank") String rank,
                          @Param("rewardId") String rewardId,
                          @Param("type") String type,
                          @Param("amount") Object amount,
                          @Param("voucherId") String voucherId,
                          @Param("skuId") String skuId,
                          @Param("custom") String custom);

    @Update("""
            UPDATE nx_v_rank_reward_rule
               SET reward_type = #{type},
                   amount = #{amount},
                   voucher_id = #{voucherId},
                   sku_id = #{skuId},
                   custom_label = #{custom},
                   status = 1,
                   updated_at = NOW()
             WHERE rank_code = #{rank}
               AND reward_id = #{rewardId}
               AND is_deleted = 0
            """)
    int updateVRankReward(@Param("rank") String rank,
                          @Param("rewardId") String rewardId,
                          @Param("type") String type,
                          @Param("amount") Object amount,
                          @Param("voucherId") String voucherId,
                          @Param("skuId") String skuId,
                          @Param("custom") String custom);

    @Update("""
            UPDATE nx_v_rank_reward_rule
               SET status = 0,
                   is_deleted = 1,
                   updated_at = NOW()
             WHERE rank_code = #{rank}
               AND reward_id = #{rewardId}
               AND is_deleted = 0
            """)
    int deleteVRankReward(@Param("rank") String rank, @Param("rewardId") String rewardId);

    @Select("""
            SELECT COALESCE(SUM(m.volume), 0) AS weeklyGmvUsd,
                   (
                     SELECT COALESCE(SUM(e.amount_usdt), 0)
                       FROM nx_commission_event e
                      WHERE e.is_deleted = 0
                        AND LOWER(e.commission_type) = 'leadership'
                        AND e.created_at >= DATE_SUB(NOW(), INTERVAL 7 DAY)
                   ) AS weeklyInjectedUsd,
                   (
                     SELECT COALESCE(SUM(e.amount_usdt), 0)
                       FROM nx_commission_event e
                      WHERE e.is_deleted = 0
                        AND LOWER(e.commission_type) = 'leadership'
                        AND e.created_at >= DATE_FORMAT(CURDATE(), '%Y-%m-01')
                   ) AS monthLeadershipUsd,
                   COUNT(DISTINCT m.member_user_id) AS participantCount
              FROM nx_team_member m
             WHERE m.is_deleted = 0
            """)
    Map<String, Object> leadershipPoolSummary();

    @Select("""
            SELECT CAST(REPLACE(c.rank_code, 'V', '') AS SIGNED) AS v,
                   c.leadership_votes AS votes,
                   COUNT(m.id) AS pop
              FROM nx_v_rank_config c
              LEFT JOIN nx_team_member m
                ON m.v_rank = c.rank_code
               AND m.is_deleted = 0
             WHERE c.is_deleted = 0
               AND c.status = 1
               AND c.leadership_votes > 0
             GROUP BY c.id, c.rank_code, c.leadership_votes, c.sort_order
             ORDER BY c.sort_order ASC, c.id ASC
            """)
    List<Map<String, Object>> leadershipRanks();

    @Select("""
            SELECT COALESCE(t.display_name, t.quota_code) AS name,
                   COALESCE(SUM(CASE WHEN u.is_deleted = 0 AND UPPER(u.status) = 'ACTIVE' THEN u.quantity ELSE 0 END), 0) AS current,
                   t.monthly_quota AS cap,
                   CASE
                     WHEN t.monthly_quota <= 0 THEN FALSE
                     WHEN COALESCE(SUM(CASE WHEN u.is_deleted = 0 AND UPPER(u.status) = 'ACTIVE' THEN u.quantity ELSE 0 END), 0) * 100 / t.monthly_quota >= 85 THEN TRUE
                     ELSE FALSE
                   END AS tight
              FROM nx_team_hardware_quota_tier t
              LEFT JOIN nx_team_hardware_quota_usage u
                ON u.quota_tier_id = t.id
               AND u.occurred_at >= DATE_FORMAT(CURDATE(), '%Y-%m-01')
             WHERE t.is_deleted = 0
               AND t.status = 1
             GROUP BY t.id, t.display_name, t.quota_code, t.monthly_quota, t.sort_order
             ORDER BY t.sort_order ASC, t.id ASC
            """)
    List<Map<String, Object>> quotaRows();

    @Select("""
            SELECT UPPER(status) AS name,
                   COUNT(1) AS count
              FROM nx_team_ambassador_application
             WHERE is_deleted = 0
             GROUP BY UPPER(status)
             ORDER BY COUNT(1) DESC, UPPER(status) ASC
            """)
    List<Map<String, Object>> ambassadorBands();

    @Select("""
            SELECT COUNT(CASE WHEN UPPER(status) = 'PENDING' THEN 1 END) AS pendingCount,
                   COALESCE(SUM(CASE WHEN UPPER(status) = 'APPROVED' THEN requested_budget_usdt ELSE 0 END), 0) AS approvedBudgetUsd,
                   COALESCE(SUM(requested_budget_usdt), 0) AS requestedBudgetUsd,
                   COALESCE(MAX(kol_budget_pct), 0) AS kolBudgetPct,
                   MIN(CASE WHEN UPPER(status) = 'PENDING' THEN event_date END) AS nextQuotaReviewDate,
                   CASE
                     WHEN COUNT(CASE WHEN UPPER(status) = 'PENDING' THEN 1 END) > 0 THEN 'pending'
                     WHEN COUNT(CASE WHEN UPPER(status) = 'APPROVED' THEN 1 END) > 0 THEN 'approved'
                     WHEN COUNT(1) > 0 THEN 'closed'
                     ELSE ''
                   END AS status
              FROM nx_team_ambassador_application
             WHERE is_deleted = 0
            """)
    Map<String, Object> ambassadorSummary();

    @Select("""
            SELECT ranked.rank_no AS `rank`,
                   CONCAT('U', LPAD(ranked.member_user_id, 8, '0')) AS userId,
                   CONCAT('$', ROUND(ranked.volume, 0)) AS gmvLabel,
                   CASE
                     WHEN a.action_type IS NOT NULL THEN COALESCE(a.reason, 'F4 已执行反欺诈处置')
                     WHEN EXISTS (
                       SELECT 1 FROM nx_risk_signal risk_signal
                        WHERE risk_signal.user_id = ranked.member_user_id
                          AND risk_signal.is_deleted = 0
                          AND risk_signal.signal_type = 'risk.leaderboard_velocity_flagged'
                          AND risk_signal.created_at >= DATE_SUB(CURDATE(), INTERVAL WEEKDAY(CURDATE()) DAY)
                     ) THEN 'K2 刷榜信号 · 待 F4 处置'
                     ELSE '本期 GV'
                   END AS tip,
                   CASE
                     WHEN a.action_type IS NOT NULL THEN CONCAT('r-', ranked.rank_no, ' dq')
                     WHEN EXISTS (
                       SELECT 1 FROM nx_risk_signal risk_signal
                        WHERE risk_signal.user_id = ranked.member_user_id
                          AND risk_signal.is_deleted = 0
                          AND risk_signal.signal_type = 'risk.leaderboard_velocity_flagged'
                          AND risk_signal.created_at >= DATE_SUB(CURDATE(), INTERVAL WEEKDAY(CURDATE()) DAY)
                     ) THEN CONCAT('r-', ranked.rank_no, ' risk')
                     ELSE CONCAT('r-', ranked.rank_no)
                   END AS className
              FROM (
                    SELECT member_user_id,
                           volume,
                           ROW_NUMBER() OVER (ORDER BY volume DESC, id ASC) AS rank_no
                      FROM nx_team_member
                     WHERE is_deleted = 0
                   ) ranked
              LEFT JOIN nx_team_leaderboard_action a
                ON a.member_user_id = ranked.member_user_id
               AND a.is_deleted = 0
               AND a.period = 'week'
               AND UPPER(a.action_type) IN ('FRAUD', 'DISQUALIFIED', 'RISK')
             WHERE ranked.rank_no <= #{limit}
             ORDER BY ranked.rank_no ASC
            """)
    List<Map<String, Object>> leaderboardPodium(@Param("limit") int limit);

    @Select("""
            SELECT (
                     SELECT COUNT(DISTINCT member_user_id)
                       FROM nx_team_member
                      WHERE is_deleted = 0
                   ) AS participantCount,
                   (
                     SELECT COUNT(DISTINCT flags.member_user_id)
                       FROM (
                             SELECT member_user_id
                               FROM nx_team_leaderboard_action
                              WHERE is_deleted = 0
                                AND period = 'week'
                                AND UPPER(action_type) IN ('FRAUD', 'DISQUALIFIED', 'RISK')
                             UNION
                             SELECT user_id AS member_user_id
                               FROM nx_risk_signal
                              WHERE is_deleted = 0
                                AND signal_type = 'risk.leaderboard_velocity_flagged'
                                AND created_at >= DATE_SUB(CURDATE(), INTERVAL WEEKDAY(CURDATE()) DAY)
                            ) flags
                   ) AS fraudHitCount,
                   (
                     SELECT COALESCE(SUM(amount_usdt), 0)
                       FROM nx_commission_event
                      WHERE is_deleted = 0
                        AND LOWER(commission_type) = 'leadership'
                        AND created_at >= DATE_FORMAT(CURDATE(), '%Y-%m-01')
                   ) AS poolUsd,
                   CASE
                     WHEN (
                       SELECT COUNT(DISTINCT member_user_id)
                         FROM nx_team_leaderboard_action
                        WHERE is_deleted = 0
                          AND period = 'week'
                          AND UPPER(action_type) IN ('FRAUD', 'DISQUALIFIED', 'RISK')
                     ) > 0 THEN 'disqualified'
                     WHEN (
                       SELECT COUNT(DISTINCT user_id)
                         FROM nx_risk_signal
                        WHERE is_deleted = 0
                          AND signal_type = 'risk.leaderboard_velocity_flagged'
                          AND created_at >= DATE_SUB(CURDATE(), INTERVAL WEEKDAY(CURDATE()) DAY)
                     ) > 0 THEN 'flagged'
                     WHEN (
                       SELECT COUNT(1)
                         FROM nx_team_member
                        WHERE is_deleted = 0
                     ) > 0 THEN 'active'
                     ELSE ''
                   END AS periodStatus
            """)
    Map<String, Object> leaderboardSummary();

    @Select("""
            SELECT CONCAT('U', LPAD(user_id, 8, '0')) AS user,
                   CAST(ROUND(left_volume, 0) AS SIGNED) AS trackA,
                   CAST(ROUND(right_volume, 0) AS SIGNED) AS trackB,
                   CAST(ROUND(matched_volume, 0) AS SIGNED) AS matchAmount,
                   CAST(ROUND(amount_usdt, 0) AS SIGNED) AS todayPaid,
                   CASE
                     WHEN UPPER(status) IN ('PAID', 'SETTLED', 'UNLOCKED') THEN 'paid'
                     WHEN UPPER(status) IN ('BLOCKED', 'REJECTED', 'FAILED', 'FROZEN') THEN 'blocked'
                     ELSE LOWER(status)
                   END AS state,
                   CASE
                     WHEN UPPER(status) IN ('PAID', 'SETTLED', 'UNLOCKED') THEN 'ok'
                     WHEN UPPER(status) IN ('BLOCKED', 'REJECTED', 'FAILED', 'FROZEN') THEN 'warn'
                     ELSE ''
                   END AS tone,
                   settlement_date AS settlementDate,
                   'nx_binary_commission_settlement' AS source
              FROM nx_binary_commission_settlement
             WHERE is_deleted = 0
             ORDER BY settlement_date DESC, id DESC
             LIMIT #{limit}
            """)
    List<Map<String, Object>> binarySettlements(@Param("limit") int limit);

    @Select("""
            SELECT COUNT(DISTINCT user_id) AS participantCount,
                   SUM(CASE WHEN UPPER(status) IN ('BLOCKED', 'REJECTED', 'FAILED', 'FROZEN') THEN 1 ELSE 0 END) AS blockedCount,
                   COALESCE(ROUND(SUM(CASE
                     WHEN settlement_date >= DATE_FORMAT(CURDATE(), '%Y-%m-01') THEN matched_volume
                     ELSE 0
                   END), 0), 0) AS monthlyMatchedUsd,
                   COALESCE(ROUND(SUM(CASE
                     WHEN settlement_date = CURDATE() THEN matched_volume
                     ELSE 0
                   END), 0), 0) AS dailyMatchUsd,
                   COALESCE(ROUND(MAX(daily_cap_usdt), 0), 0) AS maxDailyCapUsd,
                   COALESCE(ROUND(MAX(GREATEST(left_volume, right_volume)), 0), 0) AS maxTrackGmv,
                   COALESCE(ROUND(SUM(GREATEST(left_volume, right_volume) - matched_volume), 0), 0) AS residualPoolUsd
              FROM nx_binary_commission_settlement
             WHERE is_deleted = 0
            """)
    Map<String, Object> binarySettlementSummary();

    @Select("""
            SELECT CONCAT('CM-', id) AS id,
                   LOWER(commission_type) AS kind,
                   CONCAT('U', LPAD(user_id, 8, '0')) AS user,
                   CASE
                     WHEN UPPER(currency) = 'NEX' THEN amount_nex
                     ELSE amount_usdt
                   END AS amount,
                   currency,
                   CASE
                     WHEN unlock_at IS NULL OR unlock_at <= NOW() THEN 100
                     WHEN TIMESTAMPDIFF(SECOND, created_at, unlock_at) <= 0 THEN 0
                     ELSE GREATEST(0, LEAST(99, ROUND(
                       TIMESTAMPDIFF(SECOND, created_at, NOW()) * 100
                       / TIMESTAMPDIFF(SECOND, created_at, unlock_at)
                     )))
                   END AS cooldownPercent,
                   CASE
                     WHEN UPPER(status) IN ('UNLOCKED', 'AVAILABLE', 'SETTLED', 'PAID')
                       OR (unlock_at IS NOT NULL AND unlock_at <= NOW()) THEN '可提'
                     WHEN UPPER(status) IN ('REVERSED', 'ROLLBACK') THEN '异常回退'
                     WHEN UPPER(status) = 'FROZEN' THEN 'frozen'
                     WHEN UPPER(status) = 'REJECTED' THEN 'rejected'
                     ELSE '计提'
                   END AS cooldownLabel,
                   CASE
                     WHEN UPPER(status) IN ('UNLOCKED', 'AVAILABLE', 'SETTLED', 'PAID')
                       OR (unlock_at IS NOT NULL AND unlock_at <= NOW()) THEN '可提'
                     WHEN UPPER(status) IN ('REVERSED', 'ROLLBACK') THEN '异常回退'
                     WHEN UPPER(status) = 'FROZEN' THEN 'frozen'
                     WHEN UPPER(status) = 'REJECTED' THEN 'rejected'
                     ELSE '计提'
                   END AS state,
                   'nx_commission_event' AS source
              FROM nx_commission_event
             WHERE is_deleted = 0
             ORDER BY created_at DESC, id DESC
             LIMIT #{limit}
            """)
    List<Map<String, Object>> commissionEvents(@Param("limit") int limit);

    @Select("""
            SELECT LOWER(commission_type) AS `key`,
                   UPPER(commission_type) AS code,
                   CASE LOWER(commission_type)
                     WHEN 'direct' THEN '直推佣金'
                     WHEN 'network' THEN '网络版税'
                     WHEN 'binary' THEN '双轨平衡匹配'
                     WHEN 'leadership' THEN '领导奖池'
                     ELSE commission_type
                   END AS label,
                   CONCAT('$', ROUND(SUM(amount_usdt), 2)) AS amountLabel,
                   CONCAT(COUNT(1), ' 笔') AS countLabel,
                   CONCAT('k-', LOWER(commission_type)) AS className,
                   '' AS amountColor
              FROM nx_commission_event
             WHERE is_deleted = 0
             GROUP BY commission_type
             ORDER BY COUNT(1) DESC, commission_type ASC
            """)
    List<Map<String, Object>> commissionKindSummary();

    @Select("""
            SELECT DATE_FORMAT(updated_at, '%Y-%m-%d %H:%i') AS `when`,
                   CONCAT('佣金事件 ', CONCAT('CM-', id), ' 状态 ', status, ' · ', LOWER(commission_type)) AS text,
                   CASE
                     WHEN UPPER(status) IN ('REJECTED', 'FROZEN', 'REVERSED', 'ROLLBACK') THEN 'HIGH'
                     WHEN UPPER(status) IN ('UNLOCKED', 'AVAILABLE', 'SETTLED', 'PAID') THEN 'MEDIUM'
                     ELSE 'LOW'
                   END AS level
              FROM nx_commission_event
             WHERE is_deleted = 0
             ORDER BY updated_at DESC, id DESC
             LIMIT #{limit}
            """)
    List<Map<String, Object>> commissionAuditFeed(@Param("limit") int limit);

    @Update("""
            UPDATE nx_commission_event
               SET status = #{status},
                   unlock_at = CASE
                     WHEN #{status} IN ('UNLOCKED', 'AVAILABLE', 'SETTLED', 'PAID') THEN COALESCE(unlock_at, NOW())
                     ELSE unlock_at
                   END,
                   updated_at = NOW()
             WHERE is_deleted = 0
               AND (CONCAT('CM-', id) = #{eventId} OR order_no = #{eventId})
            """)
    int updateCommissionStatus(@Param("eventId") String eventId,
                               @Param("status") String status);

    // F4 · 修复2:V-Rank 票权权重写 nx_v_rank_config.leadership_votes。
    // F.pool.votes.V{n} 动态分发 → UPDATE WHERE rank_code=#{rankCode}。
    @Update("""
            UPDATE nx_v_rank_config
               SET leadership_votes = #{votes},
                   updated_at = NOW()
             WHERE rank_code = #{rankCode}
               AND is_deleted = 0
            """)
    int updateVRankLeadershipVotes(@Param("rankCode") String rankCode,
                                   @Param("votes") int votes);

    // F4 · 修复3:大使审批按数字 id 精确处置(UPDATE nx_team_ambassador_application)。
    // F.ambassador.{numericId}.status 动态分发 → 此处直匹配 BIGINT id。
    @Update("""
            UPDATE nx_team_ambassador_application
               SET status = #{status},
                   reviewer = #{reviewer},
                   review_reason = #{reason},
                   reviewed_at = NOW(),
                   updated_at = NOW()
             WHERE id = #{applicationId}
               AND is_deleted = 0
            """)
    int updateAmbassadorStatusById(@Param("applicationId") Long applicationId,
                                   @Param("status") String status,
                                   @Param("reviewer") String reviewer,
                                   @Param("reason") String reason);

    // F4 · 修复3:大使审批周期级处置 — 标签非数字(如 q3-2025)时 fallback 到"最新一条 PENDING"。
    // 一条 UPDATE 完成(子查询绕过 MySQL "不能在 FROM 同表 UPDATE" 限制)。
    @Update("""
            UPDATE nx_team_ambassador_application
               SET status = #{status},
                   reviewer = #{reviewer},
                   review_reason = #{reason},
                   reviewed_at = NOW(),
                   updated_at = NOW()
             WHERE id = (
                   SELECT t.id FROM (
                     SELECT id FROM nx_team_ambassador_application
                      WHERE is_deleted = 0
                        AND UPPER(status) = 'PENDING'
                      ORDER BY created_at DESC, id DESC
                      LIMIT 1
                   ) t
             )
            """)
    int updateLatestPendingAmbassadorStatus(@Param("status") String status,
                                            @Param("reviewer") String reviewer,
                                            @Param("reason") String reason);

    // F4 · 修复4:榜单处置(取消资格/暂停)INSERT 流水到 nx_team_leaderboard_action。
    // member_user_id=0 表示"本期全局期处置"(非针对具体用户的期级 action);period='week' 对齐前端 4 周期榜单。
    @Insert("""
            INSERT INTO nx_team_leaderboard_action
              (period, user_id, member_user_id, member_no, nickname, action_type, reason, operator)
            VALUES
              (#{period}, 0, 0, 'period-scope', #{period}, #{actionType}, #{reason}, #{operator})
            """)
    int insertLeaderboardAction(@Param("period") String period,
                                @Param("actionType") String actionType,
                                @Param("reason") String reason,
                                @Param("operator") String operator);

    // ============================================================
    // F1 V-Rank 晋升引擎(Sprint 1+2)
    // ============================================================

    /**
     * 读 13 阶 nx_v_rank_config,按 sort_order 升序(V0..V12)。
     * Sprint6 扩展:补 unilevel_depth/peer_bonus_rate/leadership_votes 三列,供 overrideVRank 联动预览读。
     */
    @Select("""
            SELECT rank_code AS rankCode,
                   self_buy_usd AS selfBuyUsd,
                   direct_refs AS directRefs,
                   team_volume_usd AS teamVolumeUsd,
                   required_downline_rank AS requiredDownlineRank,
                   required_downline_count AS requiredDownlineCount,
                   sort_order AS sortOrder,
                   unilevel_depth AS unilevelDepth,
                   peer_bonus_rate AS peerBonusRate,
                   leadership_votes AS leadershipVotes
              FROM nx_v_rank_config
             WHERE is_deleted = 0
               AND status = 1
             ORDER BY sort_order ASC, id ASC
            """)
    List<Map<String, Object>> vRankConfigRows();

    /** 读 nx_team_member 自循环行(user_id=member_user_id=userId)的 v_rank。 */
    @Select("""
            SELECT v_rank
              FROM nx_team_member
             WHERE user_id = #{userId}
               AND member_user_id = #{userId}
               AND is_deleted = 0
             LIMIT 1
            """)
    String currentMemberVRank(@Param("userId") Long userId);

    /** 更新自循环行 v_rank(WHERE 命中 0 行时返回 0,引擎上层保证自循环行存在)。 */
    @Update("""
            UPDATE nx_team_member
               SET v_rank = #{newRank},
                   updated_at = NOW()
             WHERE user_id = #{userId}
               AND member_user_id = #{userId}
               AND is_deleted = 0
            """)
    int updateMemberVRank(@Param("userId") Long userId,
                          @Param("newRank") String newRank);

    /** INSERT nx_user_level_log 晋升流水(含 Sprint1 新增 operator/snapshot/trigger_event_id/audit_no/is_manual 列)。 */
    @Insert("""
            INSERT INTO nx_user_level_log
              (user_id, level_type, from_code, to_code, reason,
               operator, snapshot, trigger_event_id, audit_no, is_manual)
            VALUES
              (#{userId}, 'VRANK', #{fromCode}, #{toCode}, #{reason},
               #{operator}, #{snapshotJson}, #{triggerEventId}, #{auditNo}, #{isManual})
            """)
    int insertUserLevelLog(@Param("userId") Long userId,
                           @Param("fromCode") String fromCode,
                           @Param("toCode") String toCode,
                           @Param("reason") String reason,
                           @Param("operator") String operator,
                           @Param("snapshotJson") String snapshotJson,
                           @Param("triggerEventId") String triggerEventId,
                           @Param("auditNo") String auditNo,
                           @Param("isManual") int isManual);

    // ============================================================
    // F1 V-Rank 奖励派发(Sprint 3)
    // ============================================================

    /** 读指定阶奖励规则(is_deleted=0 AND status=1,按 sort_order 升序)。 */
    @Select("""
            SELECT reward_id    AS rewardId,
                   rank_code    AS rankCode,
                   reward_type  AS rewardType,
                   amount,
                   voucher_id   AS voucherId,
                   sku_id       AS skuId,
                   custom_label AS customLabel,
                   sort_order   AS sortOrder
              FROM nx_v_rank_reward_rule
             WHERE is_deleted = 0
               AND status = 1
               AND rank_code = #{rankCode}
             ORDER BY sort_order ASC, id ASC
            """)
    List<Map<String, Object>> selectVRankRewardRulesByRank(@Param("rankCode") String rankCode);

    /**
     * 查培育奖接收方 L1 上线 sponsor。
     * 优先 nx_sponsorship.sponsor_user_id;表无记录时 fallback 到 nx_team_member L1 直推上级
     * (user_id WHERE member_user_id=userId AND level=1),防 sponsor 数据缺失时误派本人。
     */
    @Select("""
            SELECT COALESCE(
                (SELECT s.sponsor_user_id
                   FROM nx_sponsorship s
                  WHERE s.user_id = #{userId}
                    AND s.is_deleted = 0
                    AND s.sponsor_user_id IS NOT NULL
                  LIMIT 1),
                (SELECT m.user_id
                   FROM nx_team_member m
                  WHERE m.member_user_id = #{userId}
                    AND m.level = 1
                    AND m.is_deleted = 0
                  LIMIT 1)
            )
            """)
    Long findSponsorUserId(@Param("userId") Long userId);

    /**
     * Sprint4: 列出 nx_team_member 自循环行(user_id=member_user_id)的 userId,供 Scheduler 兜底批量评估。
     * 限 limit 条,按 user_id 升序。MVP 不分批 cursor(假设测试数据小);生产大表需 cursor 分批。
     */
    @Select("""
            SELECT user_id
              FROM nx_team_member
             WHERE user_id = member_user_id
               AND is_deleted = 0
             ORDER BY user_id ASC
             LIMIT #{limit}
            """)
    List<Long> listSelfLoopUserIds(@Param("limit") int limit);

    /** 幂等检查:同 user/rank/rewardType 已派发过则返回 1(忽略软删,防同阶重发)。 */
    @Select("""
            SELECT COUNT(1)
              FROM nx_v_rank_reward_payout
             WHERE user_id = #{userId}
               AND rank_code = #{rankCode}
               AND reward_type = #{rewardType}
               AND status IN ('GRANTED', 'PENDING_GRANT', 'REISSUED')
            """)
    int countVRankRewardPayout(@Param("userId") Long userId,
                               @Param("rankCode") String rankCode,
                               @Param("rewardType") String rewardType);

    /**
     * INSERT nx_commission_event — 资金类奖励落 D4 台账事件。
     * 注意:@Param 模式无法用 useGeneratedKeys 回填,impl 在调用后再 selectLastInsertId() 取自增 id。
     * unlock_at = NOW()+coolingDays(PRD line231 coolingDays 默认30,域独立;读 commission/cooling-days 配置),
     * COOLING 状态的 commission 由 F5 CommissionEventUnlockScheduler 到期自动解锁。
     */
    @Insert("""
            INSERT INTO nx_commission_event
              (user_id, commission_type, source_user_id, source_user_name,
               layer_no, order_no, order_amount_usd,
               amount_usdt, amount_nex, currency, status, unlock_at, remark)
            VALUES
              (#{userId}, #{commissionType}, #{sourceUserId}, NULL,
               NULL, NULL, NULL,
               #{amountUsdt}, #{amountNex}, #{currency}, #{status}, DATE_ADD(NOW(), INTERVAL #{coolingDays} DAY), #{remark})
            """)
    int insertCommissionEvent(@Param("userId") Long userId,
                              @Param("commissionType") String commissionType,
                              @Param("sourceUserId") Long sourceUserId,
                              @Param("currency") String currency,
                              @Param("amountUsdt") java.math.BigDecimal amountUsdt,
                              @Param("amountNex") java.math.BigDecimal amountNex,
                              @Param("status") String status,
                              @Param("coolingDays") int coolingDays,
                              @Param("remark") String remark);

    /**
     * F2: INSERT nx_commission_event (network kind,带 layer_no L1-L7 + order_no + order_amount_usd)。
     * 区别于通用 insertCommissionEvent(layer/order NULL);F2 unilevel 网络佣金专用,满足 PRD F5(network kind 带 layer)。
     * unlock_at = NOW()+coolingDays(PRD line231 coolingDays 默认30;读 commission/cooling-days 配置)。
     */
    @Insert("""
            INSERT INTO nx_commission_event
              (user_id, commission_type, source_user_id, source_user_name,
               layer_no, order_no, order_amount_usd,
               amount_usdt, amount_nex, currency, status, unlock_at, remark)
            VALUES
              (#{userId}, #{commissionType}, #{sourceUserId}, NULL,
               #{layerNo}, #{orderNo}, #{orderAmountUsdt},
               #{amountUsdt}, #{amountNex}, #{currency}, #{status}, DATE_ADD(NOW(), INTERVAL #{coolingDays} DAY), #{remark})
            """)
    int insertNetworkCommissionEvent(@Param("userId") Long userId,
                                     @Param("commissionType") String commissionType,
                                     @Param("sourceUserId") Long sourceUserId,
                                     @Param("layerNo") Integer layerNo,
                                     @Param("orderNo") String orderNo,
                                     @Param("orderAmountUsdt") java.math.BigDecimal orderAmountUsdt,
                                     @Param("currency") String currency,
                                     @Param("amountUsdt") java.math.BigDecimal amountUsdt,
                                     @Param("amountNex") java.math.BigDecimal amountNex,
                                     @Param("status") String status,
                                     @Param("coolingDays") int coolingDays,
                                     @Param("remark") String remark);

    /** F2 幂等:同 orderNo + 上级 userId + network 已派发计数(防订单重复结算)。 */
    @Select("""
            SELECT COUNT(1)
              FROM nx_commission_event
             WHERE order_no = #{orderNo}
               AND user_id = #{userId}
               AND LOWER(commission_type) = 'network'
            """)
    int countNetworkCommissionByOrder(@Param("userId") Long userId,
                                      @Param("orderNo") String orderNo);

    /**
     * F2 InfluenceScore 数据源(PRD line1400/1529):userId 的月度网络团队业绩 —
     * L1-L7 下级子树本月 PAID 订单 subtotal_usdt 之和(不含 userId 自己)。
     *
     * <p>递归 CTE 起点为 userId 的 level=1 直接下级(depth=1),递归步扩展 level=1 父子边,
     * WHERE s.depth < 7 限深 L7(参考 listUplineChain 的递归 + VRankPerformanceMapper.teamVolumeUSD 子树 SUM 范式)。
     * 月份口径取 COALESCE(paid_at, created_at) 的年月 = 当月;PAID/CONFIRMED/SUCCESS 且排除 REFUNDED/CHARGEBACK。
     * 用于 resolveInfluenceScore:clamp(1 + log10(volume/100), clampMin, clampMax) → L2-L7 扩展层佣金乘数。
     */
    @Select("""
            WITH RECURSIVE subtree AS (
                SELECT member_user_id, 1 AS depth
                  FROM nx_team_member
                 WHERE user_id = #{userId}
                   AND level = 1
                   AND is_deleted = 0
                UNION ALL
                SELECT c.member_user_id, s.depth + 1
                  FROM subtree s
                  JOIN nx_team_member c
                    ON c.user_id = s.member_user_id
                   AND c.level = 1
                   AND c.is_deleted = 0
                 WHERE s.depth < 7
            )
            SELECT COALESCE(SUM(o.subtotal_usdt), 0)
              FROM subtree s
              LEFT JOIN nx_order o
                ON o.user_id = s.member_user_id
               AND o.payment_status IN ('PAID', 'CONFIRMED', 'SUCCESS')
               AND o.order_status NOT IN ('REFUNDED', 'CHARGEBACK')
               AND DATE_FORMAT(COALESCE(o.paid_at, o.created_at), '%Y-%m') = DATE_FORMAT(NOW(), '%Y-%m')
               AND o.is_deleted = 0
            """)
    java.math.BigDecimal monthlyNetworkVolume(@Param("userId") Long userId);

    /**
     * F5: 列出 COOLING 且 unlock_at 已到期(unlock_at<=now)的 commission_event,供自动解锁调度器处理。
     * 返回每行 {id, userId, amountUsdt, currency}。限 limit 条(分批)。
     */
    @Select("""
            SELECT id,
                   user_id AS userId,
                   amount_usdt AS amountUsdt,
                   currency
              FROM nx_commission_event
             WHERE UPPER(status) = 'COOLING'
               AND unlock_at IS NOT NULL
               AND unlock_at <= NOW()
               AND is_deleted = 0
             ORDER BY unlock_at ASC
             LIMIT #{limit}
            """)
    List<Map<String, Object>> listCoolingDueForUnlock(@Param("limit") int limit);

    /**
     * F4: V3+ 自循环用户 + 领导池票权(nx_v_rank_config.leadership_votes),供周结算按票权分配池。
     * poolUnlockVRank=V3+(PRD line234);仅 v_rank V3-V12 且 votes>0 的用户参与。
     */
    @Select("""
            SELECT m.user_id AS userId, c.leadership_votes AS votes
              FROM nx_team_member m
              JOIN nx_v_rank_config c ON c.rank_code = m.v_rank AND c.is_deleted = 0
             WHERE m.user_id = m.member_user_id
               AND m.v_rank REGEXP '^V([3-9]|1[0-2])$'
               AND c.leadership_votes > 0
               AND m.is_deleted = 0
             ORDER BY m.user_id ASC
            """)
    List<Map<String, Object>> listV3PlusVoters();

    /**
     * F4: 平台某周(ISO YEARWEEK)已付订单 USDT 小计之和(领导池注入数据源)。
     * @param weekCode YEARWEEK(paid_at,1) 整数(如 202630)
     */
    @Select("""
            SELECT COALESCE(SUM(subtotal_usdt), 0)
              FROM nx_order
             WHERE payment_status IN ('PAID', 'CONFIRMED', 'SUCCESS')
               AND order_status NOT IN ('REFUNDED', 'CHARGEBACK')
               AND paid_at IS NOT NULL
               AND YEARWEEK(paid_at, 1) = #{weekCode}
               AND is_deleted = 0
            """)
    java.math.BigDecimal weeklyPlatformVolume(@Param("weekCode") int weekCode);

    /** F4: 当前 ISO YEARWEEK(NOW,1)。 */
    @Select("SELECT YEARWEEK(NOW(), 1)")
    int currentYearWeek();

    /** F4 幂等:某周(weekKey)领导池是否已结算(commission_type=leadership + remark 含 weekKey)。 */
    @Select("""
            SELECT COUNT(1)
              FROM nx_commission_event
             WHERE LOWER(commission_type) = 'leadership'
               AND remark LIKE CONCAT('%', #{weekKey}, '%')
            """)
    int countLeadershipByWeek(@Param("weekKey") String weekKey);

    /** 取上一条 INSERT 的自增 id(同连接内调用,紧跟 INSERT 后)。 */
    @Select("SELECT LAST_INSERT_ID()")
    Long selectLastInsertId();

    /** INSERT nx_v_rank_reward_payout — 派发流水落库。 */
    @Insert("""
            INSERT INTO nx_v_rank_reward_payout
              (payout_id, user_id, rank_code, reward_type, amount,
               voucher_id, sku_id, custom_label, sponsor_user_id, status,
               commission_event_id, bill_id, trigger_event_id, operator, reason)
            VALUES
              (#{payoutId}, #{userId}, #{rankCode}, #{rewardType}, #{amount},
               #{voucherId}, #{skuId}, #{customLabel}, #{sponsorUserId}, #{status},
               #{commissionEventId}, #{billId}, #{triggerEventId}, #{operator}, #{reason})
            """)
    int insertVRankRewardPayout(@Param("payoutId") String payoutId,
                                @Param("userId") Long userId,
                                @Param("rankCode") String rankCode,
                                @Param("rewardType") String rewardType,
                                @Param("amount") java.math.BigDecimal amount,
                                @Param("voucherId") String voucherId,
                                @Param("skuId") String skuId,
                                @Param("customLabel") String customLabel,
                                @Param("sponsorUserId") Long sponsorUserId,
                                @Param("status") String status,
                                @Param("commissionEventId") Long commissionEventId,
                                @Param("billId") String billId,
                                @Param("triggerEventId") String triggerEventId,
                                @Param("operator") String operator,
                                @Param("reason") String reason);

    // ============================================================
    // F1 V-Rank 晋升流水查询(Sprint 5 端点 3):promotion-log
    // ============================================================

    /**
     * 查 V-Rank 晋升流水(nx_user_level_log WHERE level_type='VRANK' AND is_deleted=0)。
     *
     * <p>LEFT JOIN nx_user 取 nickname;cohort 取 nx_janus_device.cohort_id(最新一条该用户的设备 cohort);
     * reason LIKE '[MANUAL]%' → isManual=true(手动覆盖标记)。ORDER BY created_at DESC LIMIT 100。
     * 用 IF(条件, 1, 0) 兼容 MyBatis Boolean 映射。
     */
    @Select("""
            <script>
            SELECT l.id,
                   l.user_id              AS userId,
                   l.from_code            AS fromCode,
                   l.to_code              AS toCode,
                   l.reason,
                   l.operator,
                   IF(l.reason LIKE '[MANUAL]%', 1, 0) AS isManual,
                   u.nickname,
                   (SELECT d.cohort_id FROM nx_janus_device d
                     WHERE d.user_id = l.user_id AND d.cohort_id IS NOT NULL
                     ORDER BY d.last_seen_at DESC, d.sid DESC LIMIT 1) AS cohort,
                   DATE_FORMAT(l.created_at, '%Y-%m-%d %H:%i:%s') AS createdAt
              FROM nx_user_level_log l
              LEFT JOIN nx_user u ON u.id = l.user_id AND u.is_deleted = 0
             WHERE l.is_deleted = 0
               AND l.level_type = 'VRANK'
               <if test="userId != null">AND l.user_id = #{userId}</if>
               <if test="v != null and v != ''">AND (l.from_code = #{v} OR l.to_code = #{v})</if>
               <if test="cohort != null and cohort != ''">
                 AND EXISTS (
                   SELECT 1 FROM nx_janus_device d
                    WHERE d.user_id = l.user_id
                      AND d.cohort_id LIKE CONCAT('%', #{cohort}, '%')
                 )
               </if>
               <if test="from != null and from != ''">AND l.created_at &gt;= #{from}</if>
               <if test="to != null and to != ''">AND l.created_at &lt;= CONCAT(#{to}, ' 23:59:59')</if>
             ORDER BY l.created_at DESC, l.id DESC
             LIMIT 100
            </script>
            """)
    List<Map<String, Object>> queryPromotionLog(@Param("userId") Long userId,
                                                @Param("v") String v,
                                                @Param("cohort") String cohort,
                                                @Param("from") String from,
                                                @Param("to") String to);

    // ============================================================
    // F1 V-Rank 派发流水查询/补发/撤销(Sprint 6):3 端点
    // ============================================================

    /**
     * 查 nx_v_rank_reward_payout 派发流水(is_deleted=0)。
     * 支持 type/v/status/userId/cursor 筛选,ORDER BY granted_at DESC LIMIT 100。
     * cursor 为 granted_at 上界(分页游标,null=首页)。
     */
    @Select("""
            <script>
            SELECT payout_id        AS payoutId,
                   user_id          AS userId,
                   rank_code        AS rankCode,
                   reward_type      AS rewardType,
                   amount,
                   voucher_id       AS voucherId,
                   sku_id           AS skuId,
                   custom_label     AS customLabel,
                   sponsor_user_id  AS sponsorUserId,
                   status,
                   commission_event_id AS commissionEventId,
                   bill_id          AS billId,
                   trigger_event_id AS triggerEventId,
                   operator,
                   reason,
                   DATE_FORMAT(granted_at, '%Y-%m-%d %H:%i:%s') AS grantedAt,
                   DATE_FORMAT(reversed_at, '%Y-%m-%d %H:%i:%s') AS reversedAt
              FROM nx_v_rank_reward_payout
             WHERE is_deleted = 0
               <if test="type != null and type != ''">AND LOWER(reward_type) = LOWER(#{type})</if>
               <if test="v != null and v != ''">AND rank_code = #{v}</if>
               <if test="status != null and status != ''">AND UPPER(status) = UPPER(#{status})</if>
               <if test="userId != null">AND user_id = #{userId}</if>
               <if test="cursor != null and cursor != ''">AND granted_at &lt; #{cursor}</if>
             ORDER BY granted_at DESC, id DESC
             LIMIT 100
            </script>
            """)
    List<Map<String, Object>> queryRewardPayouts(@Param("type") String type,
                                                  @Param("v") String v,
                                                  @Param("status") String status,
                                                  @Param("userId") Long userId,
                                                  @Param("cursor") String cursor);

    /** 按 payout_id 精确查单条 payout(忽略软删,用于 reissue/reverse 锁定原流水)。 */
    @Select("""
            SELECT payout_id        AS payoutId,
                   user_id          AS userId,
                   rank_code        AS rankCode,
                   reward_type      AS rewardType,
                   amount,
                   voucher_id       AS voucherId,
                   sku_id           AS skuId,
                   custom_label     AS customLabel,
                   sponsor_user_id  AS sponsorUserId,
                   status,
                   commission_event_id AS commissionEventId,
                   bill_id          AS billId,
                   trigger_event_id AS triggerEventId,
                   operator,
                   reason,
                   DATE_FORMAT(granted_at, '%Y-%m-%d %H:%i:%s') AS grantedAt,
                   DATE_FORMAT(reversed_at, '%Y-%m-%d %H:%i:%s') AS reversedAt
              FROM nx_v_rank_reward_payout
             WHERE payout_id = #{payoutId}
             LIMIT 1
            """)
    Map<String, Object> findRewardPayoutByPayoutId(@Param("payoutId") String payoutId);

    /**
     * UPDATE payout 状态 + operator + reason + 时间戳。
     * newStatus=REVERSED 时同时写 reversed_at=NOW();其他状态(granted_at)不动。
     * 也支持 reason 覆盖(reissue/reverse 都需要记录操作原因)。
     */
    @Update("""
            UPDATE nx_v_rank_reward_payout
               SET status = #{newStatus},
                   operator = #{operator},
                   reason = #{reason},
                   reversed_at = CASE WHEN #{newStatus} = 'REVERSED' THEN NOW() ELSE reversed_at END,
                   updated_at = NOW()
             WHERE payout_id = #{payoutId}
               AND is_deleted = 0
            """)
    int updateRewardPayoutStatus(@Param("payoutId") String payoutId,
                                 @Param("newStatus") String newStatus,
                                 @Param("operator") String operator,
                                 @Param("reason") String reason);

    /**
     * 红冲反向:UPDATE nx_commission_event.status='REVERSED'(对应 payout.commission_event_id)。
     * 用于 reverse 端点的 D4 反向冲正(OpsTeamService.postCommissionLedgerIfStatusChanged 范式)。
     */
    @Update("""
            UPDATE nx_commission_event
               SET status = 'REVERSED',
                   updated_at = NOW()
             WHERE id = #{commissionEventId}
               AND is_deleted = 0
            """)
    int reverseCommissionEvent(@Param("commissionEventId") Long commissionEventId);
}
