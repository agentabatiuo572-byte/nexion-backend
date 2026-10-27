package ffdd.opsconsole.market.mapper;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.ResultMap;
import java.time.LocalDateTime;

/** Market-owned plain SELECTs; no inference of payment or fund completeness. */
@SuppressWarnings("MybatisPlusBaseMapper") // Cross-table original-source projection has no entity CRUD surface.
public interface SupportExchangeSourceMapper {
    String ORDER="SELECT o.id,o.user_id AS customerId,o.exchange_no AS exchangeNo,o.from_asset AS fromAsset,o.to_asset AS toAsset,o.from_amount AS fromAmount,o.to_amount AS toAmount,o.rate,o.status,o.created_at AS createdAt,o.updated_at AS updatedAt,o.is_deleted AS deleted,u.sandbox AS userSandbox,u.is_deleted AS userDeleted FROM nx_exchange_order o LEFT JOIN nx_user u ON u.id=o.user_id ";
    @Select("<script>"+ORDER+"WHERE o.user_id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> ORDER BY o.id</script>")
    @Results(id="supportExchangeOrder",value={@Result(column="createdAt",property="createdAt",javaType=LocalDateTime.class),@Result(column="updatedAt",property="updatedAt",javaType=LocalDateTime.class)})
    List<Map<String,Object>> byCustomers(@Param("ids") Collection<Long> ids);
    @Select("<script>"+ORDER+"WHERE o.exchange_no IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> ORDER BY o.id</script>")
    @ResultMap("supportExchangeOrder")
    List<Map<String,Object>> byOrderNos(@Param("ids") Collection<String> ids);
    @Select("<script>SELECT l.id,l.user_id AS customerId,o.exchange_no AS exchangeNo,l.biz_no AS bizNo,l.biz_type AS bizType,l.asset,l.direction,l.amount,l.balance_after AS balanceAfter,l.status,l.created_at AS createdAt,l.updated_at AS updatedAt,l.is_deleted AS deleted FROM nx_wallet_ledger l JOIN nx_exchange_order o ON l.biz_no IN (CONCAT(o.exchange_no,'-OUT'),CONCAT(o.exchange_no,'-IN'),CONCAT(o.exchange_no,'-HOLD'),CONCAT(o.exchange_no,'-REFUND')) WHERE o.exchange_no IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> ORDER BY l.id</script>")
    List<Map<String,Object>> ledgers(@Param("ids") Collection<String> ids);
    @Select("<script>SELECT id,exchange_no AS exchangeNo,total_fee_usdt AS totalFeeUsdt,burn_ratio AS burnRatio,burn_pool_usdt AS burnPoolUsdt,fee_buffer_usdt AS feeBufferUsdt,price_usdt AS priceUsdt,nex_equivalent AS nexEquivalent,created_at AS createdAt,updated_at AS updatedAt,is_deleted AS deleted FROM nx_exchange_fee_allocation WHERE exchange_no IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> ORDER BY id</script>")
    List<Map<String,Object>> fees(@Param("ids") Collection<String> ids);
    @Select("SELECT id,user_id AS customerId,fund_incarnation AS incarnation,fund_revision AS revision,version,usdt_available AS usdtAvailable,nex_available AS nexAvailable,cregis_risk_held AS riskHeld,pending_withdraw AS pendingWithdrawal,sandbox,is_deleted AS deleted FROM nx_user_wallet WHERE user_id=#{id}")
    List<Map<String,Object>> wallets(@Param("id") long id);
    @Select("SELECT table_name AS tableName,column_name AS columnName,datetime_precision AS `precision` FROM information_schema.COLUMNS WHERE table_schema=DATABASE() AND table_name='nx_exchange_order' AND column_name='updated_at'")
    List<Map<String,Object>> timePrecisions();
}
