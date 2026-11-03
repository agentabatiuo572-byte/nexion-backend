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
    String ELIGIBLE_RISK = """
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
            """;

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
               AND production_ledger.biz_type = 'REFERRAL_REWARD' AND production_ledger.asset = 'UVT'
               AND production_ledger.direction = 'IN' AND production_ledger.status = 'SUCCESS'
               AND production_ledger.amount = s.inviter_nex AND production_ledger.is_deleted = 0
               AND #{sourceEnvironment} = 'PRODUCTION'
              JOIN nx_earnings_release_entry e
                ON e.user_id = #{userId} AND e.source_ref = CONCAT(s.settlement_no, ':INVITER:NEX')
               AND e.asset = 'UVT' AND e.amount = s.inviter_nex AND e.status = 'ACTIVE'
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
               AND production_ledger.biz_type = 'REFERRAL_REWARD' AND production_ledger.asset = 'UVT'
               AND production_ledger.direction = 'IN' AND production_ledger.status = 'SUCCESS'
               AND production_ledger.amount = s.inviter_nex AND production_ledger.is_deleted = 0
               AND #{sourceEnvironment} = 'PRODUCTION'
              JOIN nx_earnings_release_entry e
                ON e.user_id = #{userId} AND e.source_ref = CONCAT(s.settlement_no, ':INVITER:NEX')
               AND e.asset = 'UVT' AND e.amount = s.inviter_nex AND e.status = 'ACTIVE'
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
               AND sandbox_ledger.asset = 'UVT' AND sandbox_ledger.status = 'SUCCESS'
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
               AND sandbox_ledger.asset = 'UVT' AND sandbox_ledger.status = 'SUCCESS'
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
            SELECT u.id AS invitedUserId, inviter.id AS inviterUserId
              FROM nx_user u
              LEFT JOIN nx_user inviter ON inviter.id = u.sponsor_user_id AND inviter.is_deleted = 0 AND inviter.status = 'ACTIVE'
               AND inviter.id<>u.id AND inviter.sandbox=u.sandbox
               AND EXISTS (SELECT 1 FROM nx_user_wallet w WHERE w.user_id=inviter.id AND w.is_deleted=0 AND w.sandbox=u.sandbox)
              JOIN nx_user_wallet invited_wallet ON invited_wallet.user_id = u.id
                AND invited_wallet.is_deleted = 0
                AND invited_wallet.sandbox = #{accountSandbox}

              LEFT JOIN nx_referral_reward_settlement s
                ON s.invited_user_id = u.id AND s.is_deleted = 0
             WHERE u.is_deleted = 0 AND u.status = 'ACTIVE'
               AND #{sourceEnvironment} = 'PRODUCTION'
               AND COALESCE(u.sandbox, 0) = #{accountSandbox}
               AND u.created_at >= #{effectiveAt}
            """ + ELIGIBLE_RISK + """
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
            SELECT u.id AS invitedUserId, inviter.id AS inviterUserId
              FROM nx_user u
              LEFT JOIN nx_user inviter ON inviter.id = u.sponsor_user_id
               AND inviter.is_deleted = 0 AND inviter.status = 'ACTIVE'
               AND inviter.id<>u.id AND inviter.sandbox=u.sandbox
               AND EXISTS (SELECT 1 FROM nx_user_wallet w WHERE w.user_id=inviter.id AND w.is_deleted=0 AND w.sandbox=u.sandbox)
              JOIN nx_user_wallet invited_wallet ON invited_wallet.user_id = u.id
               AND invited_wallet.is_deleted = 0 AND invited_wallet.sandbox = 1

              LEFT JOIN nx_h8_sandbox_referral_settlement s
                ON s.invited_user_id = u.id AND s.run_id = #{runId} AND s.is_deleted = 0
             WHERE (#{onlyInvitedUserId} IS NULL OR u.id = #{onlyInvitedUserId})
               AND u.is_deleted = 0 AND u.status = 'ACTIVE'
               AND COALESCE(u.sandbox, 0) = 1
               AND u.created_at >= #{effectiveAt} AND s.id IS NULL
            """ + ELIGIBLE_RISK + """
             ORDER BY u.created_at ASC, u.id ASC
             LIMIT 20
            """)
    List<ReferralRow> findEligibleSandboxReferral(@Param("effectiveAt") LocalDateTime effectiveAt,
                                                 @Param("runId") String runId,
                                                 @Param("onlyInvitedUserId") Long onlyInvitedUserId,@Param("holdRisky") boolean holdRisky);

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
               LEFT JOIN nx_user inviter ON inviter.id = u.sponsor_user_id AND inviter.is_deleted = 0 AND inviter.status = 'ACTIVE'
               AND inviter.id<>u.id AND inviter.sandbox=u.sandbox
               AND EXISTS (SELECT 1 FROM nx_user_wallet w WHERE w.user_id=inviter.id AND w.is_deleted=0 AND w.sandbox=u.sandbox)
               JOIN nx_user_wallet invited_wallet ON invited_wallet.user_id = u.id
                 AND invited_wallet.is_deleted = 0
                 AND invited_wallet.sandbox = #{accountSandbox}

              WHERE u.id = #{invitedUserId} AND inviter.id <=> #{inviterUserId}
                 AND #{sourceEnvironment} = 'PRODUCTION'
                 AND COALESCE(u.sandbox, 0) = #{accountSandbox}
                AND u.is_deleted = 0 AND u.status = 'ACTIVE'
               AND u.created_at >= #{effectiveAt}
            """ + ELIGIBLE_RISK + """
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
              LEFT JOIN nx_user inviter ON inviter.id = u.sponsor_user_id
               AND inviter.is_deleted = 0 AND inviter.status = 'ACTIVE'
               AND inviter.id<>u.id AND inviter.sandbox=u.sandbox
               AND EXISTS (SELECT 1 FROM nx_user_wallet w WHERE w.user_id=inviter.id AND w.is_deleted=0 AND w.sandbox=u.sandbox)
              JOIN nx_user_wallet invited_wallet ON invited_wallet.user_id = u.id
               AND invited_wallet.is_deleted = 0 AND invited_wallet.sandbox = 1

             WHERE u.id = #{invitedUserId} AND inviter.id <=> #{inviterUserId}
               AND u.is_deleted = 0 AND u.status = 'ACTIVE'
               AND COALESCE(u.sandbox, 0) = 1
               AND u.created_at >= #{effectiveAt}
            """ + ELIGIBLE_RISK + """
               AND NOT EXISTS (
                 SELECT 1 FROM nx_h8_sandbox_referral_settlement existing
                  WHERE existing.invited_user_id = u.id AND existing.run_id = #{runId} AND existing.is_deleted = 0
               )
            """)
    int insertEligibleSandboxSettlement(@Param("settlementNo") String settlementNo,
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
                                @Param("effectiveAt") LocalDateTime effectiveAt,@Param("holdRisky") boolean holdRisky);

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
              LEFT JOIN nx_user inviter ON inviter.id=s.inviter_user_id
             WHERE s.is_deleted=0
               AND COALESCE(invited.sandbox,0)=#{accountSandbox}
               AND (s.inviter_user_id IS NULL OR inviter.sandbox=#{accountSandbox})
            """)
    long totalSettled(@Param("accountSandbox") int accountSandbox);

    @Select("""
            SELECT COUNT(*) FROM nx_user u
              LEFT JOIN nx_user inviter ON inviter.id = u.sponsor_user_id AND inviter.is_deleted = 0 AND inviter.status = 'ACTIVE'
               AND inviter.id<>u.id AND inviter.sandbox=u.sandbox
               AND EXISTS (SELECT 1 FROM nx_user_wallet w WHERE w.user_id=inviter.id AND w.is_deleted=0 AND w.sandbox=u.sandbox)
              LEFT JOIN nx_referral_reward_settlement s ON s.invited_user_id = u.id AND s.is_deleted = 0
             WHERE u.is_deleted = 0 AND u.status = 'ACTIVE'
               AND COALESCE(u.sandbox, 0) = #{accountSandbox}
               AND u.created_at >= #{effectiveAt}
            """ + ELIGIBLE_RISK + """
               AND s.id IS NULL
            """)
    long totalPending(@Param("effectiveAt") LocalDateTime effectiveAt,
                      @Param("accountSandbox") int accountSandbox,
                      @Param("holdRisky") boolean holdRisky);

    @Select("""
            SELECT COUNT(*) FROM nx_user u
              LEFT JOIN nx_user inviter ON inviter.id = u.sponsor_user_id AND inviter.is_deleted = 0 AND inviter.status = 'ACTIVE'
               AND inviter.id<>u.id AND inviter.sandbox=u.sandbox
               AND EXISTS (SELECT 1 FROM nx_user_wallet w WHERE w.user_id=inviter.id AND w.is_deleted=0 AND w.sandbox=u.sandbox)
              LEFT JOIN nx_referral_reward_settlement s
                ON s.invited_user_id = u.id AND s.is_deleted = 0
             WHERE u.is_deleted = 0 AND u.status = 'ACTIVE'
               AND COALESCE(u.sandbox, 0) = #{accountSandbox}
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
              LEFT JOIN nx_user inviter ON inviter.id=s.inviter_user_id
             WHERE s.is_deleted=0
               AND COALESCE(invited.sandbox,0)=#{accountSandbox}
               AND (s.inviter_user_id IS NULL OR inviter.sandbox=#{accountSandbox})
             ORDER BY s.id DESC LIMIT #{limit}
            """)
    List<Map<String, Object>> recentSettlements(@Param("accountSandbox") int accountSandbox,
                                                @Param("limit") int limit);

    @Select("""
            SELECT s.id AS rowId,
                   s.settlement_no AS settlementNo, s.invited_user_id AS invitedUserId,
                   s.inviter_user_id AS inviterUserId, s.newcomer_usdt AS newcomerUsdt,
                   s.newcomer_nex AS newcomerNex, s.inviter_nex AS inviterNex,
                   s.status AS status, s.created_at AS createdAt
              FROM nx_referral_reward_settlement s
              JOIN nx_user invited ON invited.id=s.invited_user_id
              LEFT JOIN nx_user inviter ON inviter.id=s.inviter_user_id
             WHERE s.is_deleted=0
               AND COALESCE(invited.sandbox,0)=#{accountSandbox}
               AND (s.inviter_user_id IS NULL OR inviter.sandbox=#{accountSandbox})
               AND (#{beforeId} IS NULL OR s.id < #{beforeId})
             ORDER BY s.id DESC LIMIT #{limit}
            """)
    List<Map<String, Object>> recentSettlementsPage(
            @Param("accountSandbox") int accountSandbox,
            @Param("beforeId") Long beforeId,
            @Param("limit") int limit);

    @Select("SELECT freeze_gift FROM nx_user_registration_origin WHERE user_id=#{id} FOR UPDATE")
    Boolean freezeNewcomer(long id);

    @Insert("""
            INSERT INTO nx_registration_gift_hold(settlement_no,user_id,sandbox,run_id,usdt,nex,state,frozen_at)
            VALUES (#{no},#{user},#{sandbox},#{run},#{usdt},#{nex},'FROZEN',UTC_TIMESTAMP(6))
            """)
    void holdGift(@Param("no") String no,@Param("user") long user,@Param("sandbox") int sandbox,
                  @Param("run") String run,@Param("usdt") BigDecimal usdt,@Param("nex") BigDecimal nex);

    record GiftHold(String settlementNo, long userId, int sandbox, String runId, BigDecimal usdt,BigDecimal nex,String lockMode) {}
    @Select("""
            <script>
            SELECT h.settlement_no settlementNo,h.user_id userId,h.sandbox,h.run_id runId,h.usdt,h.nex,s.lock_mode lockMode
              FROM nx_registration_gift_hold h
              <choose><when test='sandbox == 1'>
                JOIN nx_h8_sandbox_referral_settlement s ON s.settlement_no=h.settlement_no AND s.run_id=h.run_id
              </when><otherwise>
                JOIN nx_referral_reward_settlement s ON s.settlement_no=h.settlement_no
              </otherwise></choose>
             WHERE h.user_id=#{user} AND h.sandbox=#{sandbox} AND h.state='FROZEN' FOR UPDATE
            </script>
            """)
    List<GiftHold> frozenForUser(@Param("user") long user,@Param("sandbox") int sandbox);

    @Select("SELECT order_no FROM nx_order WHERE user_id=#{id} AND payment_status='PAID' AND paid_at IS NOT NULL AND amount_usdt>0 AND is_deleted=0 ORDER BY paid_at,id LIMIT 1 FOR SHARE")
    String firstPaidOrder(long id);

    @Select("""
            SELECT COUNT(*) FROM nx_user u LEFT JOIN nx_user inviter ON inviter.id=u.sponsor_user_id AND inviter.sandbox=u.sandbox
             WHERE u.id=#{id} AND u.status='ACTIVE' AND u.is_deleted=0 AND u.sandbox=#{sandbox}
            """ + ELIGIBLE_RISK)
    int giftReleaseAllowed(@Param("id") long id,@Param("sandbox") int sandbox,@Param("holdRisky") boolean holdRisky);

    @Update("UPDATE nx_registration_gift_hold SET state='RELEASED',paid_order_no=#{orderNo},released_at=UTC_TIMESTAMP(6) WHERE settlement_no=#{no} AND state='FROZEN'")
    int releaseGift(@Param("no") String no,@Param("orderNo") String orderNo);

    default List<ReferralRow> findPendingSandboxReferral(LocalDateTime effectiveAt,String runId,Long user) {
        return findEligibleSandboxReferral(effectiveAt,runId,user,true);
    }
    default int insertSandboxSettlement(String no,Long user,Long inviter,BigDecimal usdt,BigDecimal nex,
            BigDecimal inviterNex,String mode,String snapshot,String operator,String reason,String key,String run,
            LocalDateTime effectiveAt) {
        return insertEligibleSandboxSettlement(no,user,inviter,usdt,nex,inviterNex,mode,snapshot,operator,reason,key,run,effectiveAt,"risk_bucket".equals(mode));
    }
    @Select("SELECT user_id FROM nx_order WHERE order_no=#{no} AND payment_status='PAID' AND paid_at IS NOT NULL AND amount_usdt>0 AND is_deleted=0")
    Long paidOrderUser(String no);

    record ReferralRow(Long invitedUserId, Long inviterUserId) {}
    record AppReferralAccount(String referralCode, Integer sandbox, BigDecimal walletNexAvailable) {}
    record AppReferralLedgerSummary(Long settledCount, BigDecimal lifetimeInviterNex) {}
    record AppReferralLedgerRow(String settlementNo, BigDecimal amountNex, String ledgerStatus,
                                BigDecimal balanceAfter, String releaseBucket,
                                String sourceEnvironment, LocalDateTime settledAt) {}
    record H8SandboxCommandRow(String requestHash, String status, String responseJson) {}
}
