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
                   COALESCE(a.reason, '本期 GV') AS tip,
                   CASE WHEN a.action_type IS NULL THEN CONCAT('r-', ranked.rank_no) ELSE CONCAT('r-', ranked.rank_no, ' dq') END AS className
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
                     SELECT COUNT(1)
                       FROM nx_team_leaderboard_action
                      WHERE is_deleted = 0
                        AND period = 'week'
                        AND UPPER(action_type) IN ('FRAUD', 'DISQUALIFIED', 'RISK')
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
                       SELECT COUNT(1)
                         FROM nx_team_leaderboard_action
                        WHERE is_deleted = 0
                          AND period = 'week'
                          AND UPPER(action_type) IN ('FRAUD', 'DISQUALIFIED', 'RISK')
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
}
