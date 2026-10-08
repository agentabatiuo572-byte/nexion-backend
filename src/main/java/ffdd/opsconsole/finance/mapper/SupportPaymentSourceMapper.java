package ffdd.opsconsole.finance.mapper;

import ffdd.opsconsole.finance.facade.SupportPaymentFacts.Source;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.InsertProvider;

// Exact source observations, not generic CRUD; every read bypasses both MyBatis caches.
@SuppressWarnings("MybatisPlusBaseMapper")
public interface SupportPaymentSourceMapper {
    enum Marker { CREGIS_EVENT, LEDGER, PAYMENT, CARD_SETTLEMENT, INTENT, REFUND_BILL, DEVICE }

    @Select(SupportPaymentSourceSql.PROOF+" FOR SHARE")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    Map<String,Object> currentSourceProof(@Param("factId") String factId);

    @SelectProvider(type=SupportPaymentSourceSql.class,method="currentMarker")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    Map<String,Object> currentMarker(@Param("marker") Marker marker,@Param("id") long id);

    @SelectProvider(type=SupportPaymentSourceSql.class,method="currentBefore")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> currentBefore(@Param("source") Source source,@Param("customerId") long customer,@Param("key") String key,@Param("id") long id);

    @SelectProvider(type=SupportPaymentSourceSql.class,method="currentSettled")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> currentSettled(@Param("source") Source source,@Param("customerIds") List<Long> customers,@Param("key") String key,@Param("ids") Map<String,Object> ids);

    @InsertProvider(type=SupportPaymentSourceSql.class,method="insertLedger")
    @Options(useGeneratedKeys=true,keyProperty="ledger.id",keyColumn="id",flushCache=Options.FlushCachePolicy.TRUE)
    int insertLedger(@Param("source") Source source,@Param("ledger") Map<String,Object> ledger);

    @Select(SupportPaymentSourceSql.PROOF)
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    Map<String,Object> originalSourceProof(@Param("factId") String factId);

    @Select(SupportPaymentSourceSql.CREGIS_EVENT)
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> cregisEvents(@Param("partition") long partition,@Param("cid") long cid);
    @SelectProvider(type=SupportPaymentSourceSql.class,method="before")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> before(@Param("source") Source source,@Param("customerId") long customer,@Param("key") String key);

    @SelectProvider(type=SupportPaymentSourceSql.class,method="settled")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> settled(@Param("source") Source source,@Param("customerIds") List<Long> customers,@Param("key") String key);

    @Select(SupportPaymentSourceSql.LEDGER)
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> ledgers(@Param("key") String key);

    // Only after the canonical positive ledger has been written; absence never authorizes NEW.
    @Select(SupportPaymentSourceSql.LEDGER+" FOR SHARE")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> settledVietqrCounterpart(@Param("key") String key);

    @Select(SupportPaymentSourceSql.PAYMENT)
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> payments(@Param("orderNo") String order);

    @Select(SupportPaymentSourceSql.CARD_SETTLEMENT)
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> cardSettlements(@Param("key") String key);

    @Select(SupportPaymentSourceSql.INTENT)
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> intent(@Param("key") String key);

    @Select(SupportPaymentSourceSql.REFUND_BILL)
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> refundBills(@Param("key") String key);

    @Select(SupportPaymentSourceSql.DEVICE)
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> device(@Param("id") long id);

    @SelectProvider(type=SupportPaymentSourceSql.class,method="refunds")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> refunds(@Param("customerIds") List<Long> customers,@Param("orderNo") String order);

    @SelectProvider(type=SupportPaymentSourceSql.class,method="trialOrder")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> trialOrder(@Param("customerIds") List<Long> customers,@Param("orderNo") String order);
}
