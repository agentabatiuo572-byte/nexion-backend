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
// Statement-only SQL boundary: there is no single aggregate entity for BaseMapper<T> CRUD.
@SuppressWarnings("MybatisPlusBaseMapper")
public interface AppExchangeMapper {
    @Select("SELECT CONCAT('U', IF(id<100000000,LPAD(id,8,'0'),CAST(id AS CHAR))) FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0 FOR UPDATE")
    String lockActiveUserNo(@Param("userId") Long userId);

    @Select("""
            SELECT w.usdt_available AS usdtAvailable,w.nex_available AS nexAvailable,
                   UPPER(COALESCE(k.status,'NONE')) AS kycStatus,UPPER(COALESCE(k.country,u.country_code,'')) AS countryCode
              FROM nx_user u
              JOIN nx_user_wallet w ON w.user_id=u.id AND w.is_deleted=0
              LEFT JOIN nx_kyc_profile k ON k.user_id=u.id AND k.is_deleted=0
             WHERE u.id=#{userId} AND u.is_deleted=0 LIMIT 1 FOR UPDATE
            """)
    WalletGateRow lockWalletGate(@Param("userId") Long userId);

    @Select("""
            SELECT COALESCE(SUM(CASE WHEN UPPER(to_asset)='USDT' THEN to_amount ELSE from_amount END),0)
              FROM nx_exchange_order
             WHERE user_id=#{userId} AND is_deleted=0 AND UPPER(status) IN ('COMPLETED','SUCCESS')
               AND created_at>=CURRENT_DATE
            """)
    BigDecimal userTodayUsdt(@Param("userId") Long userId);

    @Select("""
            SELECT COALESCE(SUM(CASE WHEN UPPER(to_asset)='USDT' THEN to_amount ELSE from_amount END),0)
              FROM nx_exchange_order
             WHERE is_deleted=0 AND UPPER(status) IN ('COMPLETED','SUCCESS') AND created_at>=CURRENT_DATE
            """)
    BigDecimal platformTodayUsdt();

    @Select("""
            SELECT COALESCE(SUM(CASE WHEN UPPER(to_asset)='USDT' THEN to_amount ELSE from_amount END),0)
              FROM nx_exchange_order
             WHERE user_id=#{userId} AND is_deleted=0 AND UPPER(status) IN ('COMPLETED','SUCCESS')
            """)
    BigDecimal userLifetimeUsdt(@Param("userId") Long userId);

    @Select("""
            SELECT COUNT(1) FROM nx_emergency_geo_country_policy
             WHERE UPPER(country_code)=UPPER(#{countryCode}) AND LOWER(policy_status)='blocked' AND is_deleted=0
            """)
    int geoBlocked(@Param("countryCode") String countryCode);

    @Select("""
            SELECT setting_value FROM nx_emergency_control_setting
             WHERE setting_key=#{key} AND is_deleted=0 LIMIT 1
            """)
    String emergencyValue(@Param("key") String key);

    @Select("""
            SELECT price_usdt FROM nx_price_index
             WHERE metric_code IN ('NEX','NEX_USDT') AND status='ACTIVE' AND is_deleted=0
             ORDER BY sampled_at DESC,id DESC LIMIT 1
            """)
    BigDecimal currentPrice();

    @Select("""
            SELECT price_usdt AS priceUsdt,sampled_at AS sampledAt
              FROM nx_price_index
             WHERE metric_code IN ('NEX','NEX_USDT') AND status='ACTIVE' AND is_deleted=0
               AND sampled_at>=DATE_SUB(NOW(),INTERVAL 24 HOUR)
             ORDER BY sampled_at,id LIMIT 200
            """)
    List<MarketPoint> recentMarketPoints();

    @Update("""
            UPDATE nx_user_wallet
               SET usdt_available=usdt_available+#{usdtDelta},nex_available=nex_available+#{nexDelta},
                   version=version+1,updated_at=NOW()
             WHERE user_id=#{userId} AND is_deleted=0
               AND usdt_available+#{usdtDelta}>=0 AND nex_available+#{nexDelta}>=0
            """)
    int applyWalletDelta(@Param("userId") Long userId,@Param("usdtDelta") BigDecimal usdtDelta,
                         @Param("nexDelta") BigDecimal nexDelta);

    @Insert("""
            INSERT INTO nx_exchange_order
              (user_id,exchange_no,from_asset,to_asset,from_amount,to_amount,rate,status,created_at,updated_at,is_deleted)
            VALUES(#{userId},#{exchangeNo},#{fromAsset},#{toAsset},#{fromAmount},#{toAmount},#{rate},#{status},NOW(),NOW(),0)
            """)
    int insertOrder(ExchangeWrite row);

    @Insert("""
            INSERT INTO nx_wallet_ledger
              (user_id,biz_no,biz_type,asset,direction,amount,balance_after,status,remark,created_at,updated_at,is_deleted)
            VALUES(#{userId},#{bizNo},'EXCHANGE_SWAP',#{asset},#{direction},#{amount},#{balanceAfter},'SUCCESS',#{remark},NOW(),NOW(),0)
            """)
    int insertLedger(LedgerWrite row);

    @Select("""
            SELECT exchange_no AS exchangeNo,from_asset AS fromAsset,to_asset AS toAsset,
                   from_amount AS fromAmount,to_amount AS toAmount,rate,UPPER(status) AS status,
                   created_at AS createdAt
              FROM nx_exchange_order WHERE user_id=#{userId} AND is_deleted=0
             ORDER BY created_at DESC,id DESC LIMIT 50
            """)
    List<ExchangeRow> userOrders(@Param("userId") Long userId);

    @Select("""
            SELECT user_id AS userId,exchange_no AS exchangeNo,UPPER(from_asset) AS fromAsset,from_amount AS fromAmount
              FROM nx_exchange_order WHERE UPPER(status)='QUEUED' AND is_deleted=0
             ORDER BY created_at,id LIMIT #{limit} FOR UPDATE SKIP LOCKED
            """)
    List<QueuedRow> lockQueuedBatch(@Param("limit") int limit);

    @Update("""
            UPDATE nx_exchange_order SET to_amount=#{toAmount},rate=#{rate},status='COMPLETED',updated_at=NOW()
             WHERE exchange_no=#{exchangeNo} AND UPPER(status)='QUEUED' AND is_deleted=0
            """)
    int completeQueued(@Param("exchangeNo") String exchangeNo,@Param("toAmount") BigDecimal toAmount,
                       @Param("rate") BigDecimal rate);

    @Update("""
            UPDATE nx_exchange_order SET status='CANCELLED',updated_at=NOW()
             WHERE user_id=#{userId} AND exchange_no=#{exchangeNo} AND UPPER(status)='QUEUED' AND is_deleted=0
            """)
    int cancelOwnQueued(@Param("userId") Long userId,@Param("exchangeNo") String exchangeNo);

    @Select("""
            SELECT COALESCE((SELECT config_value FROM nx_config_item WHERE config_key='growth.phase.current'
                              AND status=1 AND is_deleted=0 LIMIT 1),'P1') AS phase,
                   GREATEST(TIMESTAMPDIFF(MONTH,u.created_at,NOW()),0) AS accountAgeMonths,
                   DATE_FORMAT(u.created_at,'%x-W%v') AS cohort
              FROM nx_user u WHERE u.id=#{userId} AND u.status='ACTIVE' AND u.is_deleted=0
            """)
    UserAttribution userAttribution(@Param("userId") Long userId);

    record WalletGateRow(BigDecimal usdtAvailable,BigDecimal nexAvailable,String kycStatus,String countryCode) {}
    record ExchangeWrite(Long userId,String exchangeNo,String fromAsset,String toAsset,BigDecimal fromAmount,
                         BigDecimal toAmount,BigDecimal rate,String status) {}
    record LedgerWrite(Long userId,String bizNo,String asset,String direction,BigDecimal amount,
                       BigDecimal balanceAfter,String remark) {}
    record ExchangeRow(String exchangeNo,String fromAsset,String toAsset,BigDecimal fromAmount,
                       BigDecimal toAmount,BigDecimal rate,String status,LocalDateTime createdAt) {}
    record QueuedRow(Long userId,String exchangeNo,String fromAsset,BigDecimal fromAmount) {}
    record UserAttribution(String phase,Integer accountAgeMonths,String cohort) {}
    record MarketPoint(BigDecimal priceUsdt,LocalDateTime sampledAt) {}
}
