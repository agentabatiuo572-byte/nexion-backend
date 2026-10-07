package ffdd.opsconsole.finance.mapper;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Internal reader; its authorized caller supplies customer scope, never current support groups. */
// Read-only payment and ledger projections span source tables; generic CRUD would broaden this contract.
@SuppressWarnings("MybatisPlusBaseMapper")
public interface SupportPaymentFactMapper {
    @Select(SupportPaymentFactSql.DEPOSITS) List<Map<String,Object>> deposits(@Param("customerIds") Collection<Long> ids);
    @Select(SupportPaymentFactSql.CARDS) List<Map<String,Object>> cards(@Param("customerIds") Collection<Long> ids);
    @Select(SupportPaymentFactSql.VIETQR) List<Map<String,Object>> vietqr(@Param("customerIds") Collection<Long> ids);
    @Select(SupportPaymentFactSql.HDPAY) List<Map<String,Object>> hdpay(@Param("customerIds") Collection<Long> ids);
    @Select(SupportPaymentFactSql.ORDERS) List<Map<String,Object>> orders(@Param("customerIds") Collection<Long> ids);
    @Select(SupportPaymentFactSql.TRIALS) List<Map<String,Object>> trials(@Param("customerIds") Collection<Long> ids);
    @Select(SupportPaymentFactSql.REFUNDS) List<Map<String,Object>> refunds(@Param("customerIds") Collection<Long> ids);
    @Select(SupportPaymentFactSql.FREE_TRIALS) List<Map<String,Object>> freeTrials(@Param("customerIds") Collection<Long> ids);
    @Select(SupportPaymentFactSql.UNMATCHED) List<Map<String,Object>> unmatched(@Param("customerIds") Collection<Long> ids);
}
