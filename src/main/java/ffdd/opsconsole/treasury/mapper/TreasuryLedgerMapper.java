package ffdd.opsconsole.treasury.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.treasury.domain.TreasuryLedgerBillView;
import ffdd.opsconsole.treasury.infrastructure.WalletLedgerEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface TreasuryLedgerMapper extends BaseMapper<WalletLedgerEntity> {

    @Insert("""
            INSERT INTO nx_wallet_ledger (
                user_id, biz_no, biz_type, asset, direction, amount, balance_after, status, remark,
                created_at, updated_at, is_deleted
            )
            VALUES (
                #{userId}, #{bizNo}, #{bizType}, #{asset}, #{direction}, #{amount}, #{balanceAfter}, #{status}, #{remark},
                NOW(), NOW(), 0
            )
            ON DUPLICATE KEY UPDATE
                user_id = VALUES(user_id),
                biz_type = VALUES(biz_type),
                asset = VALUES(asset),
                direction = VALUES(direction),
                amount = VALUES(amount),
                balance_after = VALUES(balance_after),
                status = VALUES(status),
                remark = VALUES(remark),
                updated_at = VALUES(updated_at),
                is_deleted = 0
            """)
    int insertLedgerEntry(@Param("bizNo") String bizNo,
                          @Param("userId") Long userId,
                          @Param("bizType") String bizType,
                          @Param("asset") String asset,
                          @Param("direction") String direction,
                          @Param("amount") BigDecimal amount,
                          @Param("balanceAfter") BigDecimal balanceAfter,
                          @Param("status") String status,
                          @Param("remark") String remark);

    @Select("""
            <script>
            SELECT COUNT(*) FROM nx_deposit_order
             WHERE is_deleted = 0 AND created_at &gt;= #{since}
             <if test='status != null and status != ""'>AND status = #{status}</if>
            </script>
            """)
    long countDeposits(@Param("since") LocalDateTime since, @Param("status") String status);

    @Select("""
            <script>
            SELECT COUNT(*) FROM nx_withdrawal_order
             WHERE is_deleted = 0 AND created_at &gt;= #{since}
             <if test='status != null and status != ""'>AND status = #{status}</if>
            </script>
            """)
    long countWithdrawals(@Param("since") LocalDateTime since, @Param("status") String status);

    @Select("""
            <script>
            SELECT COUNT(*) FROM nx_exchange_order
             WHERE is_deleted = 0 AND created_at &gt;= #{since}
             <if test='status != null and status != ""'>AND status = #{status}</if>
            </script>
            """)
    long countExchanges(@Param("since") LocalDateTime since, @Param("status") String status);

    @Select("""
            <script>
            SELECT COUNT(*) FROM nx_wallet_ledger
             WHERE is_deleted = 0 AND created_at &gt;= #{since}
             <if test='direction != null and direction != ""'>AND direction = #{direction}</if>
            </script>
            """)
    long countLedgers(@Param("since") LocalDateTime since, @Param("direction") String direction);

    @Select("SELECT COALESCE(SUM(usdt_available), 0) FROM nx_user_wallet WHERE is_deleted = 0")
    BigDecimal sumUsdtAvailable();

    @Select("SELECT COALESCE(SUM(pending_withdraw), 0) FROM nx_user_wallet WHERE is_deleted = 0")
    BigDecimal sumPendingWithdraw();

    @Select("SELECT COALESCE(SUM(nex_available), 0) FROM nx_user_wallet WHERE is_deleted = 0")
    BigDecimal sumNexAvailable();

    @Select("""
            SELECT COALESCE(SUM(amount_usdt), 0)
              FROM nx_staking_position
             WHERE is_deleted = 0 AND status IN ('ACTIVE', 'LOCKED')
            """)
    BigDecimal sumActiveStakingPrincipalUsdt();

    @Select("""
            SELECT COALESCE(SUM(estimated_interest_usdt), 0)
              FROM nx_staking_position
             WHERE is_deleted = 0 AND status IN ('ACTIVE', 'LOCKED')
            """)
    BigDecimal sumActiveStakingInterestUsdt();

    @Select("""
            SELECT COALESCE(SUM(amount_nex), 0)
              FROM nx_nex_lock_order
             WHERE is_deleted = 0 AND status IN ('ACTIVE', 'LOCKED')
            """)
    BigDecimal sumActiveNexLocked();

    @Select("""
            SELECT COALESCE(SUM(estimated_reward_nex), 0)
              FROM nx_nex_lock_order
             WHERE is_deleted = 0 AND status IN ('ACTIVE', 'LOCKED')
            """)
    BigDecimal sumActiveNexReward();

    @Select("""
            SELECT COALESCE(SUM(amount), 0)
              FROM nx_withdrawal_order
             WHERE is_deleted = 0
               AND asset = 'USDT'
               AND status IN (
                    'SUBMITTED', 'REVIEW_PENDING', 'EXTENDED_HOLD', 'REVIEW_PASSED', 'PROCESSING', 'SENT',
                    'FROZEN', 'REVIEW_REJECTED', 'ADDRESS_INVALID', 'TX_FAILED', 'TX_ORPHANED',
                    'PENDING', 'REVIEWING', 'DELAYED', 'PENDING_CHAIN', 'CHAIN_SUBMITTED', 'DEAD'
               )
            """)
    BigDecimal sumActiveWithdrawalQueueUsdt();

    @Select("""
            SELECT COALESCE(SUM(amount), 0)
              FROM nx_withdrawal_order
             WHERE is_deleted = 0
               AND asset = 'USDT'
               AND created_at >= DATE_SUB(NOW(), INTERVAL 24 HOUR)
            """)
    BigDecimal sumWithdrawalRequested24hUsdt();

    @Select("""
            SELECT COUNT(*)
              FROM nx_withdrawal_order
             WHERE is_deleted = 0
               AND asset = 'USDT'
               AND status IN (
                    'SUBMITTED', 'REVIEW_PENDING', 'EXTENDED_HOLD', 'REVIEW_PASSED', 'PROCESSING', 'SENT',
                    'FROZEN', 'REVIEW_REJECTED', 'ADDRESS_INVALID', 'TX_FAILED', 'TX_ORPHANED',
                    'PENDING', 'REVIEWING', 'DELAYED', 'PENDING_CHAIN', 'CHAIN_SUBMITTED', 'DEAD'
               )
            """)
    long countActiveWithdrawalQueue();

    @Select("""
            SELECT COALESCE(AVG(COALESCE(rd.risk_score, 0)), 0)
              FROM nx_withdrawal_order w
              LEFT JOIN nx_risk_decision rd ON rd.id = w.risk_decision_id AND rd.is_deleted = 0
             WHERE w.is_deleted = 0
               AND w.asset = 'USDT'
               AND w.status IN (
                    'SUBMITTED', 'REVIEW_PENDING', 'EXTENDED_HOLD', 'REVIEW_PASSED', 'PROCESSING', 'SENT',
                    'FROZEN', 'REVIEW_REJECTED', 'ADDRESS_INVALID', 'TX_FAILED', 'TX_ORPHANED',
                    'PENDING', 'REVIEWING', 'DELAYED', 'PENDING_CHAIN', 'CHAIN_SUBMITTED', 'DEAD'
               )
            """)
    BigDecimal avgActiveWithdrawalQueueRiskScore();

    @Select("""
            SELECT COALESCE(SUM(amount), 0)
              FROM nx_wallet_ledger
             WHERE is_deleted = 0
               AND asset = 'USDT'
               AND direction = 'IN'
               AND status = 'PENDING'
               AND biz_type IN ('REFERRAL_COMMISSION', 'COMMISSION', 'TEAM_COMMISSION')
            """)
    BigDecimal sumPendingCommissionUsdt();

    @Select("""
            <script>
            SELECT COALESCE(SUM(CASE WHEN direction = 'IN' THEN amount_usd ELSE -amount_usd END), 0)
              FROM nx_treasury_reserve_ledger
             WHERE is_deleted = 0
               AND status = 'CONFIRMED'
               AND created_at >= #{startAt}
               AND created_at &lt; #{endAt}
            </script>
            """)
    BigDecimal sumNetUsdtFlowBetween(@Param("startAt") LocalDateTime startAt, @Param("endAt") LocalDateTime endAt);

    @Select("""
            SELECT COALESCE(SUM(ABS(COALESCE(w.usdt_available, 0) - COALESCE(latest.balance_after, 0))), 0)
              FROM (
                    SELECT user_id
                      FROM nx_user_wallet
                     WHERE is_deleted = 0
                    UNION
                    SELECT user_id
                      FROM nx_wallet_ledger
                     WHERE is_deleted = 0
                       AND asset = 'USDT'
                       AND status = 'SUCCESS'
              ) users
              LEFT JOIN nx_user_wallet w
                ON w.user_id = users.user_id
               AND w.is_deleted = 0
              LEFT JOIN (
                    SELECT l.user_id, l.balance_after
                      FROM nx_wallet_ledger l
                      JOIN (
                            SELECT user_id, MAX(id) AS latest_id
                              FROM nx_wallet_ledger
                             WHERE is_deleted = 0
                               AND asset = 'USDT'
                               AND status = 'SUCCESS'
                             GROUP BY user_id
                      ) ids ON ids.latest_id = l.id
                     WHERE l.is_deleted = 0
              ) latest ON latest.user_id = users.user_id
            """)
    BigDecimal walletLedgerReconciliationGapUsdt();

    @Select("""
            <script>
            WITH RECURSIVE calendar AS (
                SELECT DATE(#{startAt}) AS day
                UNION ALL
                SELECT DATE_ADD(day, INTERVAL 1 DAY)
                  FROM calendar
                 WHERE DATE_ADD(day, INTERVAL 1 DAY) &lt; DATE(#{endAt})
            ), withdrawals AS (
                SELECT DATE(GREATEST(
                           COALESCE(d2_hold_until, DATE_ADD(created_at, INTERVAL #{withdrawCooldownDays} DAY)),
                           #{startAt}
                       )) AS day,
                       COALESCE(SUM(amount), 0) AS amount
                  FROM nx_withdrawal_order
                 WHERE is_deleted = 0
                   AND asset = 'USDT'
                   AND status IN (
                        'SUBMITTED', 'REVIEW_PENDING', 'EXTENDED_HOLD', 'REVIEW_PASSED', 'PROCESSING', 'SENT',
                        'FROZEN', 'REVIEW_REJECTED', 'ADDRESS_INVALID', 'TX_FAILED', 'TX_ORPHANED',
                        'PENDING', 'REVIEWING', 'DELAYED', 'PENDING_CHAIN', 'CHAIN_SUBMITTED', 'DEAD'
                   )
                   AND COALESCE(d2_hold_until, DATE_ADD(created_at, INTERVAL #{withdrawCooldownDays} DAY)) &lt; #{endAt}
                 GROUP BY DATE(GREATEST(
                           COALESCE(d2_hold_until, DATE_ADD(created_at, INTERVAL #{withdrawCooldownDays} DAY)),
                           #{startAt}
                       ))
            ), staking_interest AS (
                SELECT c.day,
                       COALESCE(SUM(CASE
                           WHEN #{interestMode} = 'AT_MATURITY' AND DATE(s.unlock_at) = c.day
                               THEN s.estimated_interest_usdt
                           WHEN #{interestMode} = 'LINEAR'
                                AND c.day &gt;= DATE(GREATEST(s.locked_at, #{startAt}))
                                AND c.day &lt;= DATE(s.unlock_at)
                               THEN s.estimated_interest_usdt / GREATEST(s.term_days, 1)
                           ELSE 0 END), 0) AS amount
                  FROM calendar c
                  LEFT JOIN nx_staking_position s
                    ON s.is_deleted = 0
                   AND s.status IN ('ACTIVE', 'LOCKED')
                   AND s.unlock_at &gt;= #{startAt}
                   AND s.locked_at &lt; #{endAt}
                 GROUP BY c.day
            )
            SELECT DATE_FORMAT(c.day, '%Y-%m-%d') AS day,
                   COALESCE(w.amount, 0) AS withdrawUsd,
                   COALESCE(si.amount, 0) AS interestUsd
              FROM calendar c
              LEFT JOIN withdrawals w ON w.day = c.day
              LEFT JOIN staking_interest si ON si.day = c.day
             ORDER BY c.day ASC
            </script>
            """)
    List<Map<String, Object>> maturityBuckets(
            @Param("startAt") LocalDateTime startAt,
            @Param("endAt") LocalDateTime endAt,
            @Param("withdrawCooldownDays") int withdrawCooldownDays,
            @Param("interestMode") String interestMode);

    @Select("""
            SELECT DATE_FORMAT(DATE(GREATEST(expires_at, #{startAt})), '%Y-%m-%d') AS day,
                   COALESCE(SUM(daily_usdt * GREATEST(duration_days, 1)), 0) AS amountUsdt
              FROM nx_trial_claim
             WHERE is_deleted = 0
               AND UPPER(status) NOT IN ('REDEEMED', 'CANCELLED', 'FAILED')
               AND expires_at < #{endAt}
             GROUP BY DATE_FORMAT(DATE(GREATEST(expires_at, #{startAt})), '%Y-%m-%d')
             ORDER BY day ASC
            """)
    List<Map<String, Object>> trialStressBuckets(
            @Param("startAt") LocalDateTime startAt,
            @Param("endAt") LocalDateTime endAt);

    @Select("""
            SELECT avgScore AS value
              FROM (
                    SELECT DATE(created_at) AS riskDay,
                           COALESCE(AVG(risk_score), 0) AS avgScore
                      FROM nx_risk_decision
                     WHERE is_deleted = 0
                       AND created_at >= #{since}
                     GROUP BY DATE(created_at)
              ) buckets
             ORDER BY riskDay ASC
            """)
    List<BigDecimal> riskPressureSeries(@Param("since") LocalDateTime since);

    @Select("""
            SELECT COALESCE(NULLIF(SUBSTRING_INDEX(rule_codes, ',', 1), ''), decision) AS nm,
                   COUNT(1) AS ct,
                   CASE
                     WHEN MAX(risk_score) >= 90 THEN 'P0'
                     WHEN MAX(risk_score) >= 70 THEN 'P1'
                     WHEN MAX(risk_score) >= 40 THEN 'P2'
                     ELSE 'P3'
                   END AS sev,
                   COALESCE(NULLIF(biz_type, ''), 'risk') AS dom,
                   'nx_risk_decision' AS source
              FROM nx_risk_decision
             WHERE is_deleted = 0
               AND created_at >= #{since}
             GROUP BY COALESCE(NULLIF(SUBSTRING_INDEX(rule_codes, ',', 1), ''), decision),
                      COALESCE(NULLIF(biz_type, ''), 'risk')
             ORDER BY ct DESC, nm ASC
             LIMIT 10
            """)
    List<Map<String, Object>> riskRuleBuckets(@Param("since") LocalDateTime since);

    @Select("""
            SELECT sev AS nm,
                   COUNT(1) AS v,
                   CASE sev
                     WHEN 'P0' THEN 'var(--danger)'
                     WHEN 'P1' THEN 'var(--warning)'
                     WHEN 'P2' THEN 'var(--cyan)'
                     ELSE 'var(--muted)'
                   END AS c
              FROM (
                    SELECT CASE
                             WHEN risk_score >= 90 THEN 'P0'
                             WHEN risk_score >= 70 THEN 'P1'
                             WHEN risk_score >= 40 THEN 'P2'
                             ELSE 'P3'
                           END AS sev
                      FROM nx_risk_decision
                     WHERE is_deleted = 0
                       AND created_at >= #{since}
              ) buckets
             GROUP BY sev
             ORDER BY FIELD(sev, 'P0', 'P1', 'P2', 'P3')
            """)
    List<Map<String, Object>> riskSeverityBuckets(@Param("since") LocalDateTime since);

    @Select("""
            SELECT DATE_FORMAT(riskDay, '%m-%d') AS label,
                   count
              FROM (
                    SELECT DATE(created_at) AS riskDay,
                           COUNT(1) AS count
                      FROM nx_risk_decision
                     WHERE is_deleted = 0
                       AND created_at >= #{since}
                     GROUP BY DATE(created_at)
              ) buckets
             ORDER BY riskDay ASC
            """)
    List<Map<String, Object>> riskVolumeBuckets(@Param("since") LocalDateTime since);

    @Select("""
            SELECT CONCAT('k4-v',m.model_version) AS modelVersion,
                   m.band_low_max AS bandLowMax,
                   m.band_high_min AS bandHighMin,
                   m.auto_escalate_score AS autoEscalateScore,
                   COUNT(s.id) AS totalUsers,
                   COALESCE(SUM(CASE WHEN COALESCE(o.override_score,s.model_score) >= m.auto_escalate_score THEN 1 ELSE 0 END),0) AS autoEscalated,
                   COALESCE(SUM(CASE WHEN COALESCE(o.override_score,s.model_score) >= m.band_high_min THEN 1 ELSE 0 END),0) AS highRisk,
                   COALESCE(SUM(CASE WHEN COALESCE(o.override_score,s.model_score) >= m.band_low_max
                                      AND COALESCE(o.override_score,s.model_score) < m.band_high_min THEN 1 ELSE 0 END),0) AS mediumRisk,
                   COALESCE(SUM(CASE WHEN COALESCE(o.override_score,s.model_score) < m.band_low_max THEN 1 ELSE 0 END),0) AS lowRisk,
                   COALESCE(SUM(CASE WHEN COALESCE(o.override_score,s.model_score) >= m.band_low_max THEN 1 ELSE 0 END),0) AS flaggedAccounts,
                   COALESCE(SUM(CASE WHEN o.id IS NOT NULL THEN 1 ELSE 0 END),0) AS activeOverrides,
                   (SELECT COUNT(*)
                      FROM nx_admin_risk_score_user stale
                      WHERE stale.is_deleted=0
                        AND (stale.model_version<>CONCAT('k4-v',m.model_version)
                             OR stale.as_of IS NULL
                             OR stale.as_of < DATE_SUB(NOW(), INTERVAL 1 DAY))) AS staleScoreUsers
               FROM (
                    SELECT model_version,band_low_max,band_high_min,auto_escalate_score
                      FROM nx_admin_risk_score_model
                     WHERE state='active' AND is_deleted=0
                     ORDER BY model_version DESC
                     LIMIT 1
              ) m
              LEFT JOIN nx_admin_risk_score_user s
                ON s.is_deleted=0
               AND s.model_version=CONCAT('k4-v',m.model_version)
               AND s.as_of >= DATE_SUB(NOW(), INTERVAL 1 DAY)
              LEFT JOIN nx_admin_risk_score_override o
                ON o.user_no=s.user_no AND o.active=1 AND o.is_deleted=0
             GROUP BY m.model_version,m.band_low_max,m.band_high_min,m.auto_escalate_score
            """)
    Map<String, Object> currentK4RiskScoreSnapshot();

    @Select("""
            SELECT event_key AS eventKey,
                   tone,
                   title,
                   body,
                   DATE_FORMAT(created_at,'%Y-%m-%d %H:%i') AS timeText,
                   is_deleted AS isDeleted
              FROM nx_admin_risk_kyc_alert
             WHERE is_deleted=0
               AND created_at>=#{since}
               AND (event_key LIKE 'threshold-hit:%'
                    OR event_key LIKE 'sla-breach:%'
                    OR event_key LIKE 'large-withdraw-burst:%')
             ORDER BY created_at DESC,id DESC
             LIMIT #{limit}
            """)
    List<Map<String, Object>> recentK5KycAlerts(
            @Param("since") LocalDateTime since, @Param("limit") int limit);

    @Select("""
            SELECT COALESCE(SUM(CASE WHEN direction = 'IN' THEN amount_usd ELSE -amount_usd END), 0)
              FROM nx_treasury_reserve_ledger
             WHERE is_deleted = 0
               AND status = 'CONFIRMED'
            """)
    BigDecimal currentReserveUsd();

    @Select("""
            SELECT COALESCE(SUM(amount_usd), 0)
              FROM nx_treasury_reserve_ledger
             WHERE is_deleted = 0
               AND status = 'CONFIRMED'
               AND direction = 'IN'
               AND reserve_no LIKE 'RSV-D3-%'
            """)
    BigDecimal injectedCumulativeUsd();

    @Select("""
            SELECT COALESCE(SUM(h.acquired_price_usdt * s.daily_dividend_rate_pct / 100), 0)
              FROM nx_genesis_holding h
              JOIN nx_genesis_series s
                ON s.series_code = h.series_code
               AND s.is_deleted = 0
             WHERE h.is_deleted = 0
               AND UPPER(h.status) IN ('ACTIVE', 'HELD', 'LISTED')
            """)
    BigDecimal genesisDailyLiabilityUsd();

    @Select("""
            SELECT COALESCE(SUM(principal_usdt + accrued_interest_usdt), 0)
              FROM nx_treasury_legacy_lock_liability
             WHERE is_deleted = 0
               AND status IN ('ACTIVE', 'LOCKED')
            """)
    BigDecimal legacyLockOtherLiabilityUsd();

    @Select("""
            SELECT COUNT(1)
              FROM nx_treasury_reserve_ledger
             WHERE is_deleted = 0 AND voucher_no = #{voucherNo}
            """)
    long countReserveVoucher(@Param("voucherNo") String voucherNo);

    @Select("""
            SELECT price_usdt
              FROM nx_price_index
             WHERE is_deleted = 0
               AND status = 'ACTIVE'
               AND metric_code IN ('NEX', 'NEX_USDT')
             ORDER BY sampled_at DESC, id DESC
             LIMIT 1
            """)
    BigDecimal latestNexUsdtPrice();

    @Insert("""
            INSERT INTO nx_treasury_reserve_ledger (
                reserve_no, voucher_no, direction, amount_usd, reason, operator,
                idempotency_key, status, created_at, updated_at, is_deleted
            )
            VALUES (
                #{reserveNo}, #{voucherNo}, 'IN', #{amountUsd}, #{reason}, #{operator},
                #{idempotencyKey}, 'CONFIRMED', NOW(), NOW(), 0
            )
            """)
    int insertReserveInjection(
            @Param("reserveNo") String reserveNo,
            @Param("voucherNo") String voucherNo,
            @Param("amountUsd") BigDecimal amountUsd,
            @Param("reason") String reason,
            @Param("operator") String operator,
            @Param("idempotencyKey") String idempotencyKey);

    @Insert("""
            INSERT INTO nx_treasury_reserve_ledger (
                reserve_no, voucher_no, direction, amount_usd, reason, operator,
                idempotency_key, status, created_at, updated_at, is_deleted
            ) VALUES (
                #{reserveNo}, #{voucherNo}, #{direction}, #{amountUsd}, #{reason}, #{operator},
                #{idempotencyKey}, 'CONFIRMED', NOW(), NOW(), 0
            )
            """)
    int insertTopupReserveEntry(
            @Param("reserveNo") String reserveNo,
            @Param("voucherNo") String voucherNo,
            @Param("direction") String direction,
            @Param("amountUsd") BigDecimal amountUsd,
            @Param("reason") String reason,
            @Param("operator") String operator,
            @Param("idempotencyKey") String idempotencyKey);

    @org.apache.ibatis.annotations.Update("""
            UPDATE nx_user_wallet
               SET usdt_available = usdt_available + #{amount},
                   pending_withdraw = pending_withdraw - #{amount},
                   version = version + 1,
                   updated_at = NOW()
             WHERE user_id = #{userId}
               AND is_deleted = 0
               AND pending_withdraw >= #{amount}
            """)
    int releasePendingWithdrawal(@Param("userId") Long userId, @Param("amount") BigDecimal amount);

    @org.apache.ibatis.annotations.Update("""
            UPDATE nx_user_wallet
               SET usdt_available = usdt_available + #{amount},
                   nex_available = nex_available + #{nexBurned},
                   pending_withdraw = pending_withdraw - #{amount},
                   version = version + 1,
                   updated_at = NOW()
             WHERE user_id = #{userId}
               AND is_deleted = 0
               AND pending_withdraw >= #{amount}
            """)
    int releasePendingWithdrawalWithNex(
            @Param("userId") Long userId,
            @Param("amount") BigDecimal amount,
            @Param("nexBurned") BigDecimal nexBurned);

    @Select("""
            <script>
            SELECT COUNT(1)
              FROM nx_wallet_ledger l
              LEFT JOIN nx_user u ON u.id = l.user_id AND u.is_deleted = 0
             WHERE l.is_deleted = 0
             <if test='type != null and type != ""'>
               <choose>
                 <when test="type == 'swap' or type == 'topup' or type == 'withdraw' or type == 'earning' or type == 'commission' or type == 'refund' or type == 'bonus'">
                   AND (CASE
                     WHEN UPPER(l.biz_type) LIKE '%BONUS%' OR UPPER(l.biz_type) LIKE '%TRIAL%' THEN 'bonus'
                     WHEN UPPER(l.biz_type) LIKE '%TOPUP%' OR UPPER(l.biz_type) LIKE '%DEPOSIT%' OR UPPER(l.biz_type) LIKE '%RECHARGE%' THEN 'topup'
                     WHEN UPPER(l.biz_type) LIKE '%WITHDRAW%' OR UPPER(l.biz_type) LIKE '%PAYOUT%' THEN 'withdraw'
                     WHEN UPPER(l.biz_type) LIKE '%COMMISSION%' THEN 'commission'
                     WHEN UPPER(l.biz_type) LIKE '%REFUND%' OR UPPER(l.biz_type) LIKE '%CHARGEBACK%' OR UPPER(l.biz_type) LIKE '%REVERSAL%' THEN 'refund'
                     WHEN UPPER(l.biz_type) LIKE '%SWAP%' OR UPPER(l.biz_type) LIKE '%EXCHANGE%' OR UPPER(l.biz_type) LIKE '%CONVERT%' THEN 'swap'
                     ELSE 'earning'
                   END) = #{type}
                 </when>
                 <otherwise>AND UPPER(l.biz_type) = UPPER(#{type})</otherwise>
               </choose>
             </if>
             <if test='userId != null'>AND l.user_id = #{userId}</if>
             <if test='bizNo != null and bizNo != ""'>AND l.biz_no = #{bizNo}</if>
             <if test='status != null and status != ""'>AND UPPER(l.status) = #{status}</if>
             <if test='from != null'>AND l.created_at &gt;= #{from}</if>
             <if test='to != null'>AND l.created_at &lt; #{to}</if>
             <if test='keyword != null and keyword != ""'>
               AND (l.biz_no LIKE CONCAT('%', #{keyword}, '%')
                    OR l.remark LIKE CONCAT('%', #{keyword}, '%')
                    OR CONCAT('U', LPAD(l.user_id, 8, '0')) LIKE CONCAT('%', #{keyword}, '%')
                    OR u.nickname LIKE CONCAT('%', #{keyword}, '%'))
             </if>
            </script>
            """)
    long countLedgerBills(@Param("type") String type, @Param("userId") Long userId,
                          @Param("keyword") String keyword, @Param("bizNo") String bizNo,
                          @Param("status") String status, @Param("from") java.time.LocalDateTime from,
                          @Param("to") java.time.LocalDateTime to);

    @Select("""
            <script>
            SELECT l.id,
                   l.user_id AS userId,
                   CONCAT('U', LPAD(l.user_id, 8, '0')) AS userNo,
                   u.nickname AS nickname,
                   l.biz_no AS bizNo,
                   l.biz_type AS bizType,
                   l.asset,
                   l.direction,
                   l.amount,
                   l.balance_after AS balanceAfter,
                   l.status,
                   l.remark,
                   l.created_at AS createdAt,
                   l.updated_at AS updatedAt
              FROM nx_wallet_ledger l
              LEFT JOIN nx_user u ON u.id = l.user_id AND u.is_deleted = 0
             WHERE l.is_deleted = 0
             <if test='type != null and type != ""'>
               <choose>
                 <when test="type == 'swap' or type == 'topup' or type == 'withdraw' or type == 'earning' or type == 'commission' or type == 'refund' or type == 'bonus'">
                   AND (CASE
                     WHEN UPPER(l.biz_type) LIKE '%BONUS%' OR UPPER(l.biz_type) LIKE '%TRIAL%' THEN 'bonus'
                     WHEN UPPER(l.biz_type) LIKE '%TOPUP%' OR UPPER(l.biz_type) LIKE '%DEPOSIT%' OR UPPER(l.biz_type) LIKE '%RECHARGE%' THEN 'topup'
                     WHEN UPPER(l.biz_type) LIKE '%WITHDRAW%' OR UPPER(l.biz_type) LIKE '%PAYOUT%' THEN 'withdraw'
                     WHEN UPPER(l.biz_type) LIKE '%COMMISSION%' THEN 'commission'
                     WHEN UPPER(l.biz_type) LIKE '%REFUND%' OR UPPER(l.biz_type) LIKE '%CHARGEBACK%' OR UPPER(l.biz_type) LIKE '%REVERSAL%' THEN 'refund'
                     WHEN UPPER(l.biz_type) LIKE '%SWAP%' OR UPPER(l.biz_type) LIKE '%EXCHANGE%' OR UPPER(l.biz_type) LIKE '%CONVERT%' THEN 'swap'
                     ELSE 'earning'
                   END) = #{type}
                 </when>
                 <otherwise>AND UPPER(l.biz_type) = UPPER(#{type})</otherwise>
               </choose>
             </if>
             <if test='userId != null'>AND l.user_id = #{userId}</if>
             <if test='bizNo != null and bizNo != ""'>AND l.biz_no = #{bizNo}</if>
             <if test='status != null and status != ""'>AND UPPER(l.status) = #{status}</if>
             <if test='from != null'>AND l.created_at &gt;= #{from}</if>
             <if test='to != null'>AND l.created_at &lt; #{to}</if>
             <if test='keyword != null and keyword != ""'>
               AND (l.biz_no LIKE CONCAT('%', #{keyword}, '%')
                    OR l.remark LIKE CONCAT('%', #{keyword}, '%')
                    OR CONCAT('U', LPAD(l.user_id, 8, '0')) LIKE CONCAT('%', #{keyword}, '%')
                    OR u.nickname LIKE CONCAT('%', #{keyword}, '%'))
             </if>
             ORDER BY l.created_at DESC, l.id DESC
             LIMIT #{pageSize} OFFSET #{offset}
            </script>
            """)
    List<TreasuryLedgerBillView> pageLedgerBills(@Param("type") String type, @Param("userId") Long userId,
                                                 @Param("keyword") String keyword, @Param("bizNo") String bizNo,
                                                 @Param("status") String status,
                                                 @Param("from") java.time.LocalDateTime from,
                                                 @Param("to") java.time.LocalDateTime to,
                                                 @Param("pageSize") int pageSize, @Param("offset") int offset);

    @Select("""
            SELECT l.id,
                   l.user_id AS userId,
                   CONCAT('U', LPAD(l.user_id, 8, '0')) AS userNo,
                   u.nickname AS nickname,
                   l.biz_no AS bizNo,
                   l.biz_type AS bizType,
                   l.asset,
                   l.direction,
                   l.amount,
                   l.balance_after AS balanceAfter,
                   l.status,
                   l.remark,
                   l.created_at AS createdAt,
                   l.updated_at AS updatedAt
              FROM nx_wallet_ledger l
              LEFT JOIN nx_user u ON u.id = l.user_id AND u.is_deleted = 0
             WHERE l.is_deleted = 0 AND l.user_id = #{userId}
             ORDER BY l.created_at DESC, l.id DESC
             LIMIT #{limit}
            """)
    List<TreasuryLedgerBillView> userLedgerRows(@Param("userId") Long userId, @Param("limit") int limit);

    @Select("""
            SELECT balance_after
              FROM nx_wallet_ledger
             WHERE is_deleted = 0 AND user_id = #{userId} AND asset = #{asset}
             ORDER BY created_at DESC, id DESC
             LIMIT 1
            """)
    BigDecimal currentUserBalance(@Param("userId") Long userId, @Param("asset") String asset);

    @Select("""
            SELECT CASE UPPER(#{asset})
                     WHEN 'USDT' THEN usdt_available
                     WHEN 'NEX' THEN nex_available
                     ELSE NULL
                   END
              FROM nx_user_wallet
             WHERE is_deleted = 0 AND user_id = #{userId}
             LIMIT 1
            """)
    BigDecimal actualUserBalance(@Param("userId") Long userId, @Param("asset") String asset);

    @Insert("""
            INSERT IGNORE INTO nx_admin_operation_mutex(lock_key, updated_at)
            VALUES(#{lockKey}, NOW())
            """)
    int ensureLedgerMutex(@Param("lockKey") String lockKey);

    @Select("SELECT lock_key FROM nx_admin_operation_mutex WHERE lock_key=#{lockKey} FOR UPDATE")
    String lockLedgerMutex(@Param("lockKey") String lockKey);

}
