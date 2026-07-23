package ffdd.opsconsole.market.mapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Persistence boundary for the server-canonical G7 repurchase vault. */
@Mapper
// Statement-only SQL boundary: repurchase commands span products, positions, tickets and wallets.
@SuppressWarnings("MybatisPlusBaseMapper")
public interface AppRepurchaseMapper {
    @Select("""
            SELECT id,product_code AS productCode,name AS productName,asset,term_days AS termDays,
                   apy_bps AS apyBps,early_penalty_bps AS earlyPenaltyBps,min_amount AS minAmount,
                   reward_multiplier AS rewardMultiplier,ticket_per_order AS ticketPerOrder,
                   preset_amounts AS presetAmounts,UPPER(status) AS status
              FROM nx_staking_product
             WHERE product_code='REPURCHASE_90D' AND is_deleted=0 LIMIT 1
            """)
    ProductRow product();

    @Select("""
            SELECT id,product_code AS productCode,name AS productName,asset,term_days AS termDays,
                   apy_bps AS apyBps,early_penalty_bps AS earlyPenaltyBps,min_amount AS minAmount,
                   reward_multiplier AS rewardMultiplier,ticket_per_order AS ticketPerOrder,
                   preset_amounts AS presetAmounts,UPPER(status) AS status
              FROM nx_staking_product
             WHERE product_code='REPURCHASE_90D' AND is_deleted=0 LIMIT 1 FOR UPDATE
            """)
    ProductRow lockProduct();

    @Update("UPDATE nx_staking_product SET apy_bps=#{value},updated_at=NOW() WHERE product_code='REPURCHASE_90D' AND is_deleted=0")
    int updateApyBps(@Param("value") BigDecimal value);

    @Update("UPDATE nx_staking_product SET term_days=#{value},updated_at=NOW() WHERE product_code='REPURCHASE_90D' AND is_deleted=0")
    int updateLockDays(@Param("value") int value);

    @Update("UPDATE nx_staking_product SET reward_multiplier=#{value},updated_at=NOW() WHERE product_code='REPURCHASE_90D' AND is_deleted=0")
    int updateNurture(@Param("value") BigDecimal value);

    @Update("UPDATE nx_staking_product SET ticket_per_order=#{value},updated_at=NOW() WHERE product_code='REPURCHASE_90D' AND is_deleted=0")
    int updateTicketPerOrder(@Param("value") int value);

    @Update("UPDATE nx_staking_product SET early_penalty_bps=#{value},updated_at=NOW() WHERE product_code='REPURCHASE_90D' AND is_deleted=0")
    int updatePenaltyBps(@Param("value") BigDecimal value);

    @Update("UPDATE nx_staking_product SET preset_amounts=#{value},updated_at=NOW() WHERE product_code='REPURCHASE_90D' AND is_deleted=0")
    int updatePresets(@Param("value") String value);

    @Select("SELECT id FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0 FOR UPDATE")
    Long lockActiveUser(@Param("userId") Long userId);

    @Select("""
            SELECT setting_value FROM nx_emergency_control_setting
             WHERE setting_key=#{settingKey} AND is_deleted=0
             LIMIT 1
            """)
    String controlValue(@Param("settingKey") String settingKey);

    @Select("""
            SELECT price_usdt FROM nx_price_index
             WHERE is_deleted=0 AND status='ACTIVE' AND metric_code IN ('NEX','NEX_USDT')
             ORDER BY sampled_at DESC,id DESC LIMIT 1
            """)
    BigDecimal latestNexUsdtPrice();

    @Select("SELECT usdt_available FROM nx_user_wallet WHERE user_id=#{userId} AND is_deleted=0 LIMIT 1 FOR UPDATE")
    BigDecimal lockWallet(@Param("userId") Long userId);

    @Select("SELECT usdt_available FROM nx_user_wallet WHERE user_id=#{userId} AND is_deleted=0 LIMIT 1")
    BigDecimal wallet(@Param("userId") Long userId);

    @Update("""
            UPDATE nx_user_wallet SET usdt_available=usdt_available-#{amount},version=version+1,updated_at=NOW()
             WHERE user_id=#{userId} AND is_deleted=0 AND usdt_available>=#{amount}
            """)
    int debitWallet(@Param("userId") Long userId, @Param("amount") BigDecimal amount);

    @Update("""
            UPDATE nx_user_wallet SET usdt_available=usdt_available+#{amount},version=version+1,updated_at=NOW()
             WHERE user_id=#{userId} AND is_deleted=0
            """)
    int creditWallet(@Param("userId") Long userId, @Param("amount") BigDecimal amount);

    @Insert("""
            INSERT INTO nx_staking_position
              (user_id,position_no,product_id,product_code,product_name,amount_usdt,apy_bps,
               early_penalty_bps,term_days,locked_at,unlock_at,estimated_interest_usdt,status,
               created_at,updated_at,is_deleted)
            VALUES
              (#{userId},#{positionNo},#{productId},#{productCode},#{productName},#{amountUsdt},#{apyBps},
               #{earlyPenaltyBps},#{termDays},#{lockedAt},#{unlockAt},#{interestUsdt},'ACTIVE',NOW(),NOW(),0)
            """)
    int insertPosition(PositionWrite row);

    @Select("""
            SELECT id,user_id AS userId,position_no AS positionNo,amount_usdt AS amountUsdt,
                   apy_bps AS apyBps,early_penalty_bps AS earlyPenaltyBps,term_days AS termDays,
                   locked_at AS lockedAt,unlock_at AS unlockAt,estimated_interest_usdt AS interestUsdt,
                   UPPER(status) AS status,claimed_at AS claimedAt,early_withdrawn_at AS earlyWithdrawnAt
              FROM nx_staking_position
             WHERE user_id=#{userId} AND product_code='REPURCHASE_90D' AND is_deleted=0
             ORDER BY created_at DESC,id DESC
            """)
    List<PositionRow> positions(@Param("userId") Long userId);

    @Select("""
            <script>
            SELECT sp.id,sp.user_id AS userId,CONCAT('U',LPAD(u.id,8,'0')) AS userNo,u.nickname,
                   sp.position_no AS positionNo,sp.amount_usdt AS amountUsdt,
                   sp.apy_bps AS apyBps,sp.early_penalty_bps AS earlyPenaltyBps,
                   sp.term_days AS termDays,sp.locked_at AS lockedAt,sp.unlock_at AS unlockAt,
                   sp.estimated_interest_usdt AS interestUsdt,UPPER(sp.status) AS status,
                   sp.claimed_at AS claimedAt,sp.early_withdrawn_at AS earlyWithdrawnAt
              FROM nx_staking_position sp
              JOIN nx_user u ON u.id=sp.user_id AND u.is_deleted=0
             WHERE sp.product_code='REPURCHASE_90D' AND sp.is_deleted=0
               <if test="status != null and status != ''">AND UPPER(sp.status)=#{status}</if>
               <if test="cursor != null">AND sp.id &lt; #{cursor}</if>
             ORDER BY sp.id DESC
             LIMIT #{limit}
            </script>
            """)
    List<AdminOrderRow> adminOrders(@Param("status") String status, @Param("cursor") Long cursor,
                                    @Param("limit") int limit);

    @Select("""
            SELECT id,user_id AS userId,position_no AS positionNo,amount_usdt AS amountUsdt,
                   apy_bps AS apyBps,early_penalty_bps AS earlyPenaltyBps,term_days AS termDays,
                   locked_at AS lockedAt,unlock_at AS unlockAt,estimated_interest_usdt AS interestUsdt,
                   UPPER(status) AS status,claimed_at AS claimedAt,early_withdrawn_at AS earlyWithdrawnAt
              FROM nx_staking_position
             WHERE user_id=#{userId} AND position_no=#{positionNo} AND product_code='REPURCHASE_90D'
               AND is_deleted=0 LIMIT 1 FOR UPDATE
            """)
    PositionRow lockPosition(@Param("userId") Long userId, @Param("positionNo") String positionNo);

    @Update("""
            UPDATE nx_staking_position SET status='MATURE_UNCLAIMED',updated_at=NOW()
             WHERE user_id=#{userId} AND product_code='REPURCHASE_90D' AND is_deleted=0
               AND UPPER(status)='ACTIVE' AND unlock_at<=#{now}
            """)
    int matureDue(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_staking_position SET status='CLAIMED',claimed_at=#{now},updated_at=NOW()
             WHERE id=#{id} AND user_id=#{userId} AND product_code='REPURCHASE_90D' AND is_deleted=0
               AND UPPER(status) IN ('ACTIVE','MATURE_UNCLAIMED') AND unlock_at<=#{now}
            """)
    int markClaimed(@Param("id") Long id, @Param("userId") Long userId, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_staking_position SET status='EARLY_WITHDRAWN',early_withdrawn_at=#{now},updated_at=NOW()
             WHERE id=#{id} AND user_id=#{userId} AND product_code='REPURCHASE_90D' AND is_deleted=0
               AND UPPER(status)='ACTIVE' AND unlock_at>#{now}
            """)
    int markEarly(@Param("id") Long id, @Param("userId") Long userId, @Param("now") LocalDateTime now);

    @Insert("""
            INSERT INTO nx_wallet_ledger
              (user_id,biz_no,biz_type,asset,direction,amount,balance_after,status,remark,created_at,updated_at,is_deleted)
            VALUES
              (#{userId},#{bizNo},#{bizType},'USDT',#{direction},#{amount},#{balanceAfter},'SUCCESS',#{remark},NOW(),NOW(),0)
            """)
    int insertLedger(LedgerWrite row);

    @Select("""
            SELECT COALESCE(SUM(quantity),0) FROM nx_g7_repurchase_ticket
             WHERE is_deleted=0 AND issued_at>=DATE_FORMAT(NOW(),'%Y-%m-01')
            """)
    long issuedTicketsThisMonth();

    @Insert("""
            INSERT INTO nx_g7_repurchase_ticket
              (ticket_no,user_id,position_no,quantity,status,issued_at,created_at,updated_at,is_deleted)
            VALUES (#{ticketNo},#{userId},#{positionNo},#{quantity},'AVAILABLE',#{issuedAt},NOW(),NOW(),0)
            """)
    int insertTicket(TicketWrite row);

    @Update("""
            UPDATE nx_g7_repurchase_ticket SET status='FORFEITED',forfeited_at=#{now},updated_at=NOW()
             WHERE user_id=#{userId} AND position_no=#{positionNo} AND status='AVAILABLE' AND is_deleted=0
            """)
    int forfeitTickets(@Param("userId") Long userId, @Param("positionNo") String positionNo,
                       @Param("now") LocalDateTime now);

    @Select("""
            SELECT COALESCE((SELECT config_value FROM nx_config_item
                              WHERE config_key='growth.phase.current' AND status=1 AND is_deleted=0 LIMIT 1),'P1') AS phase,
                   GREATEST(TIMESTAMPDIFF(MONTH,u.created_at,NOW()),0) AS accountAgeMonths,
                   DATE_FORMAT(u.created_at,'%x-W%v') AS cohort
              FROM nx_user u WHERE u.id=#{userId} AND u.status='ACTIVE' AND u.is_deleted=0
            """)
    UserAttribution attribution(@Param("userId") Long userId);

    @Select("""
            SELECT config_value FROM nx_config_item
             WHERE config_key=#{key} AND status=1 AND is_deleted=0 LIMIT 1
            """)
    String configValue(@Param("key") String key);

    record ProductRow(Long id, String productCode, String productName, String asset, Integer termDays,
                      BigDecimal apyBps, BigDecimal earlyPenaltyBps, BigDecimal minAmount,
                      BigDecimal rewardMultiplier, Integer ticketPerOrder, String presetAmounts, String status) {}
    record PositionRow(Long id, Long userId, String positionNo, BigDecimal amountUsdt, BigDecimal apyBps,
                       BigDecimal earlyPenaltyBps, Integer termDays, LocalDateTime lockedAt, LocalDateTime unlockAt,
                       BigDecimal interestUsdt, String status, LocalDateTime claimedAt,
                       LocalDateTime earlyWithdrawnAt) {}
    record PositionWrite(Long userId, String positionNo, Long productId, String productCode, String productName,
                         BigDecimal amountUsdt, BigDecimal apyBps, BigDecimal earlyPenaltyBps, Integer termDays,
                         LocalDateTime lockedAt, LocalDateTime unlockAt, BigDecimal interestUsdt) {}
    record LedgerWrite(Long userId, String bizNo, String bizType, String direction, BigDecimal amount,
                       BigDecimal balanceAfter, String remark) {}
    record TicketWrite(String ticketNo, Long userId, String positionNo, Integer quantity, LocalDateTime issuedAt) {}
    record UserAttribution(String phase, Integer accountAgeMonths, String cohort) {}
    record AdminOrderRow(Long id, Long userId, String userNo, String nickname, String positionNo,
                         BigDecimal amountUsdt, BigDecimal apyBps, BigDecimal earlyPenaltyBps,
                         Integer termDays, LocalDateTime lockedAt, LocalDateTime unlockAt,
                         BigDecimal interestUsdt, String status, LocalDateTime claimedAt,
                         LocalDateTime earlyWithdrawnAt) {}
}
