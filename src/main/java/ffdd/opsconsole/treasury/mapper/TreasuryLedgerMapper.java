package ffdd.opsconsole.treasury.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.treasury.domain.TreasuryLedgerBillView;
import ffdd.opsconsole.treasury.infrastructure.WalletLedgerEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
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
               AND status IN ('PENDING', 'REVIEWING', 'PENDING_CHAIN', 'CHAIN_SUBMITTED')
            """)
    BigDecimal sumActiveWithdrawalQueueUsdt();

    @Select("""
            SELECT COUNT(*)
              FROM nx_withdrawal_order
             WHERE is_deleted = 0
               AND asset = 'USDT'
               AND status IN ('PENDING', 'REVIEWING', 'PENDING_CHAIN', 'CHAIN_SUBMITTED')
            """)
    long countActiveWithdrawalQueue();

    @Select("""
            SELECT COALESCE(AVG(COALESCE(rd.risk_score, 0)), 0)
              FROM nx_withdrawal_order w
              LEFT JOIN nx_risk_decision rd ON rd.id = w.risk_decision_id AND rd.is_deleted = 0
             WHERE w.is_deleted = 0
               AND w.asset = 'USDT'
               AND w.status IN ('PENDING', 'REVIEWING', 'PENDING_CHAIN', 'CHAIN_SUBMITTED')
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
            SELECT COALESCE(SUM(CASE WHEN direction = 'IN' THEN amount ELSE -amount END), 0)
              FROM nx_wallet_ledger
             WHERE is_deleted = 0
               AND asset = 'USDT'
               AND status IN ('SUCCESS', 'PENDING')
               AND created_at >= #{startAt}
               AND created_at &lt; #{endAt}
            </script>
            """)
    BigDecimal sumNetUsdtFlowBetween(@Param("startAt") LocalDateTime startAt, @Param("endAt") LocalDateTime endAt);

    @Select("""
            <script>
            SELECT COUNT(1)
              FROM nx_wallet_ledger l
              LEFT JOIN nx_user u ON u.id = l.user_id AND u.is_deleted = 0
             WHERE l.is_deleted = 0
             <if test='type != null and type != ""'>AND l.biz_type = #{type}</if>
             <if test='userId != null'>AND l.user_id = #{userId}</if>
             <if test='keyword != null and keyword != ""'>
               AND (l.biz_no LIKE CONCAT('%', #{keyword}, '%')
                    OR l.remark LIKE CONCAT('%', #{keyword}, '%')
                    OR CONCAT('U', LPAD(l.user_id, 8, '0')) LIKE CONCAT('%', #{keyword}, '%')
                    OR u.nickname LIKE CONCAT('%', #{keyword}, '%'))
             </if>
            </script>
            """)
    long countLedgerBills(@Param("type") String type, @Param("userId") Long userId, @Param("keyword") String keyword);

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
             <if test='type != null and type != ""'>AND l.biz_type = #{type}</if>
             <if test='userId != null'>AND l.user_id = #{userId}</if>
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
                                                 @Param("keyword") String keyword, @Param("pageSize") int pageSize,
                                                 @Param("offset") int offset);

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

    @Insert("""
            INSERT INTO nx_wallet_asset_adjustment (
                adjustment_no, user_id, asset, direction, amount, reason_code, reason, maker, status, created_at, updated_at, is_deleted
            ) VALUES (
                #{adjustmentNo}, #{userId}, #{asset}, #{direction}, #{amount}, 'OPS_TREASURY_LEDGER_ADJUSTMENT',
                CONCAT(#{reason}, ' | relatedBizNo=', COALESCE(#{relatedBizNo}, '')),
                #{operator}, 'PENDING_REVIEW', NOW(), NOW(), 0
            )
            """)
    int insertLedgerAdjustment(@Param("adjustmentNo") String adjustmentNo, @Param("userId") Long userId,
                               @Param("asset") String asset, @Param("direction") String direction,
                               @Param("amount") BigDecimal amount, @Param("relatedBizNo") String relatedBizNo,
                               @Param("reason") String reason, @Param("operator") String operator);
}
