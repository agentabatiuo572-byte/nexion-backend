package ffdd.opsconsole.market.mapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
// Statement-only SQL boundary: staking commands span products, positions, wallets and ledgers.
@SuppressWarnings("MybatisPlusBaseMapper")
public interface AppStakingMapper {
    @Select("""
            SELECT id, product_code AS productCode, name AS productName, asset, term_days AS termDays,
                   apy_bps AS apyBps, early_penalty_bps AS earlyPenaltyBps, min_amount AS minAmount,
                   UPPER(status) AS status
              FROM nx_staking_product
             WHERE is_deleted=0 AND UPPER(product_code) IN ('USDT_30D','USDT_90D','USDT_180D','USDT_365D')
             ORDER BY term_days,id
            """)
    List<ProductRow> listCanonicalProducts();

    @Select("""
            SELECT id, product_code AS productCode, name AS productName, asset, term_days AS termDays,
                   apy_bps AS apyBps, early_penalty_bps AS earlyPenaltyBps, min_amount AS minAmount,
                   UPPER(status) AS status
              FROM nx_staking_product
             WHERE is_deleted=0 AND LOWER(REPLACE(product_code,'_',''))=#{tierKey}
             LIMIT 1 FOR UPDATE
            """)
    ProductRow lockProductByTier(@Param("tierKey") String tierKey);

    @Select("SELECT id FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0 FOR UPDATE")
    Long lockActiveUser(@Param("userId") Long userId);

    @Select("SELECT usdt_available FROM nx_user_wallet WHERE user_id=#{userId} AND is_deleted=0 LIMIT 1 FOR UPDATE")
    BigDecimal lockWalletBalance(@Param("userId") Long userId);

    @Select("SELECT usdt_available FROM nx_user_wallet WHERE user_id=#{userId} AND is_deleted=0 LIMIT 1")
    BigDecimal walletBalance(@Param("userId") Long userId);

    @Select("""
            SELECT setting_value FROM nx_emergency_control_setting
             WHERE setting_key=#{settingKey} AND is_deleted=0
             LIMIT 1
            """)
    String controlValue(@Param("settingKey") String settingKey);

    @Update("""
            UPDATE nx_user_wallet
               SET usdt_available=usdt_available-#{amount},version=version+1,updated_at=NOW()
             WHERE user_id=#{userId} AND is_deleted=0 AND usdt_available>=#{amount}
            """)
    int debitWallet(@Param("userId") Long userId, @Param("amount") BigDecimal amount);

    @Update("""
            UPDATE nx_user_wallet
               SET usdt_available=usdt_available+#{amount},version=version+1,updated_at=NOW()
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
               #{earlyPenaltyBps},#{termDays},#{lockedAt},#{unlockAt},#{estimatedInterestUsdt},'ACTIVE',
               NOW(),NOW(),0)
            """)
    int insertPosition(PositionWrite row);

    @Select("""
            SELECT id,user_id AS userId,position_no AS positionNo,product_id AS productId,
                   product_code AS productCode,product_name AS productName,amount_usdt AS amountUsdt,
                   apy_bps AS apyBps,early_penalty_bps AS earlyPenaltyBps,term_days AS termDays,
                   locked_at AS lockedAt,unlock_at AS unlockAt,estimated_interest_usdt AS estimatedInterestUsdt,
                   UPPER(status) AS status,claimed_at AS claimedAt,early_withdrawn_at AS earlyWithdrawnAt
              FROM nx_staking_position
             WHERE user_id=#{userId} AND is_deleted=0
             ORDER BY created_at DESC,id DESC
            """)
    List<PositionRow> listUserPositions(@Param("userId") Long userId);

    @Select("""
            SELECT id,user_id AS userId,position_no AS positionNo,product_id AS productId,
                   product_code AS productCode,product_name AS productName,amount_usdt AS amountUsdt,
                   apy_bps AS apyBps,early_penalty_bps AS earlyPenaltyBps,term_days AS termDays,
                   locked_at AS lockedAt,unlock_at AS unlockAt,estimated_interest_usdt AS estimatedInterestUsdt,
                   UPPER(status) AS status,claimed_at AS claimedAt,early_withdrawn_at AS earlyWithdrawnAt
              FROM nx_staking_position
             WHERE user_id=#{userId} AND position_no=#{positionNo} AND is_deleted=0
             LIMIT 1 FOR UPDATE
            """)
    PositionRow lockUserPosition(@Param("userId") Long userId, @Param("positionNo") String positionNo);

    @Update("""
            UPDATE nx_staking_position SET status='MATURE_UNCLAIMED',updated_at=NOW()
             WHERE user_id=#{userId} AND is_deleted=0 AND UPPER(status)='ACTIVE' AND unlock_at<=#{now}
            """)
    int matureDuePositions(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_staking_position SET status='CLAIMED',claimed_at=#{now},updated_at=NOW()
             WHERE id=#{id} AND user_id=#{userId} AND is_deleted=0
               AND UPPER(status) IN ('ACTIVE','MATURE_UNCLAIMED') AND unlock_at<=#{now}
            """)
    int markClaimed(@Param("id") Long id, @Param("userId") Long userId, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_staking_position SET status='EARLY_WITHDRAWN',early_withdrawn_at=#{now},updated_at=NOW()
             WHERE id=#{id} AND user_id=#{userId} AND is_deleted=0
               AND UPPER(status)='ACTIVE' AND unlock_at>#{now}
            """)
    int markEarlyWithdrawn(@Param("id") Long id, @Param("userId") Long userId, @Param("now") LocalDateTime now);

    @Insert("""
            INSERT INTO nx_wallet_ledger
              (user_id,biz_no,biz_type,asset,direction,amount,balance_after,status,remark,created_at,updated_at,is_deleted)
            VALUES
              (#{userId},#{bizNo},#{bizType},'USDT',#{direction},#{amount},#{balanceAfter},'SUCCESS',#{remark},NOW(),NOW(),0)
            """)
    int insertLedger(LedgerWrite row);

    @Select("""
            SELECT COALESCE((SELECT config_value FROM nx_config_item
                              WHERE config_key='growth.phase.current' AND status=1 AND is_deleted=0 LIMIT 1),'P1') AS phase,
                   GREATEST(TIMESTAMPDIFF(MONTH,u.created_at,NOW()),0) AS accountAgeMonths,
                   DATE_FORMAT(u.created_at,'%x-W%v') AS cohort
              FROM nx_user u WHERE u.id=#{userId} AND u.status='ACTIVE' AND u.is_deleted=0
            """)
    UserAttribution userAttribution(@Param("userId") Long userId);

    record ProductRow(Long id, String productCode, String productName, String asset, Integer termDays,
                      BigDecimal apyBps, BigDecimal earlyPenaltyBps, BigDecimal minAmount, String status) {
    }

    record PositionRow(Long id, Long userId, String positionNo, Long productId, String productCode,
                       String productName, BigDecimal amountUsdt, BigDecimal apyBps,
                       BigDecimal earlyPenaltyBps, Integer termDays, LocalDateTime lockedAt,
                       LocalDateTime unlockAt, BigDecimal estimatedInterestUsdt, String status,
                       LocalDateTime claimedAt, LocalDateTime earlyWithdrawnAt) {
    }

    record PositionWrite(Long userId, String positionNo, Long productId, String productCode,
                         String productName, BigDecimal amountUsdt, BigDecimal apyBps,
                         BigDecimal earlyPenaltyBps, Integer termDays, LocalDateTime lockedAt,
                         LocalDateTime unlockAt, BigDecimal estimatedInterestUsdt) {
    }

    record LedgerWrite(Long userId, String bizNo, String bizType, String direction,
                       BigDecimal amount, BigDecimal balanceAfter, String remark) {
    }

    record UserAttribution(String phase, Integer accountAgeMonths, String cohort) {
    }
}
