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

    @Select("SELECT COUNT(*) FROM nx_genesis_holding h JOIN nx_user u ON u.id=h.user_id AND COALESCE(u.sandbox,0)=0 WHERE h.series_code=#{seriesCode} AND h.is_deleted=0")
    long holdingCount(@Param("seriesCode") String seriesCode);

    /** Current locking read used after the series-row mutex; avoids REPEATABLE READ stale snapshots. */
    @Select("SELECT COUNT(*) FROM nx_genesis_holding h JOIN nx_user u ON u.id=h.user_id AND COALESCE(u.sandbox,0)=0 WHERE h.series_code=#{seriesCode} AND h.is_deleted=0 FOR UPDATE")
    long lockHoldingCount(@Param("seriesCode") String seriesCode);

    @Update("""
            UPDATE nx_genesis_series SET sold_supply=#{soldSupply},updated_at=NOW()
             WHERE id=#{seriesId} AND is_deleted=0 AND total_supply>=#{soldSupply}
            """)
    int updateSoldSupply(@Param("seriesId") Long seriesId, @Param("soldSupply") long soldSupply);

    @Select("SELECT id FROM nx_user WHERE id=#{userId} AND UPPER(status)='ACTIVE' AND is_deleted=0 FOR UPDATE")
    Long lockActiveUser(@Param("userId") Long userId);

    @Select("SELECT sandbox FROM nx_user WHERE id=#{userId} AND UPPER(status)='ACTIVE' AND is_deleted=0 LIMIT 1")
    Integer userSandbox(@Param("userId") Long userId);

    @Select("""
            SELECT u.id AS userId,
                   UPPER(COALESCE(
                     CASE WHEN TRIM(u.region) REGEXP '^[A-Za-z]{2}$' THEN TRIM(u.region) END,
                     CASE REPLACE(COALESCE(u.country_code,''),'+','')
                       WHEN '1' THEN 'US' WHEN '7' THEN 'RU' WHEN '44' THEN 'GB'
                       WHEN '49' THEN 'DE' WHEN '33' THEN 'FR' WHEN '34' THEN 'ES'
                       WHEN '55' THEN 'BR' WHEN '62' THEN 'ID' WHEN '63' THEN 'PH'
                       WHEN '66' THEN 'TH' WHEN '81' THEN 'JP' WHEN '82' THEN 'KR'
                       WHEN '84' THEN 'VN' WHEN '86' THEN 'CN' WHEN '971' THEN 'AE'
                     END,
                     '--')) AS countryCode,
                   COALESCE((SELECT config_value FROM nx_config_item WHERE config_key='growth.phase.current'
                              AND status=1 AND is_deleted=0 LIMIT 1),'P1') AS phase,
                   GREATEST(TIMESTAMPDIFF(DAY,u.created_at,NOW()),0) AS accountAgeDays,
                   GREATEST(TIMESTAMPDIFF(MONTH,u.created_at,NOW()),0) AS accountAgeMonths,
                   DATE_FORMAT(u.created_at,'%x-W%v') AS cohort
              FROM nx_user u
             WHERE u.id=#{userId} AND UPPER(u.status)='ACTIVE' AND u.is_deleted=0
               AND COALESCE(u.sandbox,0)=0 LIMIT 1
            """)
    UserPolicyRow userPolicy(@Param("userId") Long userId);

    @Select("""
            SELECT COUNT(*) FROM nx_genesis_holding
             WHERE user_id=#{userId} AND series_code=#{seriesCode} AND is_deleted=0
               AND UPPER(status) IN ('ACTIVE','LISTED')
            """)
    long userHoldingCount(@Param("userId") Long userId, @Param("seriesCode") String seriesCode);

    @Select("""
            SELECT COUNT(*) + 1
              FROM (SELECT h.user_id, COUNT(*) AS holdings
                      FROM nx_genesis_holding h JOIN nx_user u ON u.id=h.user_id
                       AND u.status='ACTIVE' AND u.is_deleted=0 AND COALESCE(u.sandbox,0)=0
                     WHERE h.series_code=#{seriesCode} AND h.is_deleted=0
                       AND UPPER(h.status) IN ('ACTIVE','LISTED')
                     GROUP BY h.user_id) ranked
              JOIN (SELECT COUNT(*) AS holdings
                      FROM nx_genesis_holding h JOIN nx_user u ON u.id=h.user_id
                       AND u.status='ACTIVE' AND u.is_deleted=0 AND COALESCE(u.sandbox,0)=0
                     WHERE h.user_id=#{userId} AND h.series_code=#{seriesCode} AND h.is_deleted=0
                       AND UPPER(h.status) IN ('ACTIVE','LISTED')) mine
                ON ranked.holdings > mine.holdings
            """)
    Integer currentPriorityRank(@Param("userId") Long userId, @Param("seriesCode") String seriesCode);

    @Select("""
            SELECT COUNT(DISTINCT h.user_id)
              FROM nx_genesis_holding h JOIN nx_user u ON u.id=h.user_id
               AND u.status='ACTIVE' AND u.is_deleted=0 AND COALESCE(u.sandbox,0)=0
             WHERE h.series_code=#{seriesCode} AND h.is_deleted=0
               AND UPPER(h.status) IN ('ACTIVE','LISTED')
            """)
    long activeHolderCount(@Param("seriesCode") String seriesCode);

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

    @Select("SELECT w.usdt_available FROM nx_user_wallet w JOIN nx_user u ON u.id=w.user_id AND COALESCE(u.sandbox,0)=0 AND u.is_deleted=0 WHERE w.user_id=#{userId} AND w.is_deleted=0 LIMIT 1 FOR UPDATE")
    BigDecimal lockWallet(@Param("userId") Long userId);

    @Select("SELECT w.usdt_available FROM nx_user_wallet w JOIN nx_user u ON u.id=w.user_id AND COALESCE(u.sandbox,0)=0 AND u.is_deleted=0 WHERE w.user_id=#{userId} AND w.is_deleted=0 LIMIT 1")
    BigDecimal wallet(@Param("userId") Long userId);

    @Update("""
            UPDATE nx_user_wallet SET usdt_available=usdt_available-#{amount},version=version+1,updated_at=NOW()
             WHERE user_id=#{userId} AND is_deleted=0 AND usdt_available>=#{amount}
               AND EXISTS (SELECT 1 FROM nx_user u WHERE u.id=#{userId} AND COALESCE(u.sandbox,0)=0 AND u.is_deleted=0)
            """)
    int debitWallet(@Param("userId") Long userId, @Param("amount") BigDecimal amount);

    @Update("""
            UPDATE nx_user_wallet SET usdt_available=usdt_available+#{amount},version=version+1,updated_at=NOW()
             WHERE user_id=#{userId} AND is_deleted=0
               AND EXISTS (SELECT 1 FROM nx_user u WHERE u.id=#{userId} AND COALESCE(u.sandbox,0)=0 AND u.is_deleted=0)
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
            SELECT h.id,h.holding_no AS holdingNo,h.user_id AS userId,h.order_no AS orderNo,h.series_code AS seriesCode,
                   h.acquired_price_usdt AS acquiredPriceUsdt,UPPER(h.status) AS status,
                   h.listing_price_usdt AS listingPriceUsdt,h.acquired_at AS acquiredAt,h.listed_at AS listedAt
              FROM nx_genesis_holding h
             WHERE h.user_id=#{userId} AND h.is_deleted=0 ORDER BY h.acquired_at DESC,h.id DESC
            """)
    List<HoldingRow> holdings(@Param("userId") Long userId);

    @Select("""
            SELECT h.id,h.holding_no AS holdingNo,h.user_id AS userId,h.order_no AS orderNo,h.series_code AS seriesCode,
                   h.acquired_price_usdt AS acquiredPriceUsdt,UPPER(h.status) AS status,
                   h.listing_price_usdt AS listingPriceUsdt,h.acquired_at AS acquiredAt,h.listed_at AS listedAt
              FROM nx_genesis_holding h
              JOIN nx_user u ON u.id=h.user_id AND COALESCE(u.sandbox,0)=0 AND u.is_deleted=0
             WHERE h.holding_no=#{holdingNo} AND h.is_deleted=0 LIMIT 1 FOR UPDATE
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
               AND EXISTS (SELECT 1 FROM nx_user s WHERE s.id=#{sellerUserId} AND COALESCE(s.sandbox,0)=0 AND s.is_deleted=0)
               AND EXISTS (SELECT 1 FROM nx_user b WHERE b.id=#{buyerUserId} AND COALESCE(b.sandbox,0)=0 AND b.is_deleted=0)
            """)
    int transferHolding(@Param("id") Long id, @Param("sellerUserId") Long sellerUserId,
                        @Param("buyerUserId") Long buyerUserId, @Param("orderNo") String orderNo,
                        @Param("price") BigDecimal price, @Param("acquiredAt") LocalDateTime acquiredAt);

    @Select("""
            SELECT h.holding_no AS holdingNo,h.series_code AS seriesCode,h.listing_price_usdt AS askPriceUsdt,
                   h.listed_at AS listedAt,CONCAT('usr_',RIGHT(UPPER(HEX(h.user_id)),4)) AS seller
              FROM nx_genesis_holding h JOIN nx_user u ON u.id=h.user_id AND COALESCE(u.sandbox,0)=0
             WHERE h.is_deleted=0 AND UPPER(h.status)='LISTED' AND h.listing_price_usdt>0
             ORDER BY h.listing_price_usdt,h.listed_at,h.id LIMIT 100
            """)
    List<ListingRow> listings();

    @Select("""
            SELECT
              (SELECT MIN(h.listing_price_usdt) FROM nx_genesis_holding h JOIN nx_user u ON u.id=h.user_id AND COALESCE(u.sandbox,0)=0
                WHERE h.is_deleted=0 AND UPPER(h.status)='LISTED' AND h.listing_price_usdt>0) AS floorUsdt,
              (SELECT COALESCE(SUM(o.amount_usdt),0) FROM nx_genesis_order o JOIN nx_user u ON u.id=o.user_id AND COALESCE(u.sandbox,0)=0
                WHERE o.is_deleted=0 AND UPPER(o.status)='COMPLETED'
                  AND UPPER(o.order_type)='SECONDARY' AND o.completed_at>=DATE_SUB(NOW(),INTERVAL 24 HOUR)) AS volume24hUsdt,
              (SELECT COUNT(DISTINCT h.user_id) FROM nx_genesis_holding h JOIN nx_user u ON u.id=h.user_id AND COALESCE(u.sandbox,0)=0
                WHERE h.is_deleted=0 AND UPPER(h.status) IN ('ACTIVE','LISTED')) AS owners,
              (SELECT o.unit_price_usdt FROM nx_genesis_order o JOIN nx_user u ON u.id=o.user_id AND COALESCE(u.sandbox,0)=0
                WHERE o.is_deleted=0 AND UPPER(o.status)='COMPLETED' AND UPPER(o.order_type)='SECONDARY'
                ORDER BY o.completed_at DESC,o.id DESC LIMIT 1) AS lastSaleUsdt,
              (SELECT MIN(h.listing_price_usdt) FROM nx_genesis_holding h JOIN nx_user u ON u.id=h.user_id AND COALESCE(u.sandbox,0)=0
                WHERE h.is_deleted=0 AND UPPER(h.status)='LISTED' AND h.listing_price_usdt>0
                  AND h.listed_at < DATE_SUB(NOW(),INTERVAL 7 DAY)) AS floor7dUsdt
            """)
    SecondaryStatsRow secondaryStats();

    @Select("""
            SELECT CONCAT('tx_',LEFT(SHA2(order_no,256),16)) AS orderNo,order_type AS orderType,quantity,unit_price_usdt AS unitPriceUsdt,
                   amount_usdt AS amountUsdt,royalty_usdt AS royaltyUsdt,completed_at AS completedAt
              FROM nx_genesis_order o JOIN nx_user u ON u.id=o.user_id AND COALESCE(u.sandbox,0)=0
             WHERE o.is_deleted=0 AND UPPER(o.status)='COMPLETED'
             ORDER BY o.completed_at DESC,o.id DESC LIMIT 100
            """)
    List<TransactionRow> transactions();

    @Select("""
            SELECT i.batch_no AS batchNo,i.holding_no AS holdingNo,i.amount_usdt AS amountUsdt,
                   UPPER(i.status) AS status,i.paid_at AS paidAt
              FROM nx_genesis_emission_item i
              JOIN nx_user u ON u.id=i.user_id AND COALESCE(u.sandbox,0)=0 AND u.is_deleted=0
             WHERE i.user_id=#{userId} AND i.is_deleted=0 ORDER BY i.created_at DESC,i.id DESC LIMIT 100
            """)
    List<EmissionRow> emissions(@Param("userId") Long userId);

    @Select("""
            SELECT h.id,h.holding_no AS holdingNo,h.user_id AS userId,h.order_no AS orderNo,h.series_code AS seriesCode,
                   h.acquired_price_usdt AS acquiredPriceUsdt,UPPER(h.status) AS status,
                   h.listing_price_usdt AS listingPriceUsdt,h.acquired_at AS acquiredAt,h.listed_at AS listedAt
              FROM nx_genesis_holding h
              JOIN nx_user u ON u.id=h.user_id AND COALESCE(u.sandbox,0)=0 AND u.is_deleted=0
             WHERE h.is_deleted=0 AND UPPER(h.status) IN ('ACTIVE','LISTED')
             ORDER BY h.id FOR UPDATE
            """)
    List<HoldingRow> lockEmissionHoldings();

    @Insert("""
            INSERT IGNORE INTO nx_genesis_emission_batch
              (batch_no,snapshot_at,daily_rate_pct,holder_count,total_amount_usdt,status,operator,reason,decision_ref,created_at,updated_at,is_deleted)
            VALUES
              (#{batchNo},#{snapshotAt},#{dailyRatePct},#{holderCount},#{totalAmountUsdt},'PROCESSING',#{operator},#{reason},#{decisionRef},NOW(),NOW(),0)
            """)
    int insertEmissionBatch(EmissionBatchWrite row);

    @Select("""
            SELECT batch_no AS batchNo,UPPER(status) AS status,holder_count AS holderCount,
                   total_amount_usdt AS totalAmountUsdt
              FROM nx_genesis_emission_batch
             WHERE batch_no=#{batchNo} AND is_deleted=0 LIMIT 1 FOR UPDATE
            """)
    EmissionBatchRow lockEmissionBatch(@Param("batchNo") String batchNo);

    @Insert("""
            INSERT IGNORE INTO nx_genesis_emission_item
              (batch_no,holding_no,user_id,amount_usdt,status,created_at,updated_at,is_deleted)
            SELECT #{batchNo},#{holdingNo},#{userId},#{amountUsdt},'PENDING',NOW(),NOW(),0
             WHERE EXISTS (SELECT 1 FROM nx_user u
                            WHERE u.id=#{userId} AND u.is_deleted=0 AND COALESCE(u.sandbox,0)=0)
            """)
    int insertEmissionItem(EmissionItemWrite row);

    @Select("""
            SELECT i.id,i.batch_no AS batchNo,i.holding_no AS holdingNo,i.user_id AS userId,
                   i.amount_usdt AS amountUsdt,UPPER(i.status) AS status
              FROM nx_genesis_emission_item i
              JOIN nx_user u ON u.id=i.user_id AND COALESCE(u.sandbox,0)=0 AND u.is_deleted=0
             WHERE i.batch_no=#{batchNo} AND i.is_deleted=0 AND UPPER(i.status) IN ('PENDING','FAILED')
             ORDER BY i.id FOR UPDATE
            """)
    List<EmissionItemRow> lockPendingEmissionItems(@Param("batchNo") String batchNo);

    @Update("""
            UPDATE nx_genesis_emission_item i SET status='PAID',paid_at=#{paidAt},updated_at=NOW()
             WHERE i.id=#{id} AND i.is_deleted=0 AND UPPER(i.status) IN ('PENDING','FAILED')
               AND EXISTS (SELECT 1 FROM nx_user u
                            WHERE u.id=i.user_id AND u.is_deleted=0 AND COALESCE(u.sandbox,0)=0)
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
            SELECT #{userId},#{bizNo},#{bizType},'USDT',#{direction},#{amount},#{balanceAfter},'SUCCESS',#{remark},NOW(),NOW(),0
             WHERE EXISTS (SELECT 1 FROM nx_user u
                            WHERE u.id=#{userId} AND u.is_deleted=0 AND COALESCE(u.sandbox,0)=0)
            """)
    int insertLedger(LedgerWrite row);

    record SeriesRow(Long id,String seriesCode,String name,Integer totalSupply,BigDecimal priceUsdt,
                     Integer royaltyBps,BigDecimal dailyEmissionRatePct,String status) {}
    record UserPolicyRow(Long userId,String countryCode,String phase,Integer accountAgeDays,
                         Integer accountAgeMonths,String cohort) {}
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
    record SecondaryStatsRow(BigDecimal floorUsdt,BigDecimal volume24hUsdt,Long owners,
                             BigDecimal lastSaleUsdt,BigDecimal floor7dUsdt) {}
    record EmissionRow(String batchNo,String holdingNo,BigDecimal amountUsdt,String status,LocalDateTime paidAt) {}
    record EmissionBatchWrite(String batchNo,LocalDateTime snapshotAt,BigDecimal dailyRatePct,Integer holderCount,
                              BigDecimal totalAmountUsdt,String operator,String reason,String decisionRef) {}
    record EmissionBatchRow(String batchNo,String status,Integer holderCount,BigDecimal totalAmountUsdt) {}
    record EmissionItemWrite(String batchNo,String holdingNo,Long userId,BigDecimal amountUsdt) {}
    record EmissionItemRow(Long id,String batchNo,String holdingNo,Long userId,BigDecimal amountUsdt,String status) {}
    record LedgerWrite(Long userId,String bizNo,String bizType,String direction,BigDecimal amount,
                       BigDecimal balanceAfter,String remark) {}
}
