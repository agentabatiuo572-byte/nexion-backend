package ffdd.opsconsole.growth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface ReferralRewardMapper extends BaseMapper<Object> {
    @Select("""
            SELECT u.referral_code AS referralCode, COALESCE(u.sandbox, 0) AS sandbox,
                   COALESCE(w.nex_available, 0) AS walletNexAvailable
              FROM nx_user u
              JOIN nx_user_wallet w ON w.user_id = u.id AND w.is_deleted = 0
               AND COALESCE(w.sandbox, 0) = COALESCE(u.sandbox, 0)
             WHERE u.id = #{userId} AND u.is_deleted = 0 AND u.status = 'ACTIVE'
             LIMIT 1
            """)
    AppReferralAccount appReferralAccount(@Param("userId") Long userId);

    @Select("""
            SELECT COUNT(*) FROM nx_user u
             WHERE u.id=#{userId}
               AND REPLACE(TRIM(COALESCE(u.country_code,'')),'+','')=REPLACE(#{countryCode},'+','')
               AND u.phone=#{phone} AND u.sandbox=1
               AND u.status='ACTIVE' AND u.is_deleted=0
            """)
    int developmentUserScope(@Param("userId") Long userId,
                             @Param("countryCode") String countryCode,
                             @Param("phone") String phone);

    @Select("""
            SELECT COUNT(*)
             FROM nx_user invited
             JOIN nx_user owner ON owner.id = #{userId} AND owner.is_deleted = 0 AND owner.status = 'ACTIVE'
             WHERE invited.sponsor_user_id = #{userId}
               AND invited.id <> #{userId}
               AND invited.is_deleted = 0 AND invited.status = 'ACTIVE'
               AND invited.created_at >= #{effectiveAt}
               AND #{sourceEnvironment} = 'PRODUCTION'
               AND COALESCE(owner.sandbox, 0) = #{accountSandbox}
               AND COALESCE(invited.sandbox, 0) = #{accountSandbox}
            """)
    long appInvitedCount(@Param("userId") Long userId,
                         @Param("effectiveAt") LocalDateTime effectiveAt,
                         @Param("sourceEnvironment") String sourceEnvironment,
                         @Param("accountSandbox") Integer accountSandbox);

    @Select("""
            SELECT COUNT(*)
              FROM nx_user invited
              JOIN nx_user owner ON owner.id = #{userId} AND owner.is_deleted = 0 AND owner.status = 'ACTIVE'
              LEFT JOIN nx_referral_reward_settlement s
                ON s.invited_user_id = invited.id AND s.is_deleted = 0
             WHERE invited.sponsor_user_id = #{userId} AND invited.id <> #{userId}
               AND invited.is_deleted = 0 AND invited.status = 'ACTIVE'
               AND invited.created_at >= #{effectiveAt} AND s.id IS NULL
               AND #{sourceEnvironment} = 'PRODUCTION'
               AND COALESCE(owner.sandbox, 0) = #{accountSandbox}
               AND COALESCE(invited.sandbox, 0) = #{accountSandbox}
            """)
    long appPendingCount(@Param("userId") Long userId,
                         @Param("effectiveAt") LocalDateTime effectiveAt,
                         @Param("sourceEnvironment") String sourceEnvironment,
                         @Param("accountSandbox") Integer accountSandbox);

    @Select("""
            SELECT COUNT(*)
              FROM nx_referral_reward_settlement s
              JOIN nx_user invited ON invited.id = s.invited_user_id
              JOIN nx_user inviter ON inviter.id = s.inviter_user_id
             WHERE s.inviter_user_id = #{userId} AND s.is_deleted = 0
               AND s.status = 'SETTLED' AND s.inviter_nex > 0
               AND #{sourceEnvironment} = 'PRODUCTION'
               AND COALESCE(invited.sandbox, 0) = #{accountSandbox}
               AND COALESCE(inviter.sandbox, 0) = #{accountSandbox}
            """)
    long appPositiveSettlementCount(@Param("userId") Long userId,
                                    @Param("sourceEnvironment") String sourceEnvironment,
                                    @Param("accountSandbox") Integer accountSandbox);

    @Select("""
            SELECT COUNT(*)
              FROM nx_referral_reward_settlement s
              JOIN nx_user invited ON invited.id = s.invited_user_id
              JOIN nx_user inviter ON inviter.id = s.inviter_user_id
             WHERE s.inviter_user_id = #{userId} AND s.is_deleted = 0
               AND s.status = 'SETTLED'
               AND #{sourceEnvironment} = 'PRODUCTION'
               AND COALESCE(invited.sandbox, 0) = #{accountSandbox}
               AND COALESCE(inviter.sandbox, 0) = #{accountSandbox}
            """)
    long appSettlementCount(@Param("userId") Long userId,
                            @Param("sourceEnvironment") String sourceEnvironment,
                            @Param("accountSandbox") Integer accountSandbox);

    @Select("""
            SELECT COUNT(*) AS settledCount, COALESCE(SUM(s.inviter_nex), 0) AS lifetimeInviterNex
              FROM nx_referral_reward_settlement s
              JOIN nx_user invited ON invited.id = s.invited_user_id
              JOIN nx_user inviter ON inviter.id = s.inviter_user_id
               JOIN nx_wallet_ledger production_ledger
                ON production_ledger.user_id = #{userId} AND production_ledger.biz_no = CONCAT(s.settlement_no, ':INVITER')
               AND production_ledger.biz_type = 'REFERRAL_REWARD' AND production_ledger.asset = 'NEX'
               AND production_ledger.direction = 'IN' AND production_ledger.status = 'SUCCESS'
               AND production_ledger.amount = s.inviter_nex AND production_ledger.is_deleted = 0
               AND #{sourceEnvironment} = 'PRODUCTION'
              JOIN nx_earnings_release_entry e
                ON e.user_id = #{userId} AND e.source_ref = CONCAT(s.settlement_no, ':INVITER:NEX')
               AND e.asset = 'NEX' AND e.amount = s.inviter_nex AND e.status = 'ACTIVE'
               AND e.source_type = #{sourceType} AND e.source_environment = #{sourceEnvironment}
               AND e.is_deleted = 0
             WHERE s.inviter_user_id = #{userId} AND s.is_deleted = 0
               AND s.status = 'SETTLED' AND s.inviter_nex > 0
               AND #{sourceEnvironment} = 'PRODUCTION'
               AND COALESCE(invited.sandbox, 0) = #{accountSandbox}
               AND COALESCE(inviter.sandbox, 0) = #{accountSandbox}
            """)
    AppReferralLedgerSummary appVerifiedRewardSummary(
            @Param("userId") Long userId,
            @Param("sourceType") String sourceType,
            @Param("sourceEnvironment") String sourceEnvironment,
            @Param("accountSandbox") Integer accountSandbox);

    @Select("""
            SELECT s.settlement_no AS settlementNo, s.inviter_nex AS amountNex,
                   production_ledger.status AS ledgerStatus,
                   production_ledger.balance_after AS balanceAfter,
                   e.bucket AS releaseBucket, e.source_environment AS sourceEnvironment,
                   s.created_at AS settledAt
              FROM nx_referral_reward_settlement s
              JOIN nx_user invited ON invited.id = s.invited_user_id
              JOIN nx_user inviter ON inviter.id = s.inviter_user_id
               JOIN nx_wallet_ledger production_ledger
                ON production_ledger.user_id = #{userId} AND production_ledger.biz_no = CONCAT(s.settlement_no, ':INVITER')
               AND production_ledger.biz_type = 'REFERRAL_REWARD' AND production_ledger.asset = 'NEX'
               AND production_ledger.direction = 'IN' AND production_ledger.status = 'SUCCESS'
               AND production_ledger.amount = s.inviter_nex AND production_ledger.is_deleted = 0
               AND #{sourceEnvironment} = 'PRODUCTION'
              JOIN nx_earnings_release_entry e
                ON e.user_id = #{userId} AND e.source_ref = CONCAT(s.settlement_no, ':INVITER:NEX')
               AND e.asset = 'NEX' AND e.amount = s.inviter_nex AND e.status = 'ACTIVE'
               AND e.source_type = #{sourceType} AND e.source_environment = #{sourceEnvironment}
               AND e.is_deleted = 0
             WHERE s.inviter_user_id = #{userId} AND s.is_deleted = 0
               AND s.status = 'SETTLED' AND s.inviter_nex > 0
               AND #{sourceEnvironment} = 'PRODUCTION'
               AND COALESCE(invited.sandbox, 0) = #{accountSandbox}
               AND COALESCE(inviter.sandbox, 0) = #{accountSandbox}
             ORDER BY s.id DESC
             LIMIT #{limit}
            """)
    List<AppReferralLedgerRow> appRecentVerifiedRewards(
            @Param("userId") Long userId,
            @Param("sourceType") String sourceType,
            @Param("sourceEnvironment") String sourceEnvironment,
            @Param("accountSandbox") Integer accountSandbox,
            @Param("limit") int limit);

    @Select("""
            SELECT COUNT(*)
              FROM nx_user invited
              JOIN nx_user owner ON owner.id = #{userId} AND owner.is_deleted = 0 AND owner.status = 'ACTIVE'
              LEFT JOIN nx_h8_sandbox_referral_settlement s
                ON s.invited_user_id = invited.id AND s.run_id = #{runId} AND s.is_deleted = 0
             WHERE invited.sponsor_user_id = #{userId} AND invited.id <> #{userId}
               AND invited.is_deleted = 0 AND invited.status = 'ACTIVE'
               AND invited.created_at >= #{effectiveAt}
               AND COALESCE(owner.sandbox, 0) = 1 AND COALESCE(invited.sandbox, 0) = 1
            """)
    long appSandboxInvitedCount(@Param("userId") Long userId,
                                @Param("effectiveAt") LocalDateTime effectiveAt,
                                @Param("runId") String runId);

    @Select("""
            SELECT COUNT(*)
              FROM nx_user invited
              JOIN nx_user owner ON owner.id = #{userId} AND owner.is_deleted = 0 AND owner.status = 'ACTIVE'
              LEFT JOIN nx_h8_sandbox_referral_settlement s
                ON s.invited_user_id = invited.id AND s.run_id = #{runId} AND s.is_deleted = 0
             WHERE invited.sponsor_user_id = #{userId} AND invited.id <> #{userId}
               AND invited.is_deleted = 0 AND invited.status = 'ACTIVE'
               AND invited.created_at >= #{effectiveAt} AND s.id IS NULL
               AND COALESCE(owner.sandbox, 0) = 1 AND COALESCE(invited.sandbox, 0) = 1
            """)
    long appSandboxPendingCount(@Param("userId") Long userId,
                                @Param("effectiveAt") LocalDateTime effectiveAt,
                                @Param("runId") String runId);

    @Select("""
            SELECT COUNT(*)
              FROM nx_h8_sandbox_referral_settlement s
              JOIN nx_user invited ON invited.id = s.invited_user_id
              JOIN nx_user inviter ON inviter.id = s.inviter_user_id
             WHERE s.inviter_user_id = #{userId} AND s.is_deleted = 0
               AND s.run_id = #{runId}
               AND s.status = 'SETTLED' AND s.inviter_nex > 0
               AND s.source = 'mock' AND s.source_environment = 'SANDBOX'
               AND COALESCE(invited.sandbox, 0) = 1 AND COALESCE(inviter.sandbox, 0) = 1
            """)
    long appSandboxPositiveSettlementCount(@Param("userId") Long userId,
                                           @Param("runId") String runId);

    @Select("""
            SELECT COUNT(*)
              FROM nx_h8_sandbox_referral_settlement s
              JOIN nx_user invited ON invited.id = s.invited_user_id
              JOIN nx_user inviter ON inviter.id = s.inviter_user_id
             WHERE s.inviter_user_id = #{userId} AND s.is_deleted = 0
               AND s.run_id = #{runId}
               AND s.status = 'SETTLED'
               AND s.source = 'mock' AND s.source_environment = 'SANDBOX'
               AND COALESCE(invited.sandbox, 0) = 1 AND COALESCE(inviter.sandbox, 0) = 1
            """)
    long appSandboxSettlementCount(@Param("userId") Long userId,
                                   @Param("runId") String runId);

    @Select("""
            SELECT COUNT(*) AS settledCount, COALESCE(SUM(s.inviter_nex), 0) AS lifetimeInviterNex
              FROM nx_h8_sandbox_referral_settlement s
              JOIN nx_user invited ON invited.id = s.invited_user_id
              JOIN nx_user inviter ON inviter.id = s.inviter_user_id
              JOIN nx_h8_sandbox_referral_ledger sandbox_ledger
                ON sandbox_ledger.user_id = #{userId} AND sandbox_ledger.settlement_no = s.settlement_no
               AND sandbox_ledger.run_id = #{runId}
               AND sandbox_ledger.asset = 'NEX' AND sandbox_ledger.status = 'SUCCESS'
               AND sandbox_ledger.amount = s.inviter_nex AND sandbox_ledger.is_deleted = 0
               AND sandbox_ledger.source = 'mock' AND sandbox_ledger.source_environment = 'SANDBOX'
             WHERE s.inviter_user_id = #{userId} AND s.is_deleted = 0
               AND s.run_id = #{runId}
               AND s.status = 'SETTLED' AND s.inviter_nex > 0
               AND s.source = 'mock' AND s.source_environment = 'SANDBOX'
               AND COALESCE(invited.sandbox, 0) = 1 AND COALESCE(inviter.sandbox, 0) = 1
            """)
    AppReferralLedgerSummary appVerifiedSandboxRewardSummary(@Param("userId") Long userId,
                                                             @Param("runId") String runId);

    @Select("""
            SELECT s.settlement_no AS settlementNo, s.inviter_nex AS amountNex,
                   sandbox_ledger.status AS ledgerStatus,
                   SUM(sandbox_ledger.amount) OVER (
                     PARTITION BY sandbox_ledger.user_id, sandbox_ledger.run_id
                     ORDER BY sandbox_ledger.id ASC ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
                   ) AS balanceAfter,
                   CASE WHEN s.lock_mode = 'direct' THEN 'withdrawable' ELSE 'bonus_locked' END AS releaseBucket,
                   s.source_environment AS sourceEnvironment, s.created_at AS settledAt
              FROM nx_h8_sandbox_referral_settlement s
              JOIN nx_user invited ON invited.id = s.invited_user_id
              JOIN nx_user inviter ON inviter.id = s.inviter_user_id
              JOIN nx_h8_sandbox_referral_ledger sandbox_ledger
                ON sandbox_ledger.user_id = #{userId} AND sandbox_ledger.settlement_no = s.settlement_no
               AND sandbox_ledger.run_id = #{runId}
               AND sandbox_ledger.asset = 'NEX' AND sandbox_ledger.status = 'SUCCESS'
               AND sandbox_ledger.amount = s.inviter_nex AND sandbox_ledger.is_deleted = 0
               AND sandbox_ledger.source = 'mock' AND sandbox_ledger.source_environment = 'SANDBOX'
             WHERE s.inviter_user_id = #{userId} AND s.is_deleted = 0
               AND s.run_id = #{runId}
               AND s.status = 'SETTLED' AND s.inviter_nex > 0
               AND s.source = 'mock' AND s.source_environment = 'SANDBOX'
               AND COALESCE(invited.sandbox, 0) = 1 AND COALESCE(inviter.sandbox, 0) = 1
             ORDER BY s.id DESC
             LIMIT #{limit}
            """)
    List<AppReferralLedgerRow> appRecentVerifiedSandboxRewards(
            @Param("userId") Long userId,
            @Param("runId") String runId,
            @Param("limit") int limit);

    @Select("SELECT lock_key FROM nx_admin_operation_mutex WHERE lock_key = 'H8_REWARD' FOR UPDATE")
    String lockRewardMutation();

    @Select("""
            SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND ((TABLE_NAME = 'nx_h8_sandbox_referral_ledger'
                     AND COLUMN_NAME IN ('settlement_no', 'user_id', 'asset', 'amount', 'balance_after',
                                         'status', 'source', 'source_environment', 'run_id', 'is_deleted'))
                 OR (TABLE_NAME = 'nx_h8_sandbox_referral_settlement'
                     AND COLUMN_NAME IN ('settlement_no', 'invited_user_id', 'inviter_user_id',
                                         'newcomer_usdt', 'newcomer_nex', 'inviter_nex', 'lock_mode',
                                         'config_snapshot', 'operator', 'reason', 'idempotency_key',
                                         'status', 'source', 'source_environment', 'run_id', 'is_deleted'))
                 OR (TABLE_NAME = 'nx_h8_sandbox_referral_command'
                     AND COLUMN_NAME IN ('run_id', 'idempotency_key', 'request_hash', 'status', 'response_json', 'is_deleted'))
                 OR (TABLE_NAME = 'nx_h8_sandbox_referral_audit'
                     AND COLUMN_NAME IN ('run_id', 'action', 'resource_id', 'actor', 'idempotency_key',
                                         'detail_json', 'source', 'source_environment', 'is_deleted')))
            """)
    int h8AcceptanceSandboxSchemaColumns();

    @Select("""
            SELECT request_hash AS requestHash, status, response_json AS responseJson
              FROM nx_h8_sandbox_referral_command
             WHERE run_id = #{runId} AND idempotency_key = #{idempotencyKey} AND is_deleted = 0
             LIMIT 1 FOR UPDATE
            """)
    H8SandboxCommandRow findSandboxCommand(@Param("runId") String runId,
                                           @Param("idempotencyKey") String idempotencyKey);

    @Insert("""
            INSERT IGNORE INTO nx_h8_sandbox_referral_command (
              run_id, idempotency_key, request_hash, status, created_at, updated_at, is_deleted
            ) VALUES (#{runId}, #{idempotencyKey}, #{requestHash}, 'PROCESSING', NOW(), NOW(), 0)
            """)
    int insertSandboxCommand(@Param("runId") String runId,
                              @Param("idempotencyKey") String idempotencyKey,
                              @Param("requestHash") String requestHash);

    @Update("""
            UPDATE nx_h8_sandbox_referral_command
               SET status = 'SUCCEEDED', response_json = #{responseJson}, updated_at = NOW()
             WHERE run_id = #{runId} AND idempotency_key = #{idempotencyKey}
               AND status = 'PROCESSING' AND is_deleted = 0
            """)
    int completeSandboxCommand(@Param("runId") String runId,
                               @Param("idempotencyKey") String idempotencyKey,
                               @Param("responseJson") String responseJson);

    @Insert("""
            INSERT INTO nx_h8_sandbox_referral_audit (
              run_id, action, resource_id, actor, idempotency_key, detail_json,
              source, source_environment, created_at, is_deleted
            ) VALUES (#{runId}, #{action}, #{resourceId}, #{operator}, #{idempotencyKey}, #{detailJson},
                      'mock', 'SANDBOX', NOW(), 0)
            """)
    int insertSandboxAudit(@Param("runId") String runId, @Param("action") String action,
                           @Param("resourceId") String resourceId, @Param("operator") String operator,
                           @Param("idempotencyKey") String idempotencyKey, @Param("detailJson") String detailJson);

    @Select("""
            SELECT u.id AS invitedUserId, u.sponsor_user_id AS inviterUserId
              FROM nx_user u
              JOIN nx_user inviter ON inviter.id = u.sponsor_user_id AND inviter.is_deleted = 0 AND inviter.status = 'ACTIVE'
              JOIN nx_user_wallet invited_wallet ON invited_wallet.user_id = u.id
                AND invited_wallet.is_deleted = 0
                AND invited_wallet.sandbox = #{accountSandbox}
              JOIN nx_user_wallet inviter_wallet ON inviter_wallet.user_id = inviter.id
                AND inviter_wallet.is_deleted = 0
                AND inviter_wallet.sandbox = #{accountSandbox}
              LEFT JOIN nx_referral_reward_settlement s
                ON s.invited_user_id = u.id AND s.is_deleted = 0
             WHERE u.sponsor_user_id IS NOT NULL AND u.is_deleted = 0 AND u.status = 'ACTIVE'
               AND #{sourceEnvironment} = 'PRODUCTION'
               AND COALESCE(u.sandbox, 0) = #{accountSandbox}
               AND COALESCE(inviter.sandbox, 0) = #{accountSandbox}
               AND u.sponsor_user_id <> u.id
               AND u.created_at >= #{effectiveAt}
               AND NOT EXISTS (
                 SELECT 1 FROM nx_admin_risk_arbitrage_row risk
                  WHERE risk.is_deleted = 0
                    AND risk.disposition IN ('gift_blocked','account_flagged','cluster_frozen')
                    AND (CONCAT_WS('|', risk.cell1, risk.cell2, risk.cell3, risk.cell4, risk.cell5, risk.cell6)
                           LIKE CONCAT('%', CONCAT('U', LPAD(u.id, GREATEST(8, CHAR_LENGTH(CAST(u.id AS CHAR))), '0')), '%')
                      OR CONCAT_WS('|', risk.cell1, risk.cell2, risk.cell3, risk.cell4, risk.cell5, risk.cell6)
                           LIKE CONCAT('%', CONCAT('U', LPAD(inviter.id, GREATEST(8, CHAR_LENGTH(CAST(inviter.id AS CHAR))), '0')), '%'))
               )
               AND NOT EXISTS (
                 SELECT 1
                   FROM nx_admin_risk_arbitrage_row risk
                   JOIN nx_admin_risk_multi_account_cluster risk_cluster
                     ON risk_cluster.cluster_id = risk.cluster_id
                    AND risk_cluster.is_deleted = 0
                    AND risk_cluster.nodes_json IS NOT NULL
                    AND JSON_VALID(risk_cluster.nodes_json) = 1
                  WHERE risk.is_deleted = 0
                    AND risk.disposition IN ('gift_blocked','account_flagged','cluster_frozen')
                    AND (JSON_SEARCH(risk_cluster.nodes_json, 'one', CONCAT('U', LPAD(u.id, GREATEST(8, CHAR_LENGTH(CAST(u.id AS CHAR))), '0'))) IS NOT NULL
                      OR JSON_SEARCH(risk_cluster.nodes_json, 'one', CONCAT('U', LPAD(inviter.id, GREATEST(8, CHAR_LENGTH(CAST(inviter.id AS CHAR))), '0'))) IS NOT NULL)
               )
               AND NOT EXISTS (
                 SELECT 1 FROM nx_admin_risk_multi_account_cluster cluster
                  WHERE cluster.is_deleted = 0 AND cluster.status IN ('detected','flagged','frozen')
                    AND cluster.nodes_json IS NOT NULL AND JSON_VALID(cluster.nodes_json) = 1
                    AND (JSON_SEARCH(cluster.nodes_json, 'one', CONCAT('U', LPAD(u.id, GREATEST(8, CHAR_LENGTH(CAST(u.id AS CHAR))), '0'))) IS NOT NULL
                      OR JSON_SEARCH(cluster.nodes_json, 'one', CONCAT('U', LPAD(inviter.id, GREATEST(8, CHAR_LENGTH(CAST(inviter.id AS CHAR))), '0'))) IS NOT NULL)
               )
               AND (#{holdRisky} = FALSE OR (
                 NOT EXISTS (
                   SELECT 1 FROM nx_admin_risk_arbitrage_row review_risk
                    WHERE review_risk.is_deleted = 0
                      AND (CONCAT_WS('|', review_risk.cell1, review_risk.cell2, review_risk.cell3, review_risk.cell4, review_risk.cell5, review_risk.cell6)
                             LIKE CONCAT('%', CONCAT('U', LPAD(u.id, GREATEST(8, CHAR_LENGTH(CAST(u.id AS CHAR))), '0')), '%')
                        OR CONCAT_WS('|', review_risk.cell1, review_risk.cell2, review_risk.cell3, review_risk.cell4, review_risk.cell5, review_risk.cell6)
                             LIKE CONCAT('%', CONCAT('U', LPAD(inviter.id, GREATEST(8, CHAR_LENGTH(CAST(inviter.id AS CHAR))), '0')), '%'))
                 )
                 AND NOT EXISTS (
                   SELECT 1 FROM nx_admin_risk_multi_account_cluster review_cluster
                    WHERE review_cluster.is_deleted = 0 AND review_cluster.status IN ('detected','flagged')
                      AND review_cluster.nodes_json IS NOT NULL AND JSON_VALID(review_cluster.nodes_json) = 1
                      AND (JSON_SEARCH(review_cluster.nodes_json, 'one', CONCAT('U', LPAD(u.id, GREATEST(8, CHAR_LENGTH(CAST(u.id AS CHAR))), '0'))) IS NOT NULL
                        OR JSON_SEARCH(review_cluster.nodes_json, 'one', CONCAT('U', LPAD(inviter.id, GREATEST(8, CHAR_LENGTH(CAST(inviter.id AS CHAR))), '0'))) IS NOT NULL)
                 )
               ))
               AND s.id IS NULL
               AND (#{onlyInvitedUserId} IS NULL OR u.id = #{onlyInvitedUserId})
             ORDER BY u.created_at ASC, u.id ASC
             LIMIT #{limit}
            """)
    List<ReferralRow> findPendingReferrals(@Param("effectiveAt") LocalDateTime effectiveAt,
                                           @Param("sourceEnvironment") String sourceEnvironment,
                                           @Param("accountSandbox") int accountSandbox,
                                           @Param("holdRisky") boolean holdRisky,
                                           @Param("limit") int limit,
                                           @Param("onlyInvitedUserId") Long onlyInvitedUserId);

    @Select("""
            SELECT u.id AS invitedUserId, u.sponsor_user_id AS inviterUserId
              FROM nx_user u
              JOIN nx_user inviter ON inviter.id = u.sponsor_user_id
               AND inviter.is_deleted = 0 AND inviter.status = 'ACTIVE'
              JOIN nx_user_wallet invited_wallet ON invited_wallet.user_id = u.id
               AND invited_wallet.is_deleted = 0 AND invited_wallet.sandbox = 1
              JOIN nx_user_wallet inviter_wallet ON inviter_wallet.user_id = inviter.id
               AND inviter_wallet.is_deleted = 0 AND inviter_wallet.sandbox = 1
              LEFT JOIN nx_h8_sandbox_referral_settlement s
                ON s.invited_user_id = u.id AND s.run_id = #{runId} AND s.is_deleted = 0
             WHERE (#{onlyInvitedUserId} IS NULL OR u.id = #{onlyInvitedUserId})
               AND u.sponsor_user_id IS NOT NULL AND u.sponsor_user_id <> u.id
               AND u.is_deleted = 0 AND u.status = 'ACTIVE'
               AND COALESCE(u.sandbox, 0) = 1 AND COALESCE(inviter.sandbox, 0) = 1
               AND u.created_at >= #{effectiveAt} AND s.id IS NULL
             ORDER BY u.created_at ASC, u.id ASC
             LIMIT 20
            """)
    List<ReferralRow> findPendingSandboxReferral(@Param("effectiveAt") LocalDateTime effectiveAt,
                                                 @Param("runId") String runId,
                                                 @Param("onlyInvitedUserId") Long onlyInvitedUserId);

    @Insert("""
            INSERT IGNORE INTO nx_referral_reward_settlement (
              settlement_no, invited_user_id, inviter_user_id, newcomer_usdt, newcomer_nex,
              inviter_nex, lock_mode, config_snapshot, operator, reason, idempotency_key,
              status, created_at, updated_at, is_deleted
            )
            SELECT #{settlementNo}, u.id, inviter.id, #{newcomerUsdt}, #{newcomerNex},
                   #{inviterNex}, #{lockMode}, #{configSnapshot}, #{operator}, #{reason},
                   #{idempotencyKey}, 'SETTLED', NOW(), NOW(), 0
               FROM nx_user u
               JOIN nx_user inviter ON inviter.id = u.sponsor_user_id AND inviter.is_deleted = 0 AND inviter.status = 'ACTIVE'
               JOIN nx_user_wallet invited_wallet ON invited_wallet.user_id = u.id
                 AND invited_wallet.is_deleted = 0
                 AND invited_wallet.sandbox = #{accountSandbox}
               JOIN nx_user_wallet inviter_wallet ON inviter_wallet.user_id = inviter.id
                 AND inviter_wallet.is_deleted = 0
                 AND inviter_wallet.sandbox = #{accountSandbox}
              WHERE u.id = #{invitedUserId} AND inviter.id = #{inviterUserId}
                 AND #{sourceEnvironment} = 'PRODUCTION'
                 AND COALESCE(u.sandbox, 0) = #{accountSandbox}
                 AND COALESCE(inviter.sandbox, 0) = #{accountSandbox}
                AND u.sponsor_user_id IS NOT NULL AND u.is_deleted = 0 AND u.status = 'ACTIVE'
               AND u.sponsor_user_id <> u.id AND u.created_at >= #{effectiveAt}
               AND NOT EXISTS (
                 SELECT 1 FROM nx_admin_risk_arbitrage_row risk
                  WHERE risk.is_deleted = 0
                    AND risk.disposition IN ('gift_blocked','account_flagged','cluster_frozen')
                    AND (CONCAT_WS('|', risk.cell1, risk.cell2, risk.cell3, risk.cell4, risk.cell5, risk.cell6)
                           LIKE CONCAT('%', CONCAT('U', LPAD(u.id, GREATEST(8, CHAR_LENGTH(CAST(u.id AS CHAR))), '0')), '%')
                      OR CONCAT_WS('|', risk.cell1, risk.cell2, risk.cell3, risk.cell4, risk.cell5, risk.cell6)
                           LIKE CONCAT('%', CONCAT('U', LPAD(inviter.id, GREATEST(8, CHAR_LENGTH(CAST(inviter.id AS CHAR))), '0')), '%'))
               )
               AND NOT EXISTS (
                 SELECT 1
                   FROM nx_admin_risk_arbitrage_row risk
                   JOIN nx_admin_risk_multi_account_cluster risk_cluster
                     ON risk_cluster.cluster_id = risk.cluster_id
                    AND risk_cluster.is_deleted = 0
                    AND risk_cluster.nodes_json IS NOT NULL
                    AND JSON_VALID(risk_cluster.nodes_json) = 1
                  WHERE risk.is_deleted = 0
                    AND risk.disposition IN ('gift_blocked','account_flagged','cluster_frozen')
                    AND (JSON_SEARCH(risk_cluster.nodes_json, 'one', CONCAT('U', LPAD(u.id, GREATEST(8, CHAR_LENGTH(CAST(u.id AS CHAR))), '0'))) IS NOT NULL
                      OR JSON_SEARCH(risk_cluster.nodes_json, 'one', CONCAT('U', LPAD(inviter.id, GREATEST(8, CHAR_LENGTH(CAST(inviter.id AS CHAR))), '0'))) IS NOT NULL)
               )
               AND NOT EXISTS (
                 SELECT 1 FROM nx_admin_risk_multi_account_cluster cluster
                  WHERE cluster.is_deleted = 0 AND cluster.status IN ('detected','flagged','frozen')
                    AND cluster.nodes_json IS NOT NULL AND JSON_VALID(cluster.nodes_json) = 1
                    AND (JSON_SEARCH(cluster.nodes_json, 'one', CONCAT('U', LPAD(u.id, GREATEST(8, CHAR_LENGTH(CAST(u.id AS CHAR))), '0'))) IS NOT NULL
                      OR JSON_SEARCH(cluster.nodes_json, 'one', CONCAT('U', LPAD(inviter.id, GREATEST(8, CHAR_LENGTH(CAST(inviter.id AS CHAR))), '0'))) IS NOT NULL)
               )
               AND (#{holdRisky} = FALSE OR (
                 NOT EXISTS (
                   SELECT 1 FROM nx_admin_risk_arbitrage_row review_risk
                    WHERE review_risk.is_deleted = 0
                      AND (CONCAT_WS('|', review_risk.cell1, review_risk.cell2, review_risk.cell3, review_risk.cell4, review_risk.cell5, review_risk.cell6)
                             LIKE CONCAT('%', CONCAT('U', LPAD(u.id, GREATEST(8, CHAR_LENGTH(CAST(u.id AS CHAR))), '0')), '%')
                        OR CONCAT_WS('|', review_risk.cell1, review_risk.cell2, review_risk.cell3, review_risk.cell4, review_risk.cell5, review_risk.cell6)
                             LIKE CONCAT('%', CONCAT('U', LPAD(inviter.id, GREATEST(8, CHAR_LENGTH(CAST(inviter.id AS CHAR))), '0')), '%'))
                 )
                 AND NOT EXISTS (
                   SELECT 1 FROM nx_admin_risk_multi_account_cluster review_cluster
                    WHERE review_cluster.is_deleted = 0 AND review_cluster.status IN ('detected','flagged')
                      AND review_cluster.nodes_json IS NOT NULL AND JSON_VALID(review_cluster.nodes_json) = 1
                      AND (JSON_SEARCH(review_cluster.nodes_json, 'one', CONCAT('U', LPAD(u.id, GREATEST(8, CHAR_LENGTH(CAST(u.id AS CHAR))), '0'))) IS NOT NULL
                        OR JSON_SEARCH(review_cluster.nodes_json, 'one', CONCAT('U', LPAD(inviter.id, GREATEST(8, CHAR_LENGTH(CAST(inviter.id AS CHAR))), '0'))) IS NOT NULL)
                 )
               ))
            """)
    int insertSettlement(@Param("settlementNo") String settlementNo,
                         @Param("invitedUserId") Long invitedUserId,
                         @Param("inviterUserId") Long inviterUserId,
                         @Param("newcomerUsdt") BigDecimal newcomerUsdt,
                         @Param("newcomerNex") BigDecimal newcomerNex,
                         @Param("inviterNex") BigDecimal inviterNex,
                         @Param("lockMode") String lockMode,
                         @Param("configSnapshot") String configSnapshot,
                         @Param("operator") String operator,
                         @Param("reason") String reason,
                          @Param("idempotencyKey") String idempotencyKey,
                          @Param("effectiveAt") LocalDateTime effectiveAt,
                          @Param("sourceEnvironment") String sourceEnvironment,
                          @Param("accountSandbox") int accountSandbox,
                           @Param("holdRisky") boolean holdRisky);

    @Insert("""
            INSERT IGNORE INTO nx_h8_sandbox_referral_settlement (
              settlement_no, invited_user_id, inviter_user_id, newcomer_usdt, newcomer_nex,
              inviter_nex, lock_mode, config_snapshot, operator, reason, idempotency_key, run_id,
              status, source, source_environment, created_at, updated_at, is_deleted
            )
            SELECT #{settlementNo}, u.id, inviter.id, #{newcomerUsdt}, #{newcomerNex},
                   #{inviterNex}, #{lockMode}, #{configSnapshot}, #{operator}, #{reason},
                   #{idempotencyKey}, #{runId}, 'SETTLED', 'mock', 'SANDBOX', NOW(), NOW(), 0
              FROM nx_user u
              JOIN nx_user inviter ON inviter.id = u.sponsor_user_id
               AND inviter.is_deleted = 0 AND inviter.status = 'ACTIVE'
              JOIN nx_user_wallet invited_wallet ON invited_wallet.user_id = u.id
               AND invited_wallet.is_deleted = 0 AND invited_wallet.sandbox = 1
              JOIN nx_user_wallet inviter_wallet ON inviter_wallet.user_id = inviter.id
               AND inviter_wallet.is_deleted = 0 AND inviter_wallet.sandbox = 1
             WHERE u.id = #{invitedUserId} AND inviter.id = #{inviterUserId}
               AND u.sponsor_user_id IS NOT NULL AND u.sponsor_user_id <> u.id
               AND u.is_deleted = 0 AND u.status = 'ACTIVE'
               AND COALESCE(u.sandbox, 0) = 1 AND COALESCE(inviter.sandbox, 0) = 1
               AND u.created_at >= #{effectiveAt}
               AND NOT EXISTS (
                 SELECT 1 FROM nx_h8_sandbox_referral_settlement existing
                  WHERE existing.invited_user_id = u.id AND existing.run_id = #{runId} AND existing.is_deleted = 0
               )
            """)
    int insertSandboxSettlement(@Param("settlementNo") String settlementNo,
                                @Param("invitedUserId") Long invitedUserId,
                                @Param("inviterUserId") Long inviterUserId,
                                @Param("newcomerUsdt") BigDecimal newcomerUsdt,
                                @Param("newcomerNex") BigDecimal newcomerNex,
                                @Param("inviterNex") BigDecimal inviterNex,
                                @Param("lockMode") String lockMode,
                                @Param("configSnapshot") String configSnapshot,
                                @Param("operator") String operator,
                                @Param("reason") String reason,
                                @Param("idempotencyKey") String idempotencyKey,
                                @Param("runId") String runId,
                                @Param("effectiveAt") LocalDateTime effectiveAt);

    /**
     * Server-owned sandbox proof ledger. The SELECT guard makes it impossible
     * for a sandbox H8 call to create a production-wallet ledger record.
     */
    @Insert("""
            INSERT INTO nx_h8_sandbox_referral_ledger (
              settlement_no, run_id, user_id, asset, amount, balance_after, status,
              source, source_environment, remark, created_at, is_deleted
            )
            SELECT SUBSTRING_INDEX(#{bizNo}, ':', 1), #{runId}, u.id, #{asset}, #{amount},
                   COALESCE((SELECT SUM(l.amount) FROM nx_h8_sandbox_referral_ledger l
                               WHERE l.run_id=#{runId} AND l.user_id=#{userId} AND l.asset=#{asset}
                                 AND l.is_deleted=0 AND l.status='SUCCESS'), 0) + #{amount},
                   'SUCCESS', 'mock', 'SANDBOX', #{remark}, NOW(), 0
              FROM nx_user u
             WHERE u.id = #{userId} AND u.is_deleted = 0 AND u.status = 'ACTIVE'
               AND COALESCE(u.sandbox, 0) = 1
            """)
    int insertSandboxLedger(@Param("bizNo") String bizNo,
                            @Param("runId") String runId,
                            @Param("userId") Long userId,
                            @Param("asset") String asset,
                            @Param("amount") BigDecimal amount,
                            @Param("remark") String remark);

    @Select("""
            SELECT COUNT(*) FROM nx_h8_sandbox_referral_settlement
             WHERE run_id = #{runId} AND is_deleted = 0
               AND source = 'mock' AND source_environment = 'SANDBOX'
            """)
    long totalSandboxSettled(@Param("runId") String runId);

    @Select("""
            SELECT settlement_no AS settlementNo, invited_user_id AS invitedUserId,
                   inviter_user_id AS inviterUserId, newcomer_usdt AS newcomerUsdt,
                   newcomer_nex AS newcomerNex, inviter_nex AS inviterNex,
                   status, created_at AS createdAt
              FROM nx_h8_sandbox_referral_settlement
             WHERE run_id = #{runId} AND is_deleted = 0
               AND source = 'mock' AND source_environment = 'SANDBOX'
             ORDER BY id DESC LIMIT #{limit}
            """)
    List<Map<String, Object>> recentSandboxSettlements(@Param("runId") String runId,
                                                        @Param("limit") int limit);

    @Select("""
            SELECT COUNT(*) FROM nx_referral_reward_settlement s
              JOIN nx_user invited ON invited.id=s.invited_user_id
              JOIN nx_user inviter ON inviter.id=s.inviter_user_id
             WHERE s.is_deleted=0
               AND COALESCE(invited.sandbox,0)=#{accountSandbox}
               AND COALESCE(inviter.sandbox,0)=#{accountSandbox}
            """)
    long totalSettled(@Param("accountSandbox") int accountSandbox);

    @Select("""
            SELECT COUNT(*) FROM nx_user u
              JOIN nx_user inviter ON inviter.id = u.sponsor_user_id AND inviter.is_deleted = 0 AND inviter.status = 'ACTIVE'
              LEFT JOIN nx_referral_reward_settlement s ON s.invited_user_id = u.id AND s.is_deleted = 0
             WHERE u.sponsor_user_id IS NOT NULL AND u.is_deleted = 0 AND u.status = 'ACTIVE'
               AND COALESCE(u.sandbox, 0) = #{accountSandbox}
               AND COALESCE(inviter.sandbox, 0) = #{accountSandbox}
               AND u.sponsor_user_id <> u.id
               AND u.created_at >= #{effectiveAt}
               AND NOT EXISTS (
                 SELECT 1 FROM nx_admin_risk_arbitrage_row risk
                  WHERE risk.is_deleted = 0
                    AND risk.disposition IN ('gift_blocked','account_flagged','cluster_frozen')
                    AND (CONCAT_WS('|', risk.cell1, risk.cell2, risk.cell3, risk.cell4, risk.cell5, risk.cell6)
                           LIKE CONCAT('%', CONCAT('U', LPAD(u.id, GREATEST(8, CHAR_LENGTH(CAST(u.id AS CHAR))), '0')), '%')
                      OR CONCAT_WS('|', risk.cell1, risk.cell2, risk.cell3, risk.cell4, risk.cell5, risk.cell6)
                           LIKE CONCAT('%', CONCAT('U', LPAD(inviter.id, GREATEST(8, CHAR_LENGTH(CAST(inviter.id AS CHAR))), '0')), '%'))
               )
               AND NOT EXISTS (
                 SELECT 1
                   FROM nx_admin_risk_arbitrage_row risk
                   JOIN nx_admin_risk_multi_account_cluster risk_cluster
                     ON risk_cluster.cluster_id = risk.cluster_id
                    AND risk_cluster.is_deleted = 0
                    AND risk_cluster.nodes_json IS NOT NULL
                    AND JSON_VALID(risk_cluster.nodes_json) = 1
                  WHERE risk.is_deleted = 0
                    AND risk.disposition IN ('gift_blocked','account_flagged','cluster_frozen')
                    AND (JSON_SEARCH(risk_cluster.nodes_json, 'one', CONCAT('U', LPAD(u.id, GREATEST(8, CHAR_LENGTH(CAST(u.id AS CHAR))), '0'))) IS NOT NULL
                      OR JSON_SEARCH(risk_cluster.nodes_json, 'one', CONCAT('U', LPAD(inviter.id, GREATEST(8, CHAR_LENGTH(CAST(inviter.id AS CHAR))), '0'))) IS NOT NULL)
               )
               AND NOT EXISTS (
                 SELECT 1 FROM nx_admin_risk_multi_account_cluster cluster
                  WHERE cluster.is_deleted = 0 AND cluster.status IN ('detected','flagged','frozen')
                    AND cluster.nodes_json IS NOT NULL AND JSON_VALID(cluster.nodes_json) = 1
                    AND (JSON_SEARCH(cluster.nodes_json, 'one', CONCAT('U', LPAD(u.id, GREATEST(8, CHAR_LENGTH(CAST(u.id AS CHAR))), '0'))) IS NOT NULL
                      OR JSON_SEARCH(cluster.nodes_json, 'one', CONCAT('U', LPAD(inviter.id, GREATEST(8, CHAR_LENGTH(CAST(inviter.id AS CHAR))), '0'))) IS NOT NULL)
               )
               AND (#{holdRisky} = FALSE OR (
                 NOT EXISTS (
                   SELECT 1 FROM nx_admin_risk_arbitrage_row review_risk
                    WHERE review_risk.is_deleted = 0
                      AND (CONCAT_WS('|', review_risk.cell1, review_risk.cell2, review_risk.cell3, review_risk.cell4, review_risk.cell5, review_risk.cell6)
                             LIKE CONCAT('%', CONCAT('U', LPAD(u.id, GREATEST(8, CHAR_LENGTH(CAST(u.id AS CHAR))), '0')), '%')
                        OR CONCAT_WS('|', review_risk.cell1, review_risk.cell2, review_risk.cell3, review_risk.cell4, review_risk.cell5, review_risk.cell6)
                             LIKE CONCAT('%', CONCAT('U', LPAD(inviter.id, GREATEST(8, CHAR_LENGTH(CAST(inviter.id AS CHAR))), '0')), '%'))
                 )
                 AND NOT EXISTS (
                   SELECT 1 FROM nx_admin_risk_multi_account_cluster review_cluster
                    WHERE review_cluster.is_deleted = 0 AND review_cluster.status IN ('detected','flagged')
                      AND review_cluster.nodes_json IS NOT NULL AND JSON_VALID(review_cluster.nodes_json) = 1
                      AND (JSON_SEARCH(review_cluster.nodes_json, 'one', CONCAT('U', LPAD(u.id, GREATEST(8, CHAR_LENGTH(CAST(u.id AS CHAR))), '0'))) IS NOT NULL
                        OR JSON_SEARCH(review_cluster.nodes_json, 'one', CONCAT('U', LPAD(inviter.id, GREATEST(8, CHAR_LENGTH(CAST(inviter.id AS CHAR))), '0'))) IS NOT NULL)
                 )
               ))
               AND s.id IS NULL
            """)
    long totalPending(@Param("effectiveAt") LocalDateTime effectiveAt,
                      @Param("accountSandbox") int accountSandbox,
                      @Param("holdRisky") boolean holdRisky);

    @Select("""
            SELECT COUNT(*) FROM nx_user u
              JOIN nx_user inviter ON inviter.id = u.sponsor_user_id AND inviter.is_deleted = 0 AND inviter.status = 'ACTIVE'
              LEFT JOIN nx_referral_reward_settlement s
                ON s.invited_user_id = u.id AND s.is_deleted = 0
             WHERE u.sponsor_user_id IS NOT NULL AND u.is_deleted = 0 AND u.status = 'ACTIVE'
               AND COALESCE(u.sandbox, 0) = #{accountSandbox}
               AND COALESCE(inviter.sandbox, 0) = #{accountSandbox}
               AND u.sponsor_user_id <> u.id
               AND u.created_at >= #{effectiveAt}
               AND s.id IS NULL
               AND (EXISTS (
                 SELECT 1 FROM nx_admin_risk_arbitrage_row risk
                  WHERE risk.is_deleted = 0
                    AND risk.disposition IN ('gift_blocked','account_flagged','cluster_frozen')
                    AND (CONCAT_WS('|', risk.cell1, risk.cell2, risk.cell3, risk.cell4, risk.cell5, risk.cell6)
                           LIKE CONCAT('%', CONCAT('U', LPAD(u.id, GREATEST(8, CHAR_LENGTH(CAST(u.id AS CHAR))), '0')), '%')
                      OR CONCAT_WS('|', risk.cell1, risk.cell2, risk.cell3, risk.cell4, risk.cell5, risk.cell6)
                           LIKE CONCAT('%', CONCAT('U', LPAD(inviter.id, GREATEST(8, CHAR_LENGTH(CAST(inviter.id AS CHAR))), '0')), '%'))
               ) OR EXISTS (
                 SELECT 1
                   FROM nx_admin_risk_arbitrage_row risk
                   JOIN nx_admin_risk_multi_account_cluster risk_cluster
                     ON risk_cluster.cluster_id = risk.cluster_id
                    AND risk_cluster.is_deleted = 0
                    AND risk_cluster.nodes_json IS NOT NULL
                    AND JSON_VALID(risk_cluster.nodes_json) = 1
                  WHERE risk.is_deleted = 0
                    AND risk.disposition IN ('gift_blocked','account_flagged','cluster_frozen')
                    AND (JSON_SEARCH(risk_cluster.nodes_json, 'one', CONCAT('U', LPAD(u.id, GREATEST(8, CHAR_LENGTH(CAST(u.id AS CHAR))), '0'))) IS NOT NULL
                      OR JSON_SEARCH(risk_cluster.nodes_json, 'one', CONCAT('U', LPAD(inviter.id, GREATEST(8, CHAR_LENGTH(CAST(inviter.id AS CHAR))), '0'))) IS NOT NULL)
               ) OR EXISTS (
                 SELECT 1 FROM nx_admin_risk_multi_account_cluster cluster
                  WHERE cluster.is_deleted = 0 AND cluster.status IN ('detected','flagged','frozen')
                    AND cluster.nodes_json IS NOT NULL AND JSON_VALID(cluster.nodes_json) = 1
                    AND (JSON_SEARCH(cluster.nodes_json, 'one', CONCAT('U', LPAD(u.id, GREATEST(8, CHAR_LENGTH(CAST(u.id AS CHAR))), '0'))) IS NOT NULL
                      OR JSON_SEARCH(cluster.nodes_json, 'one', CONCAT('U', LPAD(inviter.id, GREATEST(8, CHAR_LENGTH(CAST(inviter.id AS CHAR))), '0'))) IS NOT NULL)
               ) OR (#{holdRisky} = TRUE AND (
                 EXISTS (
                   SELECT 1 FROM nx_admin_risk_arbitrage_row review_risk
                    WHERE review_risk.is_deleted = 0
                      AND (CONCAT_WS('|', review_risk.cell1, review_risk.cell2, review_risk.cell3, review_risk.cell4, review_risk.cell5, review_risk.cell6)
                             LIKE CONCAT('%', CONCAT('U', LPAD(u.id, GREATEST(8, CHAR_LENGTH(CAST(u.id AS CHAR))), '0')), '%')
                        OR CONCAT_WS('|', review_risk.cell1, review_risk.cell2, review_risk.cell3, review_risk.cell4, review_risk.cell5, review_risk.cell6)
                             LIKE CONCAT('%', CONCAT('U', LPAD(inviter.id, GREATEST(8, CHAR_LENGTH(CAST(inviter.id AS CHAR))), '0')), '%'))
                 ) OR EXISTS (
                   SELECT 1 FROM nx_admin_risk_multi_account_cluster review_cluster
                    WHERE review_cluster.is_deleted = 0 AND review_cluster.status IN ('detected','flagged')
                      AND review_cluster.nodes_json IS NOT NULL AND JSON_VALID(review_cluster.nodes_json) = 1
                      AND (JSON_SEARCH(review_cluster.nodes_json, 'one', CONCAT('U', LPAD(u.id, GREATEST(8, CHAR_LENGTH(CAST(u.id AS CHAR))), '0'))) IS NOT NULL
                        OR JSON_SEARCH(review_cluster.nodes_json, 'one', CONCAT('U', LPAD(inviter.id, GREATEST(8, CHAR_LENGTH(CAST(inviter.id AS CHAR))), '0'))) IS NOT NULL)
                 )
               )))
            """)
    long totalBlockedByK2(@Param("effectiveAt") LocalDateTime effectiveAt,
                          @Param("accountSandbox") int accountSandbox,
                          @Param("holdRisky") boolean holdRisky);

    @Select("""
            SELECT s.settlement_no AS settlementNo, s.invited_user_id AS invitedUserId,
                   s.inviter_user_id AS inviterUserId, s.newcomer_usdt AS newcomerUsdt,
                   s.newcomer_nex AS newcomerNex, s.inviter_nex AS inviterNex,
                   s.status AS status, s.created_at AS createdAt
              FROM nx_referral_reward_settlement s
              JOIN nx_user invited ON invited.id=s.invited_user_id
              JOIN nx_user inviter ON inviter.id=s.inviter_user_id
             WHERE s.is_deleted=0
               AND COALESCE(invited.sandbox,0)=#{accountSandbox}
               AND COALESCE(inviter.sandbox,0)=#{accountSandbox}
             ORDER BY s.id DESC LIMIT #{limit}
            """)
    List<Map<String, Object>> recentSettlements(@Param("accountSandbox") int accountSandbox,
                                                @Param("limit") int limit);

    record ReferralRow(Long invitedUserId, Long inviterUserId) {}
    record AppReferralAccount(String referralCode, Integer sandbox, BigDecimal walletNexAvailable) {}
    record AppReferralLedgerSummary(Long settledCount, BigDecimal lifetimeInviterNex) {}
    record AppReferralLedgerRow(String settlementNo, BigDecimal amountNex, String ledgerStatus,
                                BigDecimal balanceAfter, String releaseBucket,
                                String sourceEnvironment, LocalDateTime settledAt) {}
    record H8SandboxCommandRow(String requestHash, String status, String responseJson) {}
}
