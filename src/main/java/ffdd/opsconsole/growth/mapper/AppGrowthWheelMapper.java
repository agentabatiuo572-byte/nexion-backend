package ffdd.opsconsole.growth.mapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Transactional persistence boundary for H4 server-authoritative wheel spins. */
@Mapper
// Statement-only wheel transaction boundary spanning events, wallets and reward ledgers.
@SuppressWarnings("MybatisPlusBaseMapper")
public interface AppGrowthWheelMapper {

    @Select("SELECT id FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0 FOR UPDATE")
    Long lockActiveUser(@Param("userId") Long userId);

    @Select("SELECT lock_key FROM nx_admin_operation_mutex WHERE lock_key='H4_WHEEL_PAYOUT' FOR UPDATE")
    String lockWheelPayoutMutex();

    @Select("""
            SELECT id eventId,quest_code eventCode
              FROM nx_event_quest
             WHERE quest_code=#{eventCode} AND LOWER(target_type)='wheel'
               AND status=1 AND is_deleted=0
               AND (starts_at IS NULL OR starts_at<=UTC_TIMESTAMP())
               AND (ends_at IS NULL OR ends_at>=UTC_TIMESTAMP())
             LIMIT 1 FOR UPDATE
            """)
    WheelEvent lockOpenWheelEvent(@Param("eventCode") String eventCode);

    @Select("""
            SELECT COUNT(1) FROM nx_growth_wheel_spin
             WHERE event_id=#{eventId} AND user_id=#{userId} AND spin_date=#{spinDate}
               AND source_type='DAILY' AND is_deleted=0
            """)
    int countDailySpin(
            @Param("eventId") Long eventId,
            @Param("userId") Long userId,
            @Param("spinDate") LocalDate spinDate);

    @Select("""
            SELECT ticket_id ticketId
              FROM nx_growth_spin_ticket
             WHERE user_id=#{userId} AND status='AVAILABLE' AND is_deleted=0
             ORDER BY created_at,id LIMIT 1 FOR UPDATE
            """)
    SpinTicket lockAvailableTicket(@Param("userId") Long userId);

    @Update("""
            UPDATE nx_growth_spin_ticket
               SET status='USED',used_event_code=#{eventCode},spin_date=#{spinDate},used_at=NOW(),updated_at=NOW()
             WHERE ticket_id=#{ticketId} AND user_id=#{userId} AND status='AVAILABLE' AND is_deleted=0
            """)
    int consumeTicket(
            @Param("ticketId") String ticketId,
            @Param("userId") Long userId,
            @Param("eventCode") String eventCode,
            @Param("spinDate") LocalDate spinDate);

    @Select("""
            SELECT id tierId,tier_name tierName,reward_name rewardName,
                   probability_pct probabilityPct,real_outflow realOutflow,
                   LOWER(reward_kind) rewardKind,reward_amount rewardAmount,
                   voucher_id voucherId,daily_stock dailyStock
              FROM nx_growth_wheel_tier
             WHERE status=1 AND is_deleted=0
             ORDER BY sort_order,id FOR UPDATE
            """)
    List<WheelTier> lockActiveTiers();

    @Select("""
            SELECT guard_value FROM nx_growth_wheel_guard
             WHERE guard_key=#{guardKey} AND status=1 AND is_deleted=0 LIMIT 1 FOR UPDATE
            """)
    String lockGuardValue(@Param("guardKey") String guardKey);

    @Select("""
            SELECT COALESCE(SUM(reward_amount),0)
              FROM nx_growth_wheel_spin
             WHERE spin_date=#{spinDate} AND real_outflow=1 AND is_deleted=0
            """)
    BigDecimal currentDailyRealPayout(@Param("spinDate") LocalDate spinDate);

    @Select("""
            SELECT COUNT(1) FROM nx_growth_wheel_spin
             WHERE spin_date=#{spinDate} AND tier_id=#{tierId} AND is_deleted=0
            """)
    int currentTierDailyAwards(@Param("spinDate") LocalDate spinDate, @Param("tierId") Long tierId);

    @Insert("""
            INSERT IGNORE INTO nx_growth_wheel_spin
              (spin_no,event_id,event_code,user_id,spin_date,source_type,source_id,
               tier_id,tier_name,reward_kind,reward_amount,real_outflow,downgraded,
               downgrade_reason,created_at,updated_at,is_deleted)
            VALUES
              (#{spinNo},#{event.eventId},#{event.eventCode},#{userId},#{spinDate},#{sourceType},#{sourceId},
               #{tier.tierId},#{tier.tierName},#{tier.rewardKind},#{tier.rewardAmount},#{tier.realOutflow},
               #{downgraded},#{downgradeReason},NOW(),NOW(),0)
            """)
    int insertSpin(
            @Param("spinNo") String spinNo,
            @Param("event") WheelEvent event,
            @Param("userId") Long userId,
            @Param("spinDate") LocalDate spinDate,
            @Param("sourceType") String sourceType,
            @Param("sourceId") String sourceId,
            @Param("tier") WheelTier tier,
            @Param("downgraded") boolean downgraded,
            @Param("downgradeReason") String downgradeReason);

    @Select("SELECT nex_available FROM nx_user_wallet WHERE user_id=#{userId} AND is_deleted=0 LIMIT 1 FOR UPDATE")
    BigDecimal lockWalletNex(@Param("userId") Long userId);

    @Select("SELECT usdt_available FROM nx_user_wallet WHERE user_id=#{userId} AND is_deleted=0 LIMIT 1 FOR UPDATE")
    BigDecimal lockWalletUsdt(@Param("userId") Long userId);

    @Update("""
            UPDATE nx_user_wallet SET nex_available=nex_available+#{amount},
                   lifetime_earned=lifetime_earned+#{amount},version=version+1,updated_at=NOW()
             WHERE user_id=#{userId} AND is_deleted=0
            """)
    int creditWalletNex(@Param("userId") Long userId, @Param("amount") BigDecimal amount);

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
              (#{userId},#{bizNo},'WHEEL_REWARD',#{asset},'IN',#{amount},#{balanceAfter},'POSTED',
               'H4 Lucky Spin reward',NOW(),NOW(),0)
            """)
    int insertWalletLedger(
            @Param("userId") Long userId,
            @Param("bizNo") String bizNo,
            @Param("asset") String asset,
            @Param("amount") BigDecimal amount,
            @Param("balanceAfter") BigDecimal balanceAfter);

    @Select("SELECT COALESCE(SUM(points),0) FROM nx_points_ledger WHERE user_id=#{userId} AND is_deleted=0")
    Integer currentPointsBalance(@Param("userId") Long userId);

    @Insert("""
            INSERT INTO nx_points_ledger
              (user_id,biz_no,biz_type,points,balance_after,created_at,updated_at,is_deleted)
            VALUES
              (#{userId},#{bizNo},'WHEEL_REWARD',#{points},#{balanceAfter},NOW(),NOW(),0)
            """)
    int insertPointsLedger(
            @Param("userId") Long userId,
            @Param("bizNo") String bizNo,
            @Param("points") int points,
            @Param("balanceAfter") int balanceAfter);

    @Select("""
            SELECT COALESCE((SELECT config_value FROM nx_config_item
                              WHERE config_key='growth.phase.current' AND status=1 AND is_deleted=0 LIMIT 1),'P1') phase,
                   GREATEST(TIMESTAMPDIFF(MONTH,u.created_at,NOW()),0) accountAgeMonths,
                   DATE_FORMAT(u.created_at,'%x-W%v') cohort
              FROM nx_user u WHERE u.id=#{userId} AND u.is_deleted=0 LIMIT 1
            """)
    Attribution attribution(@Param("userId") Long userId);

    record WheelEvent(Long eventId, String eventCode) {
    }

    record SpinTicket(String ticketId) {
    }

    record WheelTier(
            Long tierId,
            String tierName,
            String rewardName,
            BigDecimal probabilityPct,
            Boolean realOutflow,
            String rewardKind,
            BigDecimal rewardAmount,
            String voucherId,
            Integer dailyStock) {
    }

    record Attribution(String phase, Integer accountAgeMonths, String cohort) {
    }
}
