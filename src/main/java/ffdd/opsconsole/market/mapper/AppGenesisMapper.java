package ffdd.opsconsole.market.mapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** SQL boundary for server-authoritative Genesis purchase, holding, resale and emission state. */
@Mapper
// Statement-only SQL boundary: Genesis spans series, holdings, orders, wallets and ledgers.
@SuppressWarnings("MybatisPlusBaseMapper")
public interface AppGenesisMapper {
    @Select("""
            SELECT id,series_code AS seriesCode,name,total_supply AS totalSupply,
                   price_usdt AS priceUsdt,royalty_bps AS royaltyBps,
                   daily_dividend_rate_pct AS dailyEmissionRatePct,UPPER(status) AS status
              FROM nx_genesis_series
             WHERE is_deleted=0 AND UPPER(status)='ACTIVE'
             ORDER BY id DESC LIMIT 1
            """)
    SeriesRow activeSeries();

    @Select("""
            SELECT id,series_code AS seriesCode,name,total_supply AS totalSupply,
                   price_usdt AS priceUsdt,royalty_bps AS royaltyBps,
                   daily_dividend_rate_pct AS dailyEmissionRatePct,UPPER(status) AS status
              FROM nx_genesis_series
             WHERE is_deleted=0 AND UPPER(status)='ACTIVE'
             ORDER BY id DESC LIMIT 1 FOR UPDATE
            """)
    SeriesRow lockActiveSeries();

    @Select("SELECT COUNT(*) FROM nx_genesis_holding WHERE series_code=#{seriesCode} AND is_deleted=0")
    long holdingCount(@Param("seriesCode") String seriesCode);

    /** Current locking read used after the series-row mutex; avoids REPEATABLE READ stale snapshots. */
    @Select("SELECT COUNT(*) FROM nx_genesis_holding WHERE series_code=#{seriesCode} AND is_deleted=0 FOR UPDATE")
    long lockHoldingCount(@Param("seriesCode") String seriesCode);

    @Update("""
            UPDATE nx_genesis_series SET sold_supply=#{soldSupply},updated_at=NOW()
             WHERE id=#{seriesId} AND is_deleted=0 AND total_supply>=#{soldSupply}
            """)
    int updateSoldSupply(@Param("seriesId") Long seriesId, @Param("soldSupply") long soldSupply);

    @Select("SELECT id FROM nx_user WHERE id=#{userId} AND UPPER(status)='ACTIVE' AND is_deleted=0 FOR UPDATE")
    Long lockActiveUser(@Param("userId") Long userId);

    @Select("""
            SELECT u.id AS userId,UPPER(COALESCE(k.status,u.kyc_status,'PENDING')) AS kycStatus,
                   UPPER(COALESCE(
                     CASE WHEN TRIM(k.country) REGEXP '^[A-Za-z]{2}$' THEN TRIM(k.country) END,
                     CASE WHEN TRIM(u.region) REGEXP '^[A-Za-z]{2}$' THEN TRIM(u.region) END,
                     CASE REPLACE(COALESCE(NULLIF(k.country,''),u.country_code),'+','')
                       WHEN '1' THEN 'US' WHEN '7' THEN 'RU' WHEN '44' THEN 'GB'
                       WHEN '49' THEN 'DE' WHEN '33' THEN 'FR' WHEN '34' THEN 'ES'
                       WHEN '55' THEN 'BR' WHEN '62' THEN 'ID' WHEN '63' THEN 'PH'
                       WHEN '66' THEN 'TH' WHEN '81' THEN 'JP' WHEN '82' THEN 'KR'
                       WHEN '84' THEN 'VN' WHEN '86' THEN 'CN' WHEN '971' THEN 'AE'
                     END,
                     '--')) AS countryCode,
                   COALESCE((SELECT config_value FROM nx_config_item WHERE config_key='growth.phase.current'
                              AND status=1 AND is_deleted=0 LIMIT 1),'P1') AS phase,
                   GREATEST(TIMESTAMPDIFF(MONTH,u.created_at,NOW()),0) AS accountAgeMonths,
                   DATE_FORMAT(u.created_at,'%x-W%v') AS cohort
              FROM nx_user u
              LEFT JOIN nx_kyc_profile k ON k.user_id=u.id AND k.is_deleted=0
             WHERE u.id=#{userId} AND UPPER(u.status)='ACTIVE' AND u.is_deleted=0 LIMIT 1
            """)
    UserPolicyRow userPolicy(@Param("userId") Long userId);

    @Select("""
            SELECT COUNT(*) FROM nx_emergency_geo_country_policy
             WHERE UPPER(country_code)=UPPER(#{countryCode}) AND is_deleted=0
               AND LOWER(policy_status) IN ('blocked','limited')
            """)
    long geoBlocked(@Param("countryCode") String countryCode);

    @Select("""
            SELECT setting_value FROM nx_emergency_control_setting
             WHERE setting_key=#{settingKey} AND is_deleted=0 LIMIT 1
            """)
    String controlValue(@Param("settingKey") String settingKey);

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
            INSERT INTO nx_genesis_order
              (order_no,client_request_no,user_id,series_code,quantity,unit_price_usdt,amount_usdt,
               payment_asset,status,order_type,seller_user_id,holding_no,royalty_usdt,
               paid_at,completed_at,created_at,updated_at,is_deleted)
            VALUES
              (#{orderNo},#{clientRequestNo},#{userId},#{seriesCode},#{quantity},#{unitPriceUsdt},#{amountUsdt},
               'USDT','COMPLETED',#{orderType},#{sellerUserId},#{holdingNo},#{royaltyUsdt},
               #{completedAt},#{completedAt},NOW(),NOW(),0)
            """)
    int insertOrder(OrderWrite row);

    @Insert("""
            INSERT INTO nx_genesis_holding
              (holding_no,user_id,order_no,series_code,acquired_price_usdt,status,acquired_at,
               listing_price_usdt,listed_at,created_at,updated_at,is_deleted)
            VALUES
              (#{holdingNo},#{userId},#{orderNo},#{seriesCode},#{acquiredPriceUsdt},'ACTIVE',#{acquiredAt},
               NULL,NULL,NOW(),NOW(),0)
            """)
    int insertHolding(HoldingWrite row);

    @Select("""
            SELECT id,holding_no AS holdingNo,user_id AS userId,order_no AS orderNo,series_code AS seriesCode,
                   acquired_price_usdt AS acquiredPriceUsdt,UPPER(status) AS status,
                   listing_price_usdt AS listingPriceUsdt,acquired_at AS acquiredAt,listed_at AS listedAt
              FROM nx_genesis_holding
             WHERE user_id=#{userId} AND is_deleted=0 ORDER BY acquired_at DESC,id DESC
            """)
    List<HoldingRow> holdings(@Param("userId") Long userId);

    @Select("""
            SELECT id,holding_no AS holdingNo,user_id AS userId,order_no AS orderNo,series_code AS seriesCode,
                   acquired_price_usdt AS acquiredPriceUsdt,UPPER(status) AS status,
                   listing_price_usdt AS listingPriceUsdt,acquired_at AS acquiredAt,listed_at AS listedAt
              FROM nx_genesis_holding
             WHERE holding_no=#{holdingNo} AND is_deleted=0 LIMIT 1 FOR UPDATE
            """)
    HoldingRow lockHolding(@Param("holdingNo") String holdingNo);

    @Update("""
            UPDATE nx_genesis_holding SET status='LISTED',listing_price_usdt=#{askPrice},listed_at=#{listedAt},updated_at=NOW()
             WHERE id=#{id} AND user_id=#{userId} AND is_deleted=0 AND UPPER(status)='ACTIVE'
            """)
    int listHolding(@Param("id") Long id, @Param("userId") Long userId,
                    @Param("askPrice") BigDecimal askPrice, @Param("listedAt") LocalDateTime listedAt);

    @Update("""
            UPDATE nx_genesis_holding SET status='ACTIVE',listing_price_usdt=NULL,listed_at=NULL,updated_at=NOW()
             WHERE id=#{id} AND user_id=#{userId} AND is_deleted=0 AND UPPER(status)='LISTED'
            """)
    int cancelListing(@Param("id") Long id, @Param("userId") Long userId);

    @Update("""
            UPDATE nx_genesis_holding
               SET user_id=#{buyerUserId},order_no=#{orderNo},acquired_price_usdt=#{price},status='ACTIVE',
                   acquired_at=#{acquiredAt},listing_price_usdt=NULL,listed_at=NULL,updated_at=NOW()
             WHERE id=#{id} AND user_id=#{sellerUserId} AND is_deleted=0 AND UPPER(status)='LISTED'
            """)
    int transferHolding(@Param("id") Long id, @Param("sellerUserId") Long sellerUserId,
                        @Param("buyerUserId") Long buyerUserId, @Param("orderNo") String orderNo,
                        @Param("price") BigDecimal price, @Param("acquiredAt") LocalDateTime acquiredAt);

    @Select("""
            SELECT h.holding_no AS holdingNo,h.series_code AS seriesCode,h.listing_price_usdt AS askPriceUsdt,
                   h.listed_at AS listedAt,CONCAT('usr_',RIGHT(UPPER(HEX(h.user_id)),4)) AS seller
              FROM nx_genesis_holding h
             WHERE h.is_deleted=0 AND UPPER(h.status)='LISTED' AND h.listing_price_usdt>0
             ORDER BY h.listing_price_usdt,h.listed_at,h.id LIMIT 100
            """)
    List<ListingRow> listings();

    @Select("""
            SELECT order_no AS orderNo,order_type AS orderType,quantity,unit_price_usdt AS unitPriceUsdt,
                   amount_usdt AS amountUsdt,royalty_usdt AS royaltyUsdt,completed_at AS completedAt
              FROM nx_genesis_order
             WHERE is_deleted=0 AND UPPER(status)='COMPLETED'
             ORDER BY completed_at DESC,id DESC LIMIT 100
            """)
    List<TransactionRow> transactions();

    @Select("""
            SELECT i.batch_no AS batchNo,i.holding_no AS holdingNo,i.amount_usdt AS amountUsdt,
                   UPPER(i.status) AS status,i.paid_at AS paidAt
              FROM nx_genesis_emission_item i
             WHERE i.user_id=#{userId} AND i.is_deleted=0 ORDER BY i.created_at DESC,i.id DESC LIMIT 100
            """)
    List<EmissionRow> emissions(@Param("userId") Long userId);

    @Select("""
            SELECT id,holding_no AS holdingNo,user_id AS userId,order_no AS orderNo,series_code AS seriesCode,
                   acquired_price_usdt AS acquiredPriceUsdt,UPPER(status) AS status,
                   listing_price_usdt AS listingPriceUsdt,acquired_at AS acquiredAt,listed_at AS listedAt
              FROM nx_genesis_holding
             WHERE is_deleted=0 AND UPPER(status) IN ('ACTIVE','LISTED')
             ORDER BY id FOR UPDATE
            """)
    List<HoldingRow> lockEmissionHoldings();

    @Insert("""
            INSERT IGNORE INTO nx_genesis_emission_batch
              (batch_no,snapshot_at,daily_rate_pct,holder_count,total_amount_usdt,status,operator,reason,decision_ref,created_at,updated_at,is_deleted)
            VALUES
              (#{batchNo},#{snapshotAt},#{dailyRatePct},#{holderCount},#{totalAmountUsdt},'PROCESSING',#{operator},#{reason},#{decisionRef},NOW(),NOW(),0)
            """)
    int insertEmissionBatch(EmissionBatchWrite row);

    @Insert("""
            INSERT IGNORE INTO nx_genesis_emission_item
              (batch_no,holding_no,user_id,amount_usdt,status,created_at,updated_at,is_deleted)
            VALUES (#{batchNo},#{holdingNo},#{userId},#{amountUsdt},'PENDING',NOW(),NOW(),0)
            """)
    int insertEmissionItem(EmissionItemWrite row);

    @Select("""
            SELECT i.id,i.batch_no AS batchNo,i.holding_no AS holdingNo,i.user_id AS userId,
                   i.amount_usdt AS amountUsdt,UPPER(i.status) AS status
              FROM nx_genesis_emission_item i
             WHERE i.batch_no=#{batchNo} AND i.is_deleted=0 AND UPPER(i.status) IN ('PENDING','FAILED')
             ORDER BY i.id FOR UPDATE
            """)
    List<EmissionItemRow> lockPendingEmissionItems(@Param("batchNo") String batchNo);

    @Update("""
            UPDATE nx_genesis_emission_item SET status='PAID',paid_at=#{paidAt},updated_at=NOW()
             WHERE id=#{id} AND is_deleted=0 AND UPPER(status) IN ('PENDING','FAILED')
            """)
    int markEmissionPaid(@Param("id") Long id,@Param("paidAt") LocalDateTime paidAt);

    @Update("""
            UPDATE nx_genesis_emission_batch SET status='COMPLETED',updated_at=NOW()
             WHERE batch_no=#{batchNo} AND is_deleted=0
            """)
    int completeEmissionBatch(@Param("batchNo") String batchNo);

    @Insert("""
            INSERT INTO nx_wallet_ledger
              (user_id,biz_no,biz_type,asset,direction,amount,balance_after,status,remark,created_at,updated_at,is_deleted)
            VALUES
              (#{userId},#{bizNo},#{bizType},'USDT',#{direction},#{amount},#{balanceAfter},'SUCCESS',#{remark},NOW(),NOW(),0)
            """)
    int insertLedger(LedgerWrite row);

    record SeriesRow(Long id,String seriesCode,String name,Integer totalSupply,BigDecimal priceUsdt,
                     Integer royaltyBps,BigDecimal dailyEmissionRatePct,String status) {}
    record UserPolicyRow(Long userId,String kycStatus,String countryCode,String phase,Integer accountAgeMonths,String cohort) {}
    record OrderWrite(String orderNo,String clientRequestNo,Long userId,String seriesCode,Integer quantity,
                      BigDecimal unitPriceUsdt,BigDecimal amountUsdt,String orderType,Long sellerUserId,
                      String holdingNo,BigDecimal royaltyUsdt,LocalDateTime completedAt) {}
    record HoldingWrite(String holdingNo,Long userId,String orderNo,String seriesCode,
                        BigDecimal acquiredPriceUsdt,LocalDateTime acquiredAt) {}
    record HoldingRow(Long id,String holdingNo,Long userId,String orderNo,String seriesCode,
                      BigDecimal acquiredPriceUsdt,String status,BigDecimal listingPriceUsdt,
                      LocalDateTime acquiredAt,LocalDateTime listedAt) {}
    record ListingRow(String holdingNo,String seriesCode,BigDecimal askPriceUsdt,LocalDateTime listedAt,String seller) {}
    record TransactionRow(String orderNo,String orderType,Integer quantity,BigDecimal unitPriceUsdt,
                          BigDecimal amountUsdt,BigDecimal royaltyUsdt,LocalDateTime completedAt) {}
    record EmissionRow(String batchNo,String holdingNo,BigDecimal amountUsdt,String status,LocalDateTime paidAt) {}
    record EmissionBatchWrite(String batchNo,LocalDateTime snapshotAt,BigDecimal dailyRatePct,Integer holderCount,
                              BigDecimal totalAmountUsdt,String operator,String reason,String decisionRef) {}
    record EmissionItemWrite(String batchNo,String holdingNo,Long userId,BigDecimal amountUsdt) {}
    record EmissionItemRow(Long id,String batchNo,String holdingNo,Long userId,BigDecimal amountUsdt,String status) {}
    record LedgerWrite(Long userId,String bizNo,String bizType,String direction,BigDecimal amount,
                       BigDecimal balanceAfter,String remark) {}
}
