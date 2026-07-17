package ffdd.opsconsole.growth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface ReferralRewardMapper extends BaseMapper<Object> {
    @Select("SELECT lock_key FROM nx_admin_operation_mutex WHERE lock_key = 'H8_REWARD' FOR UPDATE")
    String lockRewardMutation();

    @Select("""
            SELECT u.id AS invitedUserId, u.sponsor_user_id AS inviterUserId
              FROM nx_user u
              JOIN nx_user inviter ON inviter.id = u.sponsor_user_id AND inviter.is_deleted = 0
              LEFT JOIN nx_referral_reward_settlement s
                ON s.invited_user_id = u.id AND s.is_deleted = 0
             WHERE u.sponsor_user_id IS NOT NULL AND u.is_deleted = 0 AND u.status = 'ACTIVE'
               AND u.sponsor_user_id <> u.id
               AND u.created_at >= #{effectiveAt}
               AND NOT EXISTS (
                 SELECT 1 FROM nx_admin_risk_arbitrage_row risk
                  WHERE risk.is_deleted = 0
                    AND risk.disposition IN ('gift_blocked','account_flagged','cluster_frozen')
                    AND (CONCAT_WS('|', risk.cell1, risk.cell2, risk.cell3, risk.cell4, risk.cell5, risk.cell6)
                           LIKE CONCAT('%', CONCAT('U', LPAD(u.id, 8, '0')), '%')
                      OR CONCAT_WS('|', risk.cell1, risk.cell2, risk.cell3, risk.cell4, risk.cell5, risk.cell6)
                           LIKE CONCAT('%', CONCAT('U', LPAD(inviter.id, 8, '0')), '%'))
               )
               AND NOT EXISTS (
                 SELECT 1 FROM nx_admin_risk_multi_account_cluster cluster
                  WHERE cluster.is_deleted = 0 AND cluster.status = 'frozen'
                    AND cluster.nodes_json IS NOT NULL AND JSON_VALID(cluster.nodes_json) = 1
                    AND (JSON_SEARCH(cluster.nodes_json, 'one', CONCAT('U', LPAD(u.id, 8, '0'))) IS NOT NULL
                      OR JSON_SEARCH(cluster.nodes_json, 'one', CONCAT('U', LPAD(inviter.id, 8, '0'))) IS NOT NULL)
               )
               AND (#{holdRisky} = FALSE OR (
                 NOT EXISTS (
                   SELECT 1 FROM nx_admin_risk_arbitrage_row review_risk
                    WHERE review_risk.is_deleted = 0
                      AND (CONCAT_WS('|', review_risk.cell1, review_risk.cell2, review_risk.cell3, review_risk.cell4, review_risk.cell5, review_risk.cell6)
                             LIKE CONCAT('%', CONCAT('U', LPAD(u.id, 8, '0')), '%')
                        OR CONCAT_WS('|', review_risk.cell1, review_risk.cell2, review_risk.cell3, review_risk.cell4, review_risk.cell5, review_risk.cell6)
                             LIKE CONCAT('%', CONCAT('U', LPAD(inviter.id, 8, '0')), '%'))
                 )
                 AND NOT EXISTS (
                   SELECT 1 FROM nx_admin_risk_multi_account_cluster review_cluster
                    WHERE review_cluster.is_deleted = 0 AND review_cluster.status IN ('detected','flagged')
                      AND review_cluster.nodes_json IS NOT NULL AND JSON_VALID(review_cluster.nodes_json) = 1
                      AND (JSON_SEARCH(review_cluster.nodes_json, 'one', CONCAT('U', LPAD(u.id, 8, '0'))) IS NOT NULL
                        OR JSON_SEARCH(review_cluster.nodes_json, 'one', CONCAT('U', LPAD(inviter.id, 8, '0'))) IS NOT NULL)
                 )
               ))
               AND s.id IS NULL
             ORDER BY u.created_at ASC, u.id ASC
             LIMIT #{limit}
            """)
    List<ReferralRow> findPendingReferrals(@Param("effectiveAt") LocalDateTime effectiveAt,
                                           @Param("holdRisky") boolean holdRisky,
                                           @Param("limit") int limit);

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
              JOIN nx_user inviter ON inviter.id = u.sponsor_user_id AND inviter.is_deleted = 0
             WHERE u.id = #{invitedUserId} AND inviter.id = #{inviterUserId}
               AND u.sponsor_user_id IS NOT NULL AND u.is_deleted = 0 AND u.status = 'ACTIVE'
               AND u.sponsor_user_id <> u.id AND u.created_at >= #{effectiveAt}
               AND NOT EXISTS (
                 SELECT 1 FROM nx_admin_risk_arbitrage_row risk
                  WHERE risk.is_deleted = 0
                    AND risk.disposition IN ('gift_blocked','account_flagged','cluster_frozen')
                    AND (CONCAT_WS('|', risk.cell1, risk.cell2, risk.cell3, risk.cell4, risk.cell5, risk.cell6)
                           LIKE CONCAT('%', CONCAT('U', LPAD(u.id, 8, '0')), '%')
                      OR CONCAT_WS('|', risk.cell1, risk.cell2, risk.cell3, risk.cell4, risk.cell5, risk.cell6)
                           LIKE CONCAT('%', CONCAT('U', LPAD(inviter.id, 8, '0')), '%'))
               )
               AND NOT EXISTS (
                 SELECT 1 FROM nx_admin_risk_multi_account_cluster cluster
                  WHERE cluster.is_deleted = 0 AND cluster.status = 'frozen'
                    AND cluster.nodes_json IS NOT NULL AND JSON_VALID(cluster.nodes_json) = 1
                    AND (JSON_SEARCH(cluster.nodes_json, 'one', CONCAT('U', LPAD(u.id, 8, '0'))) IS NOT NULL
                      OR JSON_SEARCH(cluster.nodes_json, 'one', CONCAT('U', LPAD(inviter.id, 8, '0'))) IS NOT NULL)
               )
               AND (#{holdRisky} = FALSE OR (
                 NOT EXISTS (
                   SELECT 1 FROM nx_admin_risk_arbitrage_row review_risk
                    WHERE review_risk.is_deleted = 0
                      AND (CONCAT_WS('|', review_risk.cell1, review_risk.cell2, review_risk.cell3, review_risk.cell4, review_risk.cell5, review_risk.cell6)
                             LIKE CONCAT('%', CONCAT('U', LPAD(u.id, 8, '0')), '%')
                        OR CONCAT_WS('|', review_risk.cell1, review_risk.cell2, review_risk.cell3, review_risk.cell4, review_risk.cell5, review_risk.cell6)
                             LIKE CONCAT('%', CONCAT('U', LPAD(inviter.id, 8, '0')), '%'))
                 )
                 AND NOT EXISTS (
                   SELECT 1 FROM nx_admin_risk_multi_account_cluster review_cluster
                    WHERE review_cluster.is_deleted = 0 AND review_cluster.status IN ('detected','flagged')
                      AND review_cluster.nodes_json IS NOT NULL AND JSON_VALID(review_cluster.nodes_json) = 1
                      AND (JSON_SEARCH(review_cluster.nodes_json, 'one', CONCAT('U', LPAD(u.id, 8, '0'))) IS NOT NULL
                        OR JSON_SEARCH(review_cluster.nodes_json, 'one', CONCAT('U', LPAD(inviter.id, 8, '0'))) IS NOT NULL)
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
                         @Param("holdRisky") boolean holdRisky);

    @Insert("""
            INSERT INTO nx_user_wallet (
              user_id, usdt_available, nex_available, pending_withdraw, lifetime_earned,
              version, created_at, updated_at, is_deleted
            ) VALUES (#{userId}, #{usdt}, #{nex}, 0, #{usdt} + #{nex}, 0, NOW(), NOW(), 0)
            ON DUPLICATE KEY UPDATE
              usdt_available = usdt_available + VALUES(usdt_available),
              nex_available = nex_available + VALUES(nex_available),
              lifetime_earned = lifetime_earned + VALUES(lifetime_earned),
              version = version + 1, updated_at = NOW(), is_deleted = 0
            """)
    int creditWallet(@Param("userId") Long userId, @Param("usdt") BigDecimal usdt, @Param("nex") BigDecimal nex);

    @Select("SELECT COUNT(*) FROM nx_referral_reward_settlement WHERE is_deleted = 0")
    long totalSettled();

    @Select("""
            SELECT COUNT(*) FROM nx_user u
              JOIN nx_user inviter ON inviter.id = u.sponsor_user_id AND inviter.is_deleted = 0
              LEFT JOIN nx_referral_reward_settlement s ON s.invited_user_id = u.id AND s.is_deleted = 0
             WHERE u.sponsor_user_id IS NOT NULL AND u.is_deleted = 0 AND u.status = 'ACTIVE'
               AND u.sponsor_user_id <> u.id
               AND u.created_at >= #{effectiveAt}
               AND NOT EXISTS (
                 SELECT 1 FROM nx_admin_risk_arbitrage_row risk
                  WHERE risk.is_deleted = 0
                    AND risk.disposition IN ('gift_blocked','account_flagged','cluster_frozen')
                    AND (CONCAT_WS('|', risk.cell1, risk.cell2, risk.cell3, risk.cell4, risk.cell5, risk.cell6)
                           LIKE CONCAT('%', CONCAT('U', LPAD(u.id, 8, '0')), '%')
                      OR CONCAT_WS('|', risk.cell1, risk.cell2, risk.cell3, risk.cell4, risk.cell5, risk.cell6)
                           LIKE CONCAT('%', CONCAT('U', LPAD(inviter.id, 8, '0')), '%'))
               )
               AND NOT EXISTS (
                 SELECT 1 FROM nx_admin_risk_multi_account_cluster cluster
                  WHERE cluster.is_deleted = 0 AND cluster.status = 'frozen'
                    AND cluster.nodes_json IS NOT NULL AND JSON_VALID(cluster.nodes_json) = 1
                    AND (JSON_SEARCH(cluster.nodes_json, 'one', CONCAT('U', LPAD(u.id, 8, '0'))) IS NOT NULL
                      OR JSON_SEARCH(cluster.nodes_json, 'one', CONCAT('U', LPAD(inviter.id, 8, '0'))) IS NOT NULL)
               )
               AND (#{holdRisky} = FALSE OR (
                 NOT EXISTS (
                   SELECT 1 FROM nx_admin_risk_arbitrage_row review_risk
                    WHERE review_risk.is_deleted = 0
                      AND (CONCAT_WS('|', review_risk.cell1, review_risk.cell2, review_risk.cell3, review_risk.cell4, review_risk.cell5, review_risk.cell6)
                             LIKE CONCAT('%', CONCAT('U', LPAD(u.id, 8, '0')), '%')
                        OR CONCAT_WS('|', review_risk.cell1, review_risk.cell2, review_risk.cell3, review_risk.cell4, review_risk.cell5, review_risk.cell6)
                             LIKE CONCAT('%', CONCAT('U', LPAD(inviter.id, 8, '0')), '%'))
                 )
                 AND NOT EXISTS (
                   SELECT 1 FROM nx_admin_risk_multi_account_cluster review_cluster
                    WHERE review_cluster.is_deleted = 0 AND review_cluster.status IN ('detected','flagged')
                      AND review_cluster.nodes_json IS NOT NULL AND JSON_VALID(review_cluster.nodes_json) = 1
                      AND (JSON_SEARCH(review_cluster.nodes_json, 'one', CONCAT('U', LPAD(u.id, 8, '0'))) IS NOT NULL
                        OR JSON_SEARCH(review_cluster.nodes_json, 'one', CONCAT('U', LPAD(inviter.id, 8, '0'))) IS NOT NULL)
                 )
               ))
               AND s.id IS NULL
            """)
    long totalPending(@Param("effectiveAt") LocalDateTime effectiveAt,
                      @Param("holdRisky") boolean holdRisky);

    @Select("""
            SELECT COUNT(*) FROM nx_user u
              JOIN nx_user inviter ON inviter.id = u.sponsor_user_id AND inviter.is_deleted = 0
             WHERE u.sponsor_user_id IS NOT NULL AND u.is_deleted = 0 AND u.status = 'ACTIVE'
               AND u.created_at >= #{effectiveAt}
               AND (EXISTS (
                 SELECT 1 FROM nx_admin_risk_arbitrage_row risk
                  WHERE risk.is_deleted = 0
                    AND risk.disposition IN ('gift_blocked','account_flagged','cluster_frozen')
                    AND (CONCAT_WS('|', risk.cell1, risk.cell2, risk.cell3, risk.cell4, risk.cell5, risk.cell6)
                           LIKE CONCAT('%', CONCAT('U', LPAD(u.id, 8, '0')), '%')
                      OR CONCAT_WS('|', risk.cell1, risk.cell2, risk.cell3, risk.cell4, risk.cell5, risk.cell6)
                           LIKE CONCAT('%', CONCAT('U', LPAD(inviter.id, 8, '0')), '%'))
               ) OR EXISTS (
                 SELECT 1 FROM nx_admin_risk_multi_account_cluster cluster
                  WHERE cluster.is_deleted = 0 AND cluster.status = 'frozen'
                    AND cluster.nodes_json IS NOT NULL AND JSON_VALID(cluster.nodes_json) = 1
                    AND (JSON_SEARCH(cluster.nodes_json, 'one', CONCAT('U', LPAD(u.id, 8, '0'))) IS NOT NULL
                      OR JSON_SEARCH(cluster.nodes_json, 'one', CONCAT('U', LPAD(inviter.id, 8, '0'))) IS NOT NULL)
               ) OR (#{holdRisky} = TRUE AND (
                 EXISTS (
                   SELECT 1 FROM nx_admin_risk_arbitrage_row review_risk
                    WHERE review_risk.is_deleted = 0
                      AND (CONCAT_WS('|', review_risk.cell1, review_risk.cell2, review_risk.cell3, review_risk.cell4, review_risk.cell5, review_risk.cell6)
                             LIKE CONCAT('%', CONCAT('U', LPAD(u.id, 8, '0')), '%')
                        OR CONCAT_WS('|', review_risk.cell1, review_risk.cell2, review_risk.cell3, review_risk.cell4, review_risk.cell5, review_risk.cell6)
                             LIKE CONCAT('%', CONCAT('U', LPAD(inviter.id, 8, '0')), '%'))
                 ) OR EXISTS (
                   SELECT 1 FROM nx_admin_risk_multi_account_cluster review_cluster
                    WHERE review_cluster.is_deleted = 0 AND review_cluster.status IN ('detected','flagged')
                      AND review_cluster.nodes_json IS NOT NULL AND JSON_VALID(review_cluster.nodes_json) = 1
                      AND (JSON_SEARCH(review_cluster.nodes_json, 'one', CONCAT('U', LPAD(u.id, 8, '0'))) IS NOT NULL
                        OR JSON_SEARCH(review_cluster.nodes_json, 'one', CONCAT('U', LPAD(inviter.id, 8, '0'))) IS NOT NULL)
                 )
               )))
            """)
    long totalBlockedByK2(@Param("effectiveAt") LocalDateTime effectiveAt,
                          @Param("holdRisky") boolean holdRisky);

    @Select("""
            SELECT settlement_no AS settlementNo, invited_user_id AS invitedUserId,
                   inviter_user_id AS inviterUserId, newcomer_usdt AS newcomerUsdt,
                   newcomer_nex AS newcomerNex, inviter_nex AS inviterNex, status, created_at AS createdAt
              FROM nx_referral_reward_settlement
             WHERE is_deleted = 0 ORDER BY id DESC LIMIT #{limit}
            """)
    List<Map<String, Object>> recentSettlements(@Param("limit") int limit);

    record ReferralRow(Long invitedUserId, Long inviterUserId) {}
}
