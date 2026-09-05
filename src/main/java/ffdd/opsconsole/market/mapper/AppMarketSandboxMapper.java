package ffdd.opsconsole.market.mapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * MySQL-only boundary for the explicit App market sandbox. Market orders,
 * holdings and audit rows stay in run-scoped sandbox tables. Genesis money
 * uses the App's canonical wallet/ledger tables so the wallet and bills pages
 * see the same transaction, but every such statement is restricted to an
 * active {@code nx_user.sandbox = 1} identity.
 */
@Mapper
@SuppressWarnings("MybatisPlusBaseMapper")
public interface AppMarketSandboxMapper {
    @Select("SELECT COALESCE(sandbox,0) FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0 LIMIT 1")
    Integer userSandbox(@Param("userId") Long userId);

    @Select("""
            SELECT GREATEST(0,TIMESTAMPDIFF(DAY,created_at,NOW()))
              FROM nx_user
             WHERE id=#{userId} AND UPPER(status)='ACTIVE' AND is_deleted=0
               AND COALESCE(sandbox,0)=1 LIMIT 1
            """)
    Integer sandboxAccountAgeDays(@Param("userId") Long userId);

    @Select("""
            SELECT (SELECT COUNT(*) FROM nx_genesis_sandbox_order WHERE user_id=#{userId} AND run_id<>#{runId})
                 + (SELECT COUNT(*) FROM nx_genesis_sandbox_holding WHERE user_id=#{userId} AND run_id<>#{runId})
                 + (SELECT COUNT(*) FROM nx_genesis_sandbox_ledger WHERE user_id=#{userId} AND run_id<>#{runId})
                 + (SELECT COUNT(*) FROM nx_genesis_sandbox_wallet
                     WHERE user_id=#{userId} AND run_id<>#{runId} AND version>0)
            """)
    long genesisArtifactsInOtherRuns(@Param("runId") String runId,@Param("userId") Long userId);

    @Insert("INSERT IGNORE INTO nx_market_sandbox_run_lock(run_id,domain_key,created_at,updated_at) VALUES(#{runId},'EXCHANGE',NOW(6),NOW(6))")
    int ensureExchangeRunLock(@Param("runId") String runId);

    @Select("SELECT run_id FROM nx_market_sandbox_run_lock WHERE run_id=#{runId} AND domain_key='EXCHANGE' FOR UPDATE")
    String lockExchangeRun(@Param("runId") String runId);

    @Insert("INSERT IGNORE INTO nx_market_sandbox_run_lock(run_id,domain_key,created_at,updated_at) VALUES(#{runId},'GENESIS',NOW(6),NOW(6))")
    int ensureGenesisRunLock(@Param("runId") String runId);

    @Select("SELECT run_id FROM nx_market_sandbox_run_lock WHERE run_id=#{runId} AND domain_key='GENESIS' FOR UPDATE")
    String lockGenesisRun(@Param("runId") String runId);

    @Select("""
            SELECT COALESCE(SUM(CASE WHEN from_asset='USDT' THEN from_amount ELSE from_amount*rate END),0)
              FROM nx_exchange_sandbox_order
             WHERE run_id=#{runId} AND user_id=#{userId} AND status='COMPLETED'
               AND created_at >= CURRENT_DATE AND created_at < CURRENT_DATE + INTERVAL 1 DAY
            """)
    BigDecimal userCompletedGrossToday(@Param("runId") String runId,@Param("userId") Long userId);

    @Select("""
            SELECT COALESCE(SUM(CASE WHEN from_asset='USDT' THEN from_amount ELSE from_amount*rate END),0)
              FROM nx_exchange_sandbox_order
             WHERE run_id=#{runId} AND status='COMPLETED'
               AND created_at >= CURRENT_DATE AND created_at < CURRENT_DATE + INTERVAL 1 DAY
            """)
    BigDecimal platformCompletedGrossToday(@Param("runId") String runId);

    @Insert("INSERT IGNORE INTO nx_exchange_sandbox_wallet(run_id,user_id,usdt_available,nex_available,version,created_at,updated_at) VALUES(#{runId},#{userId},#{usdt},#{nex},0,NOW(),NOW())")
    int ensureExchangeWallet(@Param("runId") String runId,@Param("userId") Long userId,@Param("usdt") BigDecimal usdt,@Param("nex") BigDecimal nex);

    @Select("SELECT run_id AS runId,user_id AS userId,usdt_available AS usdtAvailable,nex_available AS nexAvailable,version FROM nx_exchange_sandbox_wallet WHERE run_id=#{runId} AND user_id=#{userId} FOR UPDATE")
    ExchangeWallet exchangeWallet(@Param("runId") String runId,@Param("userId") Long userId);

    @Update("UPDATE nx_exchange_sandbox_wallet SET usdt_available=#{usdt},nex_available=#{nex},version=version+1,updated_at=NOW() WHERE run_id=#{runId} AND user_id=#{userId} AND version=#{version}")
    int updateExchangeWallet(@Param("runId") String runId,@Param("userId") Long userId,@Param("usdt") BigDecimal usdt,@Param("nex") BigDecimal nex,@Param("version") Long version);

    @Select("SELECT id,run_id AS runId,user_id AS userId,exchange_no AS exchangeNo,idempotency_key AS idempotencyKey,request_hash AS requestHash,from_asset AS fromAsset,to_asset AS toAsset,from_amount AS fromAmount,to_amount AS toAmount,rate,status,created_at AS createdAt FROM nx_exchange_sandbox_order WHERE run_id=#{runId} AND user_id=#{userId} AND idempotency_key=#{key} LIMIT 1 FOR UPDATE")
    ExchangeOrder exchangeByKey(@Param("runId") String runId,@Param("userId") Long userId,@Param("key") String key);

    @Select("SELECT id,run_id AS runId,user_id AS userId,exchange_no AS exchangeNo,idempotency_key AS idempotencyKey,request_hash AS requestHash,from_asset AS fromAsset,to_asset AS toAsset,from_amount AS fromAmount,to_amount AS toAmount,rate,status,created_at AS createdAt FROM nx_exchange_sandbox_order WHERE run_id=#{runId} AND user_id=#{userId} AND exchange_no=#{exchangeNo} LIMIT 1 FOR UPDATE")
    ExchangeOrder exchangeByNo(@Param("runId") String runId,@Param("userId") Long userId,@Param("exchangeNo") String exchangeNo);

    @Insert("INSERT INTO nx_exchange_sandbox_order(run_id,user_id,exchange_no,idempotency_key,request_hash,from_asset,to_asset,from_amount,to_amount,rate,status,created_at,updated_at) VALUES(#{runId},#{userId},#{exchangeNo},#{key},#{hash},#{fromAsset},#{toAsset},#{fromAmount},#{toAmount},#{rate},#{status},NOW(),NOW())")
    int insertExchangeOrder(ExchangeWrite row);

    @Update("UPDATE nx_exchange_sandbox_order SET status='CANCELLED',updated_at=NOW() WHERE run_id=#{runId} AND user_id=#{userId} AND exchange_no=#{exchangeNo} AND status='QUEUED'")
    int cancelExchange(@Param("runId") String runId,@Param("userId") Long userId,@Param("exchangeNo") String exchangeNo);

    @Select("SELECT COUNT(*) FROM nx_exchange_sandbox_order WHERE run_id=#{runId} AND user_id=#{userId}")
    long countExchangeOrders(@Param("runId") String runId,@Param("userId") Long userId);

    @Select("SELECT exchange_no AS exchangeNo,from_asset AS fromAsset,to_asset AS toAsset,from_amount AS fromAmount,to_amount AS toAmount,rate,status,created_at AS createdAt FROM nx_exchange_sandbox_order WHERE run_id=#{runId} AND user_id=#{userId} ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}")
    List<ExchangeOrderView> exchangeOrdersPage(@Param("runId") String runId,
                                                @Param("userId") Long userId,
                                                @Param("offset") long offset,
                                                @Param("limit") int limit);

    @Select("SELECT run_id AS runId,user_id AS userId,idempotency_key AS idempotencyKey,request_hash AS requestHash,exchange_no AS exchangeNo FROM nx_exchange_sandbox_operation WHERE run_id=#{runId} AND user_id=#{userId} AND idempotency_key=#{key} LIMIT 1 FOR UPDATE")
    ExchangeOperation exchangeOperation(@Param("runId") String runId,@Param("userId") Long userId,@Param("key") String key);

    @Insert("INSERT INTO nx_exchange_sandbox_operation(run_id,user_id,idempotency_key,request_hash,exchange_no,created_at) VALUES(#{runId},#{userId},#{key},#{hash},#{exchangeNo},NOW())")
    int insertExchangeOperation(ExchangeOperationWrite row);

    @Insert("INSERT INTO nx_exchange_sandbox_ledger(run_id,user_id,biz_no,asset,direction,amount,balance_after,remark,created_at) VALUES(#{runId},#{userId},#{bizNo},#{asset},#{direction},#{amount},#{balanceAfter},#{remark},NOW())")
    int insertExchangeLedger(ExchangeLedgerWrite row);

    @Select("SELECT biz_no AS bizNo,asset,direction,amount,balance_after AS balanceAfter,remark,created_at AS createdAt FROM nx_exchange_sandbox_ledger WHERE run_id=#{runId} AND user_id=#{userId} ORDER BY id DESC LIMIT 100")
    List<LedgerView> exchangeLedger(@Param("runId") String runId,@Param("userId") Long userId);

    @Insert("INSERT IGNORE INTO nx_genesis_sandbox_wallet(run_id,user_id,usdt_available,version,created_at,updated_at) VALUES(#{runId},#{userId},#{usdt},0,NOW(),NOW())")
    int ensureGenesisWallet(@Param("runId") String runId,@Param("userId") Long userId,@Param("usdt") BigDecimal usdt);

    @Select("SELECT run_id AS runId,user_id AS userId,usdt_available AS usdtAvailable,version FROM nx_genesis_sandbox_wallet WHERE run_id=#{runId} AND user_id=#{userId} FOR UPDATE")
    GenesisWallet genesisWallet(@Param("runId") String runId,@Param("userId") Long userId);

    @Update("UPDATE nx_genesis_sandbox_wallet SET usdt_available=#{usdt},version=version+1,updated_at=NOW() WHERE run_id=#{runId} AND user_id=#{userId} AND version=#{version}")
    int updateGenesisWallet(@Param("runId") String runId,@Param("userId") Long userId,@Param("usdt") BigDecimal usdt,@Param("version") Long version);

    /**
     * The App wallet and the App bills page both project nx_user_wallet / nx_wallet_ledger.
     * Genesis Sandbox must use that same authority; the sandbox predicate prevents a dev
     * command from ever reaching a production identity.
     */
    @Select("""
            SELECT w.user_id AS userId,w.usdt_available AS usdtAvailable,w.version
              FROM nx_user_wallet w
              JOIN nx_user u ON u.id=w.user_id AND UPPER(u.status)='ACTIVE'
               AND u.is_deleted=0 AND COALESCE(u.sandbox,0)=1
             WHERE w.user_id=#{userId} AND w.is_deleted=0 AND COALESCE(w.sandbox,0)=1 LIMIT 1
            """)
    CanonicalWallet canonicalGenesisWallet(@Param("userId") Long userId);

    @Select("""
            SELECT w.user_id AS userId,w.usdt_available AS usdtAvailable,w.version
              FROM nx_user_wallet w
              JOIN nx_user u ON u.id=w.user_id AND UPPER(u.status)='ACTIVE'
               AND u.is_deleted=0 AND COALESCE(u.sandbox,0)=1
             WHERE w.user_id=#{userId} AND w.is_deleted=0 AND COALESCE(w.sandbox,0)=1 LIMIT 1 FOR UPDATE
            """)
    CanonicalWallet lockCanonicalGenesisWallet(@Param("userId") Long userId);

    @Update("""
            UPDATE nx_user_wallet w
               SET w.usdt_available=w.usdt_available-#{amount},w.version=w.version+1,w.updated_at=NOW()
             WHERE w.user_id=#{userId} AND w.is_deleted=0 AND COALESCE(w.sandbox,0)=1
               AND w.usdt_available>=#{amount}
               AND EXISTS (SELECT 1 FROM nx_user u WHERE u.id=#{userId} AND UPPER(u.status)='ACTIVE'
                            AND u.is_deleted=0 AND COALESCE(u.sandbox,0)=1)
            """)
    int debitCanonicalGenesisWallet(@Param("userId") Long userId,@Param("amount") BigDecimal amount);

    @Update("""
            UPDATE nx_user_wallet w
               SET w.usdt_available=w.usdt_available+#{amount},w.version=w.version+1,w.updated_at=NOW()
             WHERE w.user_id=#{userId} AND w.is_deleted=0 AND COALESCE(w.sandbox,0)=1
               AND EXISTS (SELECT 1 FROM nx_user u WHERE u.id=#{userId} AND UPPER(u.status)='ACTIVE'
                            AND u.is_deleted=0 AND COALESCE(u.sandbox,0)=1)
            """)
    int creditCanonicalGenesisWallet(@Param("userId") Long userId,@Param("amount") BigDecimal amount);

    @Insert("""
            INSERT INTO nx_wallet_ledger
              (user_id,biz_no,biz_type,asset,direction,amount,balance_after,status,remark,created_at,updated_at,is_deleted)
            SELECT #{userId},#{bizNo},#{bizType},'USDT',#{direction},#{amount},#{balanceAfter},'SUCCESS',#{remark},NOW(),NOW(),0
             WHERE EXISTS (SELECT 1 FROM nx_user u WHERE u.id=#{userId} AND UPPER(u.status)='ACTIVE'
                            AND u.is_deleted=0 AND COALESCE(u.sandbox,0)=1)
            """)
    int insertCanonicalGenesisLedger(CanonicalLedgerWrite row);

    @Select("SELECT id,run_id AS runId,order_no AS orderNo,client_request_no AS clientRequestNo,user_id AS userId,holding_no AS holdingNo,order_type AS orderType,amount_usdt AS amountUsdt,price_usdt AS priceUsdt,seller_user_id AS sellerUserId,status,created_at AS createdAt FROM nx_genesis_sandbox_order WHERE run_id=#{runId} AND user_id=#{userId} AND client_request_no=#{key} LIMIT 1 FOR UPDATE")
    GenesisOrder genesisOrderByKey(@Param("runId") String runId,@Param("userId") Long userId,@Param("key") String key);

    @Select("""
            SELECT order_no AS orderNo,UPPER(order_type) AS orderType,
                   CAST(amount_usdt / NULLIF(price_usdt,0) AS UNSIGNED) AS quantity,
                   price_usdt AS unitPriceUsdt,amount_usdt AS amountUsdt,
                   0 AS royaltyUsdt,created_at AS completedAt
              FROM nx_genesis_sandbox_order
             WHERE run_id=#{runId} AND user_id=#{userId} AND status='COMPLETED'
               AND UPPER(order_type) IN ('PRIMARY','SECONDARY')
             ORDER BY created_at DESC,id DESC LIMIT 100
            """)
    List<GenesisOrderView> genesisOrders(@Param("runId") String runId,@Param("userId") Long userId);

    @Insert("INSERT INTO nx_genesis_sandbox_order(run_id,order_no,client_request_no,user_id,holding_no,order_type,amount_usdt,price_usdt,seller_user_id,status,created_at,updated_at) VALUES(#{runId},#{orderNo},#{key},#{userId},#{holdingNo},#{orderType},#{amount},#{price},#{sellerUserId},'COMPLETED',NOW(),NOW())")
    int insertGenesisOrder(GenesisOrderWrite row);

    @Insert("INSERT INTO nx_genesis_sandbox_holding(run_id,holding_no,order_no,user_id,series_code,acquired_price_usdt,status,listing_price_usdt,acquired_at,listed_at,created_at,updated_at) VALUES(#{runId},#{holdingNo},#{orderNo},#{userId},'GENESIS-SANDBOX',#{price},'ACTIVE',NULL,NOW(),NULL,NOW(),NOW())")
    int insertGenesisHolding(HoldingWrite row);

    @Select("SELECT id,run_id AS runId,holding_no AS holdingNo,order_no AS orderNo,user_id AS userId,series_code AS seriesCode,acquired_price_usdt AS acquiredPriceUsdt,status,listing_price_usdt AS listingPriceUsdt,acquired_at AS acquiredAt,listed_at AS listedAt FROM nx_genesis_sandbox_holding WHERE run_id=#{runId} AND user_id=#{userId} AND UPPER(status) IN ('ACTIVE','LISTED') ORDER BY id DESC LIMIT 100")
    List<HoldingView> holdings(@Param("runId") String runId,@Param("userId") Long userId);

    @Select("SELECT id,run_id AS runId,holding_no AS holdingNo,order_no AS orderNo,user_id AS userId,series_code AS seriesCode,acquired_price_usdt AS acquiredPriceUsdt,status,listing_price_usdt AS listingPriceUsdt,acquired_at AS acquiredAt,listed_at AS listedAt FROM nx_genesis_sandbox_holding WHERE run_id=#{runId} AND holding_no=#{holdingNo} LIMIT 1")
    HoldingView holdingSnapshot(@Param("runId") String runId,@Param("holdingNo") String holdingNo);

    @Select("SELECT id,run_id AS runId,holding_no AS holdingNo,order_no AS orderNo,user_id AS userId,series_code AS seriesCode,acquired_price_usdt AS acquiredPriceUsdt,status,listing_price_usdt AS listingPriceUsdt,acquired_at AS acquiredAt,listed_at AS listedAt FROM nx_genesis_sandbox_holding WHERE run_id=#{runId} AND holding_no=#{holdingNo} LIMIT 1 FOR UPDATE")
    HoldingView holding(@Param("runId") String runId,@Param("holdingNo") String holdingNo);

    @Update("UPDATE nx_genesis_sandbox_holding SET status='LISTED',listing_price_usdt=#{price},listed_at=NOW(),updated_at=NOW() WHERE run_id=#{runId} AND holding_no=#{holdingNo} AND user_id=#{userId} AND status='ACTIVE'")
    int listHolding(@Param("runId") String runId,@Param("holdingNo") String holdingNo,@Param("userId") Long userId,@Param("price") BigDecimal price);

    @Update("UPDATE nx_genesis_sandbox_holding SET status='ACTIVE',listing_price_usdt=NULL,listed_at=NULL,updated_at=NOW() WHERE run_id=#{runId} AND holding_no=#{holdingNo} AND user_id=#{userId} AND status='LISTED'")
    int cancelHolding(@Param("runId") String runId,@Param("holdingNo") String holdingNo,@Param("userId") Long userId);

    @Update("UPDATE nx_genesis_sandbox_holding SET user_id=#{buyer},order_no=#{orderNo},acquired_price_usdt=#{price},status='ACTIVE',listing_price_usdt=NULL,listed_at=NULL,acquired_at=NOW(),updated_at=NOW() WHERE run_id=#{runId} AND holding_no=#{holdingNo} AND user_id=#{seller} AND status='LISTED'")
    int transferHolding(@Param("runId") String runId,@Param("holdingNo") String holdingNo,@Param("seller") Long seller,@Param("buyer") Long buyer,@Param("orderNo") String orderNo,@Param("price") BigDecimal price);

    @Select("SELECT id,run_id AS runId,holding_no AS holdingNo,order_no AS orderNo,user_id AS userId,series_code AS seriesCode,acquired_price_usdt AS acquiredPriceUsdt,status,listing_price_usdt AS listingPriceUsdt,acquired_at AS acquiredAt,listed_at AS listedAt FROM nx_genesis_sandbox_holding WHERE run_id=#{runId} AND status='LISTED' AND listing_price_usdt>0 ORDER BY listing_price_usdt,id LIMIT 100")
    List<HoldingView> listings(@Param("runId") String runId);

    @Select("SELECT COUNT(*) FROM nx_genesis_sandbox_holding WHERE run_id=#{runId} AND UPPER(status) IN ('ACTIVE','LISTED')")
    long holdingCount(@Param("runId") String runId);

    @Select("""
            SELECT COUNT(*) + 1
              FROM (SELECT h.user_id, COUNT(*) AS holdings
                      FROM nx_genesis_sandbox_holding h
                      JOIN nx_user u ON u.id=h.user_id AND u.status='ACTIVE' AND u.is_deleted=0 AND COALESCE(u.sandbox,0)=1
                     WHERE h.run_id=#{runId} AND h.series_code=#{seriesCode}
                       AND UPPER(h.status) IN ('ACTIVE','LISTED')
                     GROUP BY h.user_id) ranked
              JOIN (SELECT h.user_id, COUNT(*) AS holdings
                      FROM nx_genesis_sandbox_holding h
                      JOIN nx_user u ON u.id=h.user_id AND u.status='ACTIVE' AND u.is_deleted=0 AND COALESCE(u.sandbox,0)=1
                     WHERE h.run_id=#{runId} AND h.series_code=#{seriesCode} AND h.user_id=#{userId}
                       AND UPPER(h.status) IN ('ACTIVE','LISTED')
                     GROUP BY h.user_id) mine ON ranked.holdings > mine.holdings
            """)
    Integer currentPriorityRank(@Param("runId") String runId, @Param("userId") Long userId,
                                @Param("seriesCode") String seriesCode);

    @Select("""
            SELECT COUNT(DISTINCT h.user_id)
              FROM nx_genesis_sandbox_holding h
              JOIN nx_user u ON u.id=h.user_id AND u.status='ACTIVE' AND u.is_deleted=0 AND COALESCE(u.sandbox,0)=1
             WHERE h.run_id=#{runId} AND h.series_code=#{seriesCode}
               AND UPPER(h.status) IN ('ACTIVE','LISTED')
            """)
    long activeHolderCount(@Param("runId") String runId, @Param("seriesCode") String seriesCode);

    @Insert("INSERT INTO nx_genesis_sandbox_ledger(run_id,user_id,biz_no,direction,amount,balance_after,remark,created_at) VALUES(#{runId},#{userId},#{bizNo},#{direction},#{amount},#{balanceAfter},#{remark},NOW())")
    int insertGenesisLedger(GenesisLedgerWrite row);

    @Select("SELECT biz_no AS bizNo,'USDT' AS asset,direction,amount,balance_after AS balanceAfter,remark,created_at AS createdAt FROM nx_genesis_sandbox_ledger WHERE run_id=#{runId} AND user_id=#{userId} ORDER BY id DESC LIMIT 100")
    List<LedgerView> genesisLedger(@Param("runId") String runId,@Param("userId") Long userId);

    record ExchangeWallet(String runId,Long userId,BigDecimal usdtAvailable,BigDecimal nexAvailable,Long version) {}
    record ExchangeOrder(Long id,String runId,Long userId,String exchangeNo,String idempotencyKey,String requestHash,String fromAsset,String toAsset,BigDecimal fromAmount,BigDecimal toAmount,BigDecimal rate,String status,LocalDateTime createdAt) {}
    record ExchangeOrderView(String exchangeNo,String fromAsset,String toAsset,BigDecimal fromAmount,BigDecimal toAmount,BigDecimal rate,String status,LocalDateTime createdAt) {}
    record ExchangeWrite(String runId,Long userId,String exchangeNo,String key,String hash,String fromAsset,String toAsset,BigDecimal fromAmount,BigDecimal toAmount,BigDecimal rate,String status) {}
    record ExchangeLedgerWrite(String runId,Long userId,String bizNo,String asset,String direction,BigDecimal amount,BigDecimal balanceAfter,String remark) {}
    record ExchangeOperation(String runId,Long userId,String idempotencyKey,String requestHash,String exchangeNo) {}
    record ExchangeOperationWrite(String runId,Long userId,String key,String hash,String exchangeNo) {}
    record GenesisWallet(String runId,Long userId,BigDecimal usdtAvailable,Long version) {}
    record CanonicalWallet(Long userId,BigDecimal usdtAvailable,Long version) {}
    record GenesisOrder(Long id,String runId,String orderNo,String clientRequestNo,Long userId,String holdingNo,String orderType,BigDecimal amountUsdt,BigDecimal priceUsdt,Long sellerUserId,String status,LocalDateTime createdAt) {}
    record GenesisOrderView(String orderNo,String orderType,Integer quantity,BigDecimal unitPriceUsdt,BigDecimal amountUsdt,BigDecimal royaltyUsdt,LocalDateTime completedAt) {}
    record GenesisOrderWrite(String runId,String orderNo,String key,Long userId,String holdingNo,String orderType,BigDecimal amount,BigDecimal price,Long sellerUserId) {}
    record HoldingView(Long id,String runId,String holdingNo,String orderNo,Long userId,String seriesCode,BigDecimal acquiredPriceUsdt,String status,BigDecimal listingPriceUsdt,LocalDateTime acquiredAt,LocalDateTime listedAt) {}
    record HoldingWrite(String runId,String holdingNo,String orderNo,Long userId,BigDecimal price) {}
    record GenesisLedgerWrite(String runId,Long userId,String bizNo,String direction,BigDecimal amount,BigDecimal balanceAfter,String remark) {}
    record CanonicalLedgerWrite(Long userId,String bizNo,String bizType,String direction,BigDecimal amount,BigDecimal balanceAfter,String remark) {}
    record LedgerView(String bizNo,String asset,String direction,BigDecimal amount,BigDecimal balanceAfter,String remark,LocalDateTime createdAt) {}
}
